package com.java_template.common.exception;

/**
 * A joined read answer exceeded cyoda's CYODA_CALLOUT_JOINED_RESPONSE_MAX_BYTES (10 MiB by default). Inside a
 * callout a read cannot be paged: narrow the condition, or raise the ceiling on the cyoda side.
 */
public class CyodaJoinedResponseTooLargeException extends CyodaOperationException {
    public CyodaJoinedResponseTooLargeException(String message) {
        super("JOINED_RESPONSE_TOO_LARGE", message, false);
    }
}
