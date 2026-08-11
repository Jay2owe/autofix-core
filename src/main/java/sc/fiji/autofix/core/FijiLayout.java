package sc.fiji.autofix.core;

import ij.IJ;
import ij.Prefs;

import java.io.File;

/**
 * Where Fiji.app is on this machine.
 *
 * <p>Everything this layer does to repair a dependency — checking a jar,
 * downloading one, disabling a rival version, finding the launcher to restart —
 * is relative to this directory. Getting it wrong does not throw; it quietly
 * repairs the wrong installation.
 *
 * <h2>The resolution order, and why it changed</h2>
 * FLASH and PULSE disagreed. FLASH asked {@code fiji.dir}, then {@code ij.dir},
 * then {@link Prefs#getHomeDir()}. PULSE asked {@link IJ#getDirectory(String)},
 * then {@code ij.dir}, and wrapped every step so a failure could not escape.
 * This module takes the union, in strictness order:
 *
 * <ol>
 *   <li>{@code fiji.dir} — set by the Fiji launcher; the most specific answer</li>
 *   <li>{@code ij.dir} — set by the ImageJ launcher</li>
 *   <li>{@code IJ.getDirectory("imagej")} — ImageJ's own answer, and the one
 *       that is correct when neither property was set, which is the case when
 *       Fiji was started from an IDE or a script</li>
 *   <li>{@link Prefs#getHomeDir()} — the last resort; it is the preferences
 *       home, which is usually but not always Fiji.app</li>
 * </ol>
 *
 * <p>Each step is guarded, PULSE-style: a probe that throws while looking for a
 * directory must not take down the sweep that called it.
 *
 * <p><b>This is a declared behaviour change for both plugins</b> (Tier 3 in
 * {@code EQUIVALENCE_HARNESS.md}). FLASH gains the {@code IJ.getDirectory} step
 * ahead of its weakest fallback; PULSE gains the two system properties ahead of
 * everything. On a machine where only one source is set — every ordinary Fiji
 * install — the answer is unchanged.
 */
public final class FijiLayout {

    private FijiLayout() {}

    /** The Fiji.app directory, or null when no source could name an existing one. */
    public static File resolveFijiDir() {
        File fromFijiProperty = directoryFromProperty("fiji.dir");
        if (fromFijiProperty != null) {
            return fromFijiProperty;
        }

        File fromIjProperty = directoryFromProperty("ij.dir");
        if (fromIjProperty != null) {
            return fromIjProperty;
        }

        try {
            String path = IJ.getDirectory("imagej");
            if (path != null) {
                File dir = new File(path);
                if (dir.isDirectory()) {
                    return dir;
                }
            }
        } catch (Throwable ignored) {
            // ImageJ may not be initialised; fall through to the preferences home.
        }

        try {
            String prefsDir = Prefs.getHomeDir();
            if (prefsDir != null) {
                File dir = new File(prefsDir);
                if (dir.isDirectory()) {
                    return dir;
                }
            }
        } catch (Throwable ignored) {
            // Nothing left to try.
        }

        return null;
    }

    /**
     * Which of the four sources {@link #resolveFijiDir()} would use, as a short
     * token — {@code "fiji.dir"}, {@code "ij.dir"}, {@code "IJ.getDirectory"},
     * {@code "Prefs.getHomeDir"} or {@code "none"}. Reported in diagnostics so a
     * user whose repair landed in the wrong installation can see why.
     */
    public static String resolutionSource() {
        if (directoryFromProperty("fiji.dir") != null) {
            return "fiji.dir";
        }
        if (directoryFromProperty("ij.dir") != null) {
            return "ij.dir";
        }
        try {
            String path = IJ.getDirectory("imagej");
            if (path != null && new File(path).isDirectory()) {
                return "IJ.getDirectory";
            }
        } catch (Throwable ignored) {
            // fall through
        }
        try {
            String prefsDir = Prefs.getHomeDir();
            if (prefsDir != null && new File(prefsDir).isDirectory()) {
                return "Prefs.getHomeDir";
            }
        } catch (Throwable ignored) {
            // fall through
        }
        return "none";
    }

    private static File directoryFromProperty(String key) {
        try {
            String value = System.getProperty(key);
            if (value == null) {
                return null;
            }
            File dir = new File(value);
            return dir.isDirectory() ? dir : null;
        } catch (Throwable ignored) {
            return null;
        }
    }
}
