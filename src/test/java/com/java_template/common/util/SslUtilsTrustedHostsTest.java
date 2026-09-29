package com.java_template.common.util;

import com.java_template.common.config.Config;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLHandshakeException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * ABOUTME: app.config.ssl-trusted-hosts relaxes certificate checks for the listed hosts only. A self-signed HTTPS
 * server on loopback (certificate for "localhost" and 127.0.0.1, made with the JDK's keytool) stands in for a
 * trusted Cyoda host; reaching it under an unlisted name must still fail certificate validation.
 */
class SslUtilsTrustedHostsTest {

    private static final char[] PASSWORD = "changeit".toCharArray();

    @TempDir
    static Path dir;

    private static HttpsServer server;
    private static int port;

    @BeforeAll
    static void startSelfSignedServer() throws Exception {
        Path keystore = dir.resolve("self-signed.p12");
        String keytool = Path.of(System.getProperty("java.home"), "bin", "keytool").toString();
        Process process = new ProcessBuilder(keytool, "-genkeypair", "-alias", "test", "-keyalg", "RSA",
                "-keysize", "2048", "-dname", "CN=localhost", "-ext", "SAN=dns:localhost,ip:127.0.0.1",
                "-validity", "2", "-storetype", "PKCS12", "-keystore", keystore.toString(),
                "-storepass", new String(PASSWORD), "-keypass", new String(PASSWORD))
                .redirectErrorStream(true).start();
        String keytoolOutput = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(process.waitFor(60, TimeUnit.SECONDS)).isTrue();
        assertThat(process.exitValue()).as(keytoolOutput).isZero();

        KeyStore ks = KeyStore.getInstance("PKCS12");
        try (InputStream in = Files.newInputStream(keystore)) {
            ks.load(in, PASSWORD);
        }
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(ks, PASSWORD);
        SSLContext serverContext = SSLContext.getInstance("TLS");
        serverContext.init(kmf.getKeyManagers(), null, null);

        server = HttpsServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setHttpsConfigurator(new HttpsConfigurator(serverContext));
        server.createContext("/", ex -> {
            byte[] body = "ok".getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, body.length);
            ex.getResponseBody().write(body);
            ex.close();
        });
        server.start();
        port = server.getAddress().getPort();
    }

    @AfterAll
    static void stop() {
        server.stop(0);
    }

    private static Config config(String trustedHosts, boolean trustAll) {
        Config config = new Config();
        config.setSslTrustedHosts(trustedHosts);
        config.setSslTrustAll(trustAll);
        return config;
    }

    private static String get(Config config, String host) throws Exception {
        HttpClient client = SslUtils.createHttpClient(config);
        HttpRequest request = HttpRequest.newBuilder(URI.create("https://" + host + ":" + port + "/")).GET().build();
        return client.send(request, HttpResponse.BodyHandlers.ofString()).body();
    }

    @Test
    void aListedHostIsTrusted() throws Exception {
        assertThat(get(config("localhost", false), "localhost")).isEqualTo("ok");
    }

    @Test
    void aListedHostWithAPortIsTrusted() throws Exception {
        assertThat(get(config("localhost:" + port, false), "localhost")).isEqualTo("ok");
    }

    @Test
    void anUnlistedHostIsStillValidated() {
        // Some other host is listed: that must not relax validation for this one.
        assertThatThrownBy(() -> get(config("cyoda.example.com", false), "localhost"))
                .hasCauseInstanceOf(SSLHandshakeException.class);
    }

    @Test
    void theSameServerUnderAnUnlistedNameIsStillValidated() {
        assertThatThrownBy(() -> get(config("localhost", false), "127.0.0.1"))
                .hasCauseInstanceOf(SSLHandshakeException.class);
    }

    @Test
    void withNoTrustedHostsTheSelfSignedCertificateIsRejected() {
        assertThatThrownBy(() -> get(config("", false), "localhost"))
                .hasCauseInstanceOf(SSLHandshakeException.class);
    }

    @Test
    void sslTrustAllStillTrustsEveryHost() throws Exception {
        assertThat(get(config("", true), "localhost")).isEqualTo("ok");
        assertThat(get(config("", true), "127.0.0.1")).isEqualTo("ok");
    }

    @Test
    void theHostDecisionMatchesListedHostsWithOrWithoutPort() {
        List<String> trusted = List.of("a.example.com", "b.example.com:8443");
        assertThat(SslUtils.isListedHost("a.example.com", trusted)).isTrue();
        assertThat(SslUtils.isListedHost("B.EXAMPLE.COM", trusted)).isTrue();
        assertThat(SslUtils.isListedHost("c.example.com", trusted)).isFalse();
        assertThat(SslUtils.isListedHost("a.example.com.evil.com", trusted)).isFalse();
        assertThat(SslUtils.isListedHost(null, trusted)).isFalse();
    }
}
