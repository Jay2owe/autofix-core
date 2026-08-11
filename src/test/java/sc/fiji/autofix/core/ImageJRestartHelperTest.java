package sc.fiji.autofix.core;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * The restart helper is the one file here that a normal run never touches, so
 * it gets the most direct coverage: launcher resolution on all three platforms,
 * the generated script text, the argv, and the log filename.
 */
public class ImageJRestartHelperTest {

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    private static final Product FLASH = Product.named("FLASH");
    private static final Product PULSE = Product.named("PULSE");

    private String originalOsName;

    @Before
    public void rememberOsName() {
        originalOsName = System.getProperty("os.name");
    }

    @After
    public void restoreOsName() {
        if (originalOsName == null) {
            System.clearProperty("os.name");
        } else {
            System.setProperty("os.name", originalOsName);
        }
    }

    // --- the product name reaches disk ---------------------------------------

    @Test
    public void logFileNameCarriesTheProductNotTheModule() {
        assertEquals("FLASH-restart-imagej.log", new ImageJRestartHelper(FLASH).logFileName());
        assertEquals("PULSE-restart-imagej.log", new ImageJRestartHelper(PULSE).logFileName());
    }

    @Test
    public void restartLogGoesBesideFijiWhenWritable() throws Exception {
        File fijiDir = folder.newFolder("Fiji.app");
        File log = new ImageJRestartHelper(FLASH).restartLogFile(fijiDir);
        assertEquals(fijiDir.getAbsolutePath(), log.getParentFile().getAbsolutePath());
        assertEquals("FLASH-restart-imagej.log", log.getName());
    }

    @Test
    public void restartLogFallsBackToTempWhenFijiIsUnknown() {
        File log = new ImageJRestartHelper(FLASH).restartLogFile(null);
        assertEquals(new File(System.getProperty("java.io.tmpdir")).getAbsolutePath(),
                log.getParentFile().getAbsolutePath());
    }

    @Test(expected = IllegalArgumentException.class)
    public void refusesToBeBuiltWithoutAProduct() {
        new ImageJRestartHelper(null);
    }

    // --- launcher resolution -------------------------------------------------

    @Test
    public void windowsPrefersTheSixtyFourBitLauncher() throws Exception {
        System.setProperty("os.name", "Windows 11");
        File fijiDir = folder.newFolder("win-fiji");
        assertTrue(new File(fijiDir, "ImageJ.exe").createNewFile());
        File preferred = new File(fijiDir, "ImageJ-win64.exe");
        assertTrue(preferred.createNewFile());

        ImageJRestartHelper.LaunchSpec spec = ImageJRestartHelper.resolveLaunchSpec(fijiDir);
        assertNotNull(spec);
        assertEquals(preferred.getAbsolutePath(), spec.getLaunchTarget().getAbsolutePath());
        assertEquals(ImageJRestartHelper.LaunchSpec.MODE_EXECUTABLE, spec.getMode());
    }

    @Test
    public void windowsFallsBackToPlainImageJExe() throws Exception {
        System.setProperty("os.name", "Windows 11");
        File fijiDir = folder.newFolder("win-fallback");
        File launcher = new File(fijiDir, "ImageJ.exe");
        assertTrue(launcher.createNewFile());

        ImageJRestartHelper.LaunchSpec spec = ImageJRestartHelper.resolveLaunchSpec(fijiDir);
        assertNotNull(spec);
        assertEquals(launcher.getAbsolutePath(), spec.getLaunchTarget().getAbsolutePath());
    }

    @Test
    public void macBundleIsOpenedRatherThanExecuted() throws Exception {
        System.setProperty("os.name", "Mac OS X");
        File fijiDir = folder.newFolder("Fiji.app");
        assertTrue(new File(fijiDir, "Contents").mkdirs());

        ImageJRestartHelper.LaunchSpec spec = ImageJRestartHelper.resolveLaunchSpec(fijiDir);
        assertNotNull(spec);
        assertEquals(fijiDir.getAbsolutePath(), spec.getLaunchTarget().getAbsolutePath());
        assertEquals(ImageJRestartHelper.LaunchSpec.MODE_MAC_APP, spec.getMode());
    }

    @Test
    public void macFindsABundleOneLevelDown() throws Exception {
        System.setProperty("os.name", "Mac OS X");
        File parent = folder.newFolder("Applications");
        File bundle = new File(parent, "Fiji.app");
        assertTrue(new File(bundle, "Contents").mkdirs());

        ImageJRestartHelper.LaunchSpec spec = ImageJRestartHelper.resolveLaunchSpec(parent);
        assertNotNull(spec);
        assertEquals(bundle.getAbsolutePath(), spec.getLaunchTarget().getAbsolutePath());
        assertEquals(ImageJRestartHelper.LaunchSpec.MODE_MAC_APP, spec.getMode());
    }

    @Test
    public void linuxResolvesItsOwnLauncher() throws Exception {
        System.setProperty("os.name", "Linux");
        File fijiDir = folder.newFolder("linux-fiji");
        File launcher = new File(fijiDir, "ImageJ-linux64");
        assertTrue(launcher.createNewFile());

        ImageJRestartHelper.LaunchSpec spec = ImageJRestartHelper.resolveLaunchSpec(fijiDir);
        assertNotNull(spec);
        assertEquals(launcher.getAbsolutePath(), spec.getLaunchTarget().getAbsolutePath());
        assertEquals(ImageJRestartHelper.LaunchSpec.MODE_EXECUTABLE, spec.getMode());
    }

    @Test
    public void anEmptyDirectoryResolvesToNothing() throws Exception {
        System.setProperty("os.name", "Linux");
        assertNull(ImageJRestartHelper.resolveLaunchSpec(folder.newFolder("empty")));
    }

    @Test
    public void aMissingDirectoryResolvesToNothing() {
        assertNull(ImageJRestartHelper.resolveLaunchSpec(null));
        assertNull(ImageJRestartHelper.resolveLaunchSpec(new File("no-such-directory-anywhere")));
    }

    // --- generated script text ------------------------------------------------

    @Test
    public void windowsScriptBracesTheLauncherPathBeforeAColon() throws Exception {
        String text = windowsScript(FLASH);
        assertFalse("PowerShell reads $LauncherPath: as an invalid scoped variable",
                text.contains("$LauncherPath:"));
        assertTrue(text.contains("${LauncherPath}"));
    }

    @Test
    public void neitherScriptEvaluatesCommandText() throws Exception {
        String windows = windowsScript(FLASH);
        assertTrue(windows.contains("does not evaluate command text"));
        assertFalse(windows.contains("Invoke-Expression"));
        assertFalse(windows.contains("iex "));

        String posix = posixScript(FLASH);
        assertTrue(posix.contains("does not evaluate command text"));
        assertFalse(posix.contains("eval "));
    }

    @Test
    public void bothScriptsNameTheProductThatWroteThem() throws Exception {
        assertTrue(windowsScript(FLASH).contains("# FLASH ImageJ/Fiji restart helper."));
        assertTrue(windowsScript(PULSE).contains("# PULSE ImageJ/Fiji restart helper."));
        assertTrue(posixScript(FLASH).contains("supplied by FLASH;"));
        assertTrue(posixScript(PULSE).contains("supplied by PULSE;"));
    }

    @Test
    public void bothScriptsRefuseToLaunchASecondCopyAfterTheTimeout() throws Exception {
        String expected = "not launching a second copy";
        assertTrue(windowsScript(FLASH).contains(expected));
        assertTrue(posixScript(FLASH).contains(expected));
        assertTrue(windowsScript(FLASH).contains("$i -lt " + ImageJRestartHelper.WAIT_SECONDS));
        assertTrue(posixScript(FLASH).contains("-ge " + ImageJRestartHelper.WAIT_SECONDS));
    }

    @Test
    public void posixScriptIsPlainShNotBash() throws Exception {
        String posix = posixScript(FLASH);
        assertTrue(posix.startsWith("#!/bin/sh\n"));
        assertFalse("$( ) is not portable to every /bin/sh this runs on", posix.contains("$("));
        assertFalse(posix.contains("[["));
    }

    @Test
    public void scriptsAreWrittenAsUtf8() throws Exception {
        File script = folder.newFile("encoding.ps1");
        new ImageJRestartHelper(FLASH).writeWindowsRestartScript(script);
        byte[] raw = Files.readAllBytes(script.toPath());
        assertFalse("a BOM breaks sh and confuses PowerShell parameter parsing",
                raw.length > 2 && (raw[0] & 0xff) == 0xEF && (raw[1] & 0xff) == 0xBB);
        assertTrue(new String(raw, StandardCharsets.UTF_8).contains("param("));
    }

    // --- argv -----------------------------------------------------------------

    @Test
    public void windowsCommandStaysHiddenWithoutBypassingExecutionPolicy() throws Exception {
        System.setProperty("os.name", "Windows 11");
        File fijiDir = folder.newFolder("argv-fiji");
        assertTrue(new File(fijiDir, "ImageJ-win64.exe").createNewFile());
        File script = folder.newFile("argv.ps1");
        File log = new File(fijiDir, "restart.log");

        ImageJRestartHelper.LaunchSpec spec = ImageJRestartHelper.resolveLaunchSpec(fijiDir);
        List<String> command = ImageJRestartHelper.windowsCommand(script, spec, log);
        String joined = join(command);

        assertTrue(joined.contains("-WindowStyle Hidden"));
        assertTrue(joined.contains("-NonInteractive"));
        assertTrue(joined.contains("-NoProfile"));
        assertFalse("bypassing a machine's script policy is not this plugin's call",
                joined.contains("-ExecutionPolicy"));
        assertFalse(joined.contains("Bypass"));
        assertEquals("powershell.exe", command.get(0));
        assertEquals(log.getAbsolutePath(), command.get(command.size() - 1));
    }

    @Test
    public void posixCommandPassesTheModeLast() throws Exception {
        System.setProperty("os.name", "Linux");
        File fijiDir = folder.newFolder("posix-argv");
        assertTrue(new File(fijiDir, "ImageJ-linux64").createNewFile());
        File script = folder.newFile("argv.sh");
        File log = new File(fijiDir, "restart.log");

        ImageJRestartHelper.LaunchSpec spec = ImageJRestartHelper.resolveLaunchSpec(fijiDir);
        List<String> command = ImageJRestartHelper.posixCommand(script, spec, log);

        assertEquals("sh", command.get(0));
        assertEquals(7, command.size());
        assertEquals(ImageJRestartHelper.LaunchSpec.MODE_EXECUTABLE, command.get(6));
    }

    // --- refusals -------------------------------------------------------------

    @Test
    public void anUnknownPlatformRefusesRatherThanGuessing() {
        System.setProperty("os.name", "TempleOS");
        ImageJRestartHelper.RestartResult result =
                new ImageJRestartHelper(FLASH).launchRestartHelper(new File("."));
        assertFalse(result.isSuccess());
        assertTrue(result.getMessage().contains("not supported on this operating system"));
    }

    @Test
    public void aFijiWithNoLauncherRefusesAndSaysWhereItLooked() throws Exception {
        System.setProperty("os.name", "Linux");
        File empty = folder.newFolder("no-launcher");
        ImageJRestartHelper.RestartResult result =
                new ImageJRestartHelper(FLASH).launchRestartHelper(empty);
        assertFalse(result.isSuccess());
        assertTrue(result.getMessage().contains(empty.getAbsolutePath()));
        assertNull(result.getScriptFile());
    }

    private String windowsScript(Product product) throws Exception {
        File script = folder.newFile("win-" + product.slug() + "-" + System.nanoTime() + ".ps1");
        new ImageJRestartHelper(product).writeWindowsRestartScript(script);
        return new String(Files.readAllBytes(script.toPath()), StandardCharsets.UTF_8);
    }

    private String posixScript(Product product) throws Exception {
        File script = folder.newFile("posix-" + product.slug() + "-" + System.nanoTime() + ".sh");
        new ImageJRestartHelper(product).writePosixRestartScript(script);
        return new String(Files.readAllBytes(script.toPath()), StandardCharsets.UTF_8);
    }

    private static String join(List<String> parts) {
        StringBuilder sb = new StringBuilder();
        for (String part : parts) {
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(part);
        }
        return sb.toString();
    }
}
