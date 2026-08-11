package sc.fiji.autofix.core;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The repair path for a dependency that is "some jars in Fiji.app": probe,
 * check what is missing, confirm the folders are writable, download, re-check,
 * re-probe, and report.
 *
 * <p>Subclass to change where the jars come from — {@link #check} and
 * {@link #repair} are overridable, which is how a plugin with its own bespoke
 * checker for one dependency reuses the surrounding flow. Subclass nothing and
 * use {@link JarDependencyFixer} for the ordinary case.
 *
 * <h2>Why it checks writability separately</h2>
 * Fiji is frequently installed under {@code C:\Program Files} or
 * {@code /Applications}, where a normal user cannot write. Discovering that
 * after a 143 MB download produces a failure message about a rename; discovering
 * it first produces one about permissions, which is the thing the user can act
 * on.
 */
public abstract class AbstractJarDependencyFixer implements DependencyFixer {

    private final Product product;
    private final int executionOrder;
    private final String verifiedMessage;
    private final String repairedMessage;

    protected AbstractJarDependencyFixer(Product product,
                                         int executionOrder,
                                         String verifiedMessage,
                                         String repairedMessage) {
        if (product == null) {
            throw new IllegalArgumentException("A jar fixer writes probe files into the user's Fiji.app; "
                    + "it must know which plugin to name them after.");
        }
        this.product = product;
        this.executionOrder = executionOrder;
        this.verifiedMessage = verifiedMessage == null ? "" : verifiedMessage;
        this.repairedMessage = repairedMessage == null ? "" : repairedMessage;
    }

    /** The plugin this fixer belongs to. Used for filenames and user-facing text. */
    protected final Product getProduct() {
        return product;
    }

    @Override
    public int getExecutionOrder() {
        return executionOrder;
    }

    @Override
    public DependencyFixResult apply(DependencySpec spec, String actionId, ProgressCallback callback) {
        if (spec == null) {
            return new DependencyFixResult(null, false, false, false, "No dependency spec was provided.");
        }
        String normalizedAction = actionId == null ? "" : actionId.trim();
        if (normalizedAction.isEmpty()) {
            normalizedAction = DependencyServiceCore.DialogAction.AUTO_FIX;
        }
        if (!DependencyServiceCore.DialogAction.AUTO_FIX.equals(normalizedAction)) {
            return new DependencyFixResult(spec.getId(), false, false, false,
                    "Unknown dependency action: " + normalizedAction);
        }

        DependencyStatus before = probe(spec);
        File fijiDir = getFijiDir();
        if (fijiDir == null) {
            return new DependencyFixResult(spec.getId(), true, false, false,
                    "Could not determine the Fiji.app directory.");
        }

        List<String> issues = check(fijiDir, spec);
        if (issues == null || issues.isEmpty()) {
            DependencyStatus after = probe(spec);
            boolean success = after != null && after.isPresent();
            /*
             * The jars are all present but the class still will not load. For a
             * restart-required dependency that is the expected state, not a
             * failure — the classloader simply has not seen the new jar yet.
             */
            if (!success && spec.isRestartRequired()) {
                success = true;
            }
            boolean restartRequired = spec.isRestartRequired() && after != null && !after.isPresent();
            return new DependencyFixResult(spec.getId(), true, success, restartRequired,
                    buildInstallMessage(verifiedMessage, restartHint(spec, after), after));
        }

        List<String> writeProblems = checkWritableTargets(fijiDir, spec);
        if (!writeProblems.isEmpty()) {
            return new DependencyFixResult(spec.getId(), true, false, false,
                    buildInstallMessage("Cannot repair " + spec.getDisplayName()
                                    + " because Fiji.app is not writable.",
                            Text.joinLines(writeProblems), before));
        }

        notify(callback, spec, "Repairing " + spec.getDisplayName() + "...");
        List<String> actions = repair(fijiDir, spec);
        if (actions == null) {
            actions = Collections.emptyList();
        }

        List<String> remainingIssues = check(fijiDir, spec);
        DependencyStatus after = probe(spec);
        boolean manifestSatisfied = remainingIssues == null || remainingIssues.isEmpty();
        boolean success = manifestSatisfied && !containsFailure(actions);
        boolean restartRequired = spec.isRestartRequired()
                && before != null
                && !before.isPresent()
                && (success || madeRuntimeChange(actions));
        String detail = appendDetails(
                Text.joinLines(actions),
                restoreGuidance(spec, actions),
                remainingIssues(spec, remainingIssues),
                restartHint(spec, after));
        return new DependencyFixResult(
                spec.getId(),
                true,
                success,
                restartRequired,
                buildInstallMessage(repairedMessage, detail, after));
    }

    /** Where the jars go. Override when a dependency lives outside Fiji.app. */
    protected File getFijiDir() {
        return FijiLayout.resolveFijiDir();
    }

    /** What is missing. Override to consult a bespoke checker instead of the spec. */
    protected List<String> check(File fijiDir, DependencySpec spec) {
        return Artifacts.check(fijiDir, spec.getArtifacts(), spec.getArtifactIgnorePrefixes());
    }

    /** Put it right. Override alongside {@link #check}, never on its own. */
    protected List<String> repair(File fijiDir, DependencySpec spec) {
        return Artifacts.repair(fijiDir, spec.getArtifacts(), spec.getArtifactIgnorePrefixes());
    }

    /**
     * Re-probes {@code spec} in a fresh context. Used before and after a repair,
     * so the reported status reflects the disk as it is now.
     */
    protected DependencyStatus probe(DependencySpec spec) {
        if (spec == null) {
            return null;
        }
        try {
            DependencyStatus status = spec.probe(ProbeContext.detect());
            return status == null
                    ? DependencyStatus.error("Probe returned no status for " + spec.getDisplayName() + ".")
                    : status.withKey(spec.getId());
        } catch (RuntimeException e) {
            return DependencyStatus.error("Probe failed for " + spec.getDisplayName()
                    + ": " + e.getClass().getSimpleName() + ": " + Text.safeMessage(e));
        }
    }

    private List<String> checkWritableTargets(File fijiDir, DependencySpec spec) {
        List<String> problems = new ArrayList<String>();
        if (spec.getArtifacts().isEmpty()) {
            return problems;
        }
        Set<String> folders = new HashSet<String>();
        for (DependencySpec.Artifact artifact : spec.getArtifacts()) {
            folders.add(artifact.getFolder());
        }
        for (String folder : folders) {
            File dir = new File(fijiDir, folder);
            if (!dir.exists() && !dir.mkdirs()) {
                problems.add("Could not create " + dir.getAbsolutePath());
                continue;
            }
            if (!dir.isDirectory()) {
                problems.add("Expected a folder but found a file: " + dir.getAbsolutePath());
                continue;
            }
            /*
             * A dot-prefixed name, so it is hidden on POSIX and sorts out of the
             * way on Windows, and product-named so a leftover from a crash is
             * attributable. Deleted in the finally, deleteOnExit if that fails.
             */
            File probe = new File(dir, "." + product.slug()
                    + "-runtime-write-test-" + System.currentTimeMillis());
            try {
                if (!probe.createNewFile()) {
                    problems.add("Could not create a temporary file in " + dir.getAbsolutePath());
                }
            } catch (Exception e) {
                problems.add("Cannot write to " + dir.getAbsolutePath() + ": "
                        + e.getClass().getSimpleName() + ": " + Text.safeMessage(e));
            } finally {
                if (probe.exists() && !probe.delete()) {
                    probe.deleteOnExit();
                }
            }
        }
        return problems;
    }

    private static void notify(ProgressCallback callback, DependencySpec spec, String message) {
        if (callback != null) {
            callback.onProgress(spec, message);
        }
    }

    private static boolean containsFailure(List<String> actions) {
        if (actions == null) {
            return false;
        }
        for (String action : actions) {
            if (action != null && action.trim().startsWith("FAILED")) {
                return true;
            }
        }
        return false;
    }

    /**
     * True when the repair changed something a restart would pick up, even if
     * the overall repair did not succeed — so a partial repair still tells the
     * user to restart rather than leaving them on a half-updated install.
     */
    private static boolean madeRuntimeChange(List<String> actions) {
        if (actions == null) {
            return false;
        }
        for (String action : actions) {
            String trimmed = action == null ? "" : action.trim();
            if (trimmed.startsWith("Downloaded:")
                    || trimmed.startsWith("Disabled:")
                    || trimmed.startsWith("Scheduled disable after Fiji closes:")) {
                return true;
            }
        }
        return false;
    }

    private static String remainingIssues(DependencySpec spec, List<String> remainingIssues) {
        if (remainingIssues == null || remainingIssues.isEmpty()) {
            return "";
        }
        return "Remaining jar issues for " + spec.getDisplayName() + ":\n" + Text.joinLines(remainingIssues);
    }

    private static String restartHint(DependencySpec spec, DependencyStatus after) {
        if (spec == null || !spec.isRestartRequired() || after == null || after.isPresent()) {
            return "";
        }
        return "Required jars are in place, but Fiji must restart before class-based verification can pass.";
    }

    private String restoreGuidance(DependencySpec spec, List<String> actions) {
        if (!Artifacts.hasDisabledJarActions(actions)) {
            return "";
        }
        return Artifacts.disabledJarRestoreGuidance(product,
                spec == null ? "" : spec.getDisplayName());
    }

    private static String appendDetails(String... details) {
        StringBuilder sb = new StringBuilder();
        if (details != null) {
            for (String detail : details) {
                appendDetail(sb, detail);
            }
        }
        return sb.toString();
    }

    private static void appendDetail(StringBuilder sb, String detail) {
        if (detail == null || detail.trim().isEmpty()) {
            return;
        }
        if (sb.length() > 0) {
            sb.append("\n\n");
        }
        sb.append(detail.trim());
    }

    private static String buildInstallMessage(String summary, String detail, DependencyStatus after) {
        StringBuilder sb = new StringBuilder();
        if (summary != null && !summary.trim().isEmpty()) {
            sb.append(summary.trim());
        }
        if (detail != null && !detail.trim().isEmpty()) {
            if (sb.length() > 0) {
                sb.append("\n\n");
            }
            sb.append(detail.trim());
        }
        String statusMessage = formatStatusMessage(after);
        if (!statusMessage.isEmpty()) {
            if (sb.length() > 0) {
                sb.append("\n\n");
            }
            sb.append(statusMessage);
        }
        return sb.toString();
    }

    private static String formatStatusMessage(DependencyStatus status) {
        if (status == null) {
            return "Status is unavailable.";
        }
        String detail = status.getDetailMessage();
        String label;
        if (status.isPresent()) {
            label = "Present";
        } else if (status.isError()) {
            label = "Error";
        } else if (status.isMissing()) {
            label = "Missing";
        } else {
            label = "Status unavailable";
        }
        if (detail == null || detail.trim().isEmpty()) {
            return label + ".";
        }
        return label + ": " + detail.trim();
    }
}
