package sc.fiji.autofix.core;

import java.io.File;

/**
 * What a {@link Probe} is allowed to look at: a classloader and the Fiji.app
 * directory. Both are supplied rather than discovered so a test can point a
 * probe at a synthetic tree and an empty classloader without touching the
 * machine it runs on.
 *
 * <p>In production {@link #detect()} builds one from
 * {@link ClassLoaders#fijiClassLoader()} and {@link FijiLayout#resolveFijiDir()}.
 */
public final class ProbeContext {

    private final ClassLoader classLoader;
    private final File fijiDir;

    public ProbeContext(ClassLoader classLoader, File fijiDir) {
        this.classLoader = classLoader;
        this.fijiDir = fijiDir;
    }

    /**
     * The context a real run uses: Fiji's plugin classloader, and Fiji.app as
     * {@link FijiLayout} resolves it.
     */
    public static ProbeContext detect() {
        return new ProbeContext(ClassLoaders.fijiClassLoader(), FijiLayout.resolveFijiDir());
    }

    /**
     * The classloader class probes consult. Never null in a context built by
     * {@link #detect()}; may be null if a caller passed one, in which case
     * {@link Probes#classProbe} falls back to Fiji's.
     */
    public ClassLoader getClassLoader() {
        return classLoader;
    }

    /** The Fiji.app directory, or null when it could not be determined. */
    public File getFijiDir() {
        return fijiDir;
    }
}
