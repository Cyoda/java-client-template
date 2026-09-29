package com.java_template.common.controller;

import com.java_template.common.exception.CyodaCredentialException;
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
 * ABOUTME: A refused call for a logged-in user, or Cyoda rejecting the service's credentials, is a server-side
 * limitation (500), not the client's fault; request errors keep the caller's status (400).
 */
class ErrorResponsesTest {

    private static final Logger LOG = LoggerFactory.getLogger(ErrorResponsesTest.class);

    private static ResponseEntity<Object> failure(Exception cause) {
        return ErrorResponses.failure(LOG, HttpStatus.BAD_REQUEST, "Failed to do it", cause);
    }

    @Test
    void aRefusedLoggedInCallIs500() {
        ResponseEntity<Object> response = failure(new CompletionException(
                new CyodaCredentialException("the M2M token is refused for alice (internal detail)")));

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
    void aRequestErrorKeepsTheGivenStatus() {
        assertThat(failure(new IllegalArgumentException("bad field")).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(failure(new CompletionException(Status.INVALID_ARGUMENT.asRuntimeException())).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }
}
