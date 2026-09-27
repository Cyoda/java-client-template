package com.java_template.common.auth;

import com.java_template.common.config.Config;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A real {@link Authentication} against a stub {@code /oauth/token} endpoint that issues a different token on
 * every request ({@code tok-1}, {@code tok-2}, …). Invalidating a rejected token must force a real new fetch
 * (Spring's authorized-client service would otherwise hand back the stored, unexpired token), and a stale
 * rejection must never discard a token another thread has fetched since.
 */
class AuthenticationTest {

    private HttpServer server;
    private final AtomicInteger issued = new AtomicInteger();
    private final List<String> requestBodies = new ArrayList<>();
    private Authentication authentication;

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/oauth/token", ex -> {
            String requestBody = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            synchronized (requestBodies) {
                requestBodies.add(requestBody);
            }
            String token = "tok-" + issued.incrementAndGet();
            byte[] body = ("{\"access_token\":\"" + token + "\",\"token_type\":\"Bearer\",\"expires_in\":3600}")
                    .getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(200, body.length);
            ex.getResponseBody().write(body);
            ex.close();
        });
        server.start();
        Config config = new Config();
        config.setCyodaApiUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/api");
        config.setCyodaClientId("client");
        config.setCyodaClientSecret("secret");
        authentication = new Authentication(config);
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    void theTokenRequestSendsNoScope() {
        authentication.getAccessToken();

        assertThat(requestBodies).hasSize(1);
        String body = requestBodies.get(0);
        assertThat(body).contains("grant_type=client_credentials");
        assertThat(body).doesNotContain("scope");
    }

    @Test
    void aTokenIsReusedWhileFresh() {
        assertThat(authentication.getAccessToken().getTokenValue()).isEqualTo("tok-1");
        assertThat(authentication.getAccessToken().getTokenValue()).isEqualTo("tok-1");
        assertThat(issued).hasValue(1);
    }

    @Test
    void invalidatingTheRejectedTokenForcesANewFetch() {
        assertThat(authentication.getAccessToken().getTokenValue()).isEqualTo("tok-1");

        authentication.invalidate("tok-1");

        assertThat(authentication.getAccessToken().getTokenValue()).isEqualTo("tok-2");
        assertThat(issued).hasValue(2);
    }

    @Test
    void theNoArgInvalidateStillForcesANewFetch() {
        assertThat(authentication.bearerToken()).contains("tok-1");

        authentication.invalidate();

        assertThat(authentication.bearerToken()).contains("tok-2");
        assertThat(issued).hasValue(2);
    }

    @Test
    void aStaleRejectionNeverDiscardsATokenFetchedSince() {
        assertThat(authentication.getAccessToken().getTokenValue()).isEqualTo("tok-1");
        authentication.invalidate("tok-1");
        assertThat(authentication.getAccessToken().getTokenValue()).isEqualTo("tok-2");

        // a second caller that had sent tok-1 reports its rejection late
        authentication.invalidate("tok-1");

        assertThat(authentication.getAccessToken().getTokenValue()).isEqualTo("tok-2");
        assertThat(issued).hasValue(2);
    }

    @Test
    void concurrentRejectionsOfOneTokenFetchExactlyOneReplacement() throws Exception {
        assertThat(authentication.getAccessToken().getTokenValue()).isEqualTo("tok-1");
        int threads = 16;
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Future<String>> results = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                results.add(pool.submit(() -> {
                    go.await();
                    authentication.invalidate("tok-1");
                    return authentication.getAccessToken().getTokenValue();
                }));
            }
            go.countDown();
            for (Future<String> result : results) {
                assertThat(result.get(10, TimeUnit.SECONDS)).isEqualTo("tok-2");
            }
        } finally {
            pool.shutdownNow();
        }
        assertThat(issued).hasValue(2);
    }
}
