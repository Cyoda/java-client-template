package com.java_template.common.exception;

import org.cyoda.cloud.api.event.common.Error;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CyodaErrorsTest {

    private static Error envelope(String message, Boolean retryable) {
        Error e = new Error();
        e.setCode("CLIENT_ERROR");
        e.setMessage(message);
        e.setRetryable(retryable);
        return e;
    }

    private static String problem(int status, String code, String detail, Boolean retryable) {
        return "{\"type\":\"about:blank\",\"title\":\"x\",\"status\":" + status + ",\"detail\":\"" + detail
                + "\",\"properties\":{\"errorCode\":\"" + code + "\"" + (retryable == null ? "" : ",\"retryable\":" + retryable) + "}}";
    }

    @Test
    void grpcCodeIsTheMessagePrefixNotTheCoarseEnvelopeCode() {
        assertThat(CyodaErrors.codeFromMessage("ENTITY_NOT_FOUND: entity id=abc not found")).isEqualTo("ENTITY_NOT_FOUND");
        assertThat(CyodaErrors.codeFromMessage("no prefix here")).isNull();

        RuntimeException e = CyodaErrors.fromGrpcEnvelope(envelope("ENTITY_NOT_FOUND: entity id=abc not found", null), false);

        assertThat(e).isInstanceOf(CyodaOperationException.class);
        assertThat(((CyodaOperationException) e).getErrorCode()).isEqualTo("ENTITY_NOT_FOUND");
    }

    @Test
    void theSameStatusIsToldApartByCode() {
        assertThat(CyodaErrors.fromHttp(410, problem(410, "CALLOUT_SUPERSEDED", "CALLOUT_SUPERSEDED: moved", null), true))
                .isInstanceOf(CyodaCalloutEndedException.class);
        assertThat(CyodaErrors.fromHttp(410, problem(410, "TRANSACTION_EXPIRED", "TRANSACTION_EXPIRED: old", null), true))
                .isInstanceOf(CyodaCalloutEndedException.class);
        assertThat(CyodaErrors.fromGrpcEnvelope(envelope("TRANSACTION_NOT_FOUND: closed", null), true))
                .isInstanceOf(CyodaCalloutEndedException.class);
    }

    @Test
    void unauthorizedEndsTheCalloutOnlyWhenJoined() {
        assertThat(CyodaErrors.fromGrpcEnvelope(envelope("UNAUTHORIZED: invalid transaction token", null), true))
                .isInstanceOf(CyodaCalloutEndedException.class);
        assertThat(CyodaErrors.fromHttp(401, problem(401, "UNAUTHORIZED", "UNAUTHORIZED: bad token", null), false))
                .isInstanceOf(CyodaHttpException.class)
                .isNotInstanceOf(CyodaCalloutEndedException.class);
    }

    @Test
    void joinedRetryablesAndTheRest() {
        assertThat(CyodaErrors.fromGrpcEnvelope(envelope("TOO_MANY_JOINED_REQUESTS: queue full", true), true))
                .isInstanceOf(CyodaRetryableException.class);
        assertThat(CyodaErrors.fromHttp(503, problem(503, "TRANSACTION_NODE_UNAVAILABLE", "x", true), true))
                .isInstanceOf(CyodaRetryableException.class);
        assertThat(CyodaErrors.fromHttp(409, problem(409, "CONFLICT", "CONFLICT: retry", true), true))
                .isInstanceOf(CyodaRetryableException.class);
        assertThat(CyodaErrors.fromHttp(409, problem(409, "CONFLICT", "CONFLICT: no", null), false))
                .isInstanceOf(CyodaHttpException.class);
        assertThat(CyodaErrors.fromHttp(413, problem(413, "JOINED_RESPONSE_TOO_LARGE", "x", null), true))
                .isInstanceOf(CyodaJoinedResponseTooLargeException.class);
        assertThat(CyodaErrors.fromGrpcEnvelope(envelope("COMMIT_IN_JOINED_TRANSACTION: use unjoined", null), true))
                .isInstanceOf(CyodaCommitInJoinedTransactionException.class);
        assertThat(CyodaErrors.fromHttp(403, problem(403, "FORBIDDEN", "FORBIDDEN: role", null), false))
                .isInstanceOf(CyodaAccessDeniedException.class);
        assertThat(CyodaErrors.fromGrpcEnvelope(envelope("MODEL_ADMIN_IN_JOINED_TRANSACTION: no", null), true))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("framework bug");
    }

    @Test
    void httpStatusIsKeptAndNonProblemBodiesStillMap() {
        RuntimeException e = CyodaErrors.fromHttp(502, "<html>bad gateway</html>", false);

        assertThat(e).isInstanceOf(CyodaHttpException.class);
        assertThat(((CyodaHttpException) e).status()).isEqualTo(502);
        assertThat(((CyodaHttpException) e).getErrorCode()).isEqualTo("HTTP_502");
    }

    @Test
    void aJoinedRest401EndsTheCalloutByStatusEvenWithoutAnErrorCode() {
        assertThat(CyodaErrors.fromHttp(401, "", true)).isInstanceOf(CyodaCalloutEndedException.class);
        assertThat(CyodaErrors.fromHttp(401, "{\"status\":401,\"detail\":\"token expired\"}", true))
                .isInstanceOf(CyodaCalloutEndedException.class);
        assertThat(CyodaErrors.fromHttp(401, problem(401, "INVALID_PASS", "INVALID_PASS: forged", null), true))
                .isInstanceOf(CyodaCalloutEndedException.class);
        // unjoined: an ordinary HTTP failure, as before
        assertThat(CyodaErrors.fromHttp(401, "", false))
                .isInstanceOf(CyodaHttpException.class)
                .isNotInstanceOf(CyodaCalloutEndedException.class);
    }
}
