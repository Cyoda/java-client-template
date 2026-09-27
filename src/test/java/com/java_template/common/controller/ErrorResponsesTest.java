package com.java_template.common.controller;

import com.java_template.common.exception.CyodaCredentialException;
import com.java_template.common.exception.CyodaHttpException;
import io.grpc.Status;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;

import java.util.concurrent.CompletionException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ABOUTME: A credential that cannot be determined or obtained, or Cyoda rejecting one (gRPC UNAUTHENTICATED,
 * REST 401), is a server-side fault (500), not the client's; request errors keep the caller's status (400).
 */
class ErrorResponsesTest {

    private static final Logger LOG = LoggerFactory.getLogger(ErrorResponsesTest.class);

    private static ResponseEntity<Object> failure(Exception cause) {
        return ErrorResponses.failure(LOG, HttpStatus.BAD_REQUEST, "Failed to do it", cause);
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
    void anUpstreamUnauthenticatedIs500() {
        ResponseEntity<Object> response = failure(new CompletionException(Status.UNAUTHENTICATED
                .withDescription("failed to obtain a Cyoda token").asRuntimeException()));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
    }

    @Test
    void anUpstreamRest401Is500() {
        ResponseEntity<Object> response = failure(new CompletionException(
                new CyodaHttpException(401, "UNAUTHORIZED", "token rejected", false)));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
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
