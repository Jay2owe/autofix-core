package sc.fiji.autofix.core;

import ij.IJ;
import ij.Prefs;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.lang.reflect.Field;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * The four-rung ladder in {@link FijiLayout} — declared change T3-1.
 *
 * <p>This is the highest-risk item in the whole extraction and it had no tests.
 * Getting it wrong does not throw: it silently repairs, downloads into, or
 * disables jars in the wrong Fiji installation.
 *
 * <p>Only the disagreement cases are interesting. On a machine where one source
 * answers, the order cannot matter; the order matters exactly when several
 * answer and disagree, which is a developer running Fiji from an IDE, or anyone
 * with two Fiji installations. Every rung here is driven to a distinct temporary
 * directory so which one won is unambiguous.
 *
 * <h2>Why rungs 3 and 4 need reflection</h2>
 * They are not independent. {@code Prefs.getImageJDir()} — what
 * {@code IJ.getDirectory("imagej")} calls — resolves
 * {@code Menus.ImageJPath} → {@code Prefs.ImageJDir} → {@code plugins.dir} →
 * {@code user.dir}, while {@code Prefs.getHomeDir()} returns {@code ImageJDir}
 * alone. Both backing fields are package-private, so the only way to make the
 * two rungs disagree is to set them directly.
 */
public class FijiLayoutTest {

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    private String savedFijiDir;
    private String savedIjDir;
    private String savedPluginsDir;
    private Object savedImageJPath;
    private Object savedImageJDir;

    @Before
    public void captureGlobalState() throws Exception {
        savedFijiDir = System.getProperty("fiji.dir");
        savedIjDir = System.getProperty("ij.dir");
        savedPluginsDir = System.getProperty("plugins.dir");
        savedImageJPath = menusImageJPath().get(null);
        savedImageJDir = prefsImageJDir().get(null);
    }

    @After
    public void restoreGlobalState() throws Exception {
        restoreProperty("fiji.dir", savedFijiDir);
        restoreProperty("ij.dir", savedIjDir);
        restoreProperty("plugins.dir", savedPluginsDir);
        menusImageJPath().set(null, savedImageJPath);
        prefsImageJDir().set(null, savedImageJDir);
    }

    // --- rungs 1 and 2: the launcher's own properties ---------------------------

    @Test
    public void fijiDirWinsOverEveryOtherSource() throws Exception {
        File wanted = folder.newFolder("from-fiji-dir");
        System.setProperty("fiji.dir", wanted.getAbsolutePath());
        System.setProperty("ij.dir", folder.newFolder("from-ij-dir").getAbsolutePath());
        setImageJPath(folder.newFolder("from-imagej").getAbsolutePath());
        setImageJDir(folder.newFolder("from-prefs").getAbsolutePath());

        assertResolvesTo(wanted, "fiji.dir");
    }

    @Test
    public void ijDirWinsWhenFijiDirIsUnset() throws Exception {
        File wanted = folder.newFolder("from-ij-dir");
        System.clearProperty("fiji.dir");
        System.setProperty("ij.dir", wanted.getAbsolutePath());
        setImageJPath(folder.newFolder("from-imagej").getAbsolutePath());

        assertResolvesTo(wanted, "ij.dir");
    }

    /**
     * The case that motivated the guard. A stale {@code fiji.dir} left over from a
     * deleted installation must not stop the ladder — it must be stepped over.
     */
    @Test
    public void aPropertyNamingAMissingDirectoryIsSteppedOverNotFatal() throws Exception {
        File wanted = folder.newFolder("from-ij-dir");
        File deleted = folder.newFolder("deleted-fiji");
        assertTrue(deleted.delete());

        System.setProperty("fiji.dir", deleted.getAbsolutePath());
        System.setProperty("ij.dir", wanted.getAbsolutePath());

        assertResolvesTo(wanted, "ij.dir");
    }

    @Test
    public void aPropertyNamingAFileRatherThanADirectoryIsSteppedOver() throws Exception {
        File wanted = folder.newFolder("from-ij-dir");
        File notADirectory = folder.newFile("fiji.app.txt");

        System.setProperty("fiji.dir", notADirectory.getAbsolutePath());
        System.setProperty("ij.dir", wanted.getAbsolutePath());

        assertResolvesTo(wanted, "ij.dir");
    }

    @Test
    public void anEmptyPropertyIsSteppedOver() throws Exception {
        File wanted = folder.newFolder("from-ij-dir");
        System.setProperty("fiji.dir", "");
        System.setProperty("ij.dir", wanted.getAbsolutePath());

        assertResolvesTo(wanted, "ij.dir");
    }

    // --- rung 3: the declared change itself -------------------------------------

    /**
     * <b>T3-1 for FLASH.</b> Before the extraction FLASH went straight from
     * {@code ij.dir} to {@code Prefs.getHomeDir()}. It now asks the running
     * ImageJ first, which is the source that is correct when no launcher set a
     * property — Fiji started from a script or an IDE.
     */
    @Test
    public void imageJsOwnAnswerIsUsedWhenNeitherPropertyIsSet() throws Exception {
        File wanted = folder.newFolder("from-imagej");
        System.clearProperty("fiji.dir");
        System.clearProperty("ij.dir");
        setImageJPath(wanted.getAbsolutePath());
        setImageJDir(folder.newFolder("from-prefs").getAbsolutePath());

        assertResolvesTo(wanted, "IJ.getDirectory");
    }

    /**
     * {@code Prefs.getImageJDir()} appends a separator to whatever it returns. If
     * that survived into the resolved directory, every path built from it —
     * {@code jars/}, {@code plugins/}, the restart log — would carry a doubled
     * separator.
     */
    @Test
    public void theTrailingSeparatorImageJAppendsDoesNotSurvive() throws Exception {
        File wanted = folder.newFolder("from-imagej");
        System.clearProperty("fiji.dir");
        System.clearProperty("ij.dir");
        setImageJPath(wanted.getAbsolutePath() + File.separator);

        File resolved = FijiLayout.resolveFijiDir();
        assertNotNull(resolved);
        assertEquals(wanted.getCanonicalPath(), resolved.getCanonicalPath());
        assertEquals(new File(wanted, "jars").getCanonicalPath(),
                new File(resolved, "jars").getCanonicalPath());
    }

    // --- rung 4: reachable only when rung 3 names something absent --------------

    /**
     * The narrow case that keeps the last rung alive. {@code Prefs.getImageJDir()}
     * prefers {@code Menus.ImageJPath} unconditionally — it does not check that
     * the path exists — so a stale value there would shadow a perfectly good
     * {@code Prefs.ImageJDir} if {@link FijiLayout} trusted rung 3 blindly.
     * Because rung 3 is checked with {@code isDirectory()}, the ladder falls
     * through instead.
     */
    @Test
    public void aStaleImageJPathDoesNotShadowAGoodPrefsHome() throws Exception {
        File wanted = folder.newFolder("from-prefs");
        File deleted = folder.newFolder("deleted-imagej");
        assertTrue(deleted.delete());

        System.clearProperty("fiji.dir");
        System.clearProperty("ij.dir");
        setImageJPath(deleted.getAbsolutePath());
        setImageJDir(wanted.getAbsolutePath());

        assertResolvesTo(wanted, "Prefs.getHomeDir");
    }

    // --- the invariant that stops the two ladders drifting ----------------------

    /**
     * {@link FijiLayout#resolveFijiDir()} and {@link FijiLayout#resolutionSource()}
     * are two hand-written copies of the same ladder. The second exists so a user
     * whose repair landed in the wrong installation can be told why — which is
     * worse than useless if it names a rung the first one did not take.
     *
     * <p>Every rung is exercised in turn and the two must agree on all of them.
     */
    @Test
    public void resolutionSourceNamesTheRungThatActuallyWon() throws Exception {
        File byFijiDir = folder.newFolder("ladder-fiji-dir");
        File byIjDir = folder.newFolder("ladder-ij-dir");
        File byImageJ = folder.newFolder("ladder-imagej");
        File byPrefs = folder.newFolder("ladder-prefs");
        File deleted = folder.newFolder("ladder-deleted");
        assertTrue(deleted.delete());

        // Rung 4: everything above it absent or stale.
        System.clearProperty("fiji.dir");
        System.clearProperty("ij.dir");
        setImageJPath(deleted.getAbsolutePath());
        setImageJDir(byPrefs.getAbsolutePath());
        assertResolvesTo(byPrefs, "Prefs.getHomeDir");

        // Rung 3 becomes available.
        setImageJPath(byImageJ.getAbsolutePath());
        assertResolvesTo(byImageJ, "IJ.getDirectory");

        // Rung 2 takes over.
        System.setProperty("ij.dir", byIjDir.getAbsolutePath());
        assertResolvesTo(byIjDir, "ij.dir");

        // Rung 1 takes over.
        System.setProperty("fiji.dir", byFijiDir.getAbsolutePath());
        assertResolvesTo(byFijiDir, "fiji.dir");
    }

    /**
     * <b>The load-bearing guard.</b> With nothing configured, ImageJ 1.53f's
     * {@code Prefs.getImageJDir()} does not give up — it returns
     * {@code ijPath + File.separator} with {@code ijPath} still null, i.e. the
     * six-character string {@code "null\"}. Measured, not assumed: that is what
     * this JVM returns.
     *
     * <p>Had {@link FijiLayout} trusted rung 3 the way it trusts a system
     * property, {@code resolveFijiDir()} would have returned
     * {@code new File("null")} — a relative path — and the autofix layer would
     * have created a directory called {@code null} next to wherever Fiji was
     * launched from, then downloaded jars into it. Because rung 3 is checked with
     * {@code isDirectory()}, the ladder steps over it and reports {@code "none"}.
     *
     * <p>The assertion is written to hold on any ImageJ version, since Fiji ships
     * a newer {@code ij} than the one compiled against here: whatever
     * {@code IJ.getDirectory} answers, the ladder either uses a directory that
     * exists or admits it found nothing. It must never hand back a path that
     * is not there.
     */
    @Test
    public void aFallbackPathThatDoesNotExistIsNeverHandedBack() throws Exception {
        System.clearProperty("fiji.dir");
        System.clearProperty("ij.dir");
        setImageJPath(null);
        setImageJDir(null);

        String viaImageJ = IJ.getDirectory("imagej");
        File resolved = FijiLayout.resolveFijiDir();
        String source = FijiLayout.resolutionSource();

        if (resolved == null) {
            assertEquals("a null answer must be reported as such", "none", source);
        } else {
            assertTrue("resolved a path that is not a directory: " + resolved
                            + " (IJ.getDirectory said " + viaImageJ + ")",
                    resolved.isDirectory());
            assertTrue("resolved something without naming which rung answered",
                    !"none".equals(source));
        }
    }

    // ------------------------------------------------------------------ helpers

    private void assertResolvesTo(File expected, String expectedSource) throws Exception {
        File resolved = FijiLayout.resolveFijiDir();
        assertNotNull("nothing resolved; expected " + expected, resolved);
        assertEquals(expected.getCanonicalPath(), resolved.getCanonicalPath());
        assertEquals(expectedSource, FijiLayout.resolutionSource());
    }

    private static void restoreProperty(String key, String value) {
        if (value == null) {
            System.clearProperty(key);
        } else {
            System.setProperty(key, value);
        }
    }

    private static void setImageJPath(String path) throws Exception {
        menusImageJPath().set(null, path);
    }

    private static void setImageJDir(String path) throws Exception {
        prefsImageJDir().set(null, path);
    }

    private static Field menusImageJPath() throws Exception {
        Field field = ij.Menus.class.getDeclaredField("ImageJPath");
        field.setAccessible(true);
        return field;
    }

    private static Field prefsImageJDir() throws Exception {
        Field field = Prefs.class.getDeclaredField("ImageJDir");
        field.setAccessible(true);
        return field;
    }
}
