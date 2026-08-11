package sc.fiji.autofix.core;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertTrue;

/**
 * The properties that make this module embeddable, asserted against the
 * compiled bytecode rather than trusted from the source.
 *
 * <p>Every rule here is easy to break by accident and invisible until a
 * consumer ships — at which point the fix is a release of every plugin that
 * embeds this one.
 */
public class EmbeddabilityTest {

    /** Every class this module ships. Update when adding one. */
    private static final List<String> MODULE_CLASSES = Arrays.asList(
            "AbstractJarDependencyFixer",
            "Artifacts",
            "BooleanProbe",
            "ClassLoaders",
            "DependencyFixPlan",
            "DependencyFixResult",
            "DependencyFixer",
            "DependencyKey",
            "DependencyServiceCore",
            "DependencySpec",
            "DependencyStatus",
            "FijiLayout",
            "ImageJRestartHelper",
            "JarDependencyFixer",
            "Os",
            "Probe",
            "ProbeContext",
            "Probes",
            "Product",
            "Sizes",
            "SpecCatalogue",
            "Text");

    /**
     * No Swing, no dialogs, no {@code IJ.error}, no {@code System.exit}.
     *
     * <p>This layer runs during startup and inside batch jobs. A dialog here
     * blocks a cluster run forever with nobody to dismiss it, and a
     * {@code System.exit} in a dependency check would take out the user's whole
     * Fiji session along with every unsaved image in it. The core throws or
     * returns a status; the plugin decides how to present it.
     */
    @Test
    public void noClassTakesOverUserInteractionOrProcessControl() throws Exception {
        List<String> offenders = new ArrayList<String>();
        for (Class<?> type : moduleClasses()) {
            String bytecode = constantPoolText(type);
            for (String banned : Arrays.asList(
                    "javax/swing", "java/awt/Dialog", "java/awt/Frame", "java/awt/Window",
                    "ij/gui/GenericDialog", "ij/gui/NonBlockingGenericDialog",
                    "ij/IJ.error", "ij/IJ.showMessage",
                    "java/lang/System.exit", "java/lang/Runtime.exit", "java/lang/Runtime.halt")) {
                if (bytecode.contains(banned)) {
                    offenders.add(type.getName() + " references " + banned);
                }
            }
        }
        assertTrue(offenders.toString(), offenders.isEmpty());
    }

    /** No {@code ResultsTable} anywhere: cores return models, not the host's tables. */
    @Test
    public void noPublicMethodTouchesAnImageJTable() throws Exception {
        List<String> offenders = new ArrayList<String>();
        for (Class<?> type : moduleClasses()) {
            for (Method method : type.getMethods()) {
                if ("ij.measure.ResultsTable".equals(method.getReturnType().getName())) {
                    offenders.add(type.getName() + "." + method.getName());
                }
                for (Class<?> parameter : method.getParameterTypes()) {
                    if ("ij.measure.ResultsTable".equals(parameter.getName())) {
                        offenders.add(type.getName() + "." + method.getName() + " parameter");
                    }
                }
            }
        }
        assertTrue(offenders.toString(), offenders.isEmpty());
    }

    /**
     * <b>The relocation trap.</b> Shading rewrites bytecode references but also
     * rewrites any string constant that looks like the relocated package — so a
     * literal naming this module's own package is silently renamed into each
     * consumer's private namespace, and two plugins stop agreeing about a value
     * the documentation says is fixed.
     *
     * <p>{@code cpc-core} shipped with five such literals (system-property and
     * image-property names beginning {@code sc.fiji.oc3d.core.}). This test is
     * why this module will not.
     */
    @Test
    public void noStringConstantNamesThisModulesPackage() throws Exception {
        List<String> offenders = new ArrayList<String>();
        for (Class<?> type : moduleClasses()) {
            if (constantPoolText(type).contains("sc.fiji.autofix")) {
                offenders.add(type.getName() + " contains a literal naming its own package; "
                        + "relocation would rewrite it");
            }
        }
        assertTrue(offenders.toString(), offenders.isEmpty());
    }

    /**
     * Reflection is confined to {@link Probes}, where it is the entire point.
     *
     * <p>The class names it looks up come from a consumer's catalogue and name
     * third-party classes, so relocation cannot touch them. A reflective lookup
     * appearing anywhere else in this module would almost certainly be naming
     * something relocatable and would break silently after shading — hence a
     * whitelist rather than a ban.
     */
    @Test
    public void reflectionByNameIsConfinedToTheProbes() throws Exception {
        List<String> offenders = new ArrayList<String>();
        for (Class<?> type : moduleClasses()) {
            if (Probes.class.getName().equals(type.getName())) {
                continue;
            }
            String bytecode = constantPoolText(type);
            for (String banned : Arrays.asList(
                    "java/lang/Class.forName", "loadClass", "getDeclaredMethod", "getDeclaredField")) {
                if (bytecode.contains(banned)) {
                    offenders.add(type.getName() + " references " + banned
                            + "; reflection belongs in Probes, where the names are third-party");
                }
            }
        }
        assertTrue(offenders.toString(), offenders.isEmpty());
    }

    /**
     * Nothing outside {@code ij} and the JDK. Embedding this module must cost a
     * consumer's user no extra install — that is the whole reason it exists.
     *
     * <p>{@code net.imagej.updater} is called out specifically: reaching for it
     * is the obvious way to "improve" this layer, and it would drag the whole
     * SciJava stack into every consumer's jar.
     */
    @Test
    public void nothingReferencesAPackageBeyondIjAndTheJdk() throws Exception {
        List<String> offenders = new ArrayList<String>();
        for (Class<?> type : moduleClasses()) {
            String bytecode = constantPoolText(type);
            for (String banned : Arrays.asList(
                    "net/imagej/updater", "net/imagej/ui", "org/scijava",
                    "loci/plugins", "loci/formats",
                    "fiji/plugin/trackmate", "mcib3d", "org/apache/poi",
                    "de/csbdresden", "org/tensorflow")) {
                if (bytecode.contains(banned)) {
                    offenders.add(type.getName() + " references " + banned);
                }
            }
        }
        assertTrue(offenders.toString(), offenders.isEmpty());
    }

    /**
     * This module <em>does</em> take {@link File} and <em>does</em> use
     * {@link Class#forName} — unlike an engine core, where both are banned. It
     * writes jars into Fiji.app and probes classes by name, and it cannot do
     * its job otherwise.
     *
     * <p>Asserted rather than merely documented so that a future reader
     * applying the engine-core checklist to this module does not "fix" it into
     * uselessness.
     */
    @Test
    public void filesystemAndReflectionAreDeliberatelyInScopeHere() throws Exception {
        assertTrue("Artifacts must reach the filesystem",
                takesOrReturnsFile(Artifacts.class));
        assertTrue("the restart helper must reach the filesystem",
                takesOrReturnsFile(ImageJRestartHelper.class));
        assertTrue("Probes must look classes up by name",
                constantPoolText(Probes.class).contains("java/lang/Class"));
    }

    private static boolean takesOrReturnsFile(Class<?> type) {
        for (Method method : type.getMethods()) {
            if (File.class.equals(method.getReturnType())) {
                return true;
            }
            for (Class<?> parameter : method.getParameterTypes()) {
                if (File.class.equals(parameter)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static List<Class<?>> moduleClasses() throws Exception {
        List<Class<?>> classes = new ArrayList<Class<?>>();
        for (String name : MODULE_CLASSES) {
            classes.add(Class.forName("sc.fiji.autofix.core." + name));
        }
        return classes;
    }

    /** The class file as Latin-1 text — enough to spot a referenced name. */
    private static String constantPoolText(Class<?> type) throws Exception {
        String resource = type.getName().replace('.', '/') + ".class";
        InputStream in = type.getClassLoader().getResourceAsStream(resource);
        assertTrue("class file not found: " + resource, in != null);
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) > 0) {
                out.write(buffer, 0, read);
            }
            return new String(out.toByteArray(), "ISO-8859-1");
        } finally {
            in.close();
        }
    }
}
