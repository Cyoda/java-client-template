package com.java_template.common.tool;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ABOUTME: A released cyoda pin is installed only when its archive matches the SHA-256 committed in
 * CYODA_SHA256SUMS, which sync-cyoda-contract.sh records. Runs copies of the real scripts in a temp project with a
 * fake release archive (--archive) and a local SHA256SUMS (--sha256sums): no network.
 */
@DisabledOnOs(OS.WINDOWS)
class PinnedReleaseChecksumTest {

    private static final Path REPO = Paths.get(System.getProperty("user.dir"));
    private static final String VERSION = "9.9.9";
    private static final List<String> PLATFORMS = List.of("linux_amd64", "linux_arm64", "darwin_amd64", "darwin_arm64");

    private record Result(int exitCode, String stdout, String stderr) {
    }

    private static Result run(Path project, Map<String, String> env, String script, String... args) throws Exception {
        List<String> command = new ArrayList<>(List.of("bash", project.resolve("scripts").resolve(script).toString()));
        command.addAll(List.of(args));
        ProcessBuilder builder = new ProcessBuilder(command).directory(project.toFile());
        builder.environment().putAll(env);
        Process process = builder.start();
        process.getOutputStream().close();
        String stdout = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        String stderr = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(process.waitFor(60, TimeUnit.SECONDS)).as("script terminated").isTrue();
        return new Result(process.exitValue(), stdout, stderr);
    }

    /** A temp project with copies of the real scripts and a release pin. */
    private static Path project(Path dir, String version) throws IOException {
        Path scripts = Files.createDirectories(dir.resolve("scripts"));
        for (String script : List.of("install-cyoda.sh", "sync-cyoda-contract.sh")) {
            Files.copy(REPO.resolve("scripts").resolve(script), scripts.resolve(script));
        }
        Path cyoda = Files.createDirectories(dir.resolve("src/main/resources/cyoda"));
        Files.writeString(cyoda.resolve("CYODA_VERSION"), version + "\ncommit=0123456789abcdef\n");
        return dir;
    }

    /** A release archive holding a stand-in cyoda binary that only answers --version. */
    private static Path fakeArchive(Path dir) throws Exception {
        Path content = Files.createDirectories(dir.resolve("archive-content"));
        Path binary = content.resolve("cyoda");
        Files.writeString(binary, "#!/bin/sh\necho \"cyoda " + VERSION + "\"\n");
        Files.setPosixFilePermissions(binary, PosixFilePermissions.fromString("rwxr-xr-x"));
        Path archive = dir.resolve("cyoda.tar.gz");
        Process tar = new ProcessBuilder("tar", "-czf", archive.toString(), "-C", content.toString(), "cyoda")
                .redirectErrorStream(true).start();
        tar.getInputStream().readAllBytes();
        assertThat(tar.waitFor(30, TimeUnit.SECONDS)).isTrue();
        assertThat(tar.exitValue()).isZero();
        return archive;
    }

    private static String sha256(Path file) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
    }

    /** SHA256SUMS lines for every platform, all with the given digest (the test's uname picks one). */
    private static String sums(String digest) {
        StringBuilder sb = new StringBuilder();
        for (String platform : PLATFORMS) {
            sb.append(digest).append("  cyoda_").append(VERSION).append('_').append(platform).append(".tar.gz\n");
        }
        return sb.toString();
    }

    private static void pinSums(Path project, String content) throws IOException {
        Files.writeString(project.resolve("src/main/resources/cyoda/CYODA_SHA256SUMS"), content);
    }

    @Test
    void anArchiveMatchingThePinnedChecksumIsInstalled(@TempDir Path dir) throws Exception {
        Path project = project(dir.resolve("p"), VERSION);
        Path archive = fakeArchive(dir);
        pinSums(project, sums(sha256(archive)));
        Path dest = dir.resolve("out");

        Result result = run(project, Map.of(), "install-cyoda.sh", "--archive", archive.toString(), "--dest", dest.toString());

        assertThat(result.exitCode()).as(result.stderr()).isZero();
        assertThat(dest.resolve("cyoda")).isExecutable();
        assertThat(result.stdout().trim()).isEqualTo(dest.resolve("cyoda").toString());
    }

    @Test
    void anArchiveNotMatchingThePinnedChecksumIsRefused(@TempDir Path dir) throws Exception {
        Path project = project(dir.resolve("p"), VERSION);
        Path archive = fakeArchive(dir);
        pinSums(project, sums("0".repeat(64)));
        Path dest = dir.resolve("out");

        Result result = run(project, Map.of(), "install-cyoda.sh", "--archive", archive.toString(), "--dest", dest.toString());

        assertThat(result.exitCode()).isNotZero();
        assertThat(result.stderr()).contains("checksum mismatch").contains("CYODA_SHA256SUMS");
        assertThat(dest.resolve("cyoda")).doesNotExist();
    }

    @Test
    void aReleasePinWithoutCommittedChecksumsFailsClosed(@TempDir Path dir) throws Exception {
        Path project = project(dir.resolve("p"), VERSION);
        Path archive = fakeArchive(dir);
        Path dest = dir.resolve("out");

        Result result = run(project, Map.of(), "install-cyoda.sh", "--archive", archive.toString(), "--dest", dest.toString());

        assertThat(result.exitCode()).isNotZero();
        assertThat(result.stderr()).contains("CYODA_SHA256SUMS is missing").contains("sync-cyoda-contract.sh");
        assertThat(dest.resolve("cyoda")).doesNotExist();
    }

    @Test
    void aReleasePinWithoutAChecksumForThisPlatformFailsClosed(@TempDir Path dir) throws Exception {
        Path project = project(dir.resolve("p"), VERSION);
        Path archive = fakeArchive(dir);
        pinSums(project, sha256(archive) + "  cyoda_" + VERSION + "_plan9_mips.tar.gz\n");

        Result result = run(project, Map.of(), "install-cyoda.sh", "--archive", archive.toString(),
                "--dest", dir.resolve("out").toString());

        assertThat(result.exitCode()).isNotZero();
        assertThat(result.stderr()).contains("has no checksum for");
    }

    /** A -dev pin is a source build: it neither needs nor reads CYODA_SHA256SUMS (it fails on Go here instead). */
    @Test
    void aDevPinDoesNotNeedCommittedChecksums(@TempDir Path dir) throws Exception {
        Path project = project(dir.resolve("p"), VERSION + "-dev");

        Result result = run(project, Map.of("PATH", "/nonexistent-bin-dir:/usr/bin:/bin"), "install-cyoda.sh",
                "--dest", dir.resolve("out").toString());

        assertThat(result.stderr()).doesNotContain("CYODA_SHA256SUMS");
        if (!Files.exists(Path.of("/usr/bin/go")) && !Files.exists(Path.of("/bin/go"))) {
            assertThat(result.exitCode()).isNotZero();
            assertThat(result.stderr()).contains("Go >= 1.26.7");
        }
    }

    @Test
    void archiveIsRefusedForADevPin(@TempDir Path dir) throws Exception {
        Path project = project(dir.resolve("p"), VERSION + "-dev");

        Result result = run(project, Map.of(), "install-cyoda.sh", "--archive", "whatever.tar.gz");

        assertThat(result.exitCode()).isNotZero();
        assertThat(result.stderr()).contains("--archive");
    }

    /** A fake cyoda-go checkout with just the files sync-cyoda-contract.sh copies, committed so HEAD resolves. */
    private static Path fakeCheckout(Path dir) throws Exception {
        for (String file : List.of("api/openapi.yaml", "proto/cyoda/cyoda-cloud-api.proto",
                "proto/cloudevents/cloudevents.proto", "docs/cyoda/schema/common/X.json")) {
            Path path = dir.resolve(file);
            Files.createDirectories(path.getParent());
            Files.writeString(path, "x\n");
        }
        for (List<String> git : List.of(List.of("git", "init", "-q"), List.of("git", "add", "."),
                List.of("git", "-c", "user.name=t", "-c", "user.email=t@example.com", "-c", "commit.gpgsign=false",
                        "commit", "-q", "-m", "init"))) {
            Process p = new ProcessBuilder(git).directory(dir.toFile()).redirectErrorStream(true).start();
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            assertThat(p.waitFor(30, TimeUnit.SECONDS)).isTrue();
            assertThat(p.exitValue()).as(out).isZero();
        }
        return dir;
    }

    @Test
    void syncingAReleaseRecordsItsArchiveChecksums(@TempDir Path dir) throws Exception {
        Path project = project(dir.resolve("p"), "0.0.1-dev");
        Path checkout = fakeCheckout(dir.resolve("cyoda-go"));
        Path releaseSums = dir.resolve("SHA256SUMS");
        String digest = "a".repeat(64);
        Files.writeString(releaseSums, sums(digest)
                + "b".repeat(64) + "  cyoda_" + VERSION + "_checksums.txt\n"
                + "c".repeat(64) + "  cyoda_" + VERSION + "_linux_amd64.deb\n");

        Result result = run(project, Map.of(), "sync-cyoda-contract.sh", "--from-src", checkout.toString(),
                "--version", VERSION, "--sha256sums", releaseSums.toString());

        assertThat(result.exitCode()).as(result.stderr()).isZero();
        String pinned = Files.readString(project.resolve("src/main/resources/cyoda/CYODA_SHA256SUMS"));
        assertThat(pinned).isEqualTo(sums(digest));
        assertThat(Files.readString(project.resolve("src/main/resources/cyoda/CYODA_VERSION"))).startsWith(VERSION + "\n");
    }

    @Test
    void syncingADevVersionRemovesStaleChecksums(@TempDir Path dir) throws Exception {
        Path project = project(dir.resolve("p"), VERSION);
        pinSums(project, sums("a".repeat(64)));
        Path checkout = fakeCheckout(dir.resolve("cyoda-go"));

        Result result = run(project, Map.of(), "sync-cyoda-contract.sh", "--from-src", checkout.toString(),
                "--version", "1.0.0-dev");

        assertThat(result.exitCode()).as(result.stderr()).isZero();
        assertThat(project.resolve("src/main/resources/cyoda/CYODA_SHA256SUMS")).doesNotExist();
    }

    @Test
    void syncingAReleaseWithoutItsArchiveChecksumsChangesNothing(@TempDir Path dir) throws Exception {
        Path project = project(dir.resolve("p"), "0.0.1-dev");
        Path checkout = fakeCheckout(dir.resolve("cyoda-go"));
        Path releaseSums = dir.resolve("SHA256SUMS");
        Files.writeString(releaseSums, "a".repeat(64) + "  something-else.tar.gz\n");

        Result result = run(project, Map.of(), "sync-cyoda-contract.sh", "--from-src", checkout.toString(),
                "--version", VERSION, "--sha256sums", releaseSums.toString());

        assertThat(result.exitCode()).isNotZero();
        assertThat(Files.readString(project.resolve("src/main/resources/cyoda/CYODA_VERSION"))).startsWith("0.0.1-dev");
        assertThat(project.resolve("src/main/resources/cyoda/CYODA_SHA256SUMS")).doesNotExist();
    }
}
