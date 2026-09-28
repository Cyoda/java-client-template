package com.cyoda.build;

import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.TaskAction;
import org.gradle.api.tasks.UntrackedTask;
import org.gradle.process.ExecOperations;

import javax.inject.Inject;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

/**
 * ABOUTME: Installs the pinned cyoda into .cyoda/bin with scripts/install-cyoda.sh, unless an explicit binary is
 * configured or the one already there matches the pin (spec §7.4). The decision is {@link CyodaInstallDecision}'s,
 * made from the binary's {@code --version}, not from Gradle's file tracking, so a binary copied or built there by
 * hand counts too. When nothing is installed the task reports UP-TO-DATE.
 */
@UntrackedTask(because = "whether to install is decided from the binary's --version against the pin")
public abstract class InstallCyodaTask extends DefaultTask {

    /** The project root, which holds scripts/install-cyoda.sh and .cyoda/bin (the script's default --dest). */
    @Internal
    public abstract DirectoryProperty getProjectRoot();

    @Internal
    public abstract RegularFileProperty getPinFile();

    /** The build's {@code -Dcyoda.bin}, when set. */
    @Internal
    public abstract Property<String> getExplicitBinaryProperty();

    /** {@code $CYODA_BIN}, when set. */
    @Internal
    public abstract Property<String> getExplicitBinaryEnv();

    @Inject
    protected abstract ExecOperations getExec();

    @TaskAction
    public void install() throws IOException {
        Path root = getProjectRoot().get().getAsFile().toPath();
        Path installed = root.resolve(".cyoda/bin/cyoda");
        String pin = Files.readString(getPinFile().get().getAsFile().toPath());
        boolean windows = System.getProperty("os.name", "").startsWith("Windows");

        CyodaInstallDecision.Result decision = CyodaInstallDecision.decide(
                getExplicitBinaryProperty().getOrNull(), getExplicitBinaryEnv().getOrNull(),
                installed, pin, windows, InstallCyodaTask::versionOf);
        switch (decision.action()) {
            case USE_EXPLICIT, UP_TO_DATE -> {
                getLogger().info("installCyoda: nothing to install: {}", decision.reason());
                setDidWork(false);
            }
            case CANNOT_INSTALL -> throw new GradleException("installCyoda: " + decision.reason());
            case INSTALL -> {
                getLogger().lifecycle("installCyoda: installing cyoda {} into {} ({})",
                        CyodaInstallDecision.pinSummary(pin), installed.getParent(), decision.reason());
                try {
                    getExec().exec(spec -> {
                        spec.workingDir(root.toFile());
                        spec.commandLine(root.resolve("scripts/install-cyoda.sh").toString());
                    });
                } catch (RuntimeException e) {
                    throw new GradleException("installCyoda: scripts/install-cyoda.sh failed; its message is above. "
                            + "To use a cyoda binary you installed yourself, pass -Dcyoda.bin=<path> or set "
                            + "CYODA_BIN; to build without the integration tests, add -x integrationTest.", e);
                }
            }
        }
    }

    /** Runs {@code <binary> --version} only: any other argument would start a server (cyoda-go #623). */
    private static String versionOf(Path binary) {
        try {
            Process p = new ProcessBuilder(binary.toString(), "--version").redirectErrorStream(true).start();
            if (!p.waitFor(10, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                throw new IllegalStateException("--version did not finish within 10 s");
            }
            return new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted", e);
        }
    }
}
