package com.java_template.common.exception;

/** A joined read answer exceeded CYODA_CALLOUT_JOINED_RESPONSE_MAX_BYTES; page the read. */
public class CyodaJoinedResponseTooLargeException extends CyodaOperationException {
    public CyodaJoinedResponseTooLargeException(String message) {
        super("JOINED_RESPONSE_TOO_LARGE", message, false);
    }
}
