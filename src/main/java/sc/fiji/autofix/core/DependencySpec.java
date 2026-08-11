package sc.fiji.autofix.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Declarative definition of one runtime dependency: how to detect it, what it
 * unlocks, what installing it costs, how to fix it, and whether the fix needs a
 * Fiji restart.
 *
 * <p>A spec is data. It carries no opinion about how the plugin displays it and
 * no reference to the catalogue it belongs to — that direction of dependency is
 * what kept this model welded to its plugin before extraction.
 *
 * <h2>Plugin-specific vocabulary</h2>
 * Criticality bands and fixer strategies are deliberately <b>not</b> modelled
 * here. FLASH grades dependencies {@code OPTIONAL_FEATURE / STARTUP_CRITICAL /
 * INTERNAL_INTEGRITY} and PULSE grades them {@code CRITICAL / OPTIONAL_FEATURE};
 * FLASH can repair by {@code MANUAL_PLUGIN_REINSTALL} and PULSE cannot. Nothing
 * in this module reads either. They belong to the plugin, and ride along in
 * {@link #attribute(String, Class)}:
 *
 * <pre>{@code
 * DependencySpec.builder(DependencyId.STARDIST_RUNTIME, "StarDist runtime")
 *         .attribute("criticality", Criticality.OPTIONAL_FEATURE)
 *         .attribute("fixerStrategy", FixerStrategy.DIRECT_JAR_DOWNLOAD)
 *         .build();
 *
 * FixerStrategy how = spec.attribute("fixerStrategy", FixerStrategy.class);
 * }</pre>
 */
public final class DependencySpec {

    /**
     * One user-visible install choice a dependency can advertise — "Install CPU
     * (~2 GB)" beside "Install GPU (~4 GB)". A spec with install options offers
     * them instead of the single Auto-Fix button.
     */
    public static final class InstallOption {
        private final String actionId;
        private final String buttonLabelTemplate;
        private final long approxDownloadSizeBytes;

        public InstallOption(String actionId, String buttonLabelTemplate, long approxDownloadSizeBytes) {
            this.actionId = actionId == null ? "" : actionId.trim();
            this.buttonLabelTemplate = buttonLabelTemplate == null ? "" : buttonLabelTemplate;
            this.approxDownloadSizeBytes = approxDownloadSizeBytes;
        }

        public String getActionId() {
            return actionId;
        }

        public String getButtonLabelTemplate() {
            return buttonLabelTemplate;
        }

        public long getApproxDownloadSizeBytes() {
            return approxDownloadSizeBytes;
        }

        /**
         * The button caption, with the approximate size appended — substituted
         * into a {@code %s} if the template has one, otherwise appended.
         */
        public String formatButtonLabel() {
            if (buttonLabelTemplate.isEmpty()) {
                return "";
            }
            String sizeSuffix = approxDownloadSizeBytes > 0
                    ? " " + Sizes.formatApproxSize(approxDownloadSizeBytes)
                    : "";
            if (buttonLabelTemplate.contains("%s")) {
                return String.format(Locale.ROOT, buttonLabelTemplate, sizeSuffix);
            }
            return buttonLabelTemplate + sizeSuffix;
        }
    }

    /**
     * One jar this dependency needs on disk, and where to get it.
     *
     * <p>{@code expectedFile} is the exact filename a correct install has;
     * {@code matchPrefix} identifies other versions of the same jar, which
     * {@link Artifacts#repair} disables rather than deletes. {@code folder} is
     * relative to Fiji.app and is in practice {@code plugins} or {@code jars}.
     * {@code expectedSha1} is verified after download and a mismatch deletes
     * the file — an empty value skips verification, which should be rare and
     * deliberate.
     *
     * <p>{@code acceptAnyExisting} relaxes the check to "some version of this
     * is installed", for jars where the plugin does not pin a version.
     */
    public static final class Artifact {
        private final String label;
        private final String expectedFile;
        private final String matchPrefix;
        private final String folder;
        private final String downloadUrl;
        private final String expectedSha1;
        private final boolean acceptAnyExisting;

        public Artifact(String label,
                        String expectedFile,
                        String matchPrefix,
                        String folder,
                        String downloadUrl,
                        boolean acceptAnyExisting) {
            this(label, expectedFile, matchPrefix, folder, downloadUrl, "", acceptAnyExisting);
        }

        public Artifact(String label,
                        String expectedFile,
                        String matchPrefix,
                        String folder,
                        String downloadUrl,
                        String expectedSha1,
                        boolean acceptAnyExisting) {
            this.label = label;
            this.expectedFile = expectedFile;
            this.matchPrefix = matchPrefix;
            this.folder = folder;
            this.downloadUrl = downloadUrl;
            this.expectedSha1 = expectedSha1 == null ? "" : expectedSha1.trim().toLowerCase(Locale.ROOT);
            this.acceptAnyExisting = acceptAnyExisting;
        }

        public String getLabel() {
            return label;
        }

        public String getExpectedFile() {
            return expectedFile;
        }

        public String getMatchPrefix() {
            return matchPrefix;
        }

        public String getFolder() {
            return folder;
        }

        public String getDownloadUrl() {
            return downloadUrl;
        }

        public String getExpectedSha1() {
            return expectedSha1;
        }

        public boolean isAcceptAnyExisting() {
            return acceptAnyExisting;
        }
    }

    public static Builder builder(DependencyKey id, String displayName) {
        return new Builder(id, displayName);
    }

    private final DependencyKey id;
    private final String displayName;
    private final String description;
    private final List<String> affectedFeatures;
    private final String detectionStrategyLabel;
    private final Probe probe;
    private final long approxDownloadSizeBytes;
    private final boolean restartRequired;
    private final String fixButtonLabelTemplate;
    private final String presentButtonLabel;
    private final boolean fixableInApp;
    private final String nonFixableReason;
    private final boolean visibleInDependenciesDialog;
    private final String dialogSectionLabel;
    private final List<String> updateSites;
    private final List<InstallOption> installOptions;
    private final List<Artifact> artifacts;
    private final List<String> artifactIgnorePrefixes;
    private final Map<String, Object> attributes;

    private DependencySpec(Builder builder) {
        this.id = builder.id;
        this.displayName = builder.displayName;
        this.description = builder.description;
        this.affectedFeatures = copy(builder.affectedFeatures);
        this.detectionStrategyLabel = builder.detectionStrategyLabel;
        this.probe = builder.probe;
        this.approxDownloadSizeBytes = builder.approxDownloadSizeBytes;
        this.restartRequired = builder.restartRequired;
        this.fixButtonLabelTemplate = builder.fixButtonLabelTemplate;
        this.presentButtonLabel = builder.presentButtonLabel;
        this.fixableInApp = builder.fixableInApp;
        this.nonFixableReason = builder.nonFixableReason;
        this.visibleInDependenciesDialog = builder.visibleInDependenciesDialog;
        this.dialogSectionLabel = builder.dialogSectionLabel;
        this.updateSites = copy(builder.updateSites);
        this.installOptions = copy(builder.installOptions);
        this.artifacts = copy(builder.artifacts);
        this.artifactIgnorePrefixes = copy(builder.artifactIgnorePrefixes);
        this.attributes = builder.attributes.isEmpty()
                ? Collections.<String, Object>emptyMap()
                : Collections.unmodifiableMap(new LinkedHashMap<String, Object>(builder.attributes));
    }

    public DependencyKey getId() {
        return id;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getDescription() {
        return description;
    }

    /** Human-readable names of the plugin features this dependency gates. */
    public List<String> getAffectedFeatures() {
        return affectedFeatures;
    }

    public String getDetectionStrategyLabel() {
        return detectionStrategyLabel;
    }

    public long getApproxDownloadSizeBytes() {
        return approxDownloadSizeBytes;
    }

    public boolean isRestartRequired() {
        return restartRequired;
    }

    public boolean isFixableInApp() {
        return fixableInApp;
    }

    public String getNonFixableReason() {
        return nonFixableReason;
    }

    public boolean isVisibleInDependenciesDialog() {
        return visibleInDependenciesDialog;
    }

    public String getDialogSectionLabel() {
        return dialogSectionLabel;
    }

    /**
     * Fiji update sites that supply this dependency, in the order the user
     * should enable them. Empty when the plugin fixes it some other way, or
     * when the dependency ships with Fiji and a missing one means a broken
     * install rather than a disabled site.
     */
    public List<String> getUpdateSites() {
        return updateSites;
    }

    public List<InstallOption> getInstallOptions() {
        return installOptions;
    }

    public List<Artifact> getArtifacts() {
        return artifacts;
    }

    /**
     * Filename prefixes {@link Artifacts} must ignore when looking for rival
     * versions of an artifact — for jars whose names collide with an unrelated
     * artifact's prefix.
     */
    public List<String> getArtifactIgnorePrefixes() {
        return artifactIgnorePrefixes;
    }

    /**
     * Plugin-owned metadata stored under {@code key}, or null when absent or of
     * another type. See the class javadoc for why criticality and fixer
     * strategy live here rather than as fields.
     */
    public <T> T attribute(String key, Class<T> type) {
        Object value = attributes.get(key);
        return type != null && type.isInstance(value) ? type.cast(value) : null;
    }

    /** Every plugin-owned attribute, in the order it was declared. */
    public Map<String, Object> getAttributes() {
        return attributes;
    }

    public DependencyStatus probe(ProbeContext context) {
        return probe.probe(context);
    }

    /** The configured probe, for a consumer that wants to run it itself. */
    public Probe getProbe() {
        return probe;
    }

    /**
     * The caption for this dependency's action button, or null when the plugin
     * offers none. A present dependency gets {@code presentButtonLabel} (a
     * "Verify …" caption); a missing one gets the fix template with the
     * approximate download size appended.
     */
    public String formatButtonLabel(DependencyStatus status) {
        if (!fixableInApp) {
            return null;
        }
        if (status != null && status.isPresent() && presentButtonLabel != null && !presentButtonLabel.isEmpty()) {
            return presentButtonLabel;
        }
        String sizeSuffix = approxDownloadSizeBytes > 0
                ? " " + Sizes.formatApproxSize(approxDownloadSizeBytes)
                : "";
        if (fixButtonLabelTemplate == null || fixButtonLabelTemplate.isEmpty()) {
            return null;
        }
        if (fixButtonLabelTemplate.contains("%s")) {
            return String.format(Locale.ROOT, fixButtonLabelTemplate, sizeSuffix);
        }
        return fixButtonLabelTemplate + sizeSuffix;
    }

    private static <T> List<T> copy(List<T> source) {
        if (source == null || source.isEmpty()) {
            return Collections.emptyList();
        }
        return Collections.unmodifiableList(new ArrayList<T>(source));
    }

    public static final class Builder {
        private final DependencyKey id;
        private final String displayName;
        private String description = "";
        private List<String> affectedFeatures = Collections.emptyList();
        private String detectionStrategyLabel = "";
        private Probe probe = new Probe() {
            @Override
            public DependencyStatus probe(ProbeContext context) {
                return DependencyStatus.error("No dependency probe configured.");
            }
        };
        private long approxDownloadSizeBytes = 0L;
        private boolean restartRequired = false;
        private String fixButtonLabelTemplate = "";
        private String presentButtonLabel = "";
        private boolean fixableInApp = false;
        private String nonFixableReason = "";
        private boolean visibleInDependenciesDialog = true;
        private String dialogSectionLabel = "";
        private List<String> updateSites = Collections.emptyList();
        private List<InstallOption> installOptions = Collections.emptyList();
        private List<Artifact> artifacts = Collections.emptyList();
        private List<String> artifactIgnorePrefixes = Collections.emptyList();
        private final Map<String, Object> attributes = new LinkedHashMap<String, Object>();

        private Builder(DependencyKey id, String displayName) {
            this.id = id;
            this.displayName = displayName;
        }

        public Builder description(String description) {
            this.description = description == null ? "" : description;
            return this;
        }

        public Builder affectedFeatures(String... features) {
            List<String> items = new ArrayList<String>();
            if (features != null) {
                for (String feature : features) {
                    if (feature != null && !feature.trim().isEmpty()) {
                        items.add(feature.trim());
                    }
                }
            }
            this.affectedFeatures = items;
            return this;
        }

        public Builder detectionStrategyLabel(String detectionStrategyLabel) {
            this.detectionStrategyLabel = detectionStrategyLabel == null ? "" : detectionStrategyLabel;
            return this;
        }

        public Builder probe(Probe probe) {
            if (probe != null) {
                this.probe = probe;
            }
            return this;
        }

        public Builder approxDownloadSizeBytes(long approxDownloadSizeBytes) {
            this.approxDownloadSizeBytes = approxDownloadSizeBytes;
            return this;
        }

        public Builder restartRequired(boolean restartRequired) {
            this.restartRequired = restartRequired;
            return this;
        }

        public Builder fixButtonLabelTemplate(String fixButtonLabelTemplate) {
            this.fixButtonLabelTemplate = fixButtonLabelTemplate == null ? "" : fixButtonLabelTemplate;
            return this;
        }

        public Builder presentButtonLabel(String presentButtonLabel) {
            this.presentButtonLabel = presentButtonLabel == null ? "" : presentButtonLabel;
            return this;
        }

        public Builder fixableInApp(boolean fixableInApp) {
            this.fixableInApp = fixableInApp;
            return this;
        }

        public Builder nonFixableReason(String nonFixableReason) {
            this.nonFixableReason = nonFixableReason == null ? "" : nonFixableReason;
            return this;
        }

        public Builder visibleInDependenciesDialog(boolean visibleInDependenciesDialog) {
            this.visibleInDependenciesDialog = visibleInDependenciesDialog;
            return this;
        }

        public Builder dialogSectionLabel(String dialogSectionLabel) {
            this.dialogSectionLabel = dialogSectionLabel == null ? "" : dialogSectionLabel;
            return this;
        }

        public Builder updateSites(String... sites) {
            List<String> items = new ArrayList<String>();
            if (sites != null) {
                for (String site : sites) {
                    if (site != null && !site.trim().isEmpty()) {
                        items.add(site);
                    }
                }
            }
            this.updateSites = items;
            return this;
        }

        public Builder installOptions(List<InstallOption> installOptions) {
            this.installOptions = installOptions == null ? Collections.<InstallOption>emptyList() : installOptions;
            return this;
        }

        public Builder installOptions(InstallOption... options) {
            List<InstallOption> items = new ArrayList<InstallOption>();
            if (options != null) {
                for (InstallOption option : options) {
                    if (option != null) {
                        items.add(option);
                    }
                }
            }
            this.installOptions = items;
            return this;
        }

        public Builder artifacts(List<Artifact> artifacts) {
            this.artifacts = artifacts == null ? Collections.<Artifact>emptyList() : artifacts;
            return this;
        }

        public Builder artifactIgnorePrefixes(List<String> artifactIgnorePrefixes) {
            this.artifactIgnorePrefixes =
                    artifactIgnorePrefixes == null ? Collections.<String>emptyList() : artifactIgnorePrefixes;
            return this;
        }

        /** Attaches plugin-owned metadata. A null value removes the key. */
        public Builder attribute(String key, Object value) {
            if (key == null || key.trim().isEmpty()) {
                return this;
            }
            if (value == null) {
                attributes.remove(key);
            } else {
                attributes.put(key, value);
            }
            return this;
        }

        public DependencySpec build() {
            return new DependencySpec(this);
        }
    }
}
