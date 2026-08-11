package sc.fiji.autofix.core;

import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.Locale;

/**
 * Download-size wording. This is the number that decides whether a user clicks
 * the button, so the rendering is pinned by the equivalence goldens rather than
 * left to a locale-sensitive default.
 */
public final class Sizes {

    private Sizes() {}

    /**
     * A download size as it appears inside a button caption — {@code "(~2.5 GB)"},
     * {@code "(~13.2 MB)"}, {@code "(~22 KB)"}. Empty for a negative input.
     *
     * <p>Rounded to one decimal place at MB and above and to whole KB below,
     * always in {@link Locale#ROOT} so a user with a comma decimal separator
     * sees the same string as everyone else and a golden stays comparable.
     * Anything under 1 KB still reads as {@code "(~1 KB)"} — deliberately, since
     * "(~0 KB)" reads as a bug rather than as "small".
     */
    public static String formatApproxSize(long bytes) {
        if (bytes < 0L) {
            return "";
        }
        if (bytes == 0L) {
            return "(~0 B)";
        }
        if (bytes >= (1L << 30)) {
            return "(~" + formatDecimal(bytes / (double) (1L << 30)) + " GB)";
        }
        if (bytes >= (1L << 20)) {
            return "(~" + formatDecimal(bytes / (double) (1L << 20)) + " MB)";
        }
        long kb = Math.max(1L, Math.round(bytes / 1024.0));
        return "(~" + kb + " KB)";
    }

    private static String formatDecimal(double value) {
        DecimalFormatSymbols symbols = DecimalFormatSymbols.getInstance(Locale.ROOT);
        DecimalFormat format = new DecimalFormat("0.#", symbols);
        return format.format(value);
    }
}
