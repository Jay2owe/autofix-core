package sc.fiji.autofix.core;

import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Jar checking, disabling and verification against a synthetic Fiji.app. */
public class ArtifactsTest {

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    @After
    public void restoreJarOps() {
        Artifacts.setJarFileOpsForTesting(null);
    }

    private static DependencySpec.Artifact jts(String expected) {
        return new DependencySpec.Artifact(
                "JTS core", expected, "jts-core-", "jars",
                "https://example.invalid/" + expected, false);
    }

    // --- check ---------------------------------------------------------------

    @Test
    public void aMissingJarIsNamedAlongWithWhatIsNeeded() throws Exception {
        File fiji = folder.newFolder("fiji-missing");
        assertTrue(new File(fiji, "jars").mkdirs());

        List<String> issues = Artifacts.check(fiji,
                Collections.singletonList(jts("jts-core-1.19.0.jar")), null);

        assertEquals(1, issues.size());
        assertEquals("JTS core: MISSING (need jts-core-1.19.0.jar)", issues.get(0));
    }

    @Test
    public void aWrongVersionSaysWhatWasFoundAndWhatIsWanted() throws Exception {
        File fiji = folder.newFolder("fiji-wrong");
        File jars = new File(fiji, "jars");
        assertTrue(jars.mkdirs());
        touch(new File(jars, "jts-core-1.18.0.jar"));

        List<String> issues = Artifacts.check(fiji,
                Collections.singletonList(jts("jts-core-1.19.0.jar")), null);

        assertEquals(1, issues.size());
        assertEquals("JTS core: found jts-core-1.18.0.jar, need jts-core-1.19.0.jar", issues.get(0));
    }

    @Test
    public void aCorrectInstallReportsNothing() throws Exception {
        File fiji = folder.newFolder("fiji-good");
        File jars = new File(fiji, "jars");
        assertTrue(jars.mkdirs());
        touch(new File(jars, "jts-core-1.19.0.jar"));

        assertTrue(Artifacts.check(fiji,
                Collections.singletonList(jts("jts-core-1.19.0.jar")), null).isEmpty());
    }

    /**
     * Fiji loads both {@code plugins/} and {@code jars/}, so a stale copy in
     * the other folder shadows the good one just as effectively as one beside
     * it. The check has to look in both or it certifies a broken install.
     */
    @Test
    public void aRivalJarInTheOtherFolderIsStillAConflict() throws Exception {
        File fiji = folder.newFolder("fiji-cross");
        File jars = new File(fiji, "jars");
        File plugins = new File(fiji, "plugins");
        assertTrue(jars.mkdirs());
        assertTrue(plugins.mkdirs());
        touch(new File(jars, "jts-core-1.19.0.jar"));
        touch(new File(plugins, "jts-core-1.18.0.jar"));

        List<String> issues = Artifacts.check(fiji,
                Collections.singletonList(jts("jts-core-1.19.0.jar")), null);

        assertEquals(1, issues.size());
        assertTrue(issues.get(0), issues.get(0).contains("conflicting extra jar(s) in plugins/"));
        assertTrue(issues.get(0), issues.get(0).contains("jts-core-1.18.0.jar"));
    }

    /**
     * The digit check after the prefix. Without it, disabling wrong versions of
     * one dependency would rename jars belonging to another whose name merely
     * starts the same way.
     */
    @Test
    public void aLongerNameSharingThePrefixIsNotTreatedAsAVersion() throws Exception {
        File fiji = folder.newFolder("fiji-prefix");
        File jars = new File(fiji, "jars");
        assertTrue(jars.mkdirs());
        touch(new File(jars, "jts-core-1.19.0.jar"));
        touch(new File(jars, "jts-core-extras-1.0.jar"));

        assertTrue("jts-core-extras- is a different artifact, not a rival version",
                Artifacts.check(fiji,
                        Collections.singletonList(jts("jts-core-1.19.0.jar")), null).isEmpty());
    }

    @Test
    public void anIgnoredPrefixIsSkipped() throws Exception {
        File fiji = folder.newFolder("fiji-ignore");
        File jars = new File(fiji, "jars");
        assertTrue(jars.mkdirs());
        touch(new File(jars, "jts-core-1.19.0.jar"));
        touch(new File(jars, "jts-core-1.18.0.jar"));

        assertFalse(Artifacts.check(fiji,
                Collections.singletonList(jts("jts-core-1.19.0.jar")), null).isEmpty());
        assertTrue(Artifacts.check(fiji,
                Collections.singletonList(jts("jts-core-1.19.0.jar")),
                Collections.singletonList("jts-core-1.18")).isEmpty());
    }

    @Test
    public void acceptAnyExistingTakesWhateverVersionIsThere() throws Exception {
        File fiji = folder.newFolder("fiji-any");
        File jars = new File(fiji, "jars");
        assertTrue(jars.mkdirs());
        touch(new File(jars, "jts-core-1.18.0.jar"));

        DependencySpec.Artifact lenient = new DependencySpec.Artifact(
                "JTS core", "jts-core-1.19.0.jar", "jts-core-", "jars", "", true);
        assertTrue(Artifacts.check(fiji, Collections.singletonList(lenient), null).isEmpty());
    }

    @Test
    public void anUnknownFijiDirectoryIsAnIssueNotAnException() {
        List<String> issues = Artifacts.check(null,
                Collections.singletonList(jts("jts-core-1.19.0.jar")), null);
        assertEquals(Collections.singletonList("Could not determine the Fiji.app directory."), issues);
    }

    // --- repair --------------------------------------------------------------

    @Test
    public void aRivalVersionIsRenamedNotDeleted() throws Exception {
        File fiji = folder.newFolder("fiji-disable");
        File jars = new File(fiji, "jars");
        assertTrue(jars.mkdirs());
        touch(new File(jars, "jts-core-1.19.0.jar"));
        File rival = new File(jars, "jts-core-1.18.0.jar");
        touch(rival);

        List<String> actions = Artifacts.repair(fiji,
                Collections.singletonList(jts("jts-core-1.19.0.jar")), null);

        assertTrue(actions.toString(), actions.contains("Disabled: jts-core-1.18.0.jar"));
        assertFalse("the rival must be gone from its old name", rival.exists());
        assertTrue("but must still exist under a .disabled- name",
                disabledCopyExists(jars, "jts-core-1.18.0.jar"));
        assertTrue(Artifacts.hasDisabledJarActions(actions));
    }

    @Test
    public void aMissingDownloadUrlFailsWithAReasonRatherThanSilently() throws Exception {
        File fiji = folder.newFolder("fiji-nourl");
        assertTrue(new File(fiji, "jars").mkdirs());
        DependencySpec.Artifact noUrl = new DependencySpec.Artifact(
                "JTS core", "jts-core-1.19.0.jar", "jts-core-", "jars", "", false);

        List<String> actions = Artifacts.repair(fiji, Collections.singletonList(noUrl), null);

        assertEquals(1, actions.size());
        assertEquals("FAILED to download jts-core-1.19.0.jar: no download URL is defined.",
                actions.get(0));
    }

    @Test
    public void aLockedJarThatCannotEvenBeScheduledSaysWhatToDoByHand() throws Exception {
        File fiji = folder.newFolder("fiji-locked");
        File jars = new File(fiji, "jars");
        assertTrue(jars.mkdirs());
        touch(new File(jars, "jts-core-1.19.0.jar"));
        touch(new File(jars, "jts-core-1.18.0.jar"));

        Artifacts.setJarFileOpsForTesting(new Artifacts.JarFileOps() {
            @Override
            public boolean rename(File source, File disabled) {
                return false;
            }

            @Override
            public boolean scheduleDisable(File source, File disabled) {
                return false;
            }
        });

        List<String> actions = Artifacts.repair(fiji,
                Collections.singletonList(jts("jts-core-1.19.0.jar")), null);

        assertEquals(1, actions.size());
        assertTrue(actions.get(0), actions.get(0).startsWith("FAILED to disable: jts-core-1.18.0.jar"));
        assertTrue(actions.get(0), actions.get(0).contains("rename or remove this jar manually"));
        assertFalse("a failure to disable is not a disable", Artifacts.hasDisabledJarActions(actions));
    }

    @Test
    public void aScheduledDisableIsReportedAndNotReattempted() throws Exception {
        File fiji = folder.newFolder("fiji-scheduled");
        File jars = new File(fiji, "jars");
        assertTrue(jars.mkdirs());
        touch(new File(jars, "jts-core-1.19.0.jar"));
        touch(new File(jars, "jts-core-1.18.0.jar"));

        final List<String> scheduled = new ArrayList<String>();
        Artifacts.setJarFileOpsForTesting(new Artifacts.JarFileOps() {
            @Override
            public boolean rename(File source, File disabled) {
                return false;
            }

            @Override
            public boolean scheduleDisable(File source, File disabled) {
                scheduled.add(source.getName());
                return true;
            }
        });

        List<String> first = Artifacts.repair(fiji,
                Collections.singletonList(jts("jts-core-1.19.0.jar")), null);
        assertTrue(first.toString(),
                first.contains("Scheduled disable after Fiji closes: jts-core-1.18.0.jar"));

        // Already scheduled: the second pass must not queue it a second time.
        List<String> second = Artifacts.repair(fiji,
                Collections.singletonList(jts("jts-core-1.19.0.jar")), null);
        assertTrue(second.toString(), second.isEmpty());
        assertEquals(1, scheduled.size());
    }

    // --- restore guidance -----------------------------------------------------

    @Test
    public void restoreGuidanceNamesTheProductAndTheRuntime() {
        String guidance = Artifacts.disabledJarRestoreGuidance(Product.named("FLASH"), "StarDist");
        assertTrue(guidance, guidance.contains("Re-run Auto-Fix for StarDist"));
        assertTrue(guidance, guidance.contains("the pinned FLASH workflow"));
        assertTrue(guidance, guidance.contains("are not deleted"));

        String pulse = Artifacts.disabledJarRestoreGuidance(Product.named("PULSE"), "");
        assertTrue(pulse, pulse.contains("Re-run Auto-Fix for this runtime"));
        assertTrue(pulse, pulse.contains("the pinned PULSE workflow"));
    }

    // --- SHA-1 ----------------------------------------------------------------

    @Test
    public void sha1MatchesTheKnownDigestOfAbc() throws Exception {
        File file = folder.newFile("abc.bin");
        Files.write(file.toPath(), "abc".getBytes(StandardCharsets.US_ASCII));
        assertEquals("a9993e364706816aba3e25717850c26c9cd0d89d", Artifacts.sha1(file));
    }

    @Test
    public void verifyIsANoOpWhenNoDigestWasDeclared() throws Exception {
        File file = folder.newFile("undeclared.bin");
        Files.write(file.toPath(), "abc".getBytes(StandardCharsets.US_ASCII));
        Artifacts.verifySha1(file, null);
        Artifacts.verifySha1(file, "   ");
    }

    @Test
    public void aDigestMismatchSaysBothValues() throws Exception {
        File file = folder.newFile("mismatch.bin");
        Files.write(file.toPath(), "abc".getBytes(StandardCharsets.US_ASCII));
        try {
            Artifacts.verifySha1(file, "0000000000000000000000000000000000000000");
            org.junit.Assert.fail("expected a mismatch");
        } catch (Exception e) {
            assertTrue(e.getMessage(), e.getMessage().contains("SHA-1 mismatch for mismatch.bin"));
            assertTrue(e.getMessage(), e.getMessage().contains("a9993e364706816aba3e25717850c26c9cd0d89d"));
        }
    }

    @Test
    public void digestComparisonIsCaseInsensitive() throws Exception {
        File file = folder.newFile("case.bin");
        Files.write(file.toPath(), "abc".getBytes(StandardCharsets.US_ASCII));
        Artifacts.verifySha1(file, "A9993E364706816ABA3E25717850C26C9CD0D89D");
    }

    // --- deferred disable script ----------------------------------------------

    @Test
    public void theDeferredDisableScriptWaitsAndNeverEvaluatesCommandText() throws Exception {
        File script = folder.newFile("deferred.ps1");
        Artifacts.writeDeferredDisableScript(script);
        String text = new String(Files.readAllBytes(script.toPath()), StandardCharsets.UTF_8);

        assertTrue(text.contains("param([string]$ParentPid,[string]$SourcePath,"
                + "[string]$DestPath,[string]$LogPath)"));
        assertTrue("must wait for the JVM holding the lock to exit",
                text.contains("while (Get-Process -Id ([int]$ParentPid)"));
        assertTrue("must brace the variable before a colon", text.contains("${SourcePath}"));
        assertFalse(text.contains("Invoke-Expression"));
        assertTrue("renames, never deletes", text.contains("Rename-Item"));
        assertFalse("must never delete the user's jar", text.contains("Remove-Item -LiteralPath $SourcePath"));
    }

    private static void touch(File file) throws Exception {
        FileOutputStream out = new FileOutputStream(file);
        try {
            out.write(new byte[]{1, 2, 3});
        } finally {
            out.close();
        }
    }

    private static boolean disabledCopyExists(File dir, String originalName) {
        File[] files = dir.listFiles();
        if (files == null) {
            return false;
        }
        for (File file : files) {
            if (file.getName().startsWith(originalName + ".disabled-")) {
                return true;
            }
        }
        return false;
    }

    /** Guards the guard: the helpers above must be able to fail. */
    @Test
    public void theDisabledCopyDetectorCanActuallyFail() throws Exception {
        File dir = folder.newFolder("negative-control");
        assertFalse(disabledCopyExists(dir, "jts-core-1.18.0.jar"));
        assertFalse(Artifacts.hasDisabledJarActions(Arrays.asList("Downloaded: x.jar")));
        assertFalse(Artifacts.hasDisabledJarActions(null));
    }
}
