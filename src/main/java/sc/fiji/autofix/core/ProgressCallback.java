package sc.fiji.autofix.core;

/**
 * Progress hook for a repair that takes long enough for a user to wonder
 * whether it has hung — a 143 MB TensorFlow download, a conda environment.
 *
 * <p>Called from whatever thread the fix runs on, which is not the event
 * dispatch thread. An implementation that touches Swing must marshal.
 */
public interface ProgressCallback {

    /** A no-op sink, so callers need no null check. */
    ProgressCallback NONE = new ProgressCallback() {
        @Override
        public void onProgress(DependencySpec spec, String message) {
            // Deliberately nothing. Fixes still report their final detail.
        }
    };

    void onProgress(DependencySpec spec, String message);
}
