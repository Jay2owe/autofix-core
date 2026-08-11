package sc.fiji.autofix.core;

import java.util.Locale;

/**
 * Which operating system this is, read from {@code os.name} every time rather
 * than cached in a static.
 *
 * <p>Re-reading looks wasteful and is not: the tests that cover launcher
 * resolution and restart-script generation set {@code os.name} to drive each
 * platform branch, and a cached value would silently pin whichever platform
 * happened to load the class first. That would make the Windows and macOS
 * branches untestable on a Linux CI runner, which is where they are checked.
 */
final class Os {

    private Os() {}

    static boolean isWindows() {
        String os = System.getProperty("os.name");
        return os != null && os.toLowerCase(Locale.ROOT).contains("win");
    }

    static boolean isMac() {
        String os = System.getProperty("os.name");
        return os != null && os.toLowerCase(Locale.ROOT).contains("mac");
    }

    static boolean isUnixLike() {
        String os = System.getProperty("os.name");
        if (os == null) {
            return false;
        }
        String lower = os.toLowerCase(Locale.ROOT);
        return lower.contains("linux")
                || lower.contains("unix")
                || lower.contains("bsd")
                || lower.contains("sunos")
                || lower.contains("aix");
    }

    /** True when this platform has a restart strategy at all. */
    static boolean isSupported() {
        return isWindows() || isMac() || isUnixLike();
    }

    /** This JVM's process id, or "" if it cannot be determined. */
    static String currentProcessId() {
        String runtimeName = java.lang.management.ManagementFactory.getRuntimeMXBean().getName();
        if (runtimeName == null) {
            return "";
        }
        int at = runtimeName.indexOf('@');
        String pid = at >= 0 ? runtimeName.substring(0, at) : runtimeName;
        return pid == null ? "" : pid.trim();
    }
}
