package com.java_template.testing.cyoda;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** The cyoda-go version pinned in CYODA_VERSION, or reported by `cyoda --version` (spec §3.4). */
public record CyodaVersion(String version, String commit) {

    public static final String INSTALL_HINT = "Install the pinned cyoda with: scripts/install-cyoda.sh "
            + "(or scripts/install-cyoda.sh --src-dir <cyoda-go checkout>), then export CYODA_BIN=<printed path> "
            + "or pass -Dcyoda.bin=<path>.";

    private static final Pattern BINARY_LINE = Pattern.compile("cyoda version (\\S+) \\(commit (\\S+), built [^)]*\\)");

    public static CyodaVersion readPin(Path pinFile) {
        try {
            List<String> lines = Files.readAllLines(pinFile).stream().map(String::trim).filter(l -> !l.isEmpty()).toList();
            if (lines.size() != 2 || !lines.get(1).startsWith("commit=")) {
                throw new IllegalStateException(pinFile + " must contain two lines: <version> and commit=<sha>");
            }
            return new CyodaVersion(lines.get(0), lines.get(1).substring("commit=".length()));
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read cyoda pin " + pinFile, e);
        }
    }

    public static CyodaVersion parseBinaryOutput(String output) {
        Matcher m = BINARY_LINE.matcher(output);
        if (!m.find()) {
            throw new IllegalStateException("unrecognised `cyoda --version` output: " + output.strip());
        }
        return new CyodaVersion(m.group(1), m.group(2));
    }

    public boolean isDevPin() {
        return version.endsWith("-dev");
    }

    /** Why {@code binary} does not satisfy {@code pin}, or empty when it does. */
    public static Optional<String> incompatibility(CyodaVersion pin, CyodaVersion binary) {
        if ("dev".equals(binary.version) && "unknown".equals(binary.commit)) {
            return Optional.of("cyoda binary reports 'dev (commit unknown)': it was built without version stamping. " + INSTALL_HINT);
        }
        if (pin.isDevPin()) {
            return sameCommit(pin.commit, binary.commit) ? Optional.empty()
                    : Optional.of("cyoda binary is at commit " + binary.commit + " but the pin is " + pin.version
                    + " at commit " + pin.commit + ". " + INSTALL_HINT);
        }
        String reported = binary.version.startsWith("v") ? binary.version.substring(1) : binary.version;
        return reported.equals(pin.version) ? Optional.empty()
                : Optional.of("cyoda binary is version " + binary.version + " but the pin is " + pin.version + ". " + INSTALL_HINT);
    }

    static boolean sameCommit(String a, String b) {
        int n = Math.min(a.length(), b.length());
        return n >= 7 && a.substring(0, n).equalsIgnoreCase(b.substring(0, n));
    }
}
