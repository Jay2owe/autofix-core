package sc.fiji.autofix.core;

/**
 * Identity of one dependency, as named by the plugin that needs it.
 *
 * <p><b>Why this is an interface and not an enum.</b> Every plugin's list of
 * dependencies is different — FLASH names twenty, PULSE names ten, and the two
 * sets do not intersect at all. An enum in this module would therefore either
 * be empty or be the union of every consumer's catalogue, which is the one
 * thing {@code PLUGIN_CORE_PATTERN.md} says a core must not become.
 *
 * <p>A plugin's own {@code DependencyId} enum implements this, which costs one
 * word at the declaration and nothing at any call site:
 *
 * <pre>{@code
 * public enum DependencyId implements DependencyKey {
 *     STARDIST_RUNTIME, TENSORFLOW_NATIVE_RUNTIME, ...
 * }
 * }</pre>
 *
 * <p>{@link Enum#name()} already satisfies this interface, so nothing is
 * implemented by hand, and {@code EnumMap}, {@code EnumSet}, {@code values()},
 * {@code valueOf(String)} and {@code switch} all keep working on the plugin
 * side. This module never assumes its keys are enums, so a consumer is free to
 * use anything else.
 *
 * <p>Implementations must be immutable and must have {@code equals} and
 * {@code hashCode} consistent with identity — an enum satisfies this for free.
 */
public interface DependencyKey {

    /**
     * A stable identifier, unique within one plugin's catalogue. For an enum
     * this is {@link Enum#name()} and needs no implementation.
     *
     * <p>Used for lookups and, when a plugin has no display name to hand, shown
     * to the user — so it should stay readable and must not change once a
     * release has shipped.
     */
    String name();
}
