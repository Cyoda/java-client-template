package com.java_template.common.exception;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.cyoda.cloud.api.event.common.Error;

import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * ABOUTME: Maps cyoda-go failures to typed exceptions (spec §4.4).
 * gRPC: the envelope's code is coarse (CLIENT_ERROR/SERVER_ERROR); the real code is the message
 * prefix up to the first colon. REST: the problem detail's properties.errorCode.
 */
public final class CyodaErrors {

    public static final Set<String> JOINED_RETRYABLE = Set.of("TOO_MANY_JOINED_REQUESTS", "TRANSACTION_NODE_UNAVAILABLE");

    private static final Pattern CODE_PREFIX = Pattern.compile("^([A-Z][A-Z0-9_]+):");
    private static final ObjectMapper OM = new ObjectMapper();

    private CyodaErrors() {
    }

    public static String codeFromMessage(String message) {
        if (message == null) {
            return null;
        }
        Matcher m = CODE_PREFIX.matcher(message);
        return m.find() ? m.group(1) : null;
    }

    public static RuntimeException fromGrpcEnvelope(Error error, boolean joined) {
        String message = error == null || error.getMessage() == null ? "Operation failed with no error details" : error.getMessage();
        String code = codeFromMessage(message);
        if (code == null) {
            code = error != null && error.getCode() != null ? error.getCode() : "UNKNOWN";
        }
        boolean retryable = error != null && Boolean.TRUE.equals(error.getRetryable());
        return map(code, null, retryable, message, joined);
    }

    public static RuntimeException fromHttp(int status, String body, boolean joined) {
        String detail = body;
        String code = null;
        boolean retryable = false;
        try {
            JsonNode json = OM.readTree(body);
            if (json != null && json.isObject()) {
                detail = firstText(json, "detail", "message", "errorMessage", "title");
                code = json.path("properties").path("errorCode").asText(null);
                retryable = json.path("properties").path("retryable").asBoolean(false);
            }
        } catch (Exception notJson) {
            // keep the raw body as the detail
        }
        if (code == null) {
            code = codeFromMessage(detail);
        }
        if (code == null) {
            code = "HTTP_" + status;
        }
        if (joined && status == 401) {
            // A joined request authenticates with the callout's tx-token as well as the credential: a 401 there
            // means the callout can no longer be served, whatever the body says (spec §4.2), so it is keyed on
            // the status, not only on an UNAUTHORIZED errorCode a proxy or gateway may not send.
            return new CyodaCalloutEndedException(code, detail == null ? "" : detail);
        }
        return map(code, status, retryable, detail == null ? "" : detail, joined);
    }

    static RuntimeException map(String code, Integer status, boolean retryable, String message, boolean joined) {
        return switch (code) {
            case "CALLOUT_SUPERSEDED", "TRANSACTION_EXPIRED", "TRANSACTION_NOT_FOUND" -> new CyodaCalloutEndedException(code, message);
            case "UNAUTHORIZED" -> joined ? new CyodaCalloutEndedException(code, message) : generic(code, status, false, message);
            case "TOO_MANY_JOINED_REQUESTS", "TRANSACTION_NODE_UNAVAILABLE" -> new CyodaRetryableException(code, message);
            case "CONFLICT" -> retryable ? new CyodaRetryableException(code, message) : generic(code, status, false, message);
            case "JOINED_RESPONSE_TOO_LARGE" -> new CyodaJoinedResponseTooLargeException(message);
            case "COMMIT_IN_JOINED_TRANSACTION" -> new CyodaCommitInJoinedTransactionException(message);
            case "FORBIDDEN" -> new CyodaAccessDeniedException(message);
            case "MODEL_ADMIN_IN_JOINED_TRANSACTION" ->
                    new IllegalStateException("framework bug: a tx-token was sent on a model/workflow admin call: " + message);
            default -> generic(code, status, retryable, message);
        };
    }

    private static CyodaOperationException generic(String code, Integer status, boolean retryable, String message) {
        return status == null ? new CyodaOperationException(code, message, retryable)
                : new CyodaHttpException(status, code, message, retryable);
    }

    private static String firstText(JsonNode json, String... fields) {
        for (String f : fields) {
            if (json.hasNonNull(f)) {
                return json.get(f).asText();
            }
        }
        return json.toString();
    }
}
