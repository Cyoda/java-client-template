package com.java_template.common.util;

import com.java_template.common.auth.CyodaTokenSource;
import com.java_template.common.call.CyodaCallContext;
import com.java_template.common.config.Config;
import com.java_template.common.config.CyodaObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * HttpUtils attaches a Cyoda credential (M2M or a forwarded user token) only to the configured
 * {@code cyoda-api-url} origin (scheme, host and port). A request with a credential to any other origin is
 * refused before any network I/O, and before a token is even fetched; a request with no credential may go
 * anywhere.
 */
class HttpUtilsOriginTest {

    private HttpServer server;
    private final List<String> seen = new CopyOnWriteArrayList<>();
    private final CyodaTokenSource tokens = mock(CyodaTokenSource.class);
    private String local;

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api", ex -> {
            seen.add(String.valueOf(ex.getRequestHeaders().getFirst("Authorization")));
            byte[] body = "{\"ok\":true}".getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(200, body.length);
            ex.getResponseBody().write(body);
            ex.close();
        });
        server.start();
        local = "http://127.0.0.1:" + server.getAddress().getPort() + "/api";
        when(tokens.bearerToken()).thenReturn(Optional.of("m2m"));
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private HttpUtils httpFor(String cyodaApiUrl) {
        CyodaObjectMapper wireMapper = CyodaObjectMapper.standalone();
        Config config = new Config();
        config.setCyodaApiUrl(cyodaApiUrl);
        return new HttpUtils(new JsonUtils(wireMapper), wireMapper, config, tokens);
    }

    @Test
    void theCyodaOriginIsAllowedWithEveryCredential() {
        HttpUtils http = httpFor(local);

        http.sendGetRequest(CyodaCallContext.m2m(), local, "entity/x").join();
        http.sendGetRequest(CyodaCallContext.forward("user"), local, "entity/x", Map.of("a", "b")).join();
        http.sendGetRequest(CyodaCallContext.none(), local, "entity/x").join();

        assertThat(seen).containsExactly("Bearer m2m", "Bearer user", "null");
    }

    @Test
    void theCyodaOriginIsMatchedWhateverTheTrailingPathOrSlash() {
        HttpUtils http = httpFor(local + "/");

        http.sendPostRequest(CyodaCallContext.m2m(), local, "entity/x", Map.of()).join();

        assertThat(seen).containsExactly("Bearer m2m");
    }

    @Test
    void aForeignOriginWithAnM2mCredentialIsRefusedBeforeAnyIo() {
        HttpUtils http = httpFor("http://cyoda.example.test/api");

        assertThatThrownBy(() -> http.sendGetRequest(CyodaCallContext.m2m(), local, "entity/x"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("127.0.0.1")
                .hasMessageNotContaining("m2m");
        assertThat(seen).isEmpty();
        verify(tokens, never()).bearerToken();
    }

    @Test
    void aForeignOriginWithAForwardedTokenIsRefusedBeforeAnyIo() {
        HttpUtils http = httpFor("http://cyoda.example.test/api");

        assertThatThrownBy(() -> http.sendPostRequest(CyodaCallContext.forward("user-jwt"), local, "entity/x", Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("127.0.0.1")
                .hasMessageNotContaining("user-jwt");
        assertThatThrownBy(() -> http.sendDeleteRequest(CyodaCallContext.forward("user-jwt"), local, "entity/x"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(seen).isEmpty();
    }

    @Test
    void aDifferentPortOrSchemeIsAForeignOrigin() {
        int port = server.getAddress().getPort();
        HttpUtils otherPort = httpFor("http://127.0.0.1:" + (port == 65535 ? port - 1 : port + 1) + "/api");
        HttpUtils otherScheme = httpFor("https://127.0.0.1:" + port + "/api");

        assertThatThrownBy(() -> otherPort.sendGetRequest(CyodaCallContext.m2m(), local, "entity/x"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> otherScheme.sendGetRequest(CyodaCallContext.m2m(), local, "entity/x"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(seen).isEmpty();
    }

    @Test
    void aForeignOriginWithNoCredentialIsAllowed() {
        HttpUtils http = httpFor("http://cyoda.example.test/api");

        http.sendGetRequest(CyodaCallContext.none(), local, "entity/x").join();

        assertThat(seen).containsExactly("null");
    }

    @Test
    void aJoinedCallWithNoCredentialIsStillRefusedAtAForeignOrigin() {
        HttpUtils http = httpFor("http://cyoda.example.test/api");

        assertThatThrownBy(() -> http.sendGetRequest(CyodaCallContext.none().withTxToken("tx-secret"), local, "entity/x"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("127.0.0.1")
                .hasMessageNotContaining("tx-secret");
        assertThat(seen).isEmpty();
    }

    @Test
    void defaultPortsAndCaseDoNotMakeAnOriginForeign() {
        assertThat(HttpUtils.sameOrigin(URI.create("https://Cyoda.Example/api"), URI.create("https://cyoda.example:443/x"))).isTrue();
        assertThat(HttpUtils.sameOrigin(URI.create("HTTP://cyoda.example/api"), URI.create("http://cyoda.example:80/api"))).isTrue();
        assertThat(HttpUtils.sameOrigin(URI.create("https://cyoda.example/api"), URI.create("http://cyoda.example/api"))).isFalse();
        assertThat(HttpUtils.sameOrigin(URI.create("https://cyoda.example/api"), URI.create("https://cyoda.example.evil/api"))).isFalse();
        assertThat(HttpUtils.sameOrigin(URI.create("https://cyoda.example/api"), URI.create("https://cyoda.example:8443/api"))).isFalse();
    }
}
