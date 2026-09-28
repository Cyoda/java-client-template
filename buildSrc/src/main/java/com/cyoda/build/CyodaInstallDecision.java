package com.cyoda.build;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * ABOUTME: Decides whether the installCyoda task installs the pinned cyoda into .cyoda/bin (spec §7.4).
 * An explicit binary (-Dcyoda.bin or CYODA_BIN) always wins and nothing is installed or run; a binary already in
 * .cyoda/bin, or failing that on PATH, whose {@code --version} matches the pin is kept, because that is the same
 * binary {@code CyodaBinary.locate()} would use at test time; a mismatching one is (re)installed, unless
 * {@code -Dcyoda.allowVersionMismatch=true} says to keep it deliberately; anything missing is installed.
 * <p>
 * The pin rule is the harness's own (testFixtures {@code CyodaVersion.incompatibility}, spec §3.4), repeated here
 * because the build logic cannot use the test fixtures: a released pin needs the same {@code major.minor.patch}
 * (a leading {@code v} ignored), a {@code -dev} pin the same commit (common prefix, at least 7 characters), and a
 * binary that reports {@code dev (commit unknown)} matches nothing.
 */
public final class CyodaInstallDecision {

    public enum Action { USE_EXPLICIT, UP_TO_DATE, INSTALL, CANNOT_INSTALL }

    public record Result(Action action, String reason) {
    }

    private static final Pattern BINARY_LINE = Pattern.compile("cyoda version (\\S+) \\(commit (\\S+), built [^)]*\\)");

    private CyodaInstallDecision() {
    }

    /**
     * @param sysPropBin           the build's {@code -Dcyoda.bin}, or null
     * @param envBin               {@code $CYODA_BIN}, or null
     * @param installed            {@code <project>/.cyoda/bin/cyoda}
     * @param pinText              the contents of CYODA_VERSION
     * @param windows              whether the build runs on Windows, which cannot run scripts/install-cyoda.sh
     * @param allowVersionMismatch {@code -Dcyoda.allowVersionMismatch=true}: a binary already at {@code installed}
     *                             or on PATH is kept even when it does not match the pin, so a binary the user
     *                             placed there deliberately is never overwritten
     * @param pathEnv              {@code $PATH}, searched (in the same order {@code CyodaBinary.locate()} uses)
     *                             only when {@code installed} does not exist
     * @param versionOf            runs {@code <binary> --version} and returns its output (and nothing else: any
     *                             other argument would start a server, cyoda-go #623); it may throw when the
     *                             binary cannot run
     */
    public static Result decide(String sysPropBin, String envBin, Path installed, String pinText, boolean windows,
            boolean allowVersionMismatch, String pathEnv, Function<Path, String> versionOf) {
        if (sysPropBin != null && !sysPropBin.isBlank()) {
            return new Result(Action.USE_EXPLICIT, "-Dcyoda.bin=" + sysPropBin + " is set");
        }
        if (envBin != null && !envBin.isBlank()) {
            return new Result(Action.USE_EXPLICIT, "CYODA_BIN=" + envBin + " is set");
        }

        Path candidate;
        String label;
        if (Files.exists(installed)) {
            candidate = installed;
            label = installed.toString();
        } else {
            candidate = findOnPath(pathEnv);
            label = candidate == null ? null : candidate + " on PATH";
        }

        String why;
        if (candidate == null) {
            why = "no cyoda at " + installed + " or on PATH";
        } else {
            String output = null;
            String failure = null;
            try {
                output = versionOf.apply(candidate);
            } catch (RuntimeException e) {
                failure = e.getMessage();
            }
            if (output != null && matchesPin(pinText, output)) {
                return new Result(Action.UP_TO_DATE, label + " matches the pin " + pinSummary(pinText));
            }
            why = output != null
                    ? label + " (" + output.strip() + ") does not match the pin " + pinSummary(pinText)
                    : label + " --version failed (" + failure + ")";
            if (allowVersionMismatch) {
                return new Result(Action.UP_TO_DATE,
                        why + "; kept because -Dcyoda.allowVersionMismatch=true is set");
            }
        }
        if (windows) {
            return new Result(Action.CANNOT_INSTALL, why + ", and scripts/install-cyoda.sh needs bash, which this "
                    + "Windows build cannot run. Install cyoda " + pinSummary(pinText) + " yourself and pass "
                    + "-Dcyoda.bin=<path> or set CYODA_BIN, or skip the integration tests with -x integrationTest.");
        }
        return new Result(Action.INSTALL, why);
    }

    /** The first executable {@code cyoda} on PATH, in the order {@code CyodaBinary.locate()} searches it. */
    private static Path findOnPath(String pathEnv) {
        if (pathEnv == null) {
            return null;
        }
        for (String dir : pathEnv.split(File.pathSeparator)) {
            Path candidate = Path.of(dir, "cyoda");
            if (Files.isExecutable(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    static boolean matchesPin(String pinText, String versionOutput) {
        String[] pin = parsePin(pinText);
        Matcher m = BINARY_LINE.matcher(versionOutput);
        if (!m.find()) {
            return false;
        }
        String version = m.group(1);
        String commit = m.group(2);
        if ("dev".equals(version) && "unknown".equals(commit)) {
            return false;
        }
        if (pin[0].endsWith("-dev")) {
            return sameCommit(pin[1], commit);
        }
        String reported = version.startsWith("v") ? version.substring(1) : version;
        return reported.equals(pin[0]);
    }

    /** "0.9.0-dev at commit df6ad2c…" or "0.9.0". */
    static String pinSummary(String pinText) {
        String[] pin = parsePin(pinText);
        return pin[0].endsWith("-dev") ? pin[0] + " at commit " + pin[1] : pin[0];
    }

    private static String[] parsePin(String pinText) {
        List<String> lines = pinText.lines().map(String::trim).filter(l -> !l.isEmpty()).toList();
        if (lines.size() != 2 || !lines.get(1).startsWith("commit=")) {
            throw new IllegalStateException("CYODA_VERSION must contain two lines: <version> and commit=<sha>");
        }
        return new String[]{lines.get(0), lines.get(1).substring("commit=".length())};
    }

    private static boolean sameCommit(String a, String b) {
        int n = Math.min(a.length(), b.length());
        return n >= 7 && a.substring(0, n).equalsIgnoreCase(b.substring(0, n));
    }
}
