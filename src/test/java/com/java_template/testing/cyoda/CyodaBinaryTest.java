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
        Files.createDirectories(dir);
        Path bin = dir.resolve("cyoda");
        Files.writeString(bin, "#!/bin/sh\necho '" + versionLine + "'\n");
        Files.setPosixFilePermissions(bin, PosixFilePermissions.fromString("rwxr-xr-x"));
        return bin;
    }

    /** A project directory with the binary where scripts/install-cyoda.sh puts it by default. */
    private static Path projectWithInstalledBinary(Path project) throws Exception {
        return fakeCyoda(project.resolve(".cyoda").resolve("bin"), "installed");
    }

    @Test
    void lookupOrderIsSystemPropertyThenEnvThenProjectInstallThenPath(
            @TempDir Path a, @TempDir Path b, @TempDir Path project, @TempDir Path onPath) throws Exception {
        Path prop = fakeCyoda(a, "x");
        Path env = fakeCyoda(b, "y");
        Path installed = projectWithInstalledBinary(project);
        Path path = fakeCyoda(onPath, "z");

        assertThat(CyodaBinary.locate(prop.toString(), env.toString(), project, onPath.toString())).isEqualTo(prop);
        assertThat(CyodaBinary.locate(null, env.toString(), project, onPath.toString())).isEqualTo(env);
        assertThat(CyodaBinary.locate(null, null, project, onPath.toString())).isEqualTo(installed);
        assertThat(CyodaBinary.locate(null, null, null, "/nonexistent:" + onPath)).isEqualTo(path);
    }

    @Test
    void theProjectInstallIsFoundWithoutAnyConfiguration(@TempDir Path project) throws Exception {
        Path installed = projectWithInstalledBinary(project);

        Path found = CyodaBinary.locate(null, "", project, "/nonexistent");

        assertThat(found).isEqualTo(installed);
        assertThat(found).isAbsolute();
    }

    @Test
    void aProjectWithoutAnInstallFallsThroughToPath(@TempDir Path project, @TempDir Path onPath) throws Exception {
        Path path = fakeCyoda(onPath, "z");

        assertThat(CyodaBinary.locate(null, null, project, onPath.toString())).isEqualTo(path);
    }

    @Test
    void theProjectDirComesFromCyodaProjectDirThenUserDir(@TempDir Path project) {
        String previous = System.getProperty("cyoda.projectDir");
        try {
            System.clearProperty("cyoda.projectDir");
            assertThat(CyodaBinary.projectDir()).isEqualTo(Path.of(System.getProperty("user.dir")));

            System.setProperty("cyoda.projectDir", project.toString());
            assertThat(CyodaBinary.projectDir()).isEqualTo(project);
        } finally {
            if (previous == null) {
                System.clearProperty("cyoda.projectDir");
            } else {
                System.setProperty("cyoda.projectDir", previous);
            }
        }
    }

    @Test
    void aMissingBinaryFailsWithTheInstallCommand(@TempDir Path project) {
        assertThatThrownBy(() -> CyodaBinary.locate(null, null, project, "/nonexistent"))
                .hasMessageContaining("No cyoda binary found")
                .hasMessageContaining(project.resolve(".cyoda/bin/cyoda").toString())
                .hasMessageContaining("scripts/install-cyoda.sh");
    }

    @Test
    void resolvePinnedRunsOnlyVersionAndEnforcesThePin(@TempDir Path dir) throws Exception {
        String sha = "df6ad2c7a1b2c3d4e5f60718293a4b5c6d7e8f90";
        Path pin = Files.writeString(dir.resolve("CYODA_VERSION"), "0.9.0-dev\ncommit=" + sha + "\n");
        Path bin = fakeCyoda(dir, "cyoda version 0.9.0-dev (commit 0000000000, built now)");
        String previous = System.getProperty("cyoda.bin");
        System.setProperty("cyoda.bin", bin.toString());
        try {
            assertThatThrownBy(() -> CyodaBinary.resolvePinned(pin, false, m -> {}))
                    .hasMessageContaining("0000000000").hasMessageContaining(sha);

            List<String> warnings = new ArrayList<>();
            assertThat(CyodaBinary.resolvePinned(pin, true, warnings::add)).isEqualTo(bin);
            assertThat(warnings).hasSize(1);
        } finally {
            if (previous == null) {
                System.clearProperty("cyoda.bin");
            } else {
                System.setProperty("cyoda.bin", previous);
            }
        }
    }
}
