package sc.fiji.autofix.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Probe, plan, fix — the whole loop, over whichever catalogue it is given.
 *
 * <p>Caches statuses because probing is not free: a class probe walks the
 * classpath and a jar probe walks Fiji.app, and a dialog that redraws would
 * otherwise re-run every probe on every repaint. {@link #invalidateStatusCache()}
 * after anything that could change the answer; every fix path does it already.
 *
 * <p>Produces {@link DialogRow}s, not widgets. A row is the finished text —
 * every label, every caption, every explanatory line — with no Swing anywhere
 * near it, so the plugin renders it however it likes and the wording stays
 * consistent between two plugins that look nothing alike. This is the boundary
 * that lets a core own presentation logic without owning presentation.
 */
public final class DependencyServiceCore {

    /** Test seam: substitutes the whole probing step. */
    public interface StatusSnapshotProvider {
        Map<DependencyKey, DependencyStatus> snapshot(List<DependencySpec> specs);
    }

    /** The two actions every dependency row can offer, beyond its install options. */
    public static final class DialogAction {
        public static final String VERIFY = "verify";
        public static final String AUTO_FIX = "auto_fix";

        private final String actionId;
        private final String label;

        public DialogAction(String actionId, String label) {
            this.actionId = actionId == null ? "" : actionId.trim();
            this.label = label == null ? "" : label;
        }

        public String getActionId() {
            return actionId;
        }

        public String getLabel() {
            return label;
        }
    }

    /** One dependency as a dialog shows it: finished text, no widgets. */
    public static final class DialogRow {
        private final DependencySpec spec;
        private final DependencyStatus status;
        private final String sectionLabel;
        private final String statusLabel;
        private final String statusDetail;
        private final String blockedLabel;
        private final String explanation;
        private final String restartLabel;
        private final String actionNote;
        private final List<DialogAction> actions;

        public DialogRow(DependencySpec spec,
                         DependencyStatus status,
                         String sectionLabel,
                         String statusLabel,
                         String statusDetail,
                         String blockedLabel,
                         String explanation,
                         String restartLabel,
                         String actionNote,
                         List<DialogAction> actions) {
            this.spec = spec;
            this.status = status;
            this.sectionLabel = sectionLabel == null ? "" : sectionLabel;
            this.statusLabel = statusLabel == null ? "" : statusLabel;
            this.statusDetail = statusDetail == null ? "" : statusDetail;
            this.blockedLabel = blockedLabel == null ? "" : blockedLabel;
            this.explanation = explanation == null ? "" : explanation;
            this.restartLabel = restartLabel == null ? "" : restartLabel;
            this.actionNote = actionNote == null ? "" : actionNote;
            this.actions = actions == null || actions.isEmpty()
                    ? Collections.<DialogAction>emptyList()
                    : Collections.unmodifiableList(new ArrayList<DialogAction>(actions));
        }

        public DependencySpec getSpec() {
            return spec;
        }

        public DependencyStatus getStatus() {
            return status;
        }

        public String getSectionLabel() {
            return sectionLabel;
        }

        public String getStatusLabel() {
            return statusLabel;
        }

        public String getStatusDetail() {
            return statusDetail;
        }

        public String getBlockedLabel() {
            return blockedLabel;
        }

        public String getExplanation() {
            return explanation;
        }

        public String getRestartLabel() {
            return restartLabel;
        }

        public String getActionNote() {
            return actionNote;
        }

        public List<DialogAction> getActions() {
            return actions;
        }
    }

    private final SpecCatalogue catalogue;
    private final StatusSnapshotProvider statusSnapshotProvider;
    private final Map<DependencyKey, DependencyFixer> fixers;

    private Map<DependencyKey, DependencyStatus> statusCache;

    public DependencyServiceCore(SpecCatalogue catalogue,
                                 Map<DependencyKey, DependencyFixer> fixers) {
        this(catalogue, fixers, null);
    }

    /**
     * @param statusSnapshotProvider substitutes probing entirely; null uses the
     *        real one. The seam exists so a consumer's tests can drive dialog
     *        and plan logic without a Fiji install.
     */
    public DependencyServiceCore(SpecCatalogue catalogue,
                                 Map<DependencyKey, DependencyFixer> fixers,
                                 StatusSnapshotProvider statusSnapshotProvider) {
        if (catalogue == null) {
            throw new IllegalArgumentException("A dependency service needs a catalogue.");
        }
        this.catalogue = catalogue;
        this.statusSnapshotProvider = statusSnapshotProvider == null
                ? DEFAULT_STATUS_PROVIDER
                : statusSnapshotProvider;
        this.fixers = fixers == null
                ? Collections.<DependencyKey, DependencyFixer>emptyMap()
                : Collections.unmodifiableMap(new LinkedHashMap<DependencyKey, DependencyFixer>(fixers));
    }

    private static final StatusSnapshotProvider DEFAULT_STATUS_PROVIDER = new StatusSnapshotProvider() {
        @Override
        public Map<DependencyKey, DependencyStatus> snapshot(List<DependencySpec> specs) {
            return snapshotStatuses(specs);
        }
    };

    /**
     * Probes every spec once, in one shared context, and returns the statuses in
     * catalogue order. A probe that throws becomes an ERROR status rather than
     * aborting the sweep — one broken probe must not hide the other nineteen
     * dependencies' state.
     */
    public static Map<DependencyKey, DependencyStatus> snapshotStatuses(List<DependencySpec> specs) {
        return snapshotStatuses(specs, ProbeContext.detect());
    }

    /** As {@link #snapshotStatuses(List)}, against a supplied context. */
    public static Map<DependencyKey, DependencyStatus> snapshotStatuses(List<DependencySpec> specs,
                                                                        ProbeContext context) {
        Map<DependencyKey, DependencyStatus> snapshot = new LinkedHashMap<DependencyKey, DependencyStatus>();
        if (specs == null) {
            return snapshot;
        }
        for (DependencySpec spec : specs) {
            if (spec == null || spec.getId() == null) {
                continue;
            }
            DependencyStatus status;
            try {
                status = spec.probe(context);
            } catch (RuntimeException e) {
                status = DependencyStatus.error("Probe failed for " + spec.getDisplayName()
                        + ": " + e.getClass().getSimpleName() + ": " + Text.safeMessage(e));
            }
            snapshot.put(spec.getId(), status == null
                    ? DependencyStatus.error("Probe returned no status for " + spec.getDisplayName() + ".")
                    : status.withKey(spec.getId()));
        }
        return snapshot;
    }

    public synchronized void invalidateStatusCache() {
        statusCache = null;
    }

    /** Re-probes everything and returns a copy of the fresh cache. */
    public synchronized Map<DependencyKey, DependencyStatus> refreshStatuses() {
        statusCache = statusSnapshotProvider.snapshot(catalogue.all());
        return new LinkedHashMap<DependencyKey, DependencyStatus>(statusCache);
    }

    /** The cached status, probing first if needed. Null when {@code id} is not in the catalogue. */
    public synchronized DependencyStatus getStatus(DependencyKey id) {
        ensureCache();
        return statusCache.get(id);
    }

    /**
     * As {@link #getStatus}, but reports an unknown dependency as CHECKING
     * rather than null — for a caller that renders a row per key and has
     * nothing sensible to draw for a null.
     */
    public synchronized DependencyStatus getStatusOrChecking(DependencyKey id) {
        DependencyStatus status = getStatus(id);
        return status != null
                ? status
                : DependencyStatus.of(id, DependencyStatus.State.CHECKING, "Not yet checked.");
    }

    /** True when {@code id} is present right now. */
    public synchronized boolean isAvailable(DependencyKey id) {
        DependencyStatus status = getStatus(id);
        return status != null && status.isPresent();
    }

    public List<DependencySpec> getAllDependencies() {
        return catalogue.all();
    }

    public List<DependencySpec> getVisibleDependencies() {
        List<DependencySpec> visible = new ArrayList<DependencySpec>();
        for (DependencySpec spec : catalogue.all()) {
            if (spec.isVisibleInDependenciesDialog()) {
                visible.add(spec);
            }
        }
        return visible;
    }

    public List<DependencySpec> getFixableDependencies() {
        List<DependencySpec> fixable = new ArrayList<DependencySpec>();
        for (DependencySpec spec : catalogue.all()) {
            if (hasRegisteredFixer(spec)) {
                fixable.add(spec);
            }
        }
        return fixable;
    }

    public synchronized List<DependencySpec> getMissingFixableDependencies() {
        ensureCache();
        List<DependencySpec> missing = new ArrayList<DependencySpec>();
        for (DependencySpec spec : getFixableDependencies()) {
            DependencyStatus status = statusCache.get(spec.getId());
            if (status != null && status.needsAttention()) {
                missing.add(spec);
            }
        }
        return missing;
    }

    /** Every status, in catalogue order. */
    public synchronized List<DependencyStatus> allStatuses() {
        ensureCache();
        List<DependencyStatus> out = new ArrayList<DependencyStatus>();
        for (DependencySpec spec : catalogue.all()) {
            out.add(getStatusOrChecking(spec.getId()));
        }
        return out;
    }

    /** Statuses that are missing or errored, in catalogue order. */
    public synchronized List<DependencyStatus> statusesNeedingAttention() {
        List<DependencyStatus> out = new ArrayList<DependencyStatus>();
        for (DependencyStatus status : allStatuses()) {
            if (status.needsAttention()) {
                out.add(status);
            }
        }
        return out;
    }

    /**
     * The ordered union of Fiji update sites that would fix everything
     * currently missing and in-app fixable. Deduplicated, first-mention order —
     * a user works down the list in the Updater and a repeat is noise.
     */
    public synchronized List<String> updateSitesToEnable() {
        Set<String> sites = new LinkedHashSet<String>();
        for (DependencySpec spec : catalogue.all()) {
            if (!spec.isFixableInApp()) {
                continue;
            }
            DependencyStatus status = getStatus(spec.getId());
            if (status == null || !status.needsAttention()) {
                continue;
            }
            sites.addAll(spec.getUpdateSites());
        }
        return new ArrayList<String>(sites);
    }

    public synchronized List<DialogRow> getDialogRows() {
        ensureCache();
        List<DialogRow> rows = new ArrayList<DialogRow>();
        for (DependencySpec spec : getVisibleDependencies()) {
            rows.add(buildDialogRow(spec, statusCache.get(spec.getId())));
        }
        return rows;
    }

    public synchronized List<DialogRow> getDialogRowsNeedingAttention() {
        ensureCache();
        List<DialogRow> rows = new ArrayList<DialogRow>();
        for (DependencySpec spec : getVisibleDependencies()) {
            DependencyStatus status = statusCache.get(spec.getId());
            if (status != null && status.needsAttention()) {
                rows.add(buildDialogRow(spec, status));
            }
        }
        return rows;
    }

    public synchronized boolean hasVisibleDependenciesNeedingAttention() {
        return !getDialogRowsNeedingAttention().isEmpty();
    }

    /** What "Fix everything" would do. See {@link DependencyFixPlan}. */
    public synchronized DependencyFixPlan planFixAll() {
        ensureCache();
        List<DependencySpec> toFix = new ArrayList<DependencySpec>();
        List<DependencySpec> alreadySatisfied = new ArrayList<DependencySpec>();
        List<DependencySpec> blocked = new ArrayList<DependencySpec>();
        long totalBytes = 0L;
        boolean restartRequired = false;

        for (DependencySpec spec : catalogue.all()) {
            DependencyStatus status = statusCache.get(spec.getId());
            if (status == null) {
                continue;
            }
            if (hasRegisteredFixer(spec)) {
                if (status.isPresent()) {
                    alreadySatisfied.add(spec);
                } else if (status.needsAttention()) {
                    toFix.add(spec);
                    totalBytes += spec.getApproxDownloadSizeBytes();
                    restartRequired = restartRequired || spec.isRestartRequired();
                }
            } else if (spec.isVisibleInDependenciesDialog() && status.needsAttention()) {
                blocked.add(spec);
            }
        }

        sortByFixExecutionOrder(toFix);
        sortByDisplayName(alreadySatisfied);
        sortByDisplayName(blocked);
        return new DependencyFixPlan(toFix, alreadySatisfied, blocked, totalBytes, restartRequired);
    }

    /**
     * Runs one row's action. {@code actionId} is
     * {@link DialogAction#VERIFY}, {@link DialogAction#AUTO_FIX}, or an install
     * option id; an empty one means Auto-Fix.
     *
     * <p>Rejects an action the spec does not advertise, rather than passing it
     * to the fixer — a typo in a dialog wiring should say so, not trigger the
     * default install.
     */
    public synchronized DependencyFixResult runDialogAction(DependencyKey id, String actionId) {
        DependencySpec spec = findSpec(id);
        if (spec == null) {
            return new DependencyFixResult(id, false, false, false, "Unknown dependency id.");
        }

        String normalizedAction = actionId == null ? "" : actionId.trim();
        if (normalizedAction.isEmpty()) {
            normalizedAction = DialogAction.AUTO_FIX;
        }

        if (DialogAction.VERIFY.equals(normalizedAction)) {
            return verifyDependency(id);
        }

        DependencyFixer fixer = fixers.get(id);
        if (!spec.isFixableInApp() || fixer == null) {
            return new DependencyFixResult(id, false, false, false,
                    spec.getNonFixableReason().isEmpty()
                            ? "No in-app fixer is available."
                            : spec.getNonFixableReason());
        }

        if (!isKnownFixAction(spec, normalizedAction)) {
            return new DependencyFixResult(id, false, false, false,
                    "Unknown dependency action: " + normalizedAction);
        }

        DependencyFixResult result = fixer.apply(spec, normalizedAction, ProgressCallback.NONE);
        invalidateStatusCache();
        return result;
    }

    /** The spec for {@code id}, or null. */
    public DependencySpec findSpec(DependencyKey id) {
        if (id == null) {
            return null;
        }
        for (DependencySpec spec : catalogue.all()) {
            if (id.equals(spec.getId())) {
                return spec;
            }
        }
        return null;
    }

    private boolean hasRegisteredFixer(DependencySpec spec) {
        return spec != null && spec.isFixableInApp() && fixers.containsKey(spec.getId());
    }

    private static boolean isKnownFixAction(DependencySpec spec, String actionId) {
        if (DialogAction.AUTO_FIX.equals(actionId)) {
            return true;
        }
        for (DependencySpec.InstallOption option : spec.getInstallOptions()) {
            if (option.getActionId().equals(actionId)) {
                return true;
            }
        }
        return false;
    }

    private DialogRow buildDialogRow(DependencySpec spec, DependencyStatus status) {
        return new DialogRow(
                spec,
                status,
                spec.getDialogSectionLabel(),
                formatStatusLabel(status),
                formatStatusDetail(status),
                buildBlockedLabel(spec),
                spec.getDescription(),
                spec.isRestartRequired()
                        ? "Restart Fiji after repair: required."
                        : "Restart Fiji after repair: not required.",
                buildActionNote(spec, status),
                buildActions(spec, status));
    }

    private synchronized void ensureCache() {
        if (statusCache == null) {
            refreshStatuses();
        }
    }

    private synchronized DependencyFixResult verifyDependency(DependencyKey id) {
        invalidateStatusCache();
        DependencyStatus after = getStatus(id);
        boolean success = after != null && after.isPresent();
        return new DependencyFixResult(id, true, success, false, formatStatusMessage(after));
    }

    private List<DialogAction> buildActions(DependencySpec spec, DependencyStatus status) {
        List<DialogAction> actions = new ArrayList<DialogAction>();
        boolean present = status != null && status.isPresent();
        if (status != null && status.isChecking()) {
            actions.add(new DialogAction(DialogAction.VERIFY, "Refresh Status"));
            return actions;
        }

        if (present) {
            String verifyLabel = resolveVerifyLabel(spec, status);
            if (!verifyLabel.isEmpty()) {
                actions.add(new DialogAction(DialogAction.VERIFY, verifyLabel));
            }
        }

        if (hasRegisteredFixer(spec) && !spec.getInstallOptions().isEmpty()) {
            for (DependencySpec.InstallOption option : spec.getInstallOptions()) {
                String label = option.formatButtonLabel();
                if (!label.isEmpty()) {
                    actions.add(new DialogAction(option.getActionId(), label));
                }
            }
            return actions;
        }

        if (!present && hasRegisteredFixer(spec)) {
            String fixLabel = spec.formatButtonLabel(status);
            if (fixLabel != null && !fixLabel.trim().isEmpty()) {
                actions.add(new DialogAction(DialogAction.AUTO_FIX, fixLabel));
            }
        }

        return actions;
    }

    private static String resolveVerifyLabel(DependencySpec spec, DependencyStatus status) {
        String label = spec.formatButtonLabel(status);
        if (label != null && !label.trim().isEmpty()) {
            return label.trim();
        }
        return "Verify";
    }

    private static String buildBlockedLabel(DependencySpec spec) {
        List<String> features = spec.getAffectedFeatures();
        if (features == null || features.isEmpty()) {
            return "Blocks: Feature scope not declared.";
        }
        StringBuilder sb = new StringBuilder("Blocks: ");
        for (String feature : features) {
            if (feature == null || feature.trim().isEmpty()) {
                continue;
            }
            if (sb.length() > "Blocks: ".length()) {
                sb.append(", ");
            }
            sb.append(feature.trim());
        }
        if (sb.length() == "Blocks: ".length()) {
            return "Blocks: Feature scope not declared.";
        }
        return sb.toString();
    }

    private static String buildActionNote(DependencySpec spec, DependencyStatus status) {
        if (status != null && status.isPresent()) {
            return "";
        }
        if (!spec.isFixableInApp()) {
            String reason = spec.getNonFixableReason() == null ? "" : spec.getNonFixableReason().trim();
            if (reason.isEmpty()) {
                return "Not fixable in-app - see README.";
            }
            return "Not fixable in-app - see README.\n" + reason;
        }
        return "";
    }

    private static String formatStatusLabel(DependencyStatus status) {
        if (status == null) {
            return "Status unavailable";
        }
        if (status.isPresent()) {
            return "Present";
        }
        if (status.isChecking()) {
            return "Checking";
        }
        if (status.isError()) {
            return "Error";
        }
        if (status.isMissing()) {
            return "Missing";
        }
        return "Status unavailable";
    }

    private static String formatStatusDetail(DependencyStatus status) {
        if (status == null) {
            return "Status is unavailable.";
        }
        String detail = status.getDetailMessage();
        if (detail == null || detail.trim().isEmpty()) {
            return "";
        }
        return detail.trim();
    }

    private static String formatStatusMessage(DependencyStatus status) {
        if (status == null) {
            return "Status is unavailable.";
        }
        String detail = formatStatusDetail(status);
        if (detail.isEmpty()) {
            return formatStatusLabel(status) + ".";
        }
        return formatStatusLabel(status) + ": " + detail;
    }

    private static void sortByDisplayName(List<DependencySpec> specs) {
        Collections.sort(specs, new Comparator<DependencySpec>() {
            @Override
            public int compare(DependencySpec left, DependencySpec right) {
                return left.getDisplayName().compareToIgnoreCase(right.getDisplayName());
            }
        });
    }

    private void sortByFixExecutionOrder(List<DependencySpec> specs) {
        Collections.sort(specs, new Comparator<DependencySpec>() {
            @Override
            public int compare(DependencySpec left, DependencySpec right) {
                DependencyFixer leftFixer = fixers.get(left.getId());
                DependencyFixer rightFixer = fixers.get(right.getId());
                int leftOrder = leftFixer == null ? Integer.MAX_VALUE : leftFixer.getExecutionOrder();
                int rightOrder = rightFixer == null ? Integer.MAX_VALUE : rightFixer.getExecutionOrder();
                if (leftOrder != rightOrder) {
                    return leftOrder < rightOrder ? -1 : 1;
                }
                if (left.isRestartRequired() != right.isRestartRequired()) {
                    return left.isRestartRequired() ? 1 : -1;
                }
                return left.getDisplayName().compareToIgnoreCase(right.getDisplayName());
            }
        });
    }
}
