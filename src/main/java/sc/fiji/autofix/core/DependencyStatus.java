package sc.fiji.autofix.core;

/**
 * Side-effect-free result of probing one dependency.
 *
 * <p>The key is optional. FLASH's status carried no key (the caller always knew
 * which spec it had asked about); PULSE's carried one (its service returns bare
 * lists of statuses). Both shapes are supported: {@link #present(String)} and
 * friends leave the key null, {@link #withKey(DependencyKey)} attaches one, and
 * {@link DependencyServiceCore} attaches it automatically on the way out of a
 * probe so a consumer reading {@link #getKey()} always sees one.
 */
public final class DependencyStatus {

    public enum State {
        PRESENT,
        CHECKING,
        MISSING,
        ERROR
    }

    private final DependencyKey key;
    private final State state;
    private final String detailMessage;

    private DependencyStatus(DependencyKey key, State state, String detailMessage) {
        this.key = key;
        this.state = state;
        this.detailMessage = detailMessage == null ? "" : detailMessage.trim();
    }

    public static DependencyStatus present(String detailMessage) {
        return new DependencyStatus(null, State.PRESENT, detailMessage);
    }

    public static DependencyStatus checking(String detailMessage) {
        return new DependencyStatus(null, State.CHECKING, detailMessage);
    }

    public static DependencyStatus missing(String detailMessage) {
        return new DependencyStatus(null, State.MISSING, detailMessage);
    }

    public static DependencyStatus error(String detailMessage) {
        return new DependencyStatus(null, State.ERROR, detailMessage);
    }

    public static DependencyStatus of(DependencyKey key, State state, String detailMessage) {
        return new DependencyStatus(key, state == null ? State.ERROR : state, detailMessage);
    }

    /**
     * This status with {@code key} attached, or {@code this} when it already
     * carries one — a probe that names its own subject is trusted over the
     * service that called it.
     */
    public DependencyStatus withKey(DependencyKey key) {
        if (this.key != null || key == null) {
            return this;
        }
        return new DependencyStatus(key, state, detailMessage);
    }

    /** The dependency this status is about, or null when the probe did not say. */
    public DependencyKey getKey() {
        return key;
    }

    public State getState() {
        return state;
    }

    public String getDetailMessage() {
        return detailMessage;
    }

    public boolean isPresent() {
        return state == State.PRESENT;
    }

    public boolean isMissing() {
        return state == State.MISSING;
    }

    public boolean isChecking() {
        return state == State.CHECKING;
    }

    public boolean isError() {
        return state == State.ERROR;
    }

    /** True when the dependency is missing or errored and needs the user's attention. */
    public boolean needsAttention() {
        return state == State.MISSING || state == State.ERROR;
    }
}
