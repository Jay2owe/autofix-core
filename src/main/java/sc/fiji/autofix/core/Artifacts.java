package sc.fiji.autofix.core;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.net.URL;
import java.net.URLConnection;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Jars on disk: what is missing, what is the wrong version, and how to put it
 * right.
 *
 * <p>Downloads by {@link URL} with SHA-1 verification and disables rival
 * versions by renaming them. It deliberately does <b>not</b> use
 * {@code net.imagej.updater}: this module must need nothing but the JDK and
 * {@code ij}, so that embedding it costs a consumer no extra install.
 *
 * <h2>Disable, never delete</h2>
 * A wrong-version jar is renamed to {@code name.jar.disabled-YYYYMMDD}, never
 * removed. The user may need it back for a different Fiji tool tomorrow, and a
 * plugin that silently deletes files out of someone's Fiji.app has no way to
 * apologise. On Windows the jar is usually locked by the running JVM, so the
 * rename is deferred to a helper that waits for Fiji to exit — see
 * {@link #scheduleWindowsDisableAfterExit}.
 */
public final class Artifacts {

    /**
     * How a jar gets renamed. The production implementation renames in place
     * and falls back to the deferred Windows helper; tests substitute one that
     * records the attempt instead of touching a real Fiji install.
     */
    public interface JarFileOps {
        boolean rename(File source, File disabled);

        boolean scheduleDisable(File source, File disabled);
    }

    private static final Set<String> SCHEDULED_DISABLES =
            Collections.synchronizedSet(new HashSet<String>());

    private static final JarFileOps DEFAULT_JAR_FILE_OPS = new JarFileOps() {
        @Override
        public boolean rename(File source, File disabled) {
            return source.renameTo(disabled);
        }

        @Override
        public boolean scheduleDisable(File source, File disabled) {
            return scheduleWindowsDisableAfterExit(source, disabled, deferredDisableProduct);
        }
    };

    private static JarFileOps jarFileOps = DEFAULT_JAR_FILE_OPS;

    /*
     * The default JarFileOps writes a log and a helper script whose names carry
     * the product, and it is a stateless singleton, so the product is
     * registered once by the consumer at startup.
     *
     * A mutable static in shared code would normally be a bug waiting for two
     * consumers to fight over it. It is safe here precisely because this module
     * is always shaded with relocation: FLASH gets its own
     * flash.internal.autofix.Artifacts and PULSE gets pulse.internal.autofix.
     * Two plugins in one Fiji hold two unrelated classes and two unrelated
     * statics. If this module were ever shipped as a shared jar — which the
     * pattern forbids — this field would be the first thing to break.
     *
     * The fallback names the module rather than guessing at a plugin: visibly
     * wrong beats silently attributed to the wrong plugin.
     */
    private static volatile Product deferredDisableProduct = Product.named("autofix");

    private Artifacts() {}

    /**
     * Names the plugin whose deferred-disable helper writes
     * {@code <Product>-runtime-repair.log} beside the jar. Call once at startup.
     * Only affects the default {@link JarFileOps}.
     */
    public static void setProduct(Product product) {
        if (product != null) {
            deferredDisableProduct = product;
        }
    }

    /**
     * Test seam: substitutes the rename/schedule implementation and clears the
     * record of what has already been scheduled. Passing null restores the real
     * one. Public because a consumer's own tests drive it.
     */
    public static void setJarFileOpsForTesting(JarFileOps ops) {
        jarFileOps = ops == null ? DEFAULT_JAR_FILE_OPS : ops;
        SCHEDULED_DISABLES.clear();
    }

    /**
     * What is wrong with {@code artifacts} under {@code fijiDir}, as lines a
     * user can read. Empty means everything is in place.
     *
     * <p>An artifact is satisfied when its exact expected filename is present
     * and no rival version sits beside it — in either {@code plugins/} or
     * {@code jars/}, because Fiji loads both and a stale copy in the other
     * folder shadows the good one just as effectively.
     */
    public static List<String> check(File fijiDir,
                                     List<DependencySpec.Artifact> artifacts,
                                     List<String> ignorePrefixes) {
        List<String> issues = new ArrayList<String>();
        if (fijiDir == null) {
            issues.add("Could not determine the Fiji.app directory.");
            return issues;
        }
        if (artifacts == null) {
            return issues;
        }

        for (DependencySpec.Artifact artifact : artifacts) {
            File dir = new File(fijiDir, artifact.getFolder());
            File expected = new File(dir, artifact.getExpectedFile());
            if (expected.exists()) {
                addConflictIssues(issues, dir, artifact, null, ignorePrefixes);
                String otherFolder = "jars".equals(artifact.getFolder()) ? "plugins" : "jars";
                addConflictIssues(issues, new File(fijiDir, otherFolder), artifact, otherFolder, ignorePrefixes);
                continue;
            }

            File compatible = findMatchingJar(dir, artifact.getMatchPrefix(), ignorePrefixes);
            if (artifact.isAcceptAnyExisting() && compatible != null) {
                continue;
            }

            if (compatible != null) {
                issues.add(artifact.getLabel() + ": found " + compatible.getName()
                        + ", need " + artifact.getExpectedFile());
            } else {
                issues.add(artifact.getLabel() + ": MISSING (need " + artifact.getExpectedFile() + ")");
            }
        }

        return issues;
    }

    /**
     * Puts {@code artifacts} right under {@code fijiDir}, returning one line per
     * action taken. A line beginning {@code "FAILED"} means the repair did not
     * complete; callers detect that rather than an exception, because a partial
     * repair still has useful lines above the failure.
     */
    public static List<String> repair(File fijiDir,
                                      List<DependencySpec.Artifact> artifacts,
                                      List<String> ignorePrefixes) {
        List<String> actions = new ArrayList<String>();
        if (fijiDir == null) {
            actions.add("FAILED: Could not determine the Fiji.app directory.");
            return actions;
        }
        if (artifacts == null) {
            return actions;
        }

        String dateSuffix = new SimpleDateFormat("yyyyMMdd").format(new Date());
        for (DependencySpec.Artifact artifact : artifacts) {
            File dir = new File(fijiDir, artifact.getFolder());
            if (!dir.exists() && !dir.mkdirs()) {
                actions.add("FAILED to prepare " + artifact.getFolder() + "/: could not create "
                        + dir.getAbsolutePath());
                continue;
            }
            if (!dir.isDirectory()) {
                actions.add("FAILED to prepare " + artifact.getFolder() + "/: not a directory: "
                        + dir.getAbsolutePath());
                continue;
            }
            File expected = new File(dir, artifact.getExpectedFile());
            if (expected.exists()) {
                disableWrongVersions(dir, artifact, dateSuffix, actions, null, ignorePrefixes);
                String otherFolder = "jars".equals(artifact.getFolder()) ? "plugins" : "jars";
                disableWrongVersions(new File(fijiDir, otherFolder), artifact, dateSuffix, actions,
                        otherFolder, ignorePrefixes);
                continue;
            }

            File compatible = findMatchingJar(dir, artifact.getMatchPrefix(), ignorePrefixes);
            if (artifact.isAcceptAnyExisting() && compatible != null) {
                actions.add("Using existing: " + compatible.getName());
                continue;
            }

            disableWrongVersions(dir, artifact, dateSuffix, actions, null, ignorePrefixes);
            String otherFolder = "jars".equals(artifact.getFolder()) ? "plugins" : "jars";
            disableWrongVersions(new File(fijiDir, otherFolder), artifact, dateSuffix, actions,
                    otherFolder, ignorePrefixes);

            if (artifact.getDownloadUrl() == null || artifact.getDownloadUrl().trim().isEmpty()) {
                actions.add("FAILED to download " + artifact.getExpectedFile() + ": no download URL is defined.");
                continue;
            }

            try {
                downloadFile(artifact.getDownloadUrl(), expected, artifact.getExpectedSha1());
                actions.add("Downloaded: " + artifact.getExpectedFile()
                        + " (" + (expected.length() / 1024) + " KB)");
            } catch (Exception e) {
                actions.add("FAILED to download " + artifact.getExpectedFile() + ": " + e.getMessage());
            }
        }

        return actions;
    }

    /** True when {@code actions} contains a jar that was disabled, or will be. */
    public static boolean hasDisabledJarActions(List<String> actions) {
        if (actions == null) {
            return false;
        }
        for (String action : actions) {
            String trimmed = action == null ? "" : action.trim();
            if (trimmed.startsWith("Disabled:")
                    || trimmed.startsWith("Scheduled disable after Fiji closes:")) {
                return true;
            }
        }
        return false;
    }

    /**
     * What to tell a user whose jars were disabled, so they can undo it. Names
     * the plugin, because the instruction ends with re-running that plugin's
     * Auto-Fix and a generic message would leave them looking for it.
     */
    public static String disabledJarRestoreGuidance(Product product, String runtimeName) {
        String label = runtimeName == null || runtimeName.trim().isEmpty()
                ? "this runtime"
                : runtimeName.trim();
        String productName = product == null ? "this plugin" : product.displayName();
        return "Disabled jars are not deleted. If another Fiji tool needs a newer jar later, "
                + "close Fiji, rename that file from .jar.disabled-YYYYMMDD back to .jar, "
                + "and restart Fiji. Re-run Auto-Fix for " + label
                + " before using the pinned " + productName + " workflow again.";
    }

    // --- jar matching --------------------------------------------------------

    private static File findMatchingJar(File dir, String prefix, List<String> ignorePrefixes) {
        File[] candidates = dir.listFiles();
        if (candidates == null) {
            return null;
        }
        for (File file : candidates) {
            String name = file.getName();
            if (!file.isFile()) {
                continue;
            }
            if (isScheduledForDisable(file)) {
                continue;
            }
            if (!name.endsWith(".jar")) {
                continue;
            }
            if (shouldIgnore(name, ignorePrefixes)) {
                continue;
            }
            if (matchesVersionedJarPrefix(name, prefix)) {
                return file;
            }
        }
        return null;
    }

    private static void addConflictIssues(List<String> issues,
                                          File dir,
                                          DependencySpec.Artifact artifact,
                                          String folderLabel,
                                          List<String> ignorePrefixes) {
        List<String> conflicts = findConflictingJars(dir, artifact, ignorePrefixes);
        if (conflicts.isEmpty()) {
            return;
        }
        if (folderLabel == null) {
            issues.add(artifact.getLabel() + ": conflicting extra jar(s) beside "
                    + artifact.getExpectedFile() + ": " + Text.joinComma(conflicts));
        } else {
            issues.add(artifact.getLabel() + ": conflicting extra jar(s) in "
                    + folderLabel + "/ while using " + artifact.getExpectedFile()
                    + ": " + Text.joinComma(conflicts));
        }
    }

    private static List<String> findConflictingJars(File dir,
                                                    DependencySpec.Artifact artifact,
                                                    List<String> ignorePrefixes) {
        List<String> conflicts = new ArrayList<String>();
        File[] candidates = dir.listFiles();
        if (candidates == null) {
            return conflicts;
        }
        for (File file : candidates) {
            String name = file.getName();
            if (!file.isFile()) {
                continue;
            }
            if (isScheduledForDisable(file)) {
                continue;
            }
            if (!name.endsWith(".jar")) {
                continue;
            }
            if (shouldIgnore(name, ignorePrefixes)) {
                continue;
            }
            if (!matchesVersionedJarPrefix(name, artifact.getMatchPrefix())) {
                continue;
            }
            if (name.equals(artifact.getExpectedFile())) {
                continue;
            }
            conflicts.add(name);
        }
        Collections.sort(conflicts);
        return conflicts;
    }

    private static void disableWrongVersions(File dir,
                                             DependencySpec.Artifact artifact,
                                             String dateSuffix,
                                             List<String> actions,
                                             String folderLabel,
                                             List<String> ignorePrefixes) {
        File[] candidates = dir.listFiles();
        if (candidates == null) {
            return;
        }
        for (File file : candidates) {
            String name = file.getName();
            if (!file.isFile()) {
                continue;
            }
            if (isScheduledForDisable(file)) {
                continue;
            }
            if (!name.endsWith(".jar")) {
                continue;
            }
            if (shouldIgnore(name, ignorePrefixes)) {
                continue;
            }
            if (!matchesVersionedJarPrefix(name, artifact.getMatchPrefix())) {
                continue;
            }
            if (name.equals(artifact.getExpectedFile())) {
                continue;
            }

            File disabled = uniqueDisabledFile(dir, name, dateSuffix);
            if (jarFileOps.rename(file, disabled)) {
                actions.add(folderLabel == null
                        ? "Disabled: " + name
                        : "Disabled: " + name + " (from " + folderLabel + "/)");
            } else if (jarFileOps.scheduleDisable(file, disabled)) {
                SCHEDULED_DISABLES.add(fileKey(file));
                actions.add(folderLabel == null
                        ? "Scheduled disable after Fiji closes: " + name
                        : "Scheduled disable after Fiji closes: " + name + " (from " + folderLabel + "/)");
            } else {
                actions.add("FAILED to disable: " + name
                        + " (the jar stayed locked and could not be scheduled for after-exit cleanup; "
                        + "close Fiji, rename or remove this jar manually, then restart Fiji.)");
            }
        }
    }

    private static boolean shouldIgnore(String fileName, List<String> ignorePrefixes) {
        if (ignorePrefixes == null || ignorePrefixes.isEmpty()) {
            return false;
        }
        for (String prefix : ignorePrefixes) {
            if (fileName.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    /**
     * True when {@code fileName} is a versioned form of {@code prefix} —
     * {@code "jts-core-1.19.0.jar"} against {@code "jts-core-"}.
     *
     * <p>The digit check is what stops {@code "bio-formats_plugins-8.1.1.jar"}
     * from matching an unrelated prefix that happens to be its own prefix's
     * prefix. Without it, disabling wrong versions would rename jars belonging
     * to a different dependency.
     */
    private static boolean matchesVersionedJarPrefix(String fileName, String prefix) {
        if (fileName == null || prefix == null || !fileName.startsWith(prefix)) {
            return false;
        }
        if (fileName.length() <= prefix.length()) {
            return false;
        }
        return Character.isDigit(fileName.charAt(prefix.length()));
    }

    private static File uniqueDisabledFile(File dir, String name, String dateSuffix) {
        File candidate = new File(dir, name + ".disabled-" + dateSuffix);
        if (!candidate.exists()) {
            return candidate;
        }
        for (int i = 2; i < 1000; i++) {
            candidate = new File(dir, name + ".disabled-" + dateSuffix + "-" + i);
            if (!candidate.exists()) {
                return candidate;
            }
        }
        return new File(dir, name + ".disabled-" + dateSuffix + "-" + System.currentTimeMillis());
    }

    private static boolean isScheduledForDisable(File file) {
        return file != null && SCHEDULED_DISABLES.contains(fileKey(file));
    }

    private static String fileKey(File file) {
        if (file == null) {
            return "";
        }
        try {
            return file.getCanonicalPath();
        } catch (IOException e) {
            return file.getAbsolutePath();
        }
    }

    // --- deferred disable, Windows -------------------------------------------

    /**
     * Starts a helper that waits for this Fiji to exit and then renames a jar
     * the running JVM has locked. Windows only; every other platform can rename
     * an open file.
     */
    static boolean scheduleWindowsDisableAfterExit(File source, File disabled, Product product) {
        if (!Os.isWindows() || source == null || disabled == null) {
            return false;
        }
        Product named = product == null ? Product.named("autofix") : product;
        try {
            File script = File.createTempFile(named.slug() + "-runtime-disable-", ".ps1");
            File log = new File(source.getParentFile(), named.displayName() + "-runtime-repair.log");
            writeDeferredDisableScript(script);

            List<String> command = new ArrayList<String>();
            command.add("powershell.exe");
            command.add("-NoProfile");
            command.add("-ExecutionPolicy");
            command.add("Bypass");
            command.add("-WindowStyle");
            command.add("Hidden");
            command.add("-File");
            command.add(script.getAbsolutePath());
            command.add(Os.currentProcessId());
            command.add(source.getAbsolutePath());
            command.add(disabled.getAbsolutePath());
            command.add(log.getAbsolutePath());

            ProcessBuilder builder = new ProcessBuilder(command);
            builder.redirectErrorStream(true);
            builder.redirectOutput(ProcessBuilder.Redirect.appendTo(log));
            builder.start();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    static void writeDeferredDisableScript(File script) throws IOException {
        BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(
                new FileOutputStream(script), StandardCharsets.UTF_8));
        try {
            writer.write("param([string]$ParentPid,[string]$SourcePath,[string]$DestPath,[string]$LogPath)\n");
            writer.write("$ErrorActionPreference = 'Stop'\n");
            writer.write("function Write-RepairLog([string]$Message) {\n");
            writer.write("  $stamp = Get-Date -Format 'yyyy-MM-dd HH:mm:ss'\n");
            writer.write("  Add-Content -LiteralPath $LogPath -Value \"$stamp $Message\" -ErrorAction SilentlyContinue\n");
            writer.write("}\n");
            writer.write("try {\n");
            writer.write("  Write-RepairLog \"Waiting to disable locked Fiji jar: $SourcePath\"\n");
            writer.write("  if ($ParentPid -match '^[0-9]+$') {\n");
            writer.write("    while (Get-Process -Id ([int]$ParentPid) -ErrorAction SilentlyContinue) { Start-Sleep -Seconds 2 }\n");
            writer.write("  }\n");
            writer.write("  for ($i = 0; $i -lt 300; $i++) {\n");
            writer.write("    if (-not (Test-Path -LiteralPath $SourcePath)) { Write-RepairLog 'Source already gone.'; exit 0 }\n");
            writer.write("    try {\n");
            writer.write("      Rename-Item -LiteralPath $SourcePath -NewName (Split-Path -Leaf $DestPath) -Force\n");
            writer.write("      Write-RepairLog \"Disabled $SourcePath -> $DestPath\"\n");
            writer.write("      Remove-Item -LiteralPath $MyInvocation.MyCommand.Path -Force -ErrorAction SilentlyContinue\n");
            writer.write("      exit 0\n");
            writer.write("    } catch {\n");
            writer.write("      Start-Sleep -Seconds 1\n");
            writer.write("    }\n");
            writer.write("  }\n");
            writer.write("  Write-RepairLog \"FAILED to disable $SourcePath after Fiji closed.\"\n");
            writer.write("  exit 1\n");
            writer.write("} catch {\n");
            writer.write("  Write-RepairLog \"FAILED to disable ${SourcePath}: $($_.Exception.Message)\"\n");
            writer.write("  exit 1\n");
            writer.write("}\n");
        } finally {
            writer.close();
        }
    }

    // --- download ------------------------------------------------------------

    /**
     * Downloads to a {@code .download} sidecar, verifies, then moves into place,
     * so an interrupted or corrupt download never leaves something that looks
     * like an installed jar. A body under 1 KB is rejected outright: that is
     * what a captive-portal or 404 HTML page looks like, and shipping it as a
     * jar produces a baffling {@code ZipException} much later.
     */
    static void downloadFile(String urlStr, File dest, String expectedSha1) throws Exception {
        File temp = new File(dest.getParentFile(), dest.getName() + ".download");
        URL url = new URL(urlStr);
        URLConnection connection = url.openConnection();
        connection.setConnectTimeout(15000);
        connection.setReadTimeout(30000);
        InputStream in = null;
        FileOutputStream out = null;
        try {
            in = connection.getInputStream();
            out = new FileOutputStream(temp);
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
        } catch (Exception e) {
            closeQuietly(out);
            closeQuietly(in);
            if (temp.exists()) {
                temp.delete();
            }
            throw e;
        } finally {
            closeQuietly(out);
            closeQuietly(in);
        }

        try {
            if (temp.length() < 1000L) {
                throw new Exception("Download too small - likely an error page");
            }

            verifySha1(temp, expectedSha1);
            if (dest.exists()) {
                dest.delete();
            }
            if (!temp.renameTo(dest)) {
                throw new Exception("Could not move download to " + dest.getName());
            }
        } finally {
            if (temp.exists()) {
                temp.delete();
            }
        }
    }

    /** No-op when no digest was declared, which is a deliberate per-artifact choice. */
    static void verifySha1(File file, String expectedSha1) throws Exception {
        if (expectedSha1 == null || expectedSha1.trim().isEmpty()) {
            return;
        }
        String actualSha1 = sha1(file);
        if (!expectedSha1.trim().equalsIgnoreCase(actualSha1)) {
            throw new Exception("SHA-1 mismatch for " + file.getName()
                    + " (expected " + expectedSha1.trim() + ", got " + actualSha1 + ")");
        }
    }

    static String sha1(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-1");
        InputStream in = new FileInputStream(file);
        try {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
            byte[] bytes = digest.digest();
            StringBuilder sb = new StringBuilder();
            for (byte b : bytes) {
                String hex = Integer.toHexString(b & 0xff);
                if (hex.length() == 1) {
                    sb.append('0');
                }
                sb.append(hex);
            }
            return sb.toString();
        } finally {
            in.close();
        }
    }

    private static void closeQuietly(java.io.Closeable closeable) {
        if (closeable == null) {
            return;
        }
        try {
            closeable.close();
        } catch (IOException ignored) {
            // Nothing useful to do; the caller is already reporting a failure.
        }
    }
}
