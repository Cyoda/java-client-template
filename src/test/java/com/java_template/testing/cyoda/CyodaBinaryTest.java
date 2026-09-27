package com.java_template.testing.cyoda;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CyodaBinaryTest {

    private static Path fakeCyoda(Path dir, String versionLine) throws Exception {
        Path bin = dir.resolve("cyoda");
        Files.writeString(bin, "#!/bin/sh\necho '" + versionLine + "'\n");
        Files.setPosixFilePermissions(bin, PosixFilePermissions.fromString("rwxr-xr-x"));
        return bin;
    }

    @Test
    void systemPropertyWinsOverEnvAndPath(@TempDir Path a, @TempDir Path b) throws Exception {
        Path prop = fakeCyoda(a, "x");
        Path env = fakeCyoda(b, "y");

        assertThat(CyodaBinary.locate(prop.toString(), env.toString(), b.toString())).isEqualTo(prop);
        assertThat(CyodaBinary.locate(null, env.toString(), a.toString())).isEqualTo(env);
        assertThat(CyodaBinary.locate(null, null, "/nonexistent:" + a)).isEqualTo(a.resolve("cyoda"));
    }

    @Test
    void aMissingBinaryFailsWithTheInstallCommand() {
        assertThatThrownBy(() -> CyodaBinary.locate(null, null, "/nonexistent"))
                .hasMessageContaining("No cyoda binary found")
                .hasMessageContaining("scripts/install-cyoda.sh");
    }

    @Test
    void resolvePinnedRunsOnlyVersionAndEnforcesThePin(@TempDir Path dir) throws Exception {
        String sha = "df6ad2c7a1b2c3d4e5f60718293a4b5c6d7e8f90";
        Path pin = Files.writeString(dir.resolve("CYODA_VERSION"), "0.9.0-dev\ncommit=" + sha + "\n");
        Path bin = fakeCyoda(dir, "cyoda version 0.9.0-dev (commit 0000000000, built now)");
        System.setProperty("cyoda.bin", bin.toString());
        try {
            assertThatThrownBy(() -> CyodaBinary.resolvePinned(pin, false, m -> {}))
                    .hasMessageContaining("0000000000").hasMessageContaining(sha);

            List<String> warnings = new ArrayList<>();
            assertThat(CyodaBinary.resolvePinned(pin, true, warnings::add)).isEqualTo(bin);
            assertThat(warnings).hasSize(1);
        } finally {
            System.clearProperty("cyoda.bin");
        }
    }
}
