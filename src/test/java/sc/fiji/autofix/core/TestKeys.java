package sc.fiji.autofix.core;

/**
 * A stand-in for a consumer's {@code DependencyId} enum.
 *
 * <p>It is an enum on purpose: the point of {@link DependencyKey} being an
 * interface is that a plugin's existing enum satisfies it with one added word
 * and keeps {@code EnumMap}, {@code values()} and {@code switch}. The tests
 * exercise the same shape a real consumer uses rather than a bespoke key type
 * that would prove nothing.
 */
enum TestKeys implements DependencyKey {
    ALPHA,
    BETA,
    GAMMA,
    UNREGISTERED
}
