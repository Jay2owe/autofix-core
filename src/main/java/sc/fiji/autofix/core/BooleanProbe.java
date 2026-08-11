package sc.fiji.autofix.core;

/**
 * The simplest possible detection strategy: is it there, yes or no.
 *
 * <p>PULSE's dependency layer was written against this shape, with the
 * user-facing detail line composed afterwards by its service. That reads well
 * until two probes need different wording for the same {@code false}, which is
 * why the richer {@link Probe} is the contract this module builds on. Wrap one
 * of these with {@link Probes#of(BooleanProbe, String, String)} to supply the
 * two messages once, at the point where they are known.
 */
public interface BooleanProbe {

    /** True when the dependency is available. Must not throw; must not block. */
    boolean isPresent();
}
