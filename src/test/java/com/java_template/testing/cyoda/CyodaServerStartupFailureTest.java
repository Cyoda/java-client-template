package com.java_template.testing.cyoda;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A startup failure must not leak the temp work dir used as the child's cwd and HOME. Uses the
 * package-private {@code CyodaServer.start(binary, profile, logDir, work)} overload so the test
 * can supply and later inspect a known work dir, without adding a public accessor just for this
 * (CyodaServer.start's public entry point creates and owns that dir internally). Exercises a
 * tiny shell script instead of the real cyoda binary, so no version check or handshake applies.
 */
class CyodaServerStartupFailureTest {

    private static Path exitingScript(Path dir, int exitCode) throws Exception {
        Path bin = dir.resolve("fake-cyoda");
        Files.writeString(bin, "#!/bin/sh\nexit " + exitCode + "\n");
        Files.setPosixFilePermissions(bin, PosixFilePermissions.fromString("rwxr-xr-x"));
        return bin;
    }

    @Test
    void aProcessThatExitsDuringStartupDeletesTheWorkDir(@TempDir Path scratch) throws Exception {
        Path binDir = Files.createDirectory(scratch.resolve("bin"));
        Path bin = exitingScript(binDir, 3);
        Path work = Files.createDirectory(scratch.resolve("work"));
        Path logDir = scratch.resolve("logs");

        assertThat(work).exists();

        assertThatThrownBy(() -> CyodaServer.start(bin, Profile.mockMemory(), logDir, work))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("exited with code 3");

        assertThat(work).doesNotExist();
    }
}
