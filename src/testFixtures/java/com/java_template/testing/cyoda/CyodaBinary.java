package com.java_template.testing.cyoda;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Finds the cyoda binary (-Dcyoda.bin, then $CYODA_BIN, then PATH) and checks it against the pin.
 * The binary is only ever run with --version here: an unrecognised argument would start a server (cyoda-go #623).
 */
public final class CyodaBinary {

    private CyodaBinary() {
    }

    public static Path locate() {
        return locate(System.getProperty("cyoda.bin"), System.getenv("CYODA_BIN"), System.getenv("PATH"));
    }

    public static Path locate(String sysProp, String envVar, String pathEnv) {
        if (sysProp != null && !sysProp.isBlank()) {
            return requireExecutable(Path.of(sysProp), "-Dcyoda.bin");
        }
        if (envVar != null && !envVar.isBlank()) {
            return requireExecutable(Path.of(envVar), "CYODA_BIN");
        }
        if (pathEnv != null) {
            for (String dir : pathEnv.split(File.pathSeparator)) {
                Path candidate = Path.of(dir, "cyoda");
                if (Files.isExecutable(candidate)) {
                    return candidate;
                }
            }
        }
        throw new IllegalStateException("No cyoda binary found (-Dcyoda.bin, CYODA_BIN, PATH). " + CyodaVersion.INSTALL_HINT);
    }

    public static CyodaVersion version(Path binary) {
        try {
            Process p = new ProcessBuilder(binary.toString(), "--version").redirectErrorStream(true).start();
            if (!p.waitFor(10, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                throw new IllegalStateException(binary + " --version did not finish within 10 s");
            }
            return CyodaVersion.parseBinaryOutput(new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new IllegalStateException("cannot run " + binary + " --version", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted running " + binary + " --version", e);
        }
    }

    public static Path resolvePinned(Path pinFile, boolean allowMismatch, Consumer<String> warn) {
        Path binary = locate();
        CyodaVersion pin = CyodaVersion.readPin(pinFile);
        CyodaVersion actual = version(binary);
        CyodaVersion.incompatibility(pin, actual).ifPresent(problem -> {
            if (!allowMismatch) {
                throw new IllegalStateException(problem + " (binary: " + binary + "; override with -Dcyoda.allowVersionMismatch=true)");
            }
            warn.accept(problem);
        });
        return binary;
    }

    private static Path requireExecutable(Path p, String source) {
        if (!Files.isExecutable(p)) {
            throw new IllegalStateException(source + " points at " + p + ", which is not an executable file. " + CyodaVersion.INSTALL_HINT);
        }
        return p;
    }
}
