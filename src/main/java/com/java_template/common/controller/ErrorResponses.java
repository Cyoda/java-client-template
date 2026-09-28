package com.java_template.common.controller;

import com.java_template.common.call.CyodaCallContexts;
import com.java_template.common.exception.CyodaCredentialException;
import com.java_template.common.exception.CyodaHttpException;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import org.slf4j.Logger;
import org.springframework.http.HttpHeaders;
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
 * The status is the caller's (e.g. 400 for a request the operation could not carry out), except:
 * <ul>
 *     <li>a call whose credential could not be determined or obtained ({@link CyodaCredentialException}) always
 *     answers 500: it is a server or configuration limitation (a refused or unsupported principal, a blank
 *     token), not an expired login;</li>
 *     <li>Cyoda rejecting a call as unauthenticated (gRPC {@code UNAUTHENTICATED}, REST 401) answers 500 when
 *     this service's own request to Cyoda used its M2M credential (a token-endpoint or IdP-federation fault),
 *     but 401 when it forwarded the logged-in user's own IdP token
 *     ({@link CyodaCallContexts#isForwardingUserToken()}), so the frontend can re-authenticate.</li>
 * </ul>
 */
public final class ErrorResponses {

    public static final String CORRELATION_ID = "correlationId";
    private static final String WWW_AUTHENTICATE_INVALID_TOKEN = "Bearer error=\"invalid_token\"";

    private ErrorResponses() {
    }

    /**
     * Logs {@code cause} at ERROR with a new correlation id and returns a ProblemDetail whose detail is
     * {@code publicMessage} plus that id, with {@code status} resolved per this class's rules.
     *
     * @param publicMessage a fixed, generic description of what failed (e.g. "Failed to create Order"); never
     *                      the exception's message or unvalidated request input
     */
    public static ProblemDetail problem(Logger logger, HttpStatus status, String publicMessage, Exception cause) {
        status = resolveStatus(status, cause);
        String correlationId = UUID.randomUUID().toString();
        logger.error("{} [correlationId={}]", publicMessage, correlationId, cause);
        ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(
                status, publicMessage + ". Correlation id: " + correlationId);
        problemDetail.setProperty(CORRELATION_ID, correlationId);
        return problemDetail;
    }

    /**
     * {@link #problem} as a response entity. A resolved 401 (a forwarded user token that Cyoda rejected) also
     * carries a {@code WWW-Authenticate: Bearer error="invalid_token"} header, so the frontend knows to
     * re-authenticate.
     */
    public static <T> ResponseEntity<T> failure(Logger logger, HttpStatus status, String publicMessage, Exception cause) {
        ProblemDetail problemDetail = problem(logger, status, publicMessage, cause);
        if (problemDetail.getStatus() == HttpStatus.UNAUTHORIZED.value()) {
            return ResponseEntity.of(problemDetail)
                    .header(HttpHeaders.WWW_AUTHENTICATE, WWW_AUTHENTICATE_INVALID_TOKEN)
                    .build();
        }
        return ResponseEntity.of(problemDetail).build();
    }

    private static HttpStatus resolveStatus(HttpStatus status, Throwable cause) {
        if (isCredentialException(cause)) {
            return HttpStatus.INTERNAL_SERVER_ERROR;
        }
        if (isUpstreamAuthRejection(cause)) {
            return CyodaCallContexts.isForwardingUserToken() ? HttpStatus.UNAUTHORIZED : HttpStatus.INTERNAL_SERVER_ERROR;
        }
        return status;
    }

    /** A credential that could not be determined or obtained, anywhere in the cause chain: always a server fault. */
    static boolean isCredentialException(Throwable cause) {
        for (Throwable t = cause; t != null; t = t.getCause()) {
            if (t instanceof CyodaCredentialException) {
                return true;
            }
        }
        return false;
    }

    /** Cyoda rejecting a call as unauthenticated (gRPC UNAUTHENTICATED, REST 401), anywhere in the cause chain. */
    static boolean isUpstreamAuthRejection(Throwable cause) {
        for (Throwable t = cause; t != null; t = t.getCause()) {
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
