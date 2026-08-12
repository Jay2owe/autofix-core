# autofix-core

The dependency-autofix chassis, as an embeddable module.

**Status:** built. 110 tests green. Both consumers migrated and passing; see
`EQUIVALENCE_HARNESS.md` for the gate and the five declared changes.
**Pattern:** `../PLUGIN_CORE_PATTERN.md`
**Depends on:** `net.imagej:ij`. Nothing else. Not even another core.
**Never shipped as a jar.**

---

## What this is

A plugin that needs StarDist, or Bio-Formats, or a Python Cellpose environment
has a choice: tell the user to go and install it, or install it for them. The
first costs roughly an order of magnitude of audience — the in-JVM StarDist
plugin has ~89k users and conda-dependent TrackMate-Cellpose has ~15k. The
second means writing this layer.

This is that layer, minus the list of what any particular plugin needs:

```
detect what is missing
  → say what installing it would cost, in megabytes, before the user commits
    → install it on one click
      → verify it actually worked
        → restart Fiji, if the fix needs one
```

FLASH and PULSE each grew this independently. `ImageJRestartHelper` was a
verbatim 375-line copy in both, differing only in a product name baked into a
log filename.

## The boundary — the whole design decision

**Chassis goes in the core. The catalogue never does.**

| In | Out — stays in the plugin |
|---|---|
| `DependencySpec` + `Artifact` + `InstallOption` — the model | `DependencyRegistry` — the catalogue of what *that* plugin needs |
| `DependencyKey`, `DependencyStatus` | `DependencyId` — the plugin's enum of its own dependencies |
| `Probe`, `ProbeContext`, `Probes` — class / command / artifact detection | Criticality bands and fixer-strategy vocabulary |
| `DependencyServiceCore` — probe → plan → fix | Every dialog, every widget, every `IJ.error` |
| `DependencyFixer`, `AbstractJarDependencyFixer`, `JarDependencyFixer` | Bespoke fixers: Cellpose (shells out to conda), TensorFlow crash sentinel |
| `Artifacts` — download by URL, SHA-1 verify, disable rival versions | |
| `ImageJRestartHelper`, `FijiLayout`, `Sizes`, `Product` | |

FLASH's registry is 1,866 lines of StarDist/TensorFlow/Cellpose/Excel/JTS specs.
PULSE's is 242 lines of TrackMate and Commons Math. They have nothing in common,
and merging them would put every plugin's dependencies in every other plugin's
dialog. **Only the machinery is shared.**

### Why `DependencyId` did not move

It looks like model. It is catalogue. FLASH names twenty dependencies, PULSE
names ten, and the two sets do not intersect at all — an enum here would either
be empty or be the union of every consumer's list.

So the core declares an interface and each plugin's existing enum implements it:

```java
public enum DependencyId implements DependencyKey {
    STARDIST_RUNTIME, TENSORFLOW_NATIVE_RUNTIME, ...   // unchanged
}
```

`Enum.name()` already satisfies the interface, so nothing is implemented by
hand, and `EnumMap`, `EnumSet`, `values()`, `valueOf()` and `switch` all keep
working on the plugin side. That mattered: FLASH's public
`EnumMap<DependencyId, DependencyStatus>` signature is implemented by eight of
its test classes, and this keeps every one of them compiling unchanged.

The same reasoning applies to `Criticality` and `FixerStrategy` — FLASH grades
dependencies `OPTIONAL_FEATURE / STARTUP_CRITICAL / INTERNAL_INTEGRITY` and
PULSE grades them `CRITICAL / OPTIONAL_FEATURE`; nothing shared reads either.
They ride along in `spec.attribute(...)` and stay the plugin's own vocabulary.

### Why the concrete fixers did not move

`CellposeRuntimeFixer` exists in both plugins and is *not* shared code. FLASH's
implements `DependencyFixer` and returns a `DependencyFixResult`; PULSE's is a
static method returning its own `Result` and streaming to a `ProgressSink`. Each
is a ten-line adapter over its own plugin's `CellposeRuntime` — 1,006 lines in
FLASH, 803 in PULSE. That pair is a genuine duplicate and a candidate for a
future `cellpose-core`; it is not this module's business.

`TensorFlowCrashSentinel` has one consumer, uses `IJ.log`, and encodes the
on-disk layout of one specific third-party artifact. That is catalogue knowledge
by definition.

## Using it

```java
// Once, at startup.
private static final Product FLASH = Product.named("FLASH");

// Your existing static registry satisfies the catalogue interface as-is.
SpecCatalogue catalogue = new SpecCatalogue() {
    public List<DependencySpec> all() { return DependencyRegistry.all(); }
};

Map<DependencyKey, DependencyFixer> fixers = new LinkedHashMap<>();
fixers.put(DependencyId.JTS_CORE,
        new JarDependencyFixer(FLASH, 13, "JTS runtime verified.", "JTS runtime repair completed."));

DependencyServiceCore service = new DependencyServiceCore(catalogue, fixers);

for (DependencyServiceCore.DialogRow row : service.getDialogRows()) {
    // Finished text. No widgets. Render it however your plugin looks.
    myPanel.add(row.getSpec().getDisplayName(), row.getStatusLabel(), row.getActions());
}
```

Declaring a dependency:

```java
DependencySpec.builder(DependencyId.JTS_CORE, "JTS core")
        .description("Geometry library used by the territories analysis.")
        .affectedFeatures("Object territories")
        .probe(Probes.composite(
                Probes.classProbe("org.locationtech.jts.geom.Geometry"),
                Probes.artifactProbe(JTS_JARS, Collections.<String>emptyList())))
        .artifacts(JTS_JARS)
        .approxDownloadSizeBytes(1_103_721L)
        .restartRequired(true)
        .fixableInApp(true)
        .fixButtonLabelTemplate("Install JTS%s")      // -> "Install JTS (~1.1 MB)"
        .attribute("fixerStrategy", FixerStrategy.DIRECT_JAR_DOWNLOAD)
        .build();
```

## Cores return models, not dialogs

`autofix-core` runs headless. It throws, or returns a status; the plugin decides
how to present. `EmbeddabilityTest` asserts this against the compiled bytecode
rather than trusting the source: no Swing, no `GenericDialog`, no `IJ.error`, no
`IJ.showMessage`, no `System.exit`, no `ResultsTable` in any signature.

`DialogRow` and `DialogAction` are in the core and are **not** an exception to
that rule. A row is finished text — every label, caption and explanatory line —
with no widget anywhere near it. That is the boundary that lets two plugins that
look nothing alike say the same words about the same problem.

## No `net.imagej.updater`

Deliberate, and asserted. This layer downloads with `java.net.URL`, verifies with
SHA-1, and shells out with `ProcessBuilder`. Reaching for the updater API is the
obvious way to "improve" it and would drag the whole SciJava stack into every
consumer's jar — which is the exact cost this module exists to avoid.
`EmbeddabilityTest` fails the build if any class references it.

PULSE opens the Updater window for the user with `IJ.doCommand("Update...")` —
a string, not a class. That stays in PULSE.

## Disable, never delete

A wrong-version jar is renamed to `name.jar.disabled-YYYYMMDD`, never removed.
The user may need it back for a different Fiji tool tomorrow, and a plugin that
silently deletes files out of someone's `Fiji.app` has no way to apologise. On
Windows the jar is usually locked by the running JVM, so the rename is deferred
to a helper that waits for Fiji to exit.

## Consuming it

```xml
<dependency>
  <groupId>io.github.jay2owe</groupId>
  <artifactId>autofix-core</artifactId>
  <version>0.1.0</version>
</dependency>
```

```xml
<artifactSet>
  <includes>
    <include>io.github.jay2owe:autofix-core</include>   <!-- Maven COORDINATE -->
  </includes>
</artifactSet>
<relocations>
  <relocation>
    <pattern>sc.fiji.autofix.core</pattern>            <!-- Java PACKAGE -->
    <shadedPattern>flash.internal.autofix</shadedPattern>
  </relocation>
</relocations>
```

Two different strings. A wrong coordinate in `<artifactSet>` does not error — it
matches nothing, the build succeeds, and the jar ships without the core classes,
surfacing as `NoClassDefFoundError` the moment a user clicks Run.

Current relocations: FLASH → `flash.internal.autofix`, PULSE →
`pulse.internal.autofix`.

`net.imagej:ij` is `provided` here and **`provided` is not transitive** — every
consumer must declare `ij` itself. Both already do.

### Do not add `<minimizeJar>`

`cpc-core`'s README recommends it, correctly, for an engine core. It is wrong
here. This module's entry points are reached through interfaces a consumer
implements (`SpecCatalogue`, `Probe`, `DependencyKey`) and its probes are
constructed reflectively-in-spirit from a catalogue the minimiser cannot see
through. Minimisation would drop classes that are only ever reached at runtime.
The whole module is 72 KB.

### The relocation trap this module avoids

Relocation rewrites bytecode references **and any string constant that looks
like the relocated package**. `oc3d-core` names five tuning and image properties
with literals beginning `sc.fiji.oc3d.core.`, and all five are silently renamed
into each consumer's private namespace — so the documented property name is
ignored and two plugins cannot read each other's image properties.

`EmbeddabilityTest.noStringConstantNamesThisModulesPackage` fails the build if a
literal here ever names `sc.fiji.autofix`. There are none today.

Reflection is confined to `Probes`, where it is the entire point — and where the
class names come from a consumer's catalogue and name third-party classes, which
relocation cannot touch. The test whitelists `Probes` and bans reflection
everywhere else, so a new reflective lookup in the wrong place fails the build
rather than breaking silently after shading.

## Consumers

| Plugin | Relocated to | Status |
|---|---|---|
| FLASH 4.0.0 | `flash.internal.autofix` | migrating |
| PULSE 5.0.1 | `pulse.internal.autofix` | migrating |
| **Registration and Drift Comparison** | *(to be decided)* | **coming — see below** |

### The third consumer, coming

`ImageJ Plugins/Registration and Drift Comparison/` is being built now and is
**vendoring roughly 600 lines in this module's exact shape** — `DependencySpec`,
`DependencyStatus`, `DependencyId`, `DependencyFixer`,
`AbstractJarDependencyFixer`, `ImageJRestartHelper` and a small
`DependencyService` — plus its own `EngineRegistry`, so the swap to the real
module is mechanical rather than a rewrite. See that plugin's `02_CONTRACT.md`
§ *The autofix layer*, which records the decision not to block on this
extraction.

Two things it should know when it swaps:

- Its vendored copy will name its jar-requirement type `Artifact`, which is what
  this module calls it. FLASH called it `JarRequirement`; the contract document
  already uses `Artifact`.
- It must pick a `Product` name before it writes anything to disk. The restart
  log, the repair log, the deferred-disable script and the writability probe are
  all named after it, and those names are the only trace a user has when a
  restart silently does not happen.

Its plugin needs this layer for a reason the other two do not have: it drives
third-party registration engines (TurboReg and friends), and its defect ledger
records that the source it is lifting from calls `System.exit(0)` after driving
TurboReg. Nothing in this module will ever do that — `EmbeddabilityTest` bans
`System.exit` outright.

## Licence

BSD-3-Clause. Links `net.imagej:ij` (ImageJ 1.x, public domain) only. Embedding
it adds no obligation to any consumer.

## Cost, stated honestly

A fix here means rebuilding and re-releasing **every** plugin that embeds it,
rather than shipping one shared jar. That is maintainer effort spent to buy user
simplicity, and it is deliberately the right way round.

## Ship gate

`EQUIVALENCE_HARNESS.md`. Extraction is a refactor: **outputs must not move**,
and for this layer "outputs" means user-facing text, the files it writes into
someone's `Fiji.app`, and two generated shell scripts that nothing exercises
until the moment they matter.
