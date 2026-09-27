package com.java_template.testing.cyoda;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * One cyoda-go subprocess on free ports with memory storage and mock IAM (spec §6.1).
 * The environment is built from scratch: HOME/XDG_CONFIG_HOME point at a temp dir so the
 * user's ~/.config/cyoda/cyoda.env and any ./.env cannot change storage or auth.
 */
public final class CyodaServer implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(CyodaServer.class);
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS");

    private final Process process;
    private final Profile profile;
    private final int httpPort;
    private final int grpcPort;
    private final Path workDir;
    private final Path logFile;

    private CyodaServer(Process process, Profile profile, int httpPort, int grpcPort, Path workDir, Path logFile) {
        this.process = process;
        this.profile = profile;
        this.httpPort = httpPort;
        this.grpcPort = grpcPort;
        this.workDir = workDir;
        this.logFile = logFile;
    }

    public static CyodaServer start(Path binary, Profile profile, Path logDir) {
        try {
            Path work = Files.createTempDirectory("cyoda-" + profile.name() + "-");
            return start(binary, profile, logDir, work);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot start cyoda " + binary, e);
        }
    }

    /**
     * Package-private so tests can supply a known work dir and observe that it is gone after a
     * startup failure, without adding a public API just for that.
     */
    static CyodaServer start(Path binary, Profile profile, Path logDir, Path work) {
        try {
            int[] ports = FreePorts.allocate(3);
            Files.createDirectories(logDir);
            Path logFile = logDir.resolve(profile.name() + "-" + TS.format(LocalDateTime.now()) + ".log");
            // The child runs with workDir as its cwd, not this JVM's; a binary path given relative
            // to this JVM's cwd (e.g. -Dcyoda.bin=build/cyoda-bin/cyoda) must be made absolute first.
            ProcessBuilder pb = new ProcessBuilder(binary.toAbsolutePath().toString())
                    .directory(work.toFile())
                    .redirectErrorStream(true)
                    .redirectOutput(logFile.toFile());
            pb.environment().clear();
            pb.environment().putAll(environment(profile, ports[0], ports[1], ports[2], work, System.getenv("PATH")));
            CyodaServer server = new CyodaServer(pb.start(), profile, ports[0], ports[1], work, logFile);
            server.awaitHealthy(Duration.ofSeconds(30));
            log.info("cyoda '{}' up: http={} grpc={} log={}", profile.name(), server.apiUrl(), ports[1], logFile);
            return server;
        } catch (IOException e) {
            deleteRecursively(work);
            throw new UncheckedIOException("cannot start cyoda " + binary, e);
        } catch (RuntimeException e) {
            deleteRecursively(work);
            throw e;
        }
    }

    static Map<String, String> environment(Profile profile, int http, int grpc, int admin, Path home, String path) {
        Map<String, String> env = new LinkedHashMap<>();
        env.put("PATH", path == null ? "/usr/bin:/bin" : path);
        env.put("HOME", home.toString());
        env.put("XDG_CONFIG_HOME", home.resolve(".config").toString());
        env.put("CYODA_HTTP_PORT", Integer.toString(http));
        env.put("CYODA_GRPC_PORT", Integer.toString(grpc));
        env.put("CYODA_ADMIN_PORT", Integer.toString(admin));
        env.put("CYODA_ADMIN_BIND_ADDRESS", "127.0.0.1");
        env.put("CYODA_CONTEXT_PATH", "/api");
        env.put("CYODA_STORAGE_BACKEND", "memory");
        env.put("CYODA_IAM_MODE", "mock");
        env.put("CYODA_IAM_MOCK_KIND", "user");
        env.put("CYODA_IAM_MOCK_ROLES", "ROLE_ADMIN,ROLE_M2M");
        env.put("CYODA_KEEPALIVE_INTERVAL", "10");
        env.put("CYODA_KEEPALIVE_TIMEOUT", "30");
        env.put("CYODA_DISPATCH_WAIT_TIMEOUT", "5s");
        env.put("CYODA_SCHEDULER_SCAN_INTERVAL", "50ms");
        env.put("CYODA_SUPPRESS_BANNER", "true");
        env.put("CYODA_ERROR_RESPONSE_MODE", "verbose");
        env.put("CYODA_LOG_LEVEL", "info");
        env.putAll(profile.overrides());
        return env;
    }

    public String apiUrl() {
        return "http://127.0.0.1:" + httpPort + "/api";
    }

    public String grpcHost() {
        return "127.0.0.1";
    }

    public int grpcPort() {
        return grpcPort;
    }

    public Profile profile() {
        return profile;
    }

    public String logTail(int lines) {
        try {
            List<String> all = Files.readAllLines(logFile);
            return String.join("\n", all.subList(Math.max(0, all.size() - lines), all.size()));
        } catch (IOException e) {
            return "(cannot read " + logFile + ": " + e.getMessage() + ")";
        }
    }

    private void awaitHealthy(Duration limit) {
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).build();
        HttpRequest health = HttpRequest.newBuilder(URI.create(apiUrl() + "/health")).timeout(Duration.ofSeconds(2)).build();
        long deadline = System.nanoTime() + limit.toNanos();
        while (System.nanoTime() < deadline) {
            if (!process.isAlive()) {
                String message = "cyoda exited with code " + process.exitValue() + " during startup:\n" + logTail(50);
                close();
                throw new IllegalStateException(message);
            }
            try {
                if (http.send(health, HttpResponse.BodyHandlers.discarding()).statusCode() == 200) {
                    return;
                }
            } catch (IOException ignored) {
                // not listening yet
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
            sleep(100);
        }
        String message = "cyoda did not become healthy within " + limit.toSeconds() + " s:\n" + logTail(50);
        close();
        throw new IllegalStateException(message);
    }

    @Override
    public void close() {
        if (process.isAlive()) {
            process.destroy();
            try {
                if (!process.waitFor(5, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                }
            } catch (InterruptedException e) {
                process.destroyForcibly();
                Thread.currentThread().interrupt();
            }
        }
        deleteRecursively(workDir);
    }

    private static void deleteRecursively(Path dir) {
        try (Stream<Path> files = Files.walk(dir)) {
            files.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        } catch (IOException ignored) {
            // temp dir cleanup is best effort
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
