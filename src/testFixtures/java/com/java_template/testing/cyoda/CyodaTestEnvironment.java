package com.java_template.testing.cyoda;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * One lazily started cyoda server per profile per test JVM, shared by every test class.
 * System properties (set by the integrationTest Gradle task): cyoda.pinFile, cyoda.logDir,
 * cyoda.projectDir, cyoda.bin, cyoda.allowVersionMismatch.
 */
public final class CyodaTestEnvironment {

    private static final Logger log = LoggerFactory.getLogger(CyodaTestEnvironment.class);
    private static final Map<String, CyodaServer> SERVERS = new ConcurrentHashMap<>();

    private CyodaTestEnvironment() {
    }

    public static synchronized CyodaServer server(Profile profile) {
        return SERVERS.computeIfAbsent(profile.name(), n -> start(profile));
    }

    public static CyodaRest rest(Profile profile) {
        return new CyodaRest(server(profile).apiUrl());
    }

    public static synchronized void closeAll() {
        SERVERS.values().forEach(CyodaServer::close);
        SERVERS.clear();
    }

    private static CyodaServer start(Profile profile) {
        Path pin = Path.of(System.getProperty("cyoda.pinFile", "src/main/resources/cyoda/CYODA_VERSION"));
        Path logDir = Path.of(System.getProperty("cyoda.logDir", "build/cyoda-logs"));
        Path binary = CyodaBinary.resolvePinned(pin, Boolean.getBoolean("cyoda.allowVersionMismatch"), log::warn);
        CyodaServer server = CyodaServer.start(binary, profile, logDir);
        Runtime.getRuntime().addShutdownHook(new Thread(server::close, "cyoda-" + profile.name() + "-shutdown"));
        return server;
    }
}
