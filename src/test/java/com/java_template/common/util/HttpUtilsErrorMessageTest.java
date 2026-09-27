package com.java_template.common.util;

import com.java_template.common.auth.CyodaTokenSource;
import com.java_template.common.call.CyodaCallContext;
import com.java_template.common.config.Config;
import com.java_template.common.config.CyodaObjectMapper;
import com.java_template.common.exception.CyodaHttpException;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/** The status, error code and message of the CyodaHttpException HttpUtils throws for a failed response. */
class HttpUtilsErrorMessageTest {

    private HttpServer server;
    private final AtomicReference<String> body = new AtomicReference<>();
    private HttpUtils httpUtils;
    private String baseUrl;

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api", ex -> {
            ex.getRequestBody().readAllBytes();
            byte[] bytes = body.get().getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/problem+json");
            ex.sendResponseHeaders(409, bytes.length);
            ex.getResponseBody().write(bytes);
            ex.close();
        });
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/api";
        CyodaObjectMapper mapper = CyodaObjectMapper.standalone();
        httpUtils = new HttpUtils(new JsonUtils(mapper), mapper, new Config(), mock(CyodaTokenSource.class));
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    void cyodaErrorCodeIsReadFromTheProblemDetail() {
        body.set("{\"type\":\"about:blank\",\"title\":\"Conflict\",\"status\":409,"
                + "\"detail\":\"cannot unlock: 1 entities exist\","
                + "\"properties\":{\"errorCode\":\"MODEL_HAS_ENTITIES\",\"entityCount\":1}}");

        assertThatThrownBy(() -> httpUtils.sendPutRequest(CyodaCallContext.none(), baseUrl, "model/X/1/unlock", null).join())
                .isInstanceOf(CompletionException.class)
                .cause()
                .isInstanceOf(CyodaHttpException.class)
                .satisfies(e -> {
                    CyodaHttpException che = (CyodaHttpException) e;
                    assertThat(che.status()).isEqualTo(409);
                    assertThat(che.getErrorCode()).isEqualTo("MODEL_HAS_ENTITIES");
                    assertThat(che.getMessage()).contains("cannot unlock: 1 entities exist");
                });
    }

    @Test
    void withoutAnErrorCodeTheDetailIsTheMessage() {
        body.set("{\"status\":409,\"detail\":\"some conflict\"}");

        assertThatThrownBy(() -> httpUtils.sendGetRequest(CyodaCallContext.none(), baseUrl, "x").join())
                .cause()
                .isInstanceOf(CyodaHttpException.class)
                .satisfies(e -> {
                    CyodaHttpException che = (CyodaHttpException) e;
                    assertThat(che.status()).isEqualTo(409);
                    assertThat(che.getErrorCode()).isEqualTo("HTTP_409");
                    assertThat(che.getMessage()).contains("some conflict");
                });
    }
}
