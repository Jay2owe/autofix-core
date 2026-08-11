package sc.fiji.autofix.core;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Relaunches Fiji after this JVM exits.
 *
 * <p>A JVM cannot restart itself, so this writes a short shell or PowerShell
 * helper to a temp file, starts it detached, and returns. The helper waits for
 * this process id to disappear and then starts the launcher it was handed. The
 * caller is responsible for asking ImageJ to quit afterwards — this class never
 * quits anything.
 *
 * <h2>Read this before changing anything here</h2>
 * Nothing about this file is exercised by a normal run. It only does its work
 * on a machine that is about to shut down, and every failure mode looks
 * identical from inside the JVM: the helper starts successfully and then does
 * nothing useful. The generated script text is therefore pinned byte-for-byte
 * by the equivalence goldens, and the argv is pinned too. If you change a line
 * of script here, a golden must change in the same commit and you must say why.
 *
 * <h2>Three deliberate refusals</h2>
 * <ul>
 *   <li><b>No {@code -ExecutionPolicy Bypass}.</b> If a site policy blocks
 *       scripts, the restart fails and the user is told to restart manually.
 *       Bypassing a machine's script policy to save one click is not this
 *       plugin's decision to make.</li>
 *   <li><b>No {@code Invoke-Expression}, no {@code eval}.</b> Every path is
 *       passed as a literal argument. The script never evaluates command text,
 *       so a Fiji installed under a directory with a quote or a semicolon in
 *       its name cannot turn into a command.</li>
 *   <li><b>It gives up rather than double-launching.</b> After
 *       {@value #WAIT_SECONDS} seconds of the old process refusing to exit, the
 *       helper logs and stops. Two Fijis sharing one {@code Fiji.app} corrupt
 *       each other's preferences.</li>
 * </ul>
 */
public final class ImageJRestartHelper {

    /** How long the helper waits for the old Fiji to exit before giving up. */
    public static final int WAIT_SECONDS = 300;

    /** The outcome of trying to start the helper — not of the restart itself. */
    public static final class RestartResult {
        private final boolean success;
        private final String message;
        private final File scriptFile;
        private final File launchTarget;

        RestartResult(boolean success, String message, File scriptFile, File launchTarget) {
            this.success = success;
            this.message = message == null ? "" : message;
            this.scriptFile = scriptFile;
            this.launchTarget = launchTarget;
        }

        /** True when the helper process started. It may still fail later, silently. */
        public boolean isSuccess() {
            return success;
        }

        public String getMessage() {
            return message;
        }

        public File getScriptFile() {
            return scriptFile;
        }

        public File getLaunchTarget() {
            return launchTarget;
        }
    }

    /** What to launch, from where, and how. */
    public static final class LaunchSpec {
        public static final String MODE_EXECUTABLE = "executable";
        public static final String MODE_MAC_APP = "mac_app";

        private final File launchTarget;
        private final File workingDirectory;
        private final String mode;

        public LaunchSpec(File launchTarget, File workingDirectory, String mode) {
            this.launchTarget = launchTarget;
            this.workingDirectory = workingDirectory;
            this.mode = mode == null || mode.trim().isEmpty() ? MODE_EXECUTABLE : mode;
        }

        public File getLaunchTarget() {
            return launchTarget;
        }

        public File getWorkingDirectory() {
            return workingDirectory;
        }

        /** {@link #MODE_EXECUTABLE} or {@link #MODE_MAC_APP} — a bundle needs {@code open}. */
        public String getMode() {
            return mode;
        }
    }

    private final Product product;

    /**
     * @param product names the log file this leaves in Fiji.app and the temp
     *        script. Those names outlive the process and are all a user has to
     *        go on when a restart silently does not happen, so they must name
     *        the plugin rather than this module.
     */
    public ImageJRestartHelper(Product product) {
        if (product == null) {
            throw new IllegalArgumentException("The restart helper writes a log into the user's Fiji.app; "
                    + "it must know which plugin to name it after.");
        }
        this.product = product;
    }

    /** The log this helper appends to — {@code <Product>-restart-imagej.log}. */
    public String logFileName() {
        return product.displayName() + "-restart-imagej.log";
    }

    /** True when a launcher was found and this platform has a restart strategy. */
    public boolean canRestartAutomatically() {
        return resolveLaunchSpec(FijiLayout.resolveFijiDir()) != null && Os.isSupported();
    }

    public RestartResult launchRestartHelper() {
        return launchRestartHelper(FijiLayout.resolveFijiDir());
    }

    public RestartResult launchRestartHelper(File fijiDir) {
        if (!Os.isSupported()) {
            return new RestartResult(false,
                    "Automatic restart is not supported on this operating system. Close and reopen ImageJ/Fiji manually.",
                    null,
                    null);
        }

        LaunchSpec spec = resolveLaunchSpec(fijiDir);
        if (spec == null) {
            String base = fijiDir == null ? "unknown Fiji.app folder" : fijiDir.getAbsolutePath();
            return new RestartResult(false,
                    "Could not find an ImageJ/Fiji launcher in " + base + ". Close and reopen ImageJ/Fiji manually.",
                    null,
                    null);
        }

        try {
            boolean windows = Os.isWindows();
            File script = File.createTempFile(
                    product.displayName() + "-restart-imagej-helper-", windows ? ".ps1" : ".sh");
            File log = restartLogFile(fijiDir);
            if (windows) {
                writeWindowsRestartScript(script);
            } else {
                writePosixRestartScript(script);
            }

            List<String> command = windows
                    ? windowsCommand(script, spec, log)
                    : posixCommand(script, spec, log);
            ProcessBuilder builder = new ProcessBuilder(command);
            builder.redirectErrorStream(true);
            builder.redirectOutput(ProcessBuilder.Redirect.appendTo(log));
            builder.start();
            return new RestartResult(true,
                    "Restart helper started. ImageJ/Fiji will reopen after the current process exits.",
                    script,
                    spec.getLaunchTarget());
        } catch (Exception e) {
            return new RestartResult(false,
                    "Could not start the restart helper: " + e.getClass().getSimpleName()
                            + ": " + Text.safeMessage(e),
                    null,
                    spec.getLaunchTarget());
        }
    }

    /**
     * The launcher to start, or null when none was found.
     *
     * <p>The candidate lists are ordered by specificity, not preference: a
     * machine with both {@code ImageJ-win64.exe} and {@code ImageJ.exe} should
     * get the 64-bit one. On macOS a {@code .app} bundle is launched with
     * {@code open -n} rather than executed directly, because executing the
     * inner binary bypasses LaunchServices and loses the app's environment.
     */
    public static LaunchSpec resolveLaunchSpec(File fijiDir) {
        if (fijiDir == null || !fijiDir.isDirectory()) {
            return null;
        }

        if (Os.isWindows()) {
            File launcher = firstExisting(fijiDir,
                    "ImageJ-win64.exe",
                    "ImageJ-win32.exe",
                    "ImageJ.exe",
                    "Fiji.exe",
                    "ImageJ-win64",
                    "ImageJ-win32");
            return launcher == null
                    ? null
                    : new LaunchSpec(launcher, launcher.getParentFile(), LaunchSpec.MODE_EXECUTABLE);
        }

        if (Os.isMac()) {
            if (looksLikeMacApp(fijiDir)) {
                File workingDir = fijiDir.getParentFile() == null ? fijiDir : fijiDir.getParentFile();
                return new LaunchSpec(fijiDir, workingDir, LaunchSpec.MODE_MAC_APP);
            }
            File childApp = new File(fijiDir, "Fiji.app");
            if (looksLikeMacApp(childApp)) {
                return new LaunchSpec(childApp, fijiDir, LaunchSpec.MODE_MAC_APP);
            }
            File launcher = firstExisting(fijiDir,
                    "ImageJ-macosx.exe",
                    "ImageJ-macosx",
                    "Contents/MacOS/ImageJ-macosx",
                    "ImageJ");
            return launcher == null
                    ? null
                    : new LaunchSpec(launcher, launcher.getParentFile(), LaunchSpec.MODE_EXECUTABLE);
        }

        File launcher = firstExisting(fijiDir,
                "ImageJ-linux64.exe",
                "ImageJ-linux32.exe",
                "ImageJ-linux.exe",
                "ImageJ-linux64",
                "ImageJ-linux32",
                "ImageJ-linux",
                "ImageJ");
        return launcher == null
                ? null
                : new LaunchSpec(launcher, launcher.getParentFile(), LaunchSpec.MODE_EXECUTABLE);
    }

    /**
     * Writes the Windows helper. Note {@code ${LauncherPath}} rather than
     * {@code $LauncherPath:} in the log line — PowerShell reads
     * {@code $Name:} as a scoped-variable prefix and fails to parse it, and
     * that failure only ever appears on a user's machine.
     */
    public void writeWindowsRestartScript(File script) throws IOException {
        BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(
                new FileOutputStream(script), StandardCharsets.UTF_8));
        try {
            writer.write("# " + product.displayName() + " ImageJ/Fiji restart helper.\n");
            writer.write("# Purpose: wait for the current ImageJ/Fiji PID to exit, then launch the resolved ImageJ executable.\n");
            writer.write("# Inputs are literal paths supplied by " + product.displayName()
                    + "; this script does not evaluate command text.\n");
            writer.write("param([string]$ParentPid,[string]$LauncherPath,[string]$WorkingDir,[string]$LogPath)\n");
            writer.write("$ErrorActionPreference = 'Continue'\n");
            writer.write("function Write-RestartLog([string]$Message) {\n");
            writer.write("  $stamp = Get-Date -Format 'yyyy-MM-dd HH:mm:ss'\n");
            writer.write("  Add-Content -LiteralPath $LogPath -Value \"$stamp $Message\" -ErrorAction SilentlyContinue\n");
            writer.write("}\n");
            writer.write("try {\n");
            writer.write("  Write-RestartLog \"Waiting for ImageJ/Fiji process $ParentPid to close.\"\n");
            writer.write("  if ($ParentPid -match '^[0-9]+$') {\n");
            writer.write("    for ($i = 0; $i -lt " + WAIT_SECONDS + "; $i++) {\n");
            writer.write("      if (-not (Get-Process -Id ([int]$ParentPid) -ErrorAction SilentlyContinue)) { break }\n");
            writer.write("      Start-Sleep -Seconds 1\n");
            writer.write("    }\n");
            writer.write("    if (Get-Process -Id ([int]$ParentPid) -ErrorAction SilentlyContinue) {\n");
            writer.write("      Write-RestartLog 'Timed out waiting for ImageJ/Fiji to close; not launching a second copy.'\n");
            writer.write("      exit 1\n");
            writer.write("    }\n");
            writer.write("  } else {\n");
            writer.write("    Start-Sleep -Seconds 2\n");
            writer.write("  }\n");
            writer.write("  Start-Sleep -Seconds 1\n");
            writer.write("  if (-not (Test-Path -LiteralPath $LauncherPath)) {\n");
            writer.write("    Write-RestartLog \"Launcher not found: ${LauncherPath}\"\n");
            writer.write("    exit 1\n");
            writer.write("  }\n");
            writer.write("  Write-RestartLog \"Launching ${LauncherPath}\"\n");
            writer.write("  Start-Process -FilePath $LauncherPath -WorkingDirectory $WorkingDir\n");
            writer.write("  Remove-Item -LiteralPath $MyInvocation.MyCommand.Path -Force -ErrorAction SilentlyContinue\n");
            writer.write("  exit 0\n");
            writer.write("} catch {\n");
            writer.write("  Write-RestartLog \"FAILED to restart ImageJ/Fiji: $($_.Exception.Message)\"\n");
            writer.write("  exit 1\n");
            writer.write("}\n");
        } finally {
            writer.close();
        }
    }

    /**
     * Writes the POSIX helper. Deliberately {@code /bin/sh}, not bash: it runs
     * on macOS and on Linux distributions that ship dash, and every construct
     * here is POSIX — {@code expr} for arithmetic, a {@code case} for the digit
     * test, backticks rather than {@code $()}.
     */
    public void writePosixRestartScript(File script) throws IOException {
        BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(
                new FileOutputStream(script), StandardCharsets.UTF_8));
        try {
            writer.write("#!/bin/sh\n");
            writer.write("# " + product.displayName() + " ImageJ/Fiji restart helper.\n");
            writer.write("# Purpose: wait for the current ImageJ/Fiji PID to exit, then launch the resolved ImageJ executable.\n");
            writer.write("# Inputs are literal paths supplied by " + product.displayName()
                    + "; this script does not evaluate command text.\n");
            writer.write("PARENT_PID=\"$1\"\n");
            writer.write("LAUNCH_TARGET=\"$2\"\n");
            writer.write("WORKING_DIR=\"$3\"\n");
            writer.write("LOG_PATH=\"$4\"\n");
            writer.write("MODE=\"$5\"\n");
            writer.write("log_msg() {\n");
            writer.write("  stamp=`date '+%Y-%m-%d %H:%M:%S'`\n");
            writer.write("  printf '%s %s\\n' \"$stamp\" \"$1\" >> \"$LOG_PATH\" 2>/dev/null\n");
            writer.write("}\n");
            writer.write("log_msg \"Waiting for ImageJ/Fiji process $PARENT_PID to close.\"\n");
            writer.write("case \"$PARENT_PID\" in\n");
            writer.write("  ''|*[!0-9]*) sleep 2 ;;\n");
            writer.write("  *)\n");
            writer.write("    i=0\n");
            writer.write("    while kill -0 \"$PARENT_PID\" 2>/dev/null; do\n");
            writer.write("      if [ \"$i\" -ge " + WAIT_SECONDS + " ]; then\n");
            writer.write("        log_msg 'Timed out waiting for ImageJ/Fiji to close; not launching a second copy.'\n");
            writer.write("        exit 1\n");
            writer.write("      fi\n");
            writer.write("      i=`expr \"$i\" + 1`\n");
            writer.write("      sleep 1\n");
            writer.write("    done\n");
            writer.write("    ;;\n");
            writer.write("esac\n");
            writer.write("sleep 1\n");
            writer.write("if [ \"$MODE\" = 'mac_app' ]; then\n");
            writer.write("  log_msg \"Opening $LAUNCH_TARGET\"\n");
            writer.write("  open -n \"$LAUNCH_TARGET\" >> \"$LOG_PATH\" 2>&1 &\n");
            writer.write("else\n");
            writer.write("  if [ ! -e \"$LAUNCH_TARGET\" ]; then\n");
            writer.write("    log_msg \"Launcher not found: $LAUNCH_TARGET\"\n");
            writer.write("    exit 1\n");
            writer.write("  fi\n");
            writer.write("  cd \"$WORKING_DIR\" || exit 1\n");
            writer.write("  log_msg \"Launching $LAUNCH_TARGET\"\n");
            writer.write("  \"$LAUNCH_TARGET\" >> \"$LOG_PATH\" 2>&1 &\n");
            writer.write("fi\n");
            writer.write("rm -f \"$0\" >/dev/null 2>&1\n");
            writer.write("exit 0\n");
        } finally {
            writer.close();
        }
    }

    public static List<String> windowsCommand(File script, LaunchSpec spec, File log) {
        List<String> command = new ArrayList<String>();
        command.add("powershell.exe");
        command.add("-NoProfile");
        command.add("-NonInteractive");
        // Hidden so a restart does not flash a console window. No
        // -ExecutionPolicy: if policy blocks scripts the UI reports failure and
        // the user restarts manually. See the class javadoc.
        command.add("-WindowStyle");
        command.add("Hidden");
        command.add("-File");
        command.add(script.getAbsolutePath());
        command.add(Os.currentProcessId());
        command.add(spec.getLaunchTarget().getAbsolutePath());
        command.add(spec.getWorkingDirectory().getAbsolutePath());
        command.add(log.getAbsolutePath());
        return command;
    }

    public static List<String> posixCommand(File script, LaunchSpec spec, File log) {
        List<String> command = new ArrayList<String>();
        command.add("sh");
        command.add(script.getAbsolutePath());
        command.add(Os.currentProcessId());
        command.add(spec.getLaunchTarget().getAbsolutePath());
        command.add(spec.getWorkingDirectory().getAbsolutePath());
        command.add(log.getAbsolutePath());
        command.add(spec.getMode());
        return command;
    }

    /**
     * The log goes in Fiji.app when that is writable, and the temp directory
     * otherwise — a read-only {@code Program Files} install must still leave a
     * trace somewhere the user can be pointed at.
     */
    public File restartLogFile(File fijiDir) {
        File dir = fijiDir != null && fijiDir.isDirectory() && fijiDir.canWrite()
                ? fijiDir
                : new File(System.getProperty("java.io.tmpdir"));
        return new File(dir, logFileName());
    }

    private static File firstExisting(File baseDir, String... relativePaths) {
        if (baseDir == null || relativePaths == null) {
            return null;
        }
        for (String relativePath : relativePaths) {
            File candidate = new File(baseDir, relativePath);
            if (candidate.exists()) {
                return candidate;
            }
        }
        return null;
    }

    private static boolean looksLikeMacApp(File dir) {
        return dir != null
                && dir.isDirectory()
                && dir.getName().toLowerCase(Locale.ROOT).endsWith(".app")
                && (new File(dir, "Contents").isDirectory()
                || new File(dir, "ImageJ-macosx").exists());
    }
}
