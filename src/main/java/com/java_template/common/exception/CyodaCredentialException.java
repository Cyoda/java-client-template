package com.java_template.common.exception;

/** The credential for a Cyoda call cannot be determined; the framework never silently downgrades to M2M. */
public class CyodaCredentialException extends IllegalStateException {
    public CyodaCredentialException(String message) {
        super(message);
    }
}
