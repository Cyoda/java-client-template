package com.java_template.common.exception;

/** 403 FORBIDDEN: the caller lacks a role, or the pass belongs to another tenant. */
public class CyodaAccessDeniedException extends CyodaOperationException {
    public CyodaAccessDeniedException(String message) {
        super("FORBIDDEN", message, false);
    }
}
