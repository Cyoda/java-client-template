package com.java_template.common.exception;

/** A Cyoda REST failure: HTTP status plus the problem detail's properties.errorCode. */
public class CyodaHttpException extends CyodaOperationException {
    private final int status;

    public CyodaHttpException(int status, String code, String message, boolean retryable) {
        super(code, message, retryable);
        this.status = status;
    }

    public int status() {
        return status;
    }
}
