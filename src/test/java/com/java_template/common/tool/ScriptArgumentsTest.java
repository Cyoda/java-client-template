package com.java_template.common.tool;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ABOUTME: Pins that scripts/sync-cyoda-contract.sh and scripts/install-cyoda.sh fail loudly, not
 * silently, when a value-taking flag is given with no value. Under `set -e`, a trailing flag whose
 * value is read with `shift 2` used to let `shift` fail with no message at all (T1, T11). None of
 * these cases reach the network.
 */
class ScriptArgumentsTest {

    private static final Path ROOT = Paths.get(System.getProperty("user.dir"));

    private record Result(int exitCode, String stderr) {
    }

    private Result run(String script, String... args) throws IOException, InterruptedException {
        String[] command = new String[args.length + 2];
        command[0] = "bash";
        command[1] = ROOT.resolve("scripts").resolve(script).toString();
        System.arraycopy(args, 0, command, 2, args.length);
        Process process = new ProcessBuilder(command)
                .directory(ROOT.toFile())
                .redirectErrorStream(false)
                .start();
        process.getOutputStream().close();
        String stderr = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
        process.getInputStream().readAllBytes();
        boolean finished = process.waitFor(30, TimeUnit.SECONDS);
        assertThat(finished).as("script terminated").isTrue();
        return new Result(process.exitValue(), stderr);
    }

    @Test
    void syncCyodaContractTrailingFromSrcFailsWithUsage() throws Exception {
        Result result = run("sync-cyoda-contract.sh", "--from-src");
        assertThat(result.exitCode()).isNotZero();
        assertThat(result.stderr()).containsIgnoringCase("usage");
    }

    @Test
    void syncCyodaContractTrailingVersionFailsWithUsage() throws Exception {
        Result result = run("sync-cyoda-contract.sh", "--version");
        assertThat(result.exitCode()).isNotZero();
        assertThat(result.stderr()).containsIgnoringCase("usage");
    }

    @Test
    void installCyodaTrailingSrcDirFailsNamingTheFlag() throws Exception {
        Result result = run("install-cyoda.sh", "--src-dir");
        assertThat(result.exitCode()).isNotZero();
        assertThat(result.stderr()).contains("--src-dir");
    }

    @Test
    void installCyodaTrailingDestFailsNamingTheFlag() throws Exception {
        Result result = run("install-cyoda.sh", "--dest");
        assertThat(result.exitCode()).isNotZero();
        assertThat(result.stderr()).contains("--dest");
    }
}
