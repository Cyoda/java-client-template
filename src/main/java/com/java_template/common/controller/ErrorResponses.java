package com.java_template.common.controller;

import org.slf4j.Logger;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;

import java.util.UUID;

/**
 * ABOUTME: Generic REST error bodies for failed Cyoda operations. An exception's message can carry internal
 * hosts, URLs or Cyoda error detail, so it is never put in the response: the client gets a generic detail and a
 * correlation id (also in the ProblemDetail's {@code correlationId} property), and the server log records the
 * exception under that id.
 */
public final class ErrorResponses {

    public static final String CORRELATION_ID = "correlationId";

    private ErrorResponses() {
    }

    /**
     * Logs {@code cause} at ERROR with a new correlation id and returns a ProblemDetail whose detail is
     * {@code publicMessage} plus that id.
     *
     * @param publicMessage a fixed, generic description of what failed (e.g. "Failed to create Order"); never
     *                      the exception's message or unvalidated request input
     */
    public static ProblemDetail problem(Logger logger, HttpStatus status, String publicMessage, Exception cause) {
        String correlationId = UUID.randomUUID().toString();
        logger.error("{} [correlationId={}]", publicMessage, correlationId, cause);
        ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(
                status, publicMessage + ". Correlation id: " + correlationId);
        problemDetail.setProperty(CORRELATION_ID, correlationId);
        return problemDetail;
    }

    /** {@link #problem} as a response entity. */
    public static <T> ResponseEntity<T> failure(Logger logger, HttpStatus status, String publicMessage, Exception cause) {
        return ResponseEntity.of(problem(logger, status, publicMessage, cause)).build();
    }
}
