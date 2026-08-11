package sc.fiji.autofix.core;

/**
 * What one fix or verify attempt did.
 *
 * <p>{@link #wasAttempted()} and {@link #isSuccess()} are separate on purpose:
 * "we did not try" and "we tried and failed" call for different words to the
 * user, and collapsing them into one boolean is how a plugin ends up telling
 * someone a repair failed when it never ran.
 */
public final class DependencyFixResult {

    private final DependencyKey dependencyId;
    private final boolean attempted;
    private final boolean success;
    private final boolean restartRequired;
    private final String message;

    public DependencyFixResult(DependencyKey dependencyId,
                               boolean attempted,
                               boolean success,
                               boolean restartRequired,
                               String message) {
        this.dependencyId = dependencyId;
        this.attempted = attempted;
        this.success = success;
        this.restartRequired = restartRequired;
        this.message = message == null ? "" : message;
    }

    public DependencyKey getDependencyId() {
        return dependencyId;
    }

    public boolean wasAttempted() {
        return attempted;
    }

    public boolean isSuccess() {
        return success;
    }

    /** True when the jars are in place but Fiji must restart before they load. */
    public boolean isRestartRequired() {
        return restartRequired;
    }

    /** The full detail to show the user. Never null; may be empty. */
    public String getMessage() {
        return message;
    }
}
