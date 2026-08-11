package sc.fiji.autofix.core;

import ij.IJ;

/**
 * Which classloader a class probe should consult.
 *
 * <p>This is not a detail. Fiji's {@code PluginClassLoader} is the only loader
 * that resolves standalone {@code .class} files dropped into {@code plugins/} —
 * the default-package, no-jar form that several venerable ImageJ 1.x plugins
 * still ship as. A plugin's own loader cannot see them, so a probe using it
 * reports a correctly-installed plugin as missing, and the user is invited to
 * reinstall something they already have.
 *
 * <p>FLASH probed with its own loader and worked around the consequence in its
 * catalogue: its {@code Iterative Deconvolve 3D} entry carries a comment
 * explaining that {@code Class.forName("Iterative_Deconvolve_3D")} always fails
 * from FLASH's context, and falls back to a menu-command probe. PULSE probed
 * with {@link IJ#getClassLoader()} and had no such problem.
 *
 * <p>PULSE's is the stricter rule and it is now the rule for both.
 * <b>Declared behaviour change</b> (Tier 3 in {@code EQUIVALENCE_HARNESS.md}):
 * a FLASH dependency that is installed as a bare class file can flip from
 * MISSING to PRESENT. That is the correct answer, arrived at late.
 */
public final class ClassLoaders {

    private ClassLoaders() {}

    /**
     * Fiji's plugin classloader, falling back to this module's own when ImageJ
     * is not initialised — which is the case in a headless unit test, where the
     * fallback is what makes the probe testable at all.
     */
    public static ClassLoader fijiClassLoader() {
        try {
            ClassLoader loader = IJ.getClassLoader();
            if (loader != null) {
                return loader;
            }
        } catch (Throwable ignored) {
            // ImageJ not initialised.
        }
        return ClassLoaders.class.getClassLoader();
    }
}
