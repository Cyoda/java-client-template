package com.cyoda.build;

/** A patch found the upstream defect it works around already fixed: the patch must be removed. */
public class FixedUpstreamException extends IllegalStateException {
    public FixedUpstreamException(String message) {
        super(message);
    }
}
