package com.java_template.common.util;

import com.java_template.common.auth.CyodaTokenSource;
import com.java_template.common.call.CyodaCallContext;
import com.java_template.common.config.Config;
import com.java_template.common.config.CyodaObjectMapper;
import com.java_template.common.exception.CyodaCalloutEndedException;
import com.java_template.common.exception.CyodaHttpException;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

/**
 * Adapted from the task-22 brief: HttpUtils takes a {@link CyodaObjectMapper} holder (not a plain
 * ObjectMapper) and a {@link CyodaTokenSource}, per the real constructor. Otherwise this follows the
 * brief's test cases verbatim.
 */
class HttpUtilsContextTest {

    private HttpServer server;
    private final List<Map<String, String>> seen = new CopyOnWriteArrayList<>();
    private final AtomicInteger unauthorizedFirst = new AtomicInteger();
    private final CyodaTokenSource tokens = mock(CyodaTokenSource.class);
    private HttpUtils http;
    private String base;

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api", ex -> {
            seen.add(Map.of(
                    "path", ex.getRequestURI().getPath(),
                    "auth", String.valueOf(ex.getRequestHeaders().getFirst("Authorization")),
                    "tx", String.valueOf(ex.getRequestHeaders().getFirst("X-Tx-Token"))));
            boolean deny = unauthorizedFirst.getAndDecrement() > 0;
            byte[] body = (deny
                    ? "{\"status\":401,\"detail\":\"UNAUTHORIZED: token\",\"properties\":{\"errorCode\":\"UNAUTHORIZED\"}}"
                    : "{\"ok\":true}").getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(deny ? 401 : 200, body.length);
            ex.getResponseBody().write(body);
            ex.close();
        });
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort() + "/api";
        when(tokens.bearerToken()).thenReturn(Optional.of("m2m"));
        CyodaObjectMapper wireMapper = CyodaObjectMapper.standalone();
        Config config = new Config();
        config.setCyodaApiUrl(base); // the stub server is the configured Cyoda origin
        http = new HttpUtils(new JsonUtils(wireMapper), wireMapper, config, tokens);
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    void entityPathsCarryTheTxTokenAndModelPathsNever() {
        CyodaCallContext joined = CyodaCallContext.m2m().withTxToken("tx-9");

        http.sendGetRequest(joined, base, "entity/abc").join();
        http.sendPostRequest(joined, base, "model/m/1/workflow/import", Map.of()).join();

        assertThat(seen.get(0)).containsEntry("auth", "Bearer m2m").containsEntry("tx", "tx-9");
        assertThat(seen.get(1)).containsEntry("auth", "Bearer m2m").containsEntry("tx", "null");
    }

    @Test
    void forwardAndNone() {
        http.sendGetRequest(CyodaCallContext.forward("user"), base, "entity/x").join();
        http.sendGetRequest(CyodaCallContext.none(), base, "entity/x").join();

        assertThat(seen.get(0)).containsEntry("auth", "Bearer user");
        assertThat(seen.get(1)).containsEntry("auth", "null");
    }

    @Test
    void m2mOutsideAScopeRefreshesAndRetriesOnce() {
        unauthorizedFirst.set(1);

        http.sendGetRequest(CyodaCallContext.m2m(), base, "entity/x").join();

        assertThat(seen).hasSize(2);
        verify(tokens).invalidate("m2m");
        verify(tokens, never()).invalidate();
    }

    @Test
    void a401InsideAScopeEndsTheCalloutWithoutRetry() {
        unauthorizedFirst.set(1);

        assertThatThrownBy(() -> http.sendGetRequest(CyodaCallContext.m2m().withTxToken("t"), base, "entity/x").join())
                .hasCauseInstanceOf(CyodaCalloutEndedException.class);
        assertThat(seen).hasSize(1);
        verify(tokens).invalidate("m2m");
        verify(tokens, never()).invalidate();
    }

    @Test
    void aForwardedTokenIsNeverRetried() {
        unauthorizedFirst.set(1);

        assertThatThrownBy(() -> http.sendGetRequest(CyodaCallContext.forward("u"), base, "entity/x").join())
                .hasCauseInstanceOf(CyodaHttpException.class);
        assertThat(seen).hasSize(1);
        verify(tokens, never()).invalidate();
        verify(tokens, never()).invalidate(anyString());
    }

    @Test
    void aBlankForwardedTokenIsRefusedBeforeAnyIo() {
        assertThatThrownBy(() -> http.sendGetRequest(CyodaCallContext.forward(" "), base, "entity/x"))
                .isInstanceOf(com.java_template.common.exception.CyodaCredentialException.class);
        assertThatThrownBy(() -> http.sendGetRequest(CyodaCallContext.forward(""), base, "entity/x"))
                .isInstanceOf(com.java_template.common.exception.CyodaCredentialException.class);
        assertThat(seen).isEmpty();
    }
}
