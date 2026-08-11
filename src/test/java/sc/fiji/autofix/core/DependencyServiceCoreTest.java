package sc.fiji.autofix.core;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/** Probe caching, plan construction, dialog rows and action dispatch. */
public class DependencyServiceCoreTest {

    // --- helpers --------------------------------------------------------------

    private static DependencySpec spec(TestKeys key, String name, boolean fixable, long bytes,
                                       boolean restart, boolean visible) {
        return DependencySpec.builder(key, name)
                .description(name + " description.")
                .affectedFeatures(name + " feature")
                .fixableInApp(fixable)
                .approxDownloadSizeBytes(bytes)
                .restartRequired(restart)
                .visibleInDependenciesDialog(visible)
                .fixButtonLabelTemplate("Install " + name + "%s")
                .presentButtonLabel("Verify " + name)
                .build();
    }

    private static SpecCatalogue catalogue(final DependencySpec... specs) {
        return new SpecCatalogue() {
            @Override
            public List<DependencySpec> all() {
                return Arrays.asList(specs);
            }
        };
    }

    private static DependencyServiceCore.StatusSnapshotProvider fixed(
            final Map<DependencyKey, DependencyStatus> statuses) {
        return new DependencyServiceCore.StatusSnapshotProvider() {
            @Override
            public Map<DependencyKey, DependencyStatus> snapshot(List<DependencySpec> specs) {
                Map<DependencyKey, DependencyStatus> out =
                        new LinkedHashMap<DependencyKey, DependencyStatus>();
                for (DependencySpec spec : specs) {
                    DependencyStatus status = statuses.get(spec.getId());
                    if (status != null) {
                        out.put(spec.getId(), status);
                    }
                }
                return out;
            }
        };
    }

    private static final class RecordingFixer implements DependencyFixer {
        private final int order;
        private final List<String> calls = new ArrayList<String>();

        RecordingFixer(int order) {
            this.order = order;
        }

        @Override
        public DependencyFixResult apply(DependencySpec spec, String actionId, ProgressCallback callback) {
            calls.add(spec.getId().name() + ":" + actionId);
            return new DependencyFixResult(spec.getId(), true, true, false, "done");
        }

        @Override
        public int getExecutionOrder() {
            return order;
        }
    }

    // --- caching --------------------------------------------------------------

    @Test
    public void statusesAreProbedOnceUntilInvalidated() {
        final int[] probeCount = {0};
        DependencyServiceCore service = new DependencyServiceCore(
                catalogue(spec(TestKeys.ALPHA, "Alpha", true, 0, false, true)),
                Collections.<DependencyKey, DependencyFixer>emptyMap(),
                new DependencyServiceCore.StatusSnapshotProvider() {
                    @Override
                    public Map<DependencyKey, DependencyStatus> snapshot(List<DependencySpec> specs) {
                        probeCount[0]++;
                        Map<DependencyKey, DependencyStatus> out =
                                new LinkedHashMap<DependencyKey, DependencyStatus>();
                        out.put(TestKeys.ALPHA, DependencyStatus.present("ok"));
                        return out;
                    }
                });

        service.getStatus(TestKeys.ALPHA);
        service.getStatus(TestKeys.ALPHA);
        service.getDialogRows();
        assertEquals("probing walks the classpath and Fiji.app; it must not repeat per call",
                1, probeCount[0]);

        service.invalidateStatusCache();
        service.getStatus(TestKeys.ALPHA);
        assertEquals(2, probeCount[0]);
    }

    @Test
    public void anUnknownKeyIsNullOrCheckingDependingOnWhichAccessorIsUsed() {
        DependencyServiceCore service = service(statuses(present(TestKeys.ALPHA)));
        assertNull(service.getStatus(TestKeys.UNREGISTERED));
        DependencyStatus checking = service.getStatusOrChecking(TestKeys.UNREGISTERED);
        assertTrue(checking.isChecking());
        assertEquals("Not yet checked.", checking.getDetailMessage());
    }

    // --- probing --------------------------------------------------------------

    @Test
    public void aProbeThatThrowsBecomesAnErrorStatusAndDoesNotStopTheSweep() {
        DependencySpec exploding = DependencySpec.builder(TestKeys.ALPHA, "Alpha")
                .probe(new Probe() {
                    @Override
                    public DependencyStatus probe(ProbeContext context) {
                        throw new IllegalStateException("boom");
                    }
                })
                .build();
        DependencySpec fine = DependencySpec.builder(TestKeys.BETA, "Beta")
                .probe(Probes.of(new BooleanProbe() {
                    @Override
                    public boolean isPresent() {
                        return true;
                    }
                }, "installed", "not installed"))
                .build();

        Map<DependencyKey, DependencyStatus> snapshot = DependencyServiceCore.snapshotStatuses(
                Arrays.asList(exploding, fine), new ProbeContext(null, null));

        assertTrue(snapshot.get(TestKeys.ALPHA).isError());
        assertTrue(snapshot.get(TestKeys.ALPHA).getDetailMessage(),
                snapshot.get(TestKeys.ALPHA).getDetailMessage()
                        .startsWith("Probe failed for Alpha: IllegalStateException: boom"));
        assertTrue("one broken probe must not hide the others",
                snapshot.get(TestKeys.BETA).isPresent());
    }

    @Test
    public void aProbeReturningNothingBecomesAnErrorRatherThanANullStatus() {
        DependencySpec silent = DependencySpec.builder(TestKeys.ALPHA, "Alpha")
                .probe(new Probe() {
                    @Override
                    public DependencyStatus probe(ProbeContext context) {
                        return null;
                    }
                })
                .build();
        Map<DependencyKey, DependencyStatus> snapshot = DependencyServiceCore.snapshotStatuses(
                Collections.singletonList(silent), new ProbeContext(null, null));
        assertTrue(snapshot.get(TestKeys.ALPHA).isError());
        assertEquals("Probe returned no status for Alpha.",
                snapshot.get(TestKeys.ALPHA).getDetailMessage());
    }

    @Test
    public void theKeyIsAttachedOnTheWayOutOfAProbeThatDidNotSetOne() {
        DependencySpec any = DependencySpec.builder(TestKeys.GAMMA, "Gamma")
                .probe(Probes.of(new BooleanProbe() {
                    @Override
                    public boolean isPresent() {
                        return false;
                    }
                }, "yes", "no"))
                .build();
        Map<DependencyKey, DependencyStatus> snapshot = DependencyServiceCore.snapshotStatuses(
                Collections.singletonList(any), new ProbeContext(null, null));
        assertSame(TestKeys.GAMMA, snapshot.get(TestKeys.GAMMA).getKey());
    }

    // --- plan -----------------------------------------------------------------

    @Test
    public void thePlanTotalsTheDownloadAndOrdersByFixerExecutionOrder() {
        DependencySpec alpha = spec(TestKeys.ALPHA, "Alpha", true, 1_000_000L, false, true);
        DependencySpec beta = spec(TestKeys.BETA, "Beta", true, 2_000_000L, true, true);
        DependencySpec gamma = spec(TestKeys.GAMMA, "Gamma", false, 0L, false, true);

        Map<DependencyKey, DependencyFixer> fixers = new LinkedHashMap<DependencyKey, DependencyFixer>();
        fixers.put(TestKeys.ALPHA, new RecordingFixer(20));
        fixers.put(TestKeys.BETA, new RecordingFixer(10));

        DependencyServiceCore service = new DependencyServiceCore(
                catalogue(alpha, beta, gamma), fixers,
                fixed(statuses(missing(TestKeys.ALPHA), missing(TestKeys.BETA), missing(TestKeys.GAMMA))));

        DependencyFixPlan plan = service.planFixAll();

        assertEquals(3_000_000L, plan.getTotalApproxDownloadBytes());
        assertTrue(plan.isRestartRequired());
        assertEquals(Arrays.asList("Beta", "Alpha"), displayNames(plan.getDependenciesToFix()));
        assertEquals("Gamma is missing, visible and unfixable — the plan must say so",
                Collections.singletonList("Gamma"), displayNames(plan.getBlockedDependencies()));
        assertTrue(plan.getAlreadySatisfied().isEmpty());
    }

    @Test
    public void anAlreadyPresentFixableDependencyIsListedAsSatisfiedNotQueued() {
        DependencySpec alpha = spec(TestKeys.ALPHA, "Alpha", true, 500L, false, true);
        Map<DependencyKey, DependencyFixer> fixers = new LinkedHashMap<DependencyKey, DependencyFixer>();
        fixers.put(TestKeys.ALPHA, new RecordingFixer(1));

        DependencyServiceCore service = new DependencyServiceCore(
                catalogue(alpha), fixers, fixed(statuses(present(TestKeys.ALPHA))));

        DependencyFixPlan plan = service.planFixAll();
        assertTrue(plan.getDependenciesToFix().isEmpty());
        assertEquals(Collections.singletonList("Alpha"), displayNames(plan.getAlreadySatisfied()));
        assertEquals(0L, plan.getTotalApproxDownloadBytes());
        assertFalse(plan.isRestartRequired());
    }

    @Test
    public void anInvisibleUnfixableDependencyIsNotShownAsBlocked() {
        DependencySpec hidden = spec(TestKeys.GAMMA, "Gamma", false, 0L, false, false);
        DependencyServiceCore service = new DependencyServiceCore(
                catalogue(hidden), Collections.<DependencyKey, DependencyFixer>emptyMap(),
                fixed(statuses(missing(TestKeys.GAMMA))));
        assertTrue(service.planFixAll().getBlockedDependencies().isEmpty());
    }

    // --- dialog rows ----------------------------------------------------------

    @Test
    public void aMissingFixableRowOffersAutoFixWithTheDownloadSize() {
        DependencySpec alpha = spec(TestKeys.ALPHA, "Alpha", true, 13_820_232L, true, true);
        Map<DependencyKey, DependencyFixer> fixers = new LinkedHashMap<DependencyKey, DependencyFixer>();
        fixers.put(TestKeys.ALPHA, new RecordingFixer(1));

        DependencyServiceCore service = new DependencyServiceCore(
                catalogue(alpha), fixers, fixed(statuses(missing(TestKeys.ALPHA))));

        List<DependencyServiceCore.DialogRow> rows = service.getDialogRows();
        assertEquals(1, rows.size());
        DependencyServiceCore.DialogRow row = rows.get(0);
        assertEquals("Missing", row.getStatusLabel());
        assertEquals("Blocks: Alpha feature", row.getBlockedLabel());
        assertEquals("Restart Fiji after repair: required.", row.getRestartLabel());
        assertEquals(1, row.getActions().size());
        assertEquals(DependencyServiceCore.DialogAction.AUTO_FIX, row.getActions().get(0).getActionId());
        assertEquals("Install Alpha (~13.2 MB)", row.getActions().get(0).getLabel());
    }

    @Test
    public void aPresentRowOffersVerifyRatherThanInstall() {
        DependencySpec alpha = spec(TestKeys.ALPHA, "Alpha", true, 1L, false, true);
        Map<DependencyKey, DependencyFixer> fixers = new LinkedHashMap<DependencyKey, DependencyFixer>();
        fixers.put(TestKeys.ALPHA, new RecordingFixer(1));

        DependencyServiceCore service = new DependencyServiceCore(
                catalogue(alpha), fixers, fixed(statuses(present(TestKeys.ALPHA))));

        DependencyServiceCore.DialogRow row = service.getDialogRows().get(0);
        assertEquals("Present", row.getStatusLabel());
        assertEquals(1, row.getActions().size());
        assertEquals(DependencyServiceCore.DialogAction.VERIFY, row.getActions().get(0).getActionId());
        assertEquals("Verify Alpha", row.getActions().get(0).getLabel());
    }

    /**
     * A background probe that has not finished must not push the user into a
     * 2 GB install of something that may already be working.
     */
    @Test
    public void aCheckingRowOffersOnlyRefresh() {
        DependencySpec alpha = spec(TestKeys.ALPHA, "Alpha", true, 2_000_000_000L, false, true);
        Map<DependencyKey, DependencyFixer> fixers = new LinkedHashMap<DependencyKey, DependencyFixer>();
        fixers.put(TestKeys.ALPHA, new RecordingFixer(1));

        DependencyServiceCore service = new DependencyServiceCore(
                catalogue(alpha), fixers,
                fixed(statuses(DependencyStatus.of(TestKeys.ALPHA,
                        DependencyStatus.State.CHECKING, "still looking"))));

        DependencyServiceCore.DialogRow row = service.getDialogRows().get(0);
        assertEquals(1, row.getActions().size());
        assertEquals("Refresh Status", row.getActions().get(0).getLabel());
    }

    @Test
    public void installOptionsReplaceTheSingleAutoFixButton() {
        DependencySpec cellpose = DependencySpec.builder(TestKeys.BETA, "Cellpose")
                .fixableInApp(true)
                .fixButtonLabelTemplate("Install Cellpose%s")
                .installOptions(
                        new DependencySpec.InstallOption("cellpose_cpu", "Set Up CPU%s", 524_288_000L),
                        new DependencySpec.InstallOption("cellpose_gpu", "Set Up GPU%s", 2_684_354_560L))
                .build();
        Map<DependencyKey, DependencyFixer> fixers = new LinkedHashMap<DependencyKey, DependencyFixer>();
        fixers.put(TestKeys.BETA, new RecordingFixer(1));

        DependencyServiceCore service = new DependencyServiceCore(
                catalogue(cellpose), fixers, fixed(statuses(missing(TestKeys.BETA))));

        List<DependencyServiceCore.DialogAction> actions = service.getDialogRows().get(0).getActions();
        assertEquals(2, actions.size());
        assertEquals("Set Up CPU (~500 MB)", actions.get(0).getLabel());
        assertEquals("Set Up GPU (~2.5 GB)", actions.get(1).getLabel());
    }

    @Test
    public void anUnfixableRowExplainsItselfRatherThanShowingADeadButton() {
        DependencySpec gamma = DependencySpec.builder(TestKeys.GAMMA, "Gamma")
                .fixableInApp(false)
                .nonFixableReason("Ships with Fiji; reinstall Fiji if it stays missing.")
                .build();
        DependencyServiceCore service = new DependencyServiceCore(
                catalogue(gamma), Collections.<DependencyKey, DependencyFixer>emptyMap(),
                fixed(statuses(missing(TestKeys.GAMMA))));

        DependencyServiceCore.DialogRow row = service.getDialogRows().get(0);
        assertTrue(row.getActions().isEmpty());
        assertEquals("Not fixable in-app - see README.\n"
                + "Ships with Fiji; reinstall Fiji if it stays missing.", row.getActionNote());
    }

    @Test
    public void aSpecWithNoDeclaredFeaturesSaysSoRatherThanShowingBlankBlocks() {
        DependencySpec bare = DependencySpec.builder(TestKeys.ALPHA, "Alpha").build();
        DependencyServiceCore service = new DependencyServiceCore(
                catalogue(bare), Collections.<DependencyKey, DependencyFixer>emptyMap(),
                fixed(statuses(missing(TestKeys.ALPHA))));
        assertEquals("Blocks: Feature scope not declared.",
                service.getDialogRows().get(0).getBlockedLabel());
    }

    @Test
    public void invisibleDependenciesAreNotRowed() {
        DependencySpec hidden = spec(TestKeys.GAMMA, "Gamma", true, 0L, false, false);
        DependencyServiceCore service = new DependencyServiceCore(
                catalogue(hidden), Collections.<DependencyKey, DependencyFixer>emptyMap(),
                fixed(statuses(missing(TestKeys.GAMMA))));
        assertTrue(service.getDialogRows().isEmpty());
        assertFalse(service.hasVisibleDependenciesNeedingAttention());
    }

    // --- action dispatch ------------------------------------------------------

    @Test
    public void anEmptyActionIdMeansAutoFix() {
        RecordingFixer fixer = new RecordingFixer(1);
        DependencyServiceCore service = serviceWithFixer(fixer);
        assertTrue(service.runDialogAction(TestKeys.ALPHA, "").isSuccess());
        assertEquals(Collections.singletonList("ALPHA:auto_fix"), fixer.calls);
    }

    @Test
    public void anActionTheSpecDoesNotAdvertiseIsRejectedRatherThanDefaulted() {
        RecordingFixer fixer = new RecordingFixer(1);
        DependencyServiceCore service = serviceWithFixer(fixer);
        DependencyFixResult result = service.runDialogAction(TestKeys.ALPHA, "cellpose_gpu");
        assertFalse(result.wasAttempted());
        assertEquals("Unknown dependency action: cellpose_gpu", result.getMessage());
        assertTrue("a typo must not trigger the default install", fixer.calls.isEmpty());
    }

    @Test
    public void anUnknownDependencySaysSo() {
        DependencyServiceCore service = serviceWithFixer(new RecordingFixer(1));
        DependencyFixResult result = service.runDialogAction(TestKeys.UNREGISTERED, "auto_fix");
        assertFalse(result.wasAttempted());
        assertEquals("Unknown dependency id.", result.getMessage());
    }

    @Test
    public void aDependencyWithNoFixerReportsItsOwnReason() {
        DependencySpec gamma = DependencySpec.builder(TestKeys.GAMMA, "Gamma")
                .fixableInApp(false)
                .nonFixableReason("Reinstall Fiji.")
                .build();
        DependencyServiceCore service = new DependencyServiceCore(
                catalogue(gamma), Collections.<DependencyKey, DependencyFixer>emptyMap(),
                fixed(statuses(missing(TestKeys.GAMMA))));
        assertEquals("Reinstall Fiji.",
                service.runDialogAction(TestKeys.GAMMA, "auto_fix").getMessage());
    }

    @Test
    public void verifyReprobesAndReportsTheFreshStatus() {
        final DependencyStatus[] current = {DependencyStatus.missing("not yet")};
        DependencySpec alpha = spec(TestKeys.ALPHA, "Alpha", true, 0L, false, true);
        DependencyServiceCore service = new DependencyServiceCore(
                catalogue(alpha), Collections.<DependencyKey, DependencyFixer>emptyMap(),
                new DependencyServiceCore.StatusSnapshotProvider() {
                    @Override
                    public Map<DependencyKey, DependencyStatus> snapshot(List<DependencySpec> specs) {
                        Map<DependencyKey, DependencyStatus> out =
                                new LinkedHashMap<DependencyKey, DependencyStatus>();
                        out.put(TestKeys.ALPHA, current[0]);
                        return out;
                    }
                });

        assertFalse(service.runDialogAction(TestKeys.ALPHA, "verify").isSuccess());
        current[0] = DependencyStatus.present("all good");
        DependencyFixResult after = service.runDialogAction(TestKeys.ALPHA, "verify");
        assertTrue(after.isSuccess());
        assertEquals("Present: all good", after.getMessage());
    }

    @Test
    public void aFixInvalidatesTheCacheSoTheNextReadReflectsIt() {
        final int[] probes = {0};
        DependencySpec alpha = spec(TestKeys.ALPHA, "Alpha", true, 0L, false, true);
        Map<DependencyKey, DependencyFixer> fixers = new LinkedHashMap<DependencyKey, DependencyFixer>();
        fixers.put(TestKeys.ALPHA, new RecordingFixer(1));
        DependencyServiceCore service = new DependencyServiceCore(
                catalogue(alpha), fixers,
                new DependencyServiceCore.StatusSnapshotProvider() {
                    @Override
                    public Map<DependencyKey, DependencyStatus> snapshot(List<DependencySpec> specs) {
                        probes[0]++;
                        Map<DependencyKey, DependencyStatus> out =
                                new LinkedHashMap<DependencyKey, DependencyStatus>();
                        out.put(TestKeys.ALPHA, probes[0] == 1
                                ? DependencyStatus.missing("no")
                                : DependencyStatus.present("yes"));
                        return out;
                    }
                });

        assertFalse(service.isAvailable(TestKeys.ALPHA));
        service.runDialogAction(TestKeys.ALPHA, "auto_fix");
        assertTrue("a completed fix must not leave a stale MISSING in the cache",
                service.isAvailable(TestKeys.ALPHA));
    }

    // --- update sites ----------------------------------------------------------

    @Test
    public void updateSitesAreDeduplicatedInFirstMentionOrder() {
        DependencySpec stardist = DependencySpec.builder(TestKeys.ALPHA, "StarDist")
                .fixableInApp(true).updateSites("StarDist", "CSBDeep").build();
        DependencySpec csbdeep = DependencySpec.builder(TestKeys.BETA, "CSBDeep")
                .fixableInApp(true).updateSites("CSBDeep").build();
        DependencySpec present = DependencySpec.builder(TestKeys.GAMMA, "TrackMate")
                .fixableInApp(true).updateSites("TrackMate").build();

        DependencyServiceCore service = new DependencyServiceCore(
                catalogue(stardist, csbdeep, present),
                Collections.<DependencyKey, DependencyFixer>emptyMap(),
                fixed(statuses(missing(TestKeys.ALPHA), missing(TestKeys.BETA), present(TestKeys.GAMMA))));

        assertEquals(Arrays.asList("StarDist", "CSBDeep"), service.updateSitesToEnable());
    }

    @Test
    public void anUnfixableDependencysUpdateSitesAreNotAdvertised() {
        DependencySpec unfixable = DependencySpec.builder(TestKeys.ALPHA, "Alpha")
                .fixableInApp(false).updateSites("SomeSite").build();
        DependencyServiceCore service = new DependencyServiceCore(
                catalogue(unfixable), Collections.<DependencyKey, DependencyFixer>emptyMap(),
                fixed(statuses(missing(TestKeys.ALPHA))));
        assertTrue(service.updateSitesToEnable().isEmpty());
    }

    // --- the key stays usable as an enum on the consumer side -------------------

    /**
     * The reason {@link DependencyKey} is an interface rather than a type this
     * module owns: a consumer keeps its enum, and everything an enum gives it.
     */
    @Test
    public void aConsumerCanStillKeyAnEnumMapOnItsOwnIds() {
        DependencyServiceCore service = service(statuses(present(TestKeys.ALPHA), missing(TestKeys.BETA)));

        EnumMap<TestKeys, DependencyStatus> narrowed = new EnumMap<TestKeys, DependencyStatus>(TestKeys.class);
        for (Map.Entry<DependencyKey, DependencyStatus> entry : service.refreshStatuses().entrySet()) {
            narrowed.put((TestKeys) entry.getKey(), entry.getValue());
        }

        assertEquals(2, narrowed.size());
        assertTrue(narrowed.get(TestKeys.ALPHA).isPresent());
        assertTrue(narrowed.get(TestKeys.BETA).isMissing());
        assertNotNull(TestKeys.valueOf("ALPHA"));
        assertEquals(4, TestKeys.values().length);
    }

    @Test
    public void statusesComeBackInCatalogueOrderNotSomeHashOrder() {
        DependencyServiceCore service = new DependencyServiceCore(
                catalogue(spec(TestKeys.GAMMA, "Gamma", false, 0, false, true),
                        spec(TestKeys.ALPHA, "Alpha", false, 0, false, true),
                        spec(TestKeys.BETA, "Beta", false, 0, false, true)),
                Collections.<DependencyKey, DependencyFixer>emptyMap(),
                fixed(statuses(present(TestKeys.ALPHA), present(TestKeys.BETA), present(TestKeys.GAMMA))));

        assertEquals(Arrays.asList((DependencyKey) TestKeys.GAMMA, TestKeys.ALPHA, TestKeys.BETA),
                new ArrayList<DependencyKey>(service.refreshStatuses().keySet()));
    }

    @Test(expected = IllegalArgumentException.class)
    public void aServiceWithNoCatalogueIsRefusedAtConstruction() {
        new DependencyServiceCore(null, Collections.<DependencyKey, DependencyFixer>emptyMap());
    }

    // --- small helpers ---------------------------------------------------------

    private static DependencyStatus present(TestKeys key) {
        return DependencyStatus.of(key, DependencyStatus.State.PRESENT, "present");
    }

    private static DependencyStatus missing(TestKeys key) {
        return DependencyStatus.of(key, DependencyStatus.State.MISSING, "missing");
    }

    private static Map<DependencyKey, DependencyStatus> statuses(DependencyStatus... items) {
        Map<DependencyKey, DependencyStatus> map = new LinkedHashMap<DependencyKey, DependencyStatus>();
        for (DependencyStatus status : items) {
            map.put(status.getKey(), status);
        }
        return map;
    }

    private static DependencyServiceCore service(Map<DependencyKey, DependencyStatus> statuses) {
        List<DependencySpec> specs = new ArrayList<DependencySpec>();
        for (DependencyKey key : statuses.keySet()) {
            specs.add(spec((TestKeys) key, key.name(), false, 0L, false, true));
        }
        return new DependencyServiceCore(
                catalogue(specs.toArray(new DependencySpec[0])),
                Collections.<DependencyKey, DependencyFixer>emptyMap(),
                fixed(statuses));
    }

    private static DependencyServiceCore serviceWithFixer(RecordingFixer fixer) {
        DependencySpec alpha = spec(TestKeys.ALPHA, "Alpha", true, 0L, false, true);
        Map<DependencyKey, DependencyFixer> fixers = new LinkedHashMap<DependencyKey, DependencyFixer>();
        fixers.put(TestKeys.ALPHA, fixer);
        return new DependencyServiceCore(catalogue(alpha), fixers,
                fixed(statuses(missing(TestKeys.ALPHA))));
    }

    private static List<String> displayNames(List<DependencySpec> specs) {
        List<String> names = new ArrayList<String>();
        for (DependencySpec spec : specs) {
            names.add(spec.getDisplayName());
        }
        return names;
    }
}
