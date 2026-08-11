package sc.fiji.autofix.core;

import ij.Menus;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Hashtable;
import java.util.List;
import java.util.Locale;

/**
 * The detection strategies every plugin of this kind needs: is the class on the
 * classpath, is the menu command registered, is the jar on disk — plus the two
 * combinators for saying "any of these will do" and "all of these must hold".
 *
 * <p>Each probe reports a detail line naming what specifically was not found.
 * That line is the whole value of the layer: "Missing runtime class:
 * de.csbdresden.stardist.StarDist2D" tells a user which update site to enable,
 * and "not installed" does not.
 */
public final class Probes {

    private Probes() {}

    /**
     * Present only when every named class loads.
     *
     * <p>Uses the context's classloader when it has one, and Fiji's plugin
     * classloader otherwise — see {@link ClassLoaders} for why that matters and
     * for the behaviour change it represents for FLASH. Loads with
     * {@code initialize = false}: a probe must not run a static initialiser,
     * which for a native-backed library means loading a DLL as a side effect of
     * asking whether it exists.
     */
    public static Probe classProbe(final String... classNames) {
        return new Probe() {
            @Override
            public DependencyStatus probe(ProbeContext context) {
                if (classNames == null || classNames.length == 0) {
                    return DependencyStatus.error("No runtime classes were configured for this dependency.");
                }
                ClassLoader loader = loaderFor(context);
                for (String className : classNames) {
                    try {
                        Class.forName(className, false, loader);
                    } catch (ClassNotFoundException e) {
                        return DependencyStatus.missing("Missing runtime class: " + className);
                    } catch (NoClassDefFoundError e) {
                        return DependencyStatus.missing("Missing runtime class: " + Text.normalizeMissingClass(e));
                    } catch (LinkageError e) {
                        return DependencyStatus.error("Could not load " + className + ": "
                                + e.getClass().getSimpleName() + ": " + Text.safeMessage(e));
                    }
                }
                return DependencyStatus.present("Required runtime classes are available.");
            }
        };
    }

    /**
     * Present when any one of the named classes loads — for a dependency whose
     * entry point was renamed between versions and where either name is
     * acceptable evidence.
     */
    public static Probe anyClassProbe(final String... classNames) {
        if (classNames == null || classNames.length == 0) {
            return failing("No runtime classes were configured for this dependency.");
        }
        Probe[] probes = new Probe[classNames.length];
        for (int i = 0; i < classNames.length; i++) {
            probes[i] = classProbe(classNames[i]);
        }
        return anyOf(probes);
    }

    /**
     * Present when ImageJ's command table has any of the named menu commands.
     *
     * <p>Matching is case-insensitive and trimmed. FLASH matched exactly, on
     * {@code Hashtable.containsKey}, which fails against a plugin whose menu
     * label differs from the catalogue's by a space or a capital — and a probe
     * that says "not installed" about something plainly in the menu is worse
     * than no probe. PULSE's looser matching is the rule for both.
     * <b>Declared behaviour change</b> for FLASH (Tier 3 in
     * {@code EQUIVALENCE_HARNESS.md}): a command probe can flip MISSING to
     * PRESENT.
     *
     * <p>The command table is the authoritative answer for whether
     * {@code IJ.run("Some Command", ...)} will work, which is why it beats a
     * class probe for ImageJ 1.x plugins that ship as bare class files.
     */
    public static Probe commandProbe(final String... commandNames) {
        return new Probe() {
            @Override
            @SuppressWarnings("rawtypes")
            public DependencyStatus probe(ProbeContext context) {
                if (commandNames == null || commandNames.length == 0) {
                    return DependencyStatus.error("No ImageJ commands were configured for this dependency.");
                }
                Hashtable commands;
                try {
                    commands = Menus.getCommands();
                } catch (Throwable t) {
                    return DependencyStatus.error("ImageJ command table is not available yet.");
                }
                if (commands == null) {
                    return DependencyStatus.error("ImageJ command table is not available yet.");
                }
                for (String commandName : commandNames) {
                    if (contains(commands, commandName)) {
                        return DependencyStatus.present("ImageJ command available: " + commandName);
                    }
                }
                return DependencyStatus.missing("ImageJ command not found: "
                        + Text.joinComma(Arrays.asList(commandNames)));
            }
        };
    }

    /**
     * Present when every artifact this dependency declares is on disk at the
     * expected version, with no rival version alongside it.
     */
    public static Probe artifactProbe(final List<DependencySpec.Artifact> artifacts,
                                      final List<String> ignorePrefixes) {
        return new Probe() {
            @Override
            public DependencyStatus probe(ProbeContext context) {
                if (context.getFijiDir() == null) {
                    return DependencyStatus.error("Could not determine the Fiji.app directory.");
                }
                List<String> issues = Artifacts.check(context.getFijiDir(), artifacts, ignorePrefixes);
                if (issues.isEmpty()) {
                    return DependencyStatus.present("All required Fiji jars are present.");
                }
                return DependencyStatus.missing(Text.joinLines(issues));
            }
        };
    }

    /**
     * Present as soon as one of {@code probes} is. Reports every probe's
     * complaint when none succeeds, because on a missing dependency the user
     * needs to see all the routes that were tried, not just the last.
     */
    public static Probe anyOf(final Probe... probes) {
        return new Probe() {
            @Override
            public DependencyStatus probe(ProbeContext context) {
                if (probes == null || probes.length == 0) {
                    return DependencyStatus.error("No probes were configured for this dependency.");
                }
                List<String> missing = new ArrayList<String>();
                List<String> errors = new ArrayList<String>();
                for (Probe probe : probes) {
                    if (probe == null) {
                        continue;
                    }
                    DependencyStatus status = probe.probe(context);
                    if (status == null) {
                        continue;
                    }
                    if (status.isPresent()) {
                        return status;
                    }
                    String detail = status.getDetailMessage();
                    if (status.isError()) {
                        errors.add(detail == null ? "" : detail);
                    } else {
                        missing.add(detail == null ? "" : detail);
                    }
                }
                if (!missing.isEmpty()) {
                    return DependencyStatus.missing(Text.joinLines(missing));
                }
                if (!errors.isEmpty()) {
                    return DependencyStatus.error(Text.joinLines(errors));
                }
                return DependencyStatus.missing("No probe could detect this dependency.");
            }
        };
    }

    /**
     * Present only when every one of {@code probes} is. An error anywhere wins
     * over a mere absence, because "could not check" and "not installed" call
     * for different actions from the user.
     */
    public static Probe composite(final Probe... probes) {
        return new Probe() {
            @Override
            public DependencyStatus probe(ProbeContext context) {
                List<String> missing = new ArrayList<String>();
                List<String> errors = new ArrayList<String>();
                if (probes != null) {
                    for (Probe probe : probes) {
                        if (probe == null) {
                            continue;
                        }
                        DependencyStatus status = probe.probe(context);
                        if (status == null || status.isPresent()) {
                            continue;
                        }
                        if (status.isError()) {
                            errors.add(status.getDetailMessage());
                        } else {
                            missing.add(status.getDetailMessage());
                        }
                    }
                }
                if (!errors.isEmpty()) {
                    return DependencyStatus.error(Text.joinLines(errors));
                }
                if (!missing.isEmpty()) {
                    return DependencyStatus.missing(Text.joinLines(missing));
                }
                return DependencyStatus.present("All checks passed.");
            }
        };
    }

    /**
     * Adapts a yes/no probe, supplying the two detail lines at the point where
     * they are known. A probe that throws becomes an error status rather than
     * escaping — the wording matches what {@link DependencyServiceCore} would
     * have produced, so the two paths are indistinguishable to a reader.
     */
    public static Probe of(final BooleanProbe probe,
                           final String presentMessage,
                           final String missingMessage) {
        return new Probe() {
            @Override
            public DependencyStatus probe(ProbeContext context) {
                if (probe == null) {
                    return DependencyStatus.error("No dependency probe configured.");
                }
                boolean present;
                try {
                    present = probe.isPresent();
                } catch (Throwable t) {
                    return DependencyStatus.error("Detection failed: " + t.getClass().getSimpleName());
                }
                return present
                        ? DependencyStatus.present(presentMessage)
                        : DependencyStatus.missing(missingMessage);
            }
        };
    }

    /** A probe that always reports the same error — for a misconfigured spec. */
    public static Probe failing(final String message) {
        return new Probe() {
            @Override
            public DependencyStatus probe(ProbeContext context) {
                return DependencyStatus.error(message);
            }
        };
    }

    private static ClassLoader loaderFor(ProbeContext context) {
        ClassLoader supplied = context == null ? null : context.getClassLoader();
        return supplied != null ? supplied : ClassLoaders.fijiClassLoader();
    }

    @SuppressWarnings("rawtypes")
    private static boolean contains(Hashtable commands, String commandName) {
        if (commandName == null) {
            return false;
        }
        String target = commandName.trim().toLowerCase(Locale.ROOT);
        for (Object key : commands.keySet()) {
            if (key != null && key.toString().trim().toLowerCase(Locale.ROOT).equals(target)) {
                return true;
            }
        }
        return false;
    }
}
