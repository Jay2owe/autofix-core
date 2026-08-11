package sc.fiji.autofix.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * What "Fix everything" would do, computed before doing any of it.
 *
 * <p>The point is the total download and the restart flag. A user who is told
 * "this will fetch about 180 MB and Fiji will need to restart" can decide;
 * a user who finds that out halfway through a 143 MB TensorFlow download
 * cannot.
 *
 * <p>{@link #getBlockedDependencies()} is the honest part: things that are
 * missing and that the plugin cannot fix. Omitting them would make the plan
 * look complete when it is not.
 */
public final class DependencyFixPlan {

    private final List<DependencySpec> dependenciesToFix;
    private final List<DependencySpec> alreadySatisfied;
    private final List<DependencySpec> blockedDependencies;
    private final long totalApproxDownloadBytes;
    private final boolean restartRequired;

    public DependencyFixPlan(List<DependencySpec> dependenciesToFix,
                             List<DependencySpec> alreadySatisfied,
                             List<DependencySpec> blockedDependencies,
                             long totalApproxDownloadBytes,
                             boolean restartRequired) {
        this.dependenciesToFix = unmodifiableCopy(dependenciesToFix);
        this.alreadySatisfied = unmodifiableCopy(alreadySatisfied);
        this.blockedDependencies = unmodifiableCopy(blockedDependencies);
        this.totalApproxDownloadBytes = totalApproxDownloadBytes;
        this.restartRequired = restartRequired;
    }

    /** In the order the fixers must run — see {@code DependencyFixer.getExecutionOrder()}. */
    public List<DependencySpec> getDependenciesToFix() {
        return dependenciesToFix;
    }

    /** Fixable and already present, by display name. Shown so the plan looks complete. */
    public List<DependencySpec> getAlreadySatisfied() {
        return alreadySatisfied;
    }

    /** Missing, visible, and with no in-app fix path. By display name. */
    public List<DependencySpec> getBlockedDependencies() {
        return blockedDependencies;
    }

    public long getTotalApproxDownloadBytes() {
        return totalApproxDownloadBytes;
    }

    /** True when any dependency in the plan needs Fiji restarted after its fix. */
    public boolean isRestartRequired() {
        return restartRequired;
    }

    private static List<DependencySpec> unmodifiableCopy(List<DependencySpec> specs) {
        if (specs == null || specs.isEmpty()) {
            return Collections.emptyList();
        }
        return Collections.unmodifiableList(new ArrayList<DependencySpec>(specs));
    }
}
