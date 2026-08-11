package sc.fiji.autofix.core;

/**
 * One in-app repair path.
 *
 * <p>A fixer never throws to signal failure — it returns a
 * {@link DependencyFixResult} carrying the detail, because half of a repair is
 * explaining to the user what happened and a stack trace does not do that.
 */
public interface DependencyFixer {

    /**
     * Runs the repair. {@code actionId} is
     * {@link DependencyServiceCore.DialogAction#AUTO_FIX} or one of the spec's
     * {@link DependencySpec.InstallOption} ids.
     */
    DependencyFixResult apply(DependencySpec spec, String actionId, ProgressCallback callback);

    /**
     * Where this fixer sits when several run together. Lower goes first.
     *
     * <p>Order is load-bearing rather than cosmetic: a runtime that another
     * depends on must land first, or the second fixer's verification probe runs
     * against a half-built state and reports a failure that a rerun would have
     * fixed.
     */
    int getExecutionOrder();
}
