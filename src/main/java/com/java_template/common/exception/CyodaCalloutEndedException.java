package com.java_template.common.exception;

/**
 * The callout this request joined has ended or moved (CALLOUT_SUPERSEDED, TRANSACTION_EXPIRED,
 * TRANSACTION_NOT_FOUND, or an invalid pass). Stop working on this request; never retry.
 */
public class CyodaCalloutEndedException extends CyodaOperationException {
    public CyodaCalloutEndedException(String code, String message) {
        super(code, message, false);
    }
}
