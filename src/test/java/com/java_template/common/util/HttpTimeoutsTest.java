package com.java_template.common.util;

import com.java_template.common.auth.Authentication;
import com.java_template.common.auth.CyodaTokenSource;
import com.java_template.common.call.CyodaCallContext;
import com.java_template.common.config.Config;
import com.java_template.common.config.CyodaObjectMapper;
import com.java_template.common.exception.CyodaCredentialException;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * No outbound HTTP call to Cyoda can hang forever: the REST client and the token client have a connect timeout,
 * each REST request and each token request is bounded by grpc-call-deadline-ms, and a thread waiting for another
 * thread's token fetch can be interrupted.
 */
class HttpTimeoutsTest {

    private static final long DEADLINE_MS = 300;

    private HttpServer server;
    private final CountDownLatch release = new CountDownLatch(1);
    private final CountDownLatch tokenRequested = new CountDownLatch(1);
    private String api;

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newCachedThreadPool());
        // every endpoint hangs until the test releases it
        server.createContext("/api", ex -> {
            tokenRequested.countDown();
            try {
                release.await(30, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            byte[] body = "{\"access_token\":\"t\",\"token_type\":\"Bearer\",\"expires_in\":3600}".getBytes(StandardCharsets.UTF_8);
            try {
                ex.getResponseHeaders().add("Content-Type", "application/json");
                ex.sendResponseHeaders(200, body.length);
                ex.getResponseBody().write(body);
            } catch (Exception clientGone) {
                // the client timed out and closed the connection
            }
            ex.close();
        });
        server.start();
        api = "http://127.0.0.1:" + server.getAddress().getPort() + "/api";
    }

    @AfterEach
    void stop() {
        release.countDown();
        server.stop(0);
    }

    private Config config() {
        Config config = new Config();
        config.setCyodaApiUrl(api);
        config.setGrpcCallDeadlineMs(DEADLINE_MS);
        config.setCyodaClientId("client");
        config.setCyodaClientSecret("secret");
        return config;
    }

    @Test
    void theRestClientHasAConnectTimeout() {
        assertThat(SslUtils.createHttpClient(config()).connectTimeout()).isPresent();
    }

    @Test
    void aRestRequestIsBoundedByTheCallDeadline() {
        CyodaTokenSource tokens = mock(CyodaTokenSource.class);
        when(tokens.bearerToken()).thenReturn(Optional.of("m2m"));
        CyodaObjectMapper mappers = CyodaObjectMapper.standalone();
        HttpUtils http = new HttpUtils(new JsonUtils(mappers), mappers, config(), tokens);

        long started = System.nanoTime();
        assertThatThrownBy(() -> http.sendGetRequest(CyodaCallContext.m2m(), api, "entity/x").join())
                .hasRootCauseInstanceOf(HttpTimeoutException.class);
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(10));
    }

    @Test
    void aTokenRequestIsBoundedByTheCallDeadline() {
        Authentication authentication = new Authentication(config());

        long started = System.nanoTime();
        assertThatThrownBy(authentication::getAccessToken).isInstanceOf(RuntimeException.class);
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(10));
    }

    @Test
    void aThreadWaitingForAnotherThreadsTokenFetchCanBeInterrupted() throws Exception {
        Config config = config();
        config.setGrpcCallDeadlineMs(30_000); // the first fetch holds the lock for as long as the endpoint hangs
        Authentication authentication = new Authentication(config);

        Thread holder = Thread.ofPlatform().start(() -> {
            try {
                authentication.getAccessToken();
            } catch (RuntimeException ignored) {
                // released at teardown
            }
        });
        assertThat(tokenRequested.await(10, TimeUnit.SECONDS)).isTrue();

        AtomicReference<Throwable> thrown = new AtomicReference<>();
        AtomicBoolean interruptFlagKept = new AtomicBoolean();
        Thread waiter = Thread.ofPlatform().start(() -> {
            try {
                authentication.getAccessToken();
            } catch (Throwable t) {
                thrown.set(t);
                interruptFlagKept.set(Thread.currentThread().isInterrupted());
            }
        });
        while (waiter.getState() != Thread.State.WAITING && waiter.isAlive()) {
            Thread.onSpinWait();
        }
        waiter.interrupt();
        waiter.join(5_000);

        assertThat(waiter.isAlive()).isFalse();
        assertThat(thrown.get()).isInstanceOf(CyodaCredentialException.class);
        assertThat(interruptFlagKept).isTrue();

        release.countDown();
        holder.join(10_000);
    }
}
