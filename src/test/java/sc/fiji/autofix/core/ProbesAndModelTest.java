package sc.fiji.autofix.core;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/** Probes, size wording, product naming, and the spec model's edges. */
public class ProbesAndModelTest {

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    private static final ProbeContext EMPTY_CLASSPATH =
            new ProbeContext(new URLClassLoader(new URL[0], null), null);

    // --- class probe ----------------------------------------------------------

    @Test
    public void aPresentClassIsPresent() {
        DependencyStatus status = Probes.classProbe("java.lang.String")
                .probe(new ProbeContext(getClass().getClassLoader(), null));
        assertTrue(status.isPresent());
        assertEquals("Required runtime classes are available.", status.getDetailMessage());
    }

    @Test
    public void anAbsentClassNamesItselfSoTheUserKnowsWhichSiteToEnable() {
        DependencyStatus status = Probes.classProbe("de.csbdresden.stardist.StarDist2D")
                .probe(EMPTY_CLASSPATH);
        assertTrue(status.isMissing());
        assertEquals("Missing runtime class: de.csbdresden.stardist.StarDist2D",
                status.getDetailMessage());
    }

    @Test
    public void everyNamedClassMustLoadForClassProbe() {
        DependencyStatus status = Probes.classProbe("java.lang.String", "no.such.Class")
                .probe(new ProbeContext(getClass().getClassLoader(), null));
        assertTrue(status.isMissing());
        assertEquals("Missing runtime class: no.such.Class", status.getDetailMessage());
    }

    @Test
    public void anyClassProbeSucceedsOnTheFirstThatLoads() {
        DependencyStatus status = Probes.anyClassProbe("no.such.Class", "java.lang.String")
                .probe(new ProbeContext(getClass().getClassLoader(), null));
        assertTrue(status.isPresent());
    }

    @Test
    public void anyClassProbeReportsEveryRouteItTriedWhenNoneWorked() {
        DependencyStatus status = Probes.anyClassProbe("first.Missing", "second.Missing")
                .probe(EMPTY_CLASSPATH);
        assertTrue(status.isMissing());
        assertEquals("Missing runtime class: first.Missing\nMissing runtime class: second.Missing",
                status.getDetailMessage());
    }

    @Test
    public void aMisconfiguredClassProbeIsAnErrorNotASilentPass() {
        assertTrue(Probes.classProbe().probe(EMPTY_CLASSPATH).isError());
        assertTrue(Probes.anyClassProbe().probe(EMPTY_CLASSPATH).isError());
    }

    /**
     * The context's loader wins when one is supplied — which is what keeps a
     * consumer's existing tests able to probe against a synthetic classpath
     * after the convergence on Fiji's plugin classloader.
     */
    @Test
    public void aSuppliedClassLoaderIsUsedInPreferenceToFijis() {
        // A class on the application classpath, not the bootstrap one: an empty
        // URLClassLoader still delegates to bootstrap, so java.lang.* would
        // resolve through it and prove nothing about which loader was consulted.
        String appClass = Probes.class.getName();
        assertTrue(Probes.classProbe(appClass)
                .probe(new ProbeContext(getClass().getClassLoader(), null)).isPresent());
        assertTrue("an empty loader must see nothing, whatever Fiji's loader has",
                Probes.classProbe(appClass).probe(EMPTY_CLASSPATH).isMissing());
    }

    // --- combinators ----------------------------------------------------------

    @Test
    public void compositeNeedsEveryProbeAndPrefersErrorOverAbsence() {
        assertTrue(Probes.composite(
                Probes.classProbe("java.lang.String"),
                Probes.classProbe("java.lang.Integer"))
                .probe(new ProbeContext(getClass().getClassLoader(), null)).isPresent());

        DependencyStatus mixed = Probes.composite(
                Probes.failing("cannot check"),
                Probes.classProbe("no.such.Class"))
                .probe(EMPTY_CLASSPATH);
        assertTrue("'could not check' and 'not installed' need different words to the user",
                mixed.isError());
        assertEquals("cannot check", mixed.getDetailMessage());
    }

    @Test
    public void anEmptyCompositePassesAndAnEmptyAnyOfDoesNot() {
        assertTrue(Probes.composite().probe(EMPTY_CLASSPATH).isPresent());
        assertTrue(Probes.anyOf().probe(EMPTY_CLASSPATH).isError());
    }

    // --- boolean adapter -------------------------------------------------------

    @Test
    public void aBooleanProbeSuppliesBothMessagesUpFront() {
        Probe yes = Probes.of(new BooleanProbe() {
            @Override
            public boolean isPresent() {
                return true;
            }
        }, "Installed.", "Not found.");
        Probe no = Probes.of(new BooleanProbe() {
            @Override
            public boolean isPresent() {
                return false;
            }
        }, "Installed.", "Not found. Enable update site(s): TrackMate");

        assertEquals("Installed.", yes.probe(EMPTY_CLASSPATH).getDetailMessage());
        assertTrue(no.probe(EMPTY_CLASSPATH).isMissing());
        assertEquals("Not found. Enable update site(s): TrackMate",
                no.probe(EMPTY_CLASSPATH).getDetailMessage());
    }

    @Test
    public void aBooleanProbeThatThrowsBecomesAnErrorWithTheExceptionName() {
        Probe boom = Probes.of(new BooleanProbe() {
            @Override
            public boolean isPresent() {
                throw new UnsupportedOperationException("nope");
            }
        }, "yes", "no");
        DependencyStatus status = boom.probe(EMPTY_CLASSPATH);
        assertTrue(status.isError());
        assertEquals("Detection failed: UnsupportedOperationException", status.getDetailMessage());
    }

    // --- artifact probe --------------------------------------------------------

    @Test
    public void anArtifactProbeWithNoFijiDirectoryIsAnErrorNotAMiss() {
        DependencyStatus status = Probes.artifactProbe(
                Collections.singletonList(new DependencySpec.Artifact(
                        "JTS", "jts-core-1.19.0.jar", "jts-core-", "jars", "", false)),
                null).probe(new ProbeContext(null, null));
        assertTrue(status.isError());
        assertEquals("Could not determine the Fiji.app directory.", status.getDetailMessage());
    }

    @Test
    public void anArtifactProbeReportsWhatIsMissing() throws Exception {
        File fiji = folder.newFolder("probe-fiji");
        assertTrue(new File(fiji, "jars").mkdirs());
        DependencyStatus status = Probes.artifactProbe(
                Collections.singletonList(new DependencySpec.Artifact(
                        "JTS core", "jts-core-1.19.0.jar", "jts-core-", "jars", "", false)),
                null).probe(new ProbeContext(null, fiji));
        assertTrue(status.isMissing());
        assertEquals("JTS core: MISSING (need jts-core-1.19.0.jar)", status.getDetailMessage());
    }

    // --- size wording ----------------------------------------------------------

    @Test
    public void downloadSizesReadTheWayAUserExpects() {
        assertEquals("", Sizes.formatApproxSize(-1L));
        assertEquals("(~0 B)", Sizes.formatApproxSize(0L));
        assertEquals("(~1 KB)", Sizes.formatApproxSize(1L));
        assertEquals("(~1 KB)", Sizes.formatApproxSize(1024L));
        assertEquals("(~22 KB)", Sizes.formatApproxSize(22_775L));
        assertEquals("(~1 MB)", Sizes.formatApproxSize(1L << 20));
        assertEquals("(~13.2 MB)", Sizes.formatApproxSize(13_820_232L));
        assertEquals("(~137.2 MB)", Sizes.formatApproxSize(143_819_615L));
        assertEquals("(~1 GB)", Sizes.formatApproxSize(1L << 30));
        assertEquals("(~2.5 GB)", Sizes.formatApproxSize(2_684_354_560L));
    }

    /**
     * The boundary between "KB" and nothing. Anything non-zero rounds up to
     * 1 KB, because "(~0 KB)" reads as a bug rather than as "small".
     */
    @Test
    public void aTinyDownloadStillReadsAsOneKilobyte() {
        assertEquals("(~1 KB)", Sizes.formatApproxSize(3L));
        assertEquals("(~1 KB)", Sizes.formatApproxSize(511L));
    }

    // --- product ---------------------------------------------------------------

    @Test
    public void aProductSlugsItsOwnName() {
        assertEquals("flash", Product.named("FLASH").slug());
        assertEquals("FLASH", Product.named("FLASH").displayName());
        assertEquals("my-plugin", Product.named("My Plugin").slug());
        assertEquals("regdrift", Product.named("RegDrift").slug());
    }

    @Test
    public void aProductWithAnExplicitSlugKeepsIt() {
        Product product = Product.of("3D Objects Counter+", "oc3dplus");
        assertEquals("oc3dplus", product.slug());
        assertEquals("3D Objects Counter+", product.displayName());
    }

    @Test(expected = IllegalArgumentException.class)
    public void aBlankProductNameIsRefusedRatherThanProducingNamelessFiles() {
        Product.named("   ");
    }

    // --- spec model ------------------------------------------------------------

    @Test
    public void theSizeSuffixGoesWhereTheTemplateSaysOrAtTheEnd() {
        DependencySpec withPlaceholder = DependencySpec.builder(TestKeys.ALPHA, "Alpha")
                .fixableInApp(true)
                .approxDownloadSizeBytes(13_820_232L)
                .fixButtonLabelTemplate("Install Excel support%s now")
                .build();
        assertEquals("Install Excel support (~13.2 MB) now", withPlaceholder.formatButtonLabel(null));

        DependencySpec withoutPlaceholder = DependencySpec.builder(TestKeys.ALPHA, "Alpha")
                .fixableInApp(true)
                .approxDownloadSizeBytes(13_820_232L)
                .fixButtonLabelTemplate("Install Excel support")
                .build();
        assertEquals("Install Excel support (~13.2 MB)", withoutPlaceholder.formatButtonLabel(null));
    }

    @Test
    public void anUnfixableSpecOffersNoButtonAtAll() {
        DependencySpec spec = DependencySpec.builder(TestKeys.ALPHA, "Alpha")
                .fixableInApp(false)
                .fixButtonLabelTemplate("Install Alpha%s")
                .build();
        assertNull(spec.formatButtonLabel(null));
    }

    @Test
    public void zeroBytesAddsNoSuffix() {
        DependencySpec spec = DependencySpec.builder(TestKeys.ALPHA, "Alpha")
                .fixableInApp(true)
                .fixButtonLabelTemplate("Install Alpha%s")
                .build();
        assertEquals("Install Alpha", spec.formatButtonLabel(null));
    }

    @Test
    public void pluginVocabularyRidesAlongWithoutTheCoreKnowingWhatItMeans() {
        DependencySpec spec = DependencySpec.builder(TestKeys.ALPHA, "Alpha")
                .attribute("criticality", "STARTUP_CRITICAL")
                .attribute("fixerStrategy", TestKeys.BETA)
                .build();

        assertEquals("STARTUP_CRITICAL", spec.attribute("criticality", String.class));
        assertSame(TestKeys.BETA, spec.attribute("fixerStrategy", TestKeys.class));
        assertNull("a wrong type reads as absent rather than throwing at a dialog",
                spec.attribute("criticality", Integer.class));
        assertNull(spec.attribute("nothing-here", String.class));
        assertEquals(2, spec.getAttributes().size());
    }

    @Test
    public void aSpecWithNoProbeConfiguredErrorsRatherThanClaimingPresence() {
        DependencyStatus status = DependencySpec.builder(TestKeys.ALPHA, "Alpha").build()
                .probe(EMPTY_CLASSPATH);
        assertTrue(status.isError());
        assertEquals("No dependency probe configured.", status.getDetailMessage());
    }

    @Test
    public void listAccessorsAreImmutableAndNeverNull() {
        DependencySpec spec = DependencySpec.builder(TestKeys.ALPHA, "Alpha").build();
        assertTrue(spec.getArtifacts().isEmpty());
        assertTrue(spec.getInstallOptions().isEmpty());
        assertTrue(spec.getUpdateSites().isEmpty());
        assertTrue(spec.getAffectedFeatures().isEmpty());
        try {
            spec.getUpdateSites().add("nope");
            org.junit.Assert.fail("a spec must not be mutable through its accessors");
        } catch (UnsupportedOperationException expected) {
            // as designed
        }
    }

    @Test
    public void blankUpdateSitesAndFeaturesAreDroppedRatherThanShownAsEmptyEntries() {
        DependencySpec spec = DependencySpec.builder(TestKeys.ALPHA, "Alpha")
                .updateSites("StarDist", "  ", null, "CSBDeep")
                .affectedFeatures("Detection", "", null)
                .build();
        assertEquals(2, spec.getUpdateSites().size());
        assertEquals(1, spec.getAffectedFeatures().size());
    }

    @Test
    public void aStatusKeepsTheKeyAProbeGaveItRatherThanBeingRelabelled() {
        DependencyStatus explicit = DependencyStatus.of(TestKeys.ALPHA,
                DependencyStatus.State.PRESENT, "detail");
        assertSame(TestKeys.ALPHA, explicit.withKey(TestKeys.BETA).getKey());

        DependencyStatus anonymous = DependencyStatus.present("detail");
        assertNull(anonymous.getKey());
        assertSame(TestKeys.BETA, anonymous.withKey(TestKeys.BETA).getKey());
    }

    @Test
    public void statusDetailIsTrimmedSoDialogsDoNotRenderStrayWhitespace() {
        assertEquals("tidy", DependencyStatus.missing("  tidy  ").getDetailMessage());
        assertEquals("", DependencyStatus.missing(null).getDetailMessage());
    }

    @Test
    public void needsAttentionCoversMissingAndErrorAndNothingElse() {
        assertTrue(DependencyStatus.missing("x").needsAttention());
        assertTrue(DependencyStatus.error("x").needsAttention());
        assertFalse(DependencyStatus.present("x").needsAttention());
        assertFalse("a probe still running is not yet a problem to report",
                DependencyStatus.checking("x").needsAttention());
    }
}
