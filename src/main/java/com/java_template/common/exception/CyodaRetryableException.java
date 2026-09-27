package com.java_template.common.exception;

/** A transient refusal Cyoda marks retryable (e.g. TOO_MANY_JOINED_REQUESTS). */
public class CyodaRetryableException extends CyodaOperationException {
    public CyodaRetryableException(String code, String message) {
        super(code, message, true);
    }
}
