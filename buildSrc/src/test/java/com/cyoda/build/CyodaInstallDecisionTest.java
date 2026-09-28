package com.cyoda.build;

import com.cyoda.build.CyodaInstallDecision.Action;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ABOUTME: When the installCyoda task installs the pinned cyoda into .cyoda/bin, and when it leaves things alone:
 * an explicit binary (-Dcyoda.bin / CYODA_BIN) always wins, and a binary that already matches the pin is kept.
 */
class CyodaInstallDecisionTest {

    private static final String DEV_PIN = "0.9.0-dev\ncommit=df6ad2c715215ae519c892ab9fabb0e613fe9802\n";
    private static final String RELEASE_PIN = "0.9.0\ncommit=df6ad2c715215ae519c892ab9fabb0e613fe9802\n";
    private static final String DEV_BINARY =
            "cyoda version 0.9.0-dev (commit df6ad2c715215ae519c892ab9fabb0e613fe9802, built 2026-09-27T18:31:32Z)";

    @TempDir
    Path project;

    private final List<Path> ranVersionOf = new ArrayList<>();

    private Path installed() throws Exception {
        Path bin = project.resolve(".cyoda/bin/cyoda");
        Files.createDirectories(bin.getParent());
        Files.writeString(bin, "fake");
        return bin;
    }

    private Function<Path, String> reporting(String output) {
        return p -> {
            ranVersionOf.add(p);
            return output;
        };
    }

    @Test
    void anExplicitSystemPropertySkipsTheInstallWithoutRunningAnything() {
        var d = CyodaInstallDecision.decide("/opt/cyoda", null, project.resolve(".cyoda/bin/cyoda"), DEV_PIN, false,
                reporting("never"));

        assertThat(d.action()).isEqualTo(Action.USE_EXPLICIT);
        assertThat(d.reason()).contains("-Dcyoda.bin");
        assertThat(ranVersionOf).isEmpty();
    }

    @Test
    void anExplicitEnvironmentVariableSkipsTheInstallEvenOnWindows() {
        var d = CyodaInstallDecision.decide(null, "/opt/cyoda", project.resolve(".cyoda/bin/cyoda"), DEV_PIN, true,
                reporting("never"));

        assertThat(d.action()).isEqualTo(Action.USE_EXPLICIT);
        assertThat(d.reason()).contains("CYODA_BIN");
        assertThat(ranVersionOf).isEmpty();
    }

    @Test
    void blankOverridesDoNotCount() {
        var d = CyodaInstallDecision.decide(" ", "", project.resolve(".cyoda/bin/cyoda"), DEV_PIN, false,
                reporting("never"));

        assertThat(d.action()).isEqualTo(Action.INSTALL);
    }

    @Test
    void aMissingBinaryIsInstalled() {
        var d = CyodaInstallDecision.decide(null, null, project.resolve(".cyoda/bin/cyoda"), DEV_PIN, false,
                reporting("never"));

        assertThat(d.action()).isEqualTo(Action.INSTALL);
        assertThat(d.reason()).contains("no cyoda");
        assertThat(ranVersionOf).isEmpty();
    }

    @Test
    void aBinaryMatchingADevPinIsKept() throws Exception {
        Path bin = installed();

        var d = CyodaInstallDecision.decide(null, null, bin, DEV_PIN, false, reporting(DEV_BINARY));

        assertThat(d.action()).isEqualTo(Action.UP_TO_DATE);
        assertThat(ranVersionOf).containsExactly(bin);
    }

    @Test
    void aBinaryMatchingAReleasePinIsKeptWithOrWithoutTheLeadingV() throws Exception {
        Path bin = installed();

        assertThat(CyodaInstallDecision.decide(null, null, bin, RELEASE_PIN, false,
                reporting("cyoda version v0.9.0 (commit 1234567, built now)")).action()).isEqualTo(Action.UP_TO_DATE);
        assertThat(CyodaInstallDecision.decide(null, null, bin, RELEASE_PIN, false,
                reporting("cyoda version 0.9.0 (commit 1234567, built now)")).action()).isEqualTo(Action.UP_TO_DATE);
    }

    @Test
    void aBinaryAtAnotherCommitIsReinstalled() throws Exception {
        var d = CyodaInstallDecision.decide(null, null, installed(), DEV_PIN, false,
                reporting("cyoda version 0.9.0-dev (commit 0000000000, built now)"));

        assertThat(d.action()).isEqualTo(Action.INSTALL);
        assertThat(d.reason()).contains("does not match the pin");
    }

    @Test
    void aBinaryAtAnotherReleaseIsReinstalled() throws Exception {
        var d = CyodaInstallDecision.decide(null, null, installed(), RELEASE_PIN, false,
                reporting("cyoda version 0.8.4 (commit 1234567, built now)"));

        assertThat(d.action()).isEqualTo(Action.INSTALL);
    }

    @Test
    void anUnstampedOrUnreadableBinaryIsReinstalled() throws Exception {
        Path bin = installed();

        assertThat(CyodaInstallDecision.decide(null, null, bin, DEV_PIN, false,
                reporting("cyoda version dev (commit unknown, built unknown)")).action()).isEqualTo(Action.INSTALL);
        assertThat(CyodaInstallDecision.decide(null, null, bin, DEV_PIN, false,
                reporting("garbage")).action()).isEqualTo(Action.INSTALL);
        assertThat(CyodaInstallDecision.decide(null, null, bin, DEV_PIN, false,
                p -> { throw new IllegalStateException("cannot run"); }).action()).isEqualTo(Action.INSTALL);
    }

    @Test
    void onWindowsAnInstallIsRefusedWithTheWayAround() {
        var d = CyodaInstallDecision.decide(null, null, project.resolve(".cyoda/bin/cyoda"), DEV_PIN, true,
                reporting("never"));

        assertThat(d.action()).isEqualTo(Action.CANNOT_INSTALL);
        assertThat(d.reason()).contains("scripts/install-cyoda.sh").contains("-Dcyoda.bin").contains("CYODA_BIN");
    }

    @Test
    void onWindowsAMatchingBinaryIsStillKept() throws Exception {
        var d = CyodaInstallDecision.decide(null, null, installed(), DEV_PIN, true, reporting(DEV_BINARY));

        assertThat(d.action()).isEqualTo(Action.UP_TO_DATE);
    }
}
