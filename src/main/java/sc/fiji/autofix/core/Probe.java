package sc.fiji.autofix.core;

/**
 * A detection strategy for one dependency.
 *
 * <p>Implementations must be cheap, side-effect-free and safe to call off the
 * event thread. They report {@link DependencyStatus#missing} rather than
 * throwing; {@link DependencyServiceCore} converts an escaping exception into
 * {@link DependencyStatus#error} so one bad probe cannot take down a whole
 * status sweep.
 *
 * <p>A probe returns a status, not a boolean, because the detail line is the
 * part the user actually reads — "Missing runtime class: org.apache.poi.ss.…"
 * is actionable and "false" is not. A boolean probe is still easy to write:
 * see {@link Probes#of(BooleanProbe, String, String)}.
 */
public interface Probe {
    DependencyStatus probe(ProbeContext context);
}
