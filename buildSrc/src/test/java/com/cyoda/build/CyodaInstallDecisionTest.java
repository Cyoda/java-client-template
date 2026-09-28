package com.cyoda.build;

import com.cyoda.build.CyodaInstallDecision.Action;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ABOUTME: When the installCyoda task installs the pinned cyoda into .cyoda/bin, and when it leaves things alone:
 * an explicit binary (-Dcyoda.bin / CYODA_BIN) always wins, a binary that already matches the pin is kept (whether
 * at .cyoda/bin or, failing that, on PATH), and -Dcyoda.allowVersionMismatch=true keeps a deliberately mismatching
 * one instead of overwriting it.
 */
class CyodaInstallDecisionTest {

    private static final String DEV_PIN = "0.9.0-dev\ncommit=df6ad2c715215ae519c892ab9fabb0e613fe9802\n";
    private static final String RELEASE_PIN = "0.9.0\ncommit=df6ad2c715215ae519c892ab9fabb0e613fe9802\n";
    private static final String DEV_BINARY =
            "cyoda version 0.9.0-dev (commit df6ad2c715215ae519c892ab9fabb0e613fe9802, built 2026-09-27T18:31:32Z)";
    private static final String MISMATCHED_BINARY =
            "cyoda version 0.9.0-dev (commit 0000000000, built 2026-09-27T18:31:32Z)";

    @TempDir
    Path project;

    private final List<Path> ranVersionOf = new ArrayList<>();

    /** An executable "cyoda" at .cyoda/bin, matching what scripts/install-cyoda.sh leaves behind (it chmod +x's it). */
    private Path installed() throws Exception {
        Path bin = project.resolve(".cyoda/bin/cyoda");
        Files.createDirectories(bin.getParent());
        Files.writeString(bin, "fake");
        makeExecutable(bin);
        return bin;
    }

    /** A present but non-executable "cyoda" at .cyoda/bin: CyodaBinary.locate() skips this, so decide() must too. */
    private Path installedButNotExecutable() throws Exception {
        Path bin = project.resolve(".cyoda/bin/cyoda");
        Files.createDirectories(bin.getParent());
        Files.writeString(bin, "fake");
        return bin;
    }

    /** An executable "cyoda" in its own directory, suitable for putting on PATH. */
    private Path onPath() throws Exception {
        Path dir = project.resolve("path-bin");
        Files.createDirectories(dir);
        Path bin = dir.resolve("cyoda");
        Files.writeString(bin, "fake");
        makeExecutable(bin);
        return bin;
    }

    private static void makeExecutable(Path bin) throws Exception {
        try {
            Files.setPosixFilePermissions(bin, PosixFilePermissions.fromString("rwxr-xr-x"));
        } catch (UnsupportedOperationException ignored) {
            // non-POSIX filesystem (e.g. Windows); Files.isExecutable still needs a real check there
            bin.toFile().setExecutable(true);
        }
    }

    private Function<Path, String> reporting(String output) {
        return p -> {
            ranVersionOf.add(p);
            return output;
        };
    }

    private CyodaInstallDecision.Result decide(String sysPropBin, String envBin, Path installed, String pinText,
            boolean windows, Function<Path, String> versionOf) {
        return CyodaInstallDecision.decide(sysPropBin, envBin, installed, pinText, windows, false, null, versionOf);
    }

    @Test
    void anExplicitSystemPropertySkipsTheInstallWithoutRunningAnything() {
        var d = decide("/opt/cyoda", null, project.resolve(".cyoda/bin/cyoda"), DEV_PIN, false, reporting("never"));

        assertThat(d.action()).isEqualTo(Action.USE_EXPLICIT);
        assertThat(d.reason()).contains("-Dcyoda.bin");
        assertThat(ranVersionOf).isEmpty();
    }

    @Test
    void anExplicitEnvironmentVariableSkipsTheInstallEvenOnWindows() {
        var d = decide(null, "/opt/cyoda", project.resolve(".cyoda/bin/cyoda"), DEV_PIN, true, reporting("never"));

        assertThat(d.action()).isEqualTo(Action.USE_EXPLICIT);
        assertThat(d.reason()).contains("CYODA_BIN");
        assertThat(ranVersionOf).isEmpty();
    }

    @Test
    void blankOverridesDoNotCount() {
        var d = decide(" ", "", project.resolve(".cyoda/bin/cyoda"), DEV_PIN, false, reporting("never"));

        assertThat(d.action()).isEqualTo(Action.INSTALL);
    }

    @Test
    void aMissingBinaryIsInstalled() {
        var d = decide(null, null, project.resolve(".cyoda/bin/cyoda"), DEV_PIN, false, reporting("never"));

        assertThat(d.action()).isEqualTo(Action.INSTALL);
        assertThat(d.reason()).contains("no cyoda");
        assertThat(ranVersionOf).isEmpty();
    }

    @Test
    void aBinaryMatchingADevPinIsKept() throws Exception {
        Path bin = installed();

        var d = decide(null, null, bin, DEV_PIN, false, reporting(DEV_BINARY));

        assertThat(d.action()).isEqualTo(Action.UP_TO_DATE);
        assertThat(ranVersionOf).containsExactly(bin);
    }

    @Test
    void aBinaryMatchingAReleasePinIsKeptWithOrWithoutTheLeadingV() throws Exception {
        Path bin = installed();

        assertThat(decide(null, null, bin, RELEASE_PIN, false,
                reporting("cyoda version v0.9.0 (commit 1234567, built now)")).action()).isEqualTo(Action.UP_TO_DATE);
        assertThat(decide(null, null, bin, RELEASE_PIN, false,
                reporting("cyoda version 0.9.0 (commit 1234567, built now)")).action()).isEqualTo(Action.UP_TO_DATE);
    }

    @Test
    void aBinaryAtAnotherCommitIsReinstalled() throws Exception {
        var d = decide(null, null, installed(), DEV_PIN, false,
                reporting("cyoda version 0.9.0-dev (commit 0000000000, built now)"));

        assertThat(d.action()).isEqualTo(Action.INSTALL);
        assertThat(d.reason()).contains("does not match the pin");
    }

    @Test
    void aBinaryAtAnotherReleaseIsReinstalled() throws Exception {
        var d = decide(null, null, installed(), RELEASE_PIN, false,
                reporting("cyoda version 0.8.4 (commit 1234567, built now)"));

        assertThat(d.action()).isEqualTo(Action.INSTALL);
    }

    @Test
    void anUnstampedOrUnreadableBinaryIsReinstalled() throws Exception {
        Path bin = installed();

        assertThat(decide(null, null, bin, DEV_PIN, false,
                reporting("cyoda version dev (commit unknown, built unknown)")).action()).isEqualTo(Action.INSTALL);
        assertThat(decide(null, null, bin, DEV_PIN, false,
                reporting("garbage")).action()).isEqualTo(Action.INSTALL);
        assertThat(decide(null, null, bin, DEV_PIN, false,
                p -> { throw new IllegalStateException("cannot run"); }).action()).isEqualTo(Action.INSTALL);
    }

    @Test
    void onWindowsAnInstallIsRefusedWithTheWayAround() {
        var d = decide(null, null, project.resolve(".cyoda/bin/cyoda"), DEV_PIN, true, reporting("never"));

        assertThat(d.action()).isEqualTo(Action.CANNOT_INSTALL);
        assertThat(d.reason()).contains("scripts/install-cyoda.sh").contains("-Dcyoda.bin").contains("CYODA_BIN");
    }

    @Test
    void onWindowsAMatchingBinaryIsStillKept() throws Exception {
        var d = decide(null, null, installed(), DEV_PIN, true, reporting(DEV_BINARY));

        assertThat(d.action()).isEqualTo(Action.UP_TO_DATE);
    }

    @Test
    void aPinMatchingBinaryOnPathIsKeptWithoutInstallingWhenNoneIsInstalled() throws Exception {
        Path onPath = onPath();
        Path installed = project.resolve(".cyoda/bin/cyoda");
        String pathEnv = onPath.getParent().toString();

        var d = CyodaInstallDecision.decide(null, null, installed, DEV_PIN, false, false, pathEnv,
                reporting(DEV_BINARY));

        assertThat(d.action()).isEqualTo(Action.UP_TO_DATE);
        assertThat(d.reason()).contains("PATH");
        assertThat(ranVersionOf).containsExactly(onPath);
    }

    @Test
    void aMismatchingBinaryOnPathIsIgnoredWhenNoneIsInstalled() throws Exception {
        Path onPath = onPath();
        Path installed = project.resolve(".cyoda/bin/cyoda");
        String pathEnv = onPath.getParent().toString();

        var d = CyodaInstallDecision.decide(null, null, installed, DEV_PIN, false, false, pathEnv,
                reporting(MISMATCHED_BINARY));

        assertThat(d.action()).isEqualTo(Action.INSTALL);
    }

    @Test
    void anInstalledBinaryTakesPrecedenceOverPath() throws Exception {
        Path installed = installed();
        Path onPath = onPath();

        var d = CyodaInstallDecision.decide(null, null, installed, DEV_PIN, false, false,
                onPath.getParent().toString(), reporting(DEV_BINARY));

        assertThat(d.action()).isEqualTo(Action.UP_TO_DATE);
        // .cyoda/bin/cyoda is checked, not the one on PATH, matching CyodaBinary.locate()'s order.
        assertThat(ranVersionOf).containsExactly(installed);
    }

    @Test
    void allowVersionMismatchKeepsAMismatchingInstalledBinaryInstead() throws Exception {
        Path bin = installed();

        var d = CyodaInstallDecision.decide(null, null, bin, DEV_PIN, false, true, null,
                reporting(MISMATCHED_BINARY));

        // A distinct action from plain UP_TO_DATE: this binary does NOT match the pin, and the task logs it
        // at WARN, not the quiet INFO a real match gets.
        assertThat(d.action()).isEqualTo(Action.MISMATCH_ALLOWED);
        assertThat(d.reason()).contains("allowVersionMismatch");
    }

    @Test
    void allowVersionMismatchKeepsAMismatchingBinaryOnPathInstead() throws Exception {
        Path onPath = onPath();
        Path installed = project.resolve(".cyoda/bin/cyoda");

        var d = CyodaInstallDecision.decide(null, null, installed, DEV_PIN, false, true,
                onPath.getParent().toString(), reporting(MISMATCHED_BINARY));

        assertThat(d.action()).isEqualTo(Action.MISMATCH_ALLOWED);
        assertThat(d.reason()).contains("allowVersionMismatch");
    }

    @Test
    void allowVersionMismatchStillInstallsWhenNoBinaryExistsAnywhere() {
        var d = CyodaInstallDecision.decide(null, null, project.resolve(".cyoda/bin/cyoda"), DEV_PIN, false, true,
                null, reporting("never"));

        assertThat(d.action()).isEqualTo(Action.INSTALL);
    }

    @Test
    void aPresentButNonExecutableInstalledBinaryFallsBackToPath() throws Exception {
        Path installed = installedButNotExecutable();
        Path onPath = onPath();

        // CyodaBinary.locate() skips a non-executable .cyoda/bin/cyoda and falls through to PATH; decide() must
        // make the same choice, or it could needlessly reinstall over a binary nothing will ever run.
        var d = CyodaInstallDecision.decide(null, null, installed, DEV_PIN, false, false,
                onPath.getParent().toString(), reporting(DEV_BINARY));

        assertThat(d.action()).isEqualTo(Action.UP_TO_DATE);
        assertThat(d.reason()).contains("PATH");
        assertThat(ranVersionOf).containsExactly(onPath);
    }
}
