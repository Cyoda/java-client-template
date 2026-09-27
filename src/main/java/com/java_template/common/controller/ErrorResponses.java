package com.java_template.common.controller;

import com.java_template.common.exception.CyodaCredentialException;
import com.java_template.common.exception.CyodaHttpException;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
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
 * <p>
 * The status is the caller's (e.g. 400 for a request the operation could not carry out), except for a call
 * whose credential could not be determined or obtained ({@link CyodaCredentialException}) or that Cyoda rejected
 * as unauthenticated (gRPC {@code UNAUTHENTICATED}, REST 401): the caller's own request was already authenticated
 * by this service, so those are the service's own configuration faults (token endpoint, IdP federation), not the
 * client's, and they answer 500.
 */
public final class ErrorResponses {

    public static final String CORRELATION_ID = "correlationId";

    private ErrorResponses() {
    }

    /**
     * Logs {@code cause} at ERROR with a new correlation id and returns a ProblemDetail whose detail is
     * {@code publicMessage} plus that id, with {@code status}, or 500 for a server-side credential failure.
     *
     * @param publicMessage a fixed, generic description of what failed (e.g. "Failed to create Order"); never
     *                      the exception's message or unvalidated request input
     */
    public static ProblemDetail problem(Logger logger, HttpStatus status, String publicMessage, Exception cause) {
        if (isServerSideCredentialFailure(cause)) {
            status = HttpStatus.INTERNAL_SERVER_ERROR;
        }
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

    /** A credential that could not be determined or obtained, or Cyoda rejecting one, anywhere in the cause chain. */
    static boolean isServerSideCredentialFailure(Throwable cause) {
        for (Throwable t = cause; t != null; t = t.getCause()) {
            if (t instanceof CyodaCredentialException) {
                return true;
            }
            if (t instanceof StatusRuntimeException sre && sre.getStatus().getCode() == Status.Code.UNAUTHENTICATED) {
                return true;
            }
            if (t instanceof CyodaHttpException che && che.status() == 401) {
                return true;
            }
        }
        return false;
    }
}
