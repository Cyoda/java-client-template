package com.java_template.common.auth;

import com.java_template.common.config.Config;
import com.java_template.common.exception.CyodaCredentialException;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A real {@link Authentication} against a stub {@code /oauth/token} endpoint that issues a different token on
 * every request ({@code tok-1}, {@code tok-2}, …).
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
        SecurityContextHolder.clearContext();
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
    void invalidateForcesANewFetch() {
        assertThat(authentication.bearerToken()).contains("tok-1");

        authentication.invalidate();

        assertThat(authentication.bearerToken()).contains("tok-2");
        assertThat(issued).hasValue(2);
    }

    @Test
    void theM2mTokenIsRefusedForAnAuthenticatedUserOnTheThread() {
        SecurityContextHolder.getContext().setAuthentication(AuthenticatedCallerGuardTestSupport.jwtUser());

        assertThatThrownBy(() -> authentication.bearerToken())
                .isInstanceOf(CyodaCredentialException.class);
        assertThat(issued).hasValue(0);
    }

    @Test
    void anAnonymousRequestStillGetsTheM2mToken() {
        SecurityContextHolder.getContext().setAuthentication(new AnonymousAuthenticationToken(
                "key", "anonymousUser", AuthorityUtils.createAuthorityList("ROLE_ANONYMOUS")));

        assertThat(authentication.bearerToken()).contains("tok-1");
    }

    @Test
    void aCalloutThreadWithoutASecurityContextStillGetsTheM2mToken() throws Exception {
        // The caller's thread holds a user; a processor/criterion thread never inherits it.
        SecurityContextHolder.getContext().setAuthentication(AuthenticatedCallerGuardTestSupport.jwtUser());
        CompletableFuture<java.util.Optional<String>> onCalloutThread = new CompletableFuture<>();

        Thread callout = new Thread(() -> {
            try {
                onCalloutThread.complete(authentication.bearerToken());
            } catch (Throwable t) {
                onCalloutThread.completeExceptionally(t);
            }
        });
        callout.start();

        assertThat(onCalloutThread.get(10, TimeUnit.SECONDS)).contains("tok-1");
    }
}
