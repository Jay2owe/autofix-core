# Equivalence harness

The before/after testing contract for the `autofix-core` extraction. Shared by
**FLASH** and **PULSE**, and inherited by **Registration and Drift Comparison**
when it swaps its vendored copy for the real module.

Modelled on `../oc3d-core/EQUIVALENCE_HARNESS.md`. **Rule: nothing ships until
the outputs match.** This document defines what "match" means, because for this
layer a naive reading of it is not achievable and pretending otherwise would
hide real regressions.

---

## 1. What "output" means here, and why that changes the shape of the gate

`oc3d-core`'s gate compares **numbers** — voxel counts, centroids, surface
areas. Nothing in this layer produces a number. Its outputs are:

| Kind | Examples | Who sees it |
|---|---|---|
| **Text a user reads** | `"Missing runtime class: de.csbdresden.stardist.StarDist2D"`, `"Install StarDist runtime (~21.7 MB)"`, `"Blocks: 3D object detection"` | Every dependency dialog, every gate dialog |
| **Files written into Fiji.app** | `FLASH-restart-imagej.log`, `stardist-1.2.1.jar.disabled-20260811`, `.flash-runtime-write-test-…` | The user, later, when something has gone wrong |
| **Generated scripts** | the PowerShell and `/bin/sh` restart helpers, the deferred-disable helper | Nobody, until a restart silently fails |
| **Process arguments** | the `powershell.exe … -WindowStyle Hidden -File …` argv | Nobody, ever |
| **Ordering** | fix execution order, update-site order, dialog row order | The user, as the sequence they are walked through |

Three consequences:

1. **The gate is byte-comparison of text, not tolerance on floats.** There is no
   Tier 2 here. A string either matches or it does not. Anything that would have
   been Tier 2 is a Tier 3 decision instead.
2. **The most important surface has no test coverage upstream and no user
   feedback loop.** `ImageJRestartHelper` only acts on a machine that is about to
   shut down, and every failure mode looks identical from inside the JVM: the
   helper starts, and then nothing happens. It gets the strictest treatment here.
3. **Filenames are outputs.** `FLASH-restart-imagej.log` is not an implementation
   detail — it is the only trace a user has when a restart does not happen, and
   it is exactly the kind of string a shared module silently renames.

---

## 2. Reference matrix — what "before" means

| Case | Consumer | Pinned reference |
|---|---|---|
| **A** | FLASH 4.0.0 | its 20-dependency catalogue, its 6 fixers, `FeatureDependencyGate` |
| **B** | PULSE 5.0.1 | its 10-dependency catalogue, `DependencyGate`, `EngineAvailability` |

The two are pinned **separately**. They disagreed about four things before the
extraction (§4), so a single global reference is impossible and asserting one
would mean asserting away a real difference.

### Pre-extraction baseline, recorded 2026-08-11

| | Tests | Result |
|---|---|---|
| FLASH | 4,429 | 1 failure, 3 errors — **all pre-existing, none in `flash.pipeline.runtime`** |
| — of which the autofix layer | 66 | green |
| PULSE | 967 | green, 2 skipped |
| `autofix-core` | 100 | green |

The four FLASH failures are the honest before-state:

- `ExcelSummaryExportAnalysisTest` × 2 — `AccessDeniedException` while scanning
  `%TEMP%`, caused by an unreadable leftover directory from an unrelated tool.
  Environmental.
- `VariationResumeOwnershipTest` × 2 — image-lease ownership during variation
  resume. Unrelated subsystem.

**The gate requires the same four and no others**, not zero. Recording "green
before" when it was not would let this extraction inherit the blame for them, or
worse, let a fifth failure hide among them.

#### Amendment, 2026-08-12: the baseline is six, not four

Two more went red the next day and had to be attributed before the gate could
close:

- `CellposeLocalTrainingServiceTest.managedProcessTimeoutTerminatesRootAndPipeInheritingDescendant`
- `CellposeOneShotIntegrationTest.oneShotManagedTimeoutTerminatesRootAndDescendant`

Both spawn a real JVM using the whole surefire test classpath and give it a **1
ms** timeout, then assert the spawned process's child announced its PID before
the tree was torn down. Adding a jar to that classpath — which this extraction
does — lengthens JVM startup, so there was a plausible causal path from the
migration to the failure and it could not be waved away as flakiness.

It was settled empirically rather than by argument: a clean `git worktree` at
`HEAD`, carrying neither the migration nor any uncommitted work, **fails both
tests with the same two assertion messages**. Not caused here. Their margin was
already near zero and the machine is slower today than on the 11th.

**The gate therefore requires the same six and no seventh.** These two are
genuinely flaky and should be given a real timeout rather than left to the
weather — but that is a separate change against FLASH, not part of this
extraction, and fixing them here would mean editing an unrelated subsystem inside
a refactor that claims to change nothing.

Both plugins build with `-Denforcer.skip=true`, which is the documented
convention in each repo's `AGENTS.md` and not a defect introduced here.

---

## 3. Tier 1 — bit-identical. No exceptions, no tolerance.

Everything in this table must be **byte-for-byte identical** before and after.

### 3.1 The restart helper — highest risk in the whole extraction

| Golden | Why it must be exact |
|---|---|
| The complete generated **Windows PowerShell script** | Reviewed once, for `${LauncherPath}` bracing, no `Invoke-Expression`, no `-ExecutionPolicy Bypass`, and the timeout that refuses to launch a second Fiji. A one-character change reintroduces a bug nobody will see until a user's session is destroyed |
| The complete generated **POSIX shell script** | Same, plus: it is `/bin/sh`, not bash. `$(…)`, `[[`, and `local` are all absent by design |
| The **argv** for both platforms, with the pid substituted | `-WindowStyle Hidden`, `-NonInteractive`, `-NoProfile`, and the deliberate absence of `-ExecutionPolicy` |
| `LaunchSpec` for each of: Windows with `ImageJ-win64.exe`; Windows with only `ImageJ.exe`; a macOS `.app`; a macOS parent containing `Fiji.app`; Linux; an empty directory | Launcher *preference order* is behaviour. Picking `ImageJ.exe` over `ImageJ-win64.exe` starts a 32-bit JVM on a 64-bit machine and the user's memory settings silently stop applying |
| **The log filename** — `FLASH-restart-imagej.log`, `PULSE-restart-imagej.log` | The single easiest thing to break silently, and the only trace a user has |
| **The temp script prefix** — `FLASH-restart-imagej-helper-`, `PULSE-…` | Same |
| The log's **fallback location** when Fiji.app is not writable | A read-only `Program Files` install must still leave a trace somewhere |

Drive the platform branches by setting `os.name` and restoring it. All three
platforms are checked from whichever one the test runs on; a Windows-only test of
the macOS branch is not coverage.

### 3.2 Product-named files written into the user's Fiji.app

| Golden | Before | Must stay |
|---|---|---|
| Writability probe | `.flash-runtime-write-test-<millis>` | `.flash-…` for FLASH |
| Deferred-disable helper | `flash-runtime-disable-*.ps1` | lowercase slug |
| Deferred-disable log | `FLASH-runtime-repair.log` | display name |
| Disabled jar | `<name>.jar.disabled-YYYYMMDD`, then `-2`, `-3`… on collision | exactly |

A shared module that renames any of these has changed what it leaves on a
stranger's disk.

### 3.3 Every user-facing string

For **each spec in each plugin's catalogue**, under every reachable status:

- the probed `DependencyStatus` detail line, for PRESENT / MISSING / ERROR /
  CHECKING
- `formatButtonLabel` for present and missing, including the size suffix
- every `InstallOption` caption
- the `DialogRow` fields: status label, status detail, blocked label,
  explanation, restart label, action note
- the ordered `DialogAction` list — ids and captions
- `Artifacts.check` issue lines and `Artifacts.repair` action lines
- `disabledJarRestoreGuidance` for a named runtime and for an unnamed one
- the fix-result message for success, failure, restart-required and
  not-attempted
- PULSE's service-composed detail lines: `"Not found. Enable update site(s): X"`,
  `"Not set up yet. …"`, `"Not found. Expected to ship with Fiji — …"`,
  `"Detection failed: <SimpleName>"`

Probes must be **substituted** to force each outcome. The real probes read the
live classpath and Fiji.app, so a golden captured from them would encode the
capturing machine and fail on any other.

### 3.4 Size wording

`Sizes.formatApproxSize` over a ladder: `-1, 0, 1, 511, 1024, 22775,
1<<20, 13820232, 143819615, 1<<30, 2684354560`. Locale-independent — pinned to
`Locale.ROOT`, so a machine with a comma decimal separator gives the same string.

### 3.5 Ordering

- `DependencyFixPlan.getDependenciesToFix()` — fixer execution order, then
  restart-last, then display name
- `getAlreadySatisfied()` and `getBlockedDependencies()` — display name,
  case-insensitive
- `updateSitesToEnable()` — deduplicated, first-mention order
- status maps — catalogue order, not hash order

### 3.6 Structural

- No new dependency on either plugin's compile classpath beyond `autofix-core`
- Each plugin's shaded jar contains its relocated package and **not**
  `sc/fiji/autofix/core/`
- Each plugin's own test suite: the same result as the baseline in §2

---

## 4. Tier 3 — declared behaviour changes, signed off

Four behaviours differed between FLASH and PULSE. The decision taken at Gate 1
was **adopt the stricter rule in both** rather than carry two configurations
forever. Each is a real change on a real machine, each is listed here, and each
needs a CHANGELOG line in the consumer it affects.

### T3-1 · `resolveFijiDir` order

| | Before | After |
|---|---|---|
| FLASH | `fiji.dir` → `ij.dir` → `Prefs.getHomeDir()` | `fiji.dir` → `ij.dir` → `IJ.getDirectory("imagej")` → `Prefs.getHomeDir()` |
| PULSE | `IJ.getDirectory("imagej")` → `ij.dir` | same as above |

Also: every step is now guarded, so a throwing lookup cannot take down the sweep
that called it — PULSE's form, which FLASH lacked.

**Who is affected.** On any machine where exactly one source resolves — every
ordinary Fiji install — the answer is unchanged. It differs only where several
are set and disagree, i.e. a developer running Fiji from an IDE with a stale
`ij.dir`. **Risk if wrong:** repairs land in the wrong Fiji installation. This is
the one to test on a real machine before release, not just in the harness.

### T3-2 · `commandProbe` matching

FLASH matched menu commands exactly, case-sensitively, via
`Hashtable.containsKey`. PULSE trimmed and lowercased. **PULSE's rule wins.**

**Who is affected.** FLASH only. A command probe can flip MISSING → PRESENT
where a plugin's menu label differs from FLASH's catalogue by a space or a
capital. That is the correct answer arrived at late — a probe that reports "not
installed" about something plainly in the user's menu is worse than no probe —
but it is a change, and a user who had been told to install something may now be
told they already have it.

### T3-3 · `classProbe` classloader

FLASH used its own classloader. PULSE used `IJ.getClassLoader()`, Fiji's
`PluginClassLoader`. **PULSE's rule wins.**

**Who is affected.** FLASH only, and specifically the ImageJ 1.x plugins that
ship as bare default-package `.class` files rather than jars. FLASH's own
catalogue documents the consequence at its `Iterative Deconvolve 3D` entry:
`Class.forName("Iterative_Deconvolve_3D")` never resolved from FLASH's context,
so a menu-command probe was bolted on as the primary detector. Those probes may
now succeed on the class route too.

**The test seam survives.** `Probes.classProbe` prefers the classloader on the
`ProbeContext` when one is supplied, falling back to Fiji's. FLASH's existing
tests that probe against a synthetic `URLClassLoader` keep working unchanged.

### T3-4 · Probe contract

FLASH's probes returned a `DependencyStatus` carrying their own detail line.
PULSE's returned a `boolean` and its service composed the text afterwards.
**FLASH's rule wins** — one `false` cannot carry two different explanations, and
PULSE's service had already grown a chain of `if`s reconstructing what the probe
knew and threw away.

**Who is affected.** PULSE only, and only structurally: `Probes.of(BooleanProbe,
present, missing)` adapts an existing boolean probe by supplying both messages at
the point where they are known. **PULSE's exact detail strings are Tier 1 and do
not move** — the composition site changes, the text does not.

### T3-5 · A spec with no probe at all

Found during PULSE's migration, not during the boundary read — which is what the
gate is for.

| | Before | After |
|---|---|---|
| PULSE | `MISSING`, carrying the spec's ordinary update-site wording | `ERROR "No dependency probe configured."` |
| FLASH | already errored | unchanged |

PULSE's service tested `spec.getProbe() != null && spec.getProbe().isPresent()`,
so a spec with no probe fell through to "not present" and the user was told to
enable an update site and install something that may well already be there — to
fix what is actually a fault in the catalogue. FLASH's builder already defaulted
to an error probe. **Same stricter-rule-wins decision as T3-2 and T3-3.**

**Who is affected.** Nobody, today: all ten of PULSE's specs declare a probe and
all twenty of FLASH's do, so this moves no text any user has seen. It appears
only in the harness's synthetic `probe=null` scenario. It is declared anyway,
because "unreachable" is a property of today's catalogue and not of the code.

### How a declared change is reconciled with an immutable golden

Not by regenerating the golden. PULSE's harness carries a `DeclaredChange`
class: each item above is expressed as a **rule that rewrites the golden the way
the decision says it should have moved**, plus the exact number of lines that
rule may touch. The rewritten golden must then match the new output byte for
byte.

That is strictly stronger than regenerating, and the reason is the line count:

- a diff the declared change does not fully explain still fails;
- a rule broad enough to absorb an unrelated regression fails on the count;
- the original golden stays in the repository as the record of what the shipped
  plugin actually did, with the reason for every deviation readable beside it;
- reverting a declared change means deleting its rule — the golden is still the
  original, so it simply starts passing again.

### Release-note text, ready to paste

Neither FLASH nor PULSE keeps a `CHANGELOG.md`; FLASH records releases as
one-line entries under *Current release history* in `VERSIONING.md`, PULSE
records none. Rather than invent a file in two repositories for a change that has
not been released, the user-facing wording is parked here and goes into whichever
release these land in. **Written for a user, not a maintainer** — nobody outside
these three repositories will ever hear the words "core" or "extraction".

**FLASH** — affected by T3-1, T3-2, T3-3:

> - Dependency checks now also consult ImageJ's own installation directory when
>   working out where Fiji lives, after `fiji.dir` and `ij.dir`. On an ordinary
>   install the answer is unchanged; it matters only where several of those point
>   at different copies of Fiji.
> - Menu-command detection now ignores capitalisation and surrounding spaces, so
>   a plugin whose menu label differs from FLASH's list only by a capital or a
>   space is no longer reported as missing.
> - Class detection now uses Fiji's own plugin classloader, so ImageJ 1.x plugins
>   that ship as loose `.class` files rather than jars are found directly instead
>   of only through their menu entry.

**PULSE** — affected by T3-1, T3-4 (no user-visible change), T3-5:

> - Dependency checks now look for Fiji in a fixed order — `fiji.dir`, `ij.dir`,
>   ImageJ's own installation directory, then ImageJ's home directory — and a
>   lookup that fails no longer stops the ones after it.
> - A dependency whose entry declares no way of detecting it is now reported as a
>   configuration fault rather than as "not installed". Every dependency PULSE
>   ships declares one, so no existing message changes.

### Sign-off

Shipping without a decision on each of these is the failure mode to avoid. All
five are decided; what remains is placing the text above into each consumer's
release notes and, for T3-1, one check on a real Fiji install.

---

## 5. Three comparison traps

**Timestamps and pids.** The restart argv contains this JVM's process id; the
disabled-jar name contains today's date; the write-probe filename contains
`System.currentTimeMillis()`. Normalise each with an **explicit, documented
substitution** — never by weakening an assertion to `contains`. A golden that
matches everything proves nothing.

**Absolute paths.** Every fixture path is a temp directory that differs per run.
Compare paths **relative to the synthetic Fiji.app root**, and assert the root
itself separately.

**The note describing a normalisation is part of the golden.** Caught on
2026-08-12, one day after capture, when `disabled-jar-naming.txt` failed with all
25 behaviour lines identical. The header read:

```
# NORMALISED: today's date (20260811) is written as <TODAY>
```

The body was correctly normalised; the sentence *explaining* the normalisation
had the live date interpolated into it, so the golden matched only on the day it
was captured. A normalisation note must name the rule and never the value —
`today's date is written as <TODAY>`. Anything a golden's own preamble
interpolates has to survive the same scrutiny as the lines beneath it.

---

## 6. Corpus

### Synthetic Fiji.app trees

- empty; `plugins/` only; `jars/` only; both
- the exact expected jar present
- a rival version present in the same folder
- a rival version present in the **other** folder — Fiji loads both, so a stale
  copy in `jars/` shadows a good one in `plugins/`
- a name sharing the prefix but not versioned (`jts-core-extras-1.0.jar`) —
  must **not** be treated as a rival
- an ignored prefix present
- an unwritable folder
- a jar that cannot be renamed and cannot be scheduled
- a `.disabled-YYYYMMDD` name already taken, forcing the `-2` suffix

### Launcher trees

Windows with `ImageJ-win64.exe` + `ImageJ.exe`; Windows with `ImageJ.exe` only;
macOS `.app` with `Contents/`; macOS parent containing `Fiji.app`; Linux with
`ImageJ-linux64`; an empty directory; a nonexistent directory.

### Status matrix

Every spec × {PRESENT, MISSING, ERROR, CHECKING} × {fixable, not fixable} ×
{visible, hidden} × {restart required, not}. Substituted probes throughout.

### Negative control

At least one test per harness that **deliberately perturbs an input and requires
the differ to notice.** A comparison that cannot fail is not evidence.

---

## 7. Mechanics

```
1. Record each plugin's git SHA and its baseline test result (§2).
2. Run the harness over the corpus. Write to golden/<sha>/.
3. Commit the goldens.
4. Copy — never move — the layer into autofix-core. Both plugins keep building.
5. Rewire each consumer. Re-run identically into candidate/.
6. Diff. Apply the tier contract.
7. Delete the plugin's own copies only once the gate is green.
```

The golden set is captured **once, from the pre-extraction build**, and is
immutable. If a golden is later found to be wrong, that is a bug report against
the shipped plugin — fix it as its own change with its own release note, never by
quietly regenerating goldens to make a diff go away.

Goldens are regenerated only under an explicit flag
(`-D<plugin>.autofix.goldens.write=true`), and a missing golden **fails** rather
than being silently created.

---

## 8. Ship gate

All of the following, in writing, before any jar is published:

- [ ] Tier 1: **zero** differences across the whole corpus, both consumers
- [ ] Both restart scripts byte-identical; argv identical; no `-ExecutionPolicy`
- [ ] `FLASH-restart-imagej.log` and `PULSE-restart-imagej.log` unchanged
- [ ] Every product-named file in §3.2 unchanged
- [ ] T3-1 … T3-5 signed off, with the §4 release-note text placed in each
      affected consumer's notes
- [ ] T3-1 verified on a real Fiji install, not only in the harness
- [ ] FLASH: 4,429 tests, the same 6 pre-existing failures (§2 amendment), no
      seventh
- [ ] PULSE: 967 tests green
- [ ] `autofix-core`: all tests green, `EmbeddabilityTest` included
- [ ] Each shaded jar contains its relocated package and **not** `sc/fiji/autofix/core/`
- [ ] No string literal in the core names `sc.fiji.autofix` (asserted, not eyeballed)
- [ ] Neither consumer acquired a new runtime dependency
- [ ] `Cores/PLUGIN_CORE_PATTERN.md` "Embedded by" table updated
- [ ] Both plugins verified on a **bare** Fiji, not the developer's
