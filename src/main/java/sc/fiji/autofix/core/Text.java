package sc.fiji.autofix.core;

import java.util.List;

/**
 * The string helpers this layer repeats. Package-private: they are shared
 * implementation, not API, and every one of them is load-bearing on text a user
 * reads, so they are pinned by the equivalence goldens.
 */
final class Text {

    private Text() {}

    /** Joins non-blank trimmed lines with a newline. */
    static String joinLines(List<String> lines) {
        if (lines == null || lines.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (String line : lines) {
            if (line == null || line.trim().isEmpty()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append(line.trim());
        }
        return sb.toString();
    }

    /** Joins non-blank trimmed items with ", ". */
    static String joinComma(List<String> items) {
        if (items == null || items.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (String item : items) {
            if (item == null || item.trim().isEmpty()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(item.trim());
        }
        return sb.toString();
    }

    /**
     * A throwable's message, or a stand-in. Never null, so it can be
     * concatenated into a status detail without a guard at every site.
     */
    static String safeMessage(Throwable throwable) {
        if (throwable == null || throwable.getMessage() == null || throwable.getMessage().trim().isEmpty()) {
            return "no detail message";
        }
        return throwable.getMessage().trim();
    }

    /**
     * The class name out of a {@link NoClassDefFoundError}. The JVM reports it
     * slash-separated, and sometimes wrapped in "Could not initialize class",
     * neither of which is what a user recognises.
     */
    static String normalizeMissingClass(NoClassDefFoundError error) {
        String message = error == null ? "" : error.getMessage();
        if (message == null || message.trim().isEmpty()) {
            return "unknown";
        }
        String trimmed = message.trim();
        if (trimmed.startsWith("Could not initialize class ")) {
            trimmed = trimmed.substring("Could not initialize class ".length()).trim();
        }
        return trimmed.replace('/', '.');
    }
}
