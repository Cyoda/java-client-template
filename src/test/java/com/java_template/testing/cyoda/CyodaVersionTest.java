package com.java_template.testing.cyoda;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CyodaVersionTest {

    private static final String SHA = "df6ad2c7a1b2c3d4e5f60718293a4b5c6d7e8f90";

    @Test
    void readsThePinFile(@TempDir Path dir) throws Exception {
        Path pin = Files.writeString(dir.resolve("CYODA_VERSION"), "0.9.0-dev\ncommit=" + SHA + "\n");

        CyodaVersion v = CyodaVersion.readPin(pin);

        assertThat(v.version()).isEqualTo("0.9.0-dev");
        assertThat(v.commit()).isEqualTo(SHA);
        assertThat(v.isDevPin()).isTrue();
    }

    @Test
    void rejectsAMalformedPinFile(@TempDir Path dir) throws Exception {
        Path pin = Files.writeString(dir.resolve("CYODA_VERSION"), "0.9.0\n");

        assertThatThrownBy(() -> CyodaVersion.readPin(pin)).hasMessageContaining("commit=<sha>");
    }

    @Test
    void parsesTheBinaryVersionLine() {
        CyodaVersion v = CyodaVersion.parseBinaryOutput(
                "cyoda version 0.8.4 (commit e86115417899938dd1ea2b38c1441fee43d0bb6a, built 2026-09-09T22:13:55Z)\n");

        assertThat(v).isEqualTo(new CyodaVersion("0.8.4", "e86115417899938dd1ea2b38c1441fee43d0bb6a"));
    }

    @Test
    void releasedPinsCompareVersions() {
        CyodaVersion pin = new CyodaVersion("0.9.0", SHA);

        assertThat(CyodaVersion.incompatibility(pin, new CyodaVersion("0.9.0", "abc1234"))).isEmpty();
        assertThat(CyodaVersion.incompatibility(pin, new CyodaVersion("0.8.4", "abc1234")))
                .get().asString().contains("0.8.4").contains("0.9.0").contains("scripts/install-cyoda.sh");
    }

    @Test
    void devPinsCompareCommits() {
        CyodaVersion pin = new CyodaVersion("0.9.0-dev", SHA);

        assertThat(CyodaVersion.incompatibility(pin, new CyodaVersion("0.9.0-dev", SHA))).isEmpty();
        assertThat(CyodaVersion.incompatibility(pin, new CyodaVersion("0.9.0-dev", "df6ad2c"))).isEmpty();
        assertThat(CyodaVersion.incompatibility(pin, new CyodaVersion("0.9.0-dev", "0123456789"))).isPresent();
    }

    @Test
    void anUnstampedDevBinaryIsRefused() {
        CyodaVersion pin = new CyodaVersion("0.9.0-dev", SHA);

        assertThat(CyodaVersion.incompatibility(pin, new CyodaVersion("dev", "unknown")))
                .get().asString().contains("dev (commit unknown)").contains("scripts/install-cyoda.sh");
    }
}
