package com.java_template.common.controller;

import com.java_template.common.exception.CyodaCredentialException;
import com.java_template.common.exception.CyodaHttpException;
import io.grpc.Status;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.time.Instant;
import java.util.concurrent.CompletionException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ABOUTME: A credential that cannot be determined or obtained ({@link CyodaCredentialException}) is always a
 * server-side fault (500). Cyoda rejecting a call as unauthenticated (gRPC UNAUTHENTICATED, REST 401) is a
 * server-side fault (500) for the service's own M2M credential, but 401 when the rejected credential was the
 * logged-in user's own token, forwarded per {@link com.java_template.common.call.CyodaCallContexts}; request
 * errors keep the caller's status (400).
 */
class ErrorResponsesTest {

    private static final Logger LOG = LoggerFactory.getLogger(ErrorResponsesTest.class);

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    private static ResponseEntity<Object> failure(Exception cause) {
        return ErrorResponses.failure(LOG, HttpStatus.BAD_REQUEST, "Failed to do it", cause);
    }

    private static void authenticateAsJwtUser() {
        Jwt token = Jwt.withTokenValue("user-token").header("alg", "RS256").subject("u1")
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60)).build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(token));
    }

    @Test
    void aCredentialFailureIs500() {
        ResponseEntity<Object> response = failure(new CompletionException(
                new CyodaCredentialException("principal of type alice (internal detail) cannot be forwarded")));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        ProblemDetail body = (ProblemDetail) response.getBody();
        assertThat(body.getDetail()).startsWith("Failed to do it").doesNotContain("alice");
        assertThat(body.getProperties()).containsKey(ErrorResponses.CORRELATION_ID);
    }

    @Test
    void aCredentialFailureIs500EvenForAJwtUser() {
        authenticateAsJwtUser();

        ResponseEntity<Object> response = failure(new CompletionException(
                new CyodaCredentialException("principal of type alice cannot be forwarded")));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
    }

    @Test
    void anUpstreamUnauthenticatedIs500ForAnM2mOrNoneCaller() {
        ResponseEntity<Object> response = failure(new CompletionException(Status.UNAUTHENTICATED
                .withDescription("failed to obtain a Cyoda token").asRuntimeException()));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
    }

    @Test
    void anUpstreamRest401Is500ForAnM2mOrNoneCaller() {
        ResponseEntity<Object> response = failure(new CompletionException(
                new CyodaHttpException(401, "UNAUTHORIZED", "token rejected", false)));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
    }

    @Test
    void anUpstreamUnauthenticatedIs401ForAJwtUser() {
        authenticateAsJwtUser();

        ResponseEntity<Object> response = failure(new CompletionException(Status.UNAUTHENTICATED
                .withDescription("failed to obtain a Cyoda token").asRuntimeException()));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getHeaders().getFirst(HttpHeaders.WWW_AUTHENTICATE))
                .isEqualTo("Bearer error=\"invalid_token\"");
        ProblemDetail body = (ProblemDetail) response.getBody();
        assertThat(body.getDetail()).startsWith("Failed to do it");
    }

    @Test
    void anUpstreamRest401Is401ForAJwtUser() {
        authenticateAsJwtUser();

        ResponseEntity<Object> response = failure(new CompletionException(
                new CyodaHttpException(401, "UNAUTHORIZED", "token rejected", false)));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getHeaders().getFirst(HttpHeaders.WWW_AUTHENTICATE))
                .isEqualTo("Bearer error=\"invalid_token\"");
    }

    @Test
    void aRequestErrorKeepsTheGivenStatus() {
        assertThat(failure(new IllegalArgumentException("bad field")).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(failure(new CompletionException(Status.INVALID_ARGUMENT.asRuntimeException())).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(failure(new CyodaHttpException(404, "HTTP_404", "not found", false)).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }
}
