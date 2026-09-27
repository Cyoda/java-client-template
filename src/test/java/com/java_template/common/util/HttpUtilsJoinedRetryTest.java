package com.java_template.common.util;

import com.java_template.common.auth.CyodaTokenSource;
import com.java_template.common.call.CyodaCallContext;
import com.java_template.common.config.Config;
import com.java_template.common.config.CyodaObjectMapper;
import com.java_template.common.exception.CyodaRetryableException;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * REST retries TOO_MANY_JOINED_REQUESTS, which cyoda-go's txjoin middleware answers before it reads the request
 * body or runs the handler (so nothing was applied), with the same bounded backoff as gRPC: at most 3 retries.
 * It never retries TRANSACTION_NODE_UNAVAILABLE: on REST the reverse proxy may answer it after the owning node
 * applied the write.
 */
class HttpUtilsJoinedRetryTest {

    private HttpServer server;
    private final AtomicInteger requests = new AtomicInteger();
    private volatile int refusals;
    private volatile String refusalCode = "TOO_MANY_JOINED_REQUESTS";
    private HttpUtils http;
    private String base;

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api", ex -> {
            ex.getRequestBody().readAllBytes();
            boolean refuse = requests.incrementAndGet() <= refusals;
            byte[] body = (refuse
                    ? "{\"status\":503,\"detail\":\"refused\",\"properties\":{\"errorCode\":\"" + refusalCode + "\",\"retryable\":true}}"
                    : "{\"ok\":true}").getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(refuse ? 503 : 200, body.length);
            ex.getResponseBody().write(body);
            ex.close();
        });
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort() + "/api";
        CyodaTokenSource tokens = mock(CyodaTokenSource.class);
        when(tokens.bearerToken()).thenReturn(Optional.of("m2m"));
        CyodaObjectMapper wireMapper = CyodaObjectMapper.standalone();
        Config config = new Config();
        config.setCyodaApiUrl(base);
        http = new HttpUtils(new JsonUtils(wireMapper), wireMapper, config, tokens);
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private static CyodaCallContext joined() {
        return CyodaCallContext.m2m().withTxToken("tx-1");
    }

    @Test
    void aQueueFullRefusalIsRetriedWithBackoffUntilItIsAdmitted() {
        refusals = 3;

        assertThat(http.sendPostRequest(joined(), base, "entity/JSON/thing/1", Map.of("a", 1)).join().path("json").path("ok").asBoolean())
                .isTrue();
        assertThat(requests).hasValue(4);
    }

    @Test
    void aQueueFullRefusalIsRetriedAtMostThreeTimes() {
        refusals = 100;

        assertThatThrownBy(() -> Futures.joinUnwrapped(http.sendPostRequest(joined(), base, "entity/JSON/thing/1", Map.of())))
                .isInstanceOf(CyodaRetryableException.class)
                .satisfies(e -> assertThat(((CyodaRetryableException) e).getErrorCode()).isEqualTo("TOO_MANY_JOINED_REQUESTS"));
        assertThat(requests).hasValue(4);
    }

    @Test
    void aTransactionNodeUnavailableIsNeverRetriedOverRest() {
        refusals = 1;
        refusalCode = "TRANSACTION_NODE_UNAVAILABLE";

        assertThatThrownBy(() -> Futures.joinUnwrapped(http.sendPostRequest(joined(), base, "entity/JSON/thing/1", Map.of())))
                .isInstanceOf(CyodaRetryableException.class)
                .satisfies(e -> assertThat(((CyodaRetryableException) e).getErrorCode()).isEqualTo("TRANSACTION_NODE_UNAVAILABLE"));
        assertThat(requests).hasValue(1);
    }
}
