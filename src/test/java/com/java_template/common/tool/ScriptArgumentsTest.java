package com.java_template.common.tool;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
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
    void syncCyodaContractTrailingSha256sumsFailsWithUsage() throws Exception {
        Result result = run("sync-cyoda-contract.sh", "--sha256sums");
        assertThat(result.exitCode()).isNotZero();
        assertThat(result.stderr()).containsIgnoringCase("usage");
    }

    @Test
    void installCyodaTrailingArchiveFailsNamingTheFlag() throws Exception {
        Result result = run("install-cyoda.sh", "--archive");
        assertThat(result.exitCode()).isNotZero();
        assertThat(result.stderr()).contains("--archive");
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

    /**
     * Pins the two pure helpers that give the Go-version hint its content: reading the required
     * version out of a checkout's go.mod, and comparing two dot-separated versions. Extracted
     * verbatim from the real script (not reimplemented) so a future edit to either function is
     * exercised here too. No network, no real install.
     */
    private String requiredGoVersion(Path checkout) throws IOException, InterruptedException {
        return callHelper("required_go_version", checkout.toString());
    }

    private boolean versionLt(String a, String b) throws IOException, InterruptedException {
        return callHelperResult("version_lt", a, b).exitCode() == 0;
    }

    private record HelperResult(int exitCode, String stdout, String stderr) {
    }

    private static final String HELPER_FUNCTIONS =
            "/^required_go_version()/,/^}/p;/^version_lt()/,/^}/p;"
                    + "/^numeric_prefix()/,/^}/p;/^warn_if_go_too_old()/,/^}/p";

    private HelperResult callHelperResult(String function, String... args) throws IOException, InterruptedException {
        return callHelperResult(Map.of(), function, args);
    }

    private HelperResult callHelperResult(Map<String, String> env, String function, String... args)
            throws IOException, InterruptedException {
        Path script = ROOT.resolve("scripts").resolve("install-cyoda.sh");
        // A stub for info(): warn_if_go_too_old calls it, but it's a one-line function ("info() { ...; }"),
        // and the sed range extraction below (built for the multi-line functions under test, each closed by
        // its own "}" line) would either miss it or, worse, over-match past it looking for a "^}" line.
        String cmd = "info() { :; }; set -e; eval \"$(sed -n '" + HELPER_FUNCTIONS + "' '"
                + script + "')\"; " + function + " \"$@\"";
        String[] command = new String[args.length + 4];
        command[0] = "bash";
        command[1] = "-c";
        command[2] = cmd;
        command[3] = "install-cyoda-test";
        System.arraycopy(args, 0, command, 4, args.length);
        ProcessBuilder builder = new ProcessBuilder(command).directory(ROOT.toFile());
        builder.environment().putAll(env);
        Process process = builder.start();
        process.getOutputStream().close();
        String stdout = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        String stderr = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
        boolean finished = process.waitFor(10, TimeUnit.SECONDS);
        assertThat(finished).as("helper call terminated").isTrue();
        return new HelperResult(process.exitValue(), stdout.trim(), stderr);
    }

    private String callHelper(String function, String... args) throws IOException, InterruptedException {
        return callHelperResult(function, args).stdout();
    }

    /** A fake `go` on PATH so `go env GOVERSION` reports a chosen string without needing a real Go install. */
    private Path fakeGoReporting(Path binDir, String govVersionOutput) throws IOException {
        Path go = binDir.resolve("go");
        Files.writeString(go, "#!/usr/bin/env bash\necho \"" + govVersionOutput + "\"\n");
        assertThat(go.toFile().setExecutable(true)).isTrue();
        return binDir;
    }

    @Test
    void requiredGoVersionReadsTheGoDirective(@TempDir Path checkout) throws Exception {
        Files.writeString(checkout.resolve("go.mod"), "module example.com/foo\n\ngo 1.26.7\n");
        assertThat(requiredGoVersion(checkout)).isEqualTo("1.26.7");
    }

    /**
     * The `go` directive is the real minimum the module needs; `toolchain` only names the version
     * `go build` will fetch and use, which can overstate the requirement. Prefer `go`.
     */
    @Test
    void requiredGoVersionPrefersTheGoDirectiveOverToolchain(@TempDir Path checkout) throws Exception {
        Files.writeString(checkout.resolve("go.mod"), "module example.com/foo\n\ngo 1.24\n\ntoolchain go1.26.7\n");
        assertThat(requiredGoVersion(checkout)).isEqualTo("1.24");
    }

    @Test
    void requiredGoVersionFallsBackToToolchainWithoutAGoDirective(@TempDir Path checkout) throws Exception {
        Files.writeString(checkout.resolve("go.mod"), "module example.com/foo\n\ntoolchain go1.26.7\n");
        assertThat(requiredGoVersion(checkout)).isEqualTo("1.26.7");
    }

    @Test
    void requiredGoVersionIsBlankWithoutAGoDirective(@TempDir Path checkout) throws Exception {
        Files.writeString(checkout.resolve("go.mod"), "module example.com/foo\n");
        assertThat(requiredGoVersion(checkout)).isEmpty();
    }

    @Test
    void requiredGoVersionIsBlankWithoutAGoMod(@TempDir Path checkout) throws Exception {
        assertThat(requiredGoVersion(checkout)).isEmpty();
    }

    @Test
    void versionLtComparesDotSeparatedVersions() throws Exception {
        assertThat(versionLt("1.26.1", "1.26.7")).isTrue();
        assertThat(versionLt("1.26.7", "1.26.1")).isFalse();
        assertThat(versionLt("1.26.7", "1.26.7")).isFalse();
        assertThat(versionLt("1.20", "1.26.7")).isTrue();
    }

    @Test
    void numericPrefixStripsAnRcOrBetaSuffix() throws Exception {
        assertThat(callHelper("numeric_prefix", "1.26rc1")).isEqualTo("1.26");
        assertThat(callHelper("numeric_prefix", "1.26.1beta2")).isEqualTo("1.26.1");
        assertThat(callHelper("numeric_prefix", "1.26.7")).isEqualTo("1.26.7");
    }

    /**
     * `go env GOVERSION` can report a pre-release string like "go1.26rc1" for a locally installed
     * pre-release toolchain. Without stripping the suffix first, version_lt's numeric comparisons
     * (`-eq`/`-lt` on "26rc1") fail with a noisy "integer expression expected" and, because the
     * failing `[` is not guarded inside an `if`/`||`, would abort the whole call under `set -e`.
     */
    @Test
    void warnIfGoTooOldTreatsAnRcVersionAsPlainAndStaysQuiet(@TempDir Path checkout, @TempDir Path bin)
            throws Exception {
        Files.writeString(checkout.resolve("go.mod"), "module example.com/foo\n\ngo 1.26.7\n");
        fakeGoReporting(bin, "go1.26rc1");

        HelperResult result = callHelperResult(Map.of("PATH", bin + File.pathSeparator + "/usr/bin:/bin:/usr/sbin:/sbin"), "warn_if_go_too_old",
                checkout.toString());

        assertThat(result.exitCode()).isZero();
        assertThat(result.stderr()).doesNotContainIgnoringCase("integer expression");
    }

    /**
     * `go env GOVERSION` failing outright (not just missing) must not kill the script silently
     * under `set -e`: the bare (non-`local`) assignment previously let its exit code propagate.
     */
    @Test
    void warnIfGoTooOldSurvivesGoEnvFailingOutright(@TempDir Path checkout, @TempDir Path bin) throws Exception {
        Files.writeString(checkout.resolve("go.mod"), "module example.com/foo\n\ngo 1.26.7\n");
        Path go = bin.resolve("go");
        Files.writeString(go, "#!/usr/bin/env bash\nexit 1\n");
        assertThat(go.toFile().setExecutable(true)).isTrue();

        HelperResult result = callHelperResult(Map.of("PATH", bin + File.pathSeparator + "/usr/bin:/bin:/usr/sbin:/sbin"), "warn_if_go_too_old",
                checkout.toString());

        assertThat(result.exitCode()).isZero();
    }

    @Test
    void warnIfGoTooOldSurvivesNoGoOnPathAtAll(@TempDir Path checkout) throws Exception {
        assumeFalse(Files.exists(Path.of("/usr/bin/go")) || Files.exists(Path.of("/bin/go")), "no go on the bare PATH");
        Files.writeString(checkout.resolve("go.mod"), "module example.com/foo\n\ngo 1.26.7\n");

        HelperResult result = callHelperResult(Map.of("PATH", "/usr/bin:/bin"), "warn_if_go_too_old",
                checkout.toString());

        assertThat(result.exitCode()).isZero();
    }
}
