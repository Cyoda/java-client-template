package com.java_template.common.exception;

/** A joined write reached a COMMIT_BEFORE_DISPATCH processor's transaction; make it with CalloutScope.unjoined. */
public class CyodaCommitInJoinedTransactionException extends CyodaOperationException {
    public CyodaCommitInJoinedTransactionException(String message) {
        super("COMMIT_IN_JOINED_TRANSACTION", message, false);
    }
}
