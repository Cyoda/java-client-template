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
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * ABOUTME: Pins that scripts/sync-cyoda-contract.sh and scripts/install-cyoda.sh fail loudly, not
 * silently, when a value-taking flag is given with no value. Under `set -e`, a trailing flag whose
 * value is read with `shift 2` used to let `shift` fail with no message at all (T1, T11). Also that
 * install-cyoda.sh names Go when a source build has none. None of these cases reach the network.
 */
@DisabledOnOs(OS.WINDOWS)
class ScriptArgumentsTest {

    private static final Path ROOT = Paths.get(System.getProperty("user.dir"));

    private record Result(int exitCode, String stderr) {
    }

    private Result run(String script, String... args) throws IOException, InterruptedException {
        return run(Map.of(), script, args);
    }

    private Result run(Map<String, String> env, String script, String... args) throws IOException, InterruptedException {
        String[] command = new String[args.length + 2];
        command[0] = "bash";
        command[1] = ROOT.resolve("scripts").resolve(script).toString();
        System.arraycopy(args, 0, command, 2, args.length);
        ProcessBuilder builder = new ProcessBuilder(command)
                .directory(ROOT.toFile())
                .redirectErrorStream(false);
        builder.environment().putAll(env);
        Process process = builder.start();
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

    /**
     * The build's installCyoda task runs this script with no arguments; for the -dev pin that is a source build.
     * Without Go it must say so, and name the way around it, before it reaches the network.
     */
    @Test
    void installCyodaWithoutGoSaysGoIsNeededAndHowToSkipIt(@TempDir Path dest) throws Exception {
        assumeTrue(Files.readString(ROOT.resolve("src/main/resources/cyoda/CYODA_VERSION")).lines().findFirst()
                .orElseThrow().endsWith("-dev"), "the pin is a -dev version");
        assumeFalse(Files.exists(Path.of("/usr/bin/go")) || Files.exists(Path.of("/bin/go")), "no go on the bare PATH");

        Result result = run(Map.of("PATH", "/usr/bin:/bin"), "install-cyoda.sh", "--dest", dest.toString());

        assertThat(result.exitCode()).isNotZero();
        assertThat(result.stderr()).contains("Go >= 1.26.7").contains("-dev").contains("CYODA_BIN");
    }
}
