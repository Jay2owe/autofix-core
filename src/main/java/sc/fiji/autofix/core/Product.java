package sc.fiji.autofix.core;

import java.util.Locale;

/**
 * Which plugin is doing the fixing.
 *
 * <p>This exists because the old code baked "FLASH" and "PULSE" into filenames
 * it writes into the user's Fiji.app and temp directory: a restart log, a
 * repair log, two helper scripts and a writability probe file. Those names
 * survive the process that wrote them and are the only trace a user has when a
 * restart or a jar swap goes wrong, so they must keep naming the plugin that
 * caused them rather than the shared module.
 *
 * <p>Shared code that writes a file a human might later find takes one of
 * these. Shared code that does not, does not.
 *
 * <pre>{@code
 * private static final Product FLASH = Product.named("FLASH");
 * // -> "FLASH-restart-imagej.log", "flash-runtime-disable-1234.ps1"
 * }</pre>
 */
public final class Product {

    private final String displayName;
    private final String slug;

    private Product(String displayName, String slug) {
        this.displayName = displayName;
        this.slug = slug;
    }

    /**
     * A product whose lowercase slug is derived from {@code displayName} —
     * {@code "FLASH"} gives {@code "flash"}. Correct for both current
     * consumers; use {@link #of(String, String)} when they must differ.
     *
     * @throws IllegalArgumentException if {@code displayName} is blank, since a
     *         blank name would silently produce filenames like
     *         {@code "-restart-imagej.log"} that name nothing.
     */
    public static Product named(String displayName) {
        String trimmed = displayName == null ? "" : displayName.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("A product needs a display name; it ends up in filenames.");
        }
        return new Product(trimmed, slugify(trimmed));
    }

    /** A product whose slug is not simply the lowercase display name. */
    public static Product of(String displayName, String slug) {
        String trimmedName = displayName == null ? "" : displayName.trim();
        String trimmedSlug = slug == null ? "" : slug.trim();
        if (trimmedName.isEmpty()) {
            throw new IllegalArgumentException("A product needs a display name; it ends up in filenames.");
        }
        if (trimmedSlug.isEmpty()) {
            throw new IllegalArgumentException("A product needs a slug; it ends up in filenames.");
        }
        return new Product(trimmedName, trimmedSlug);
    }

    /** The name as a user sees it in a message — "FLASH". */
    public String displayName() {
        return displayName;
    }

    /** The name as it appears in a lowercase filename — "flash". */
    public String slug() {
        return slug;
    }

    @Override
    public String toString() {
        return displayName;
    }

    private static String slugify(String value) {
        StringBuilder sb = new StringBuilder();
        String lower = value.toLowerCase(Locale.ROOT);
        for (int i = 0; i < lower.length(); i++) {
            char c = lower.charAt(i);
            if (Character.isLetterOrDigit(c)) {
                sb.append(c);
            } else if (c == ' ' || c == '_' || c == '-') {
                sb.append('-');
            }
        }
        String slug = sb.toString();
        return slug.isEmpty() ? "plugin" : slug;
    }
}
