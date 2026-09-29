package com.java_template.common.exception;

import lombok.Getter;

/**
 * ABOUTME: Exception thrown when workflow export operations fail.
 *<p>
 * <p>This exception provides specific error information about workflow export failures
 * without requiring generic exception handling. It includes HTTP status code information
 * to help the controller layer determine the appropriate HTTP response.</p>
 *<p>
 * <p><strong>Common Causes:</strong></p>
 * <ul>
 *   <li>Entity model not found (404 from Cyoda API)</li>
 *   <li>Network connectivity issues</li>
 *   <li>Authentication/authorization failures</li>
 *   <li>Invalid entity name or version</li>
 *   <li>Cyoda API errors</li>
 * </ul>
 */
@Getter
public class WorkflowExportException extends RuntimeException {

    private final Integer httpStatusCode;

    /**
     * Constructs a new WorkflowExportException with the specified detail message and cause.
     *
     * @param message the detail message explaining the failure
     * @param cause the cause of the failure
     */
    public WorkflowExportException(String message, Throwable cause) {
        super(message, cause);
        this.httpStatusCode = null;
    }

    /**
     * Constructs a new WorkflowExportException with HTTP status code.
     *
     * @param message the detail message explaining the failure
     * @param httpStatusCode the HTTP status code from the failed API call
     */
    public WorkflowExportException(String message, Integer httpStatusCode) {
        super(message);
        this.httpStatusCode = httpStatusCode;
    }

    /**
     * Constructs a new WorkflowExportException with HTTP status code and a cause.
     *
     * @param message the detail message explaining the failure
     * @param httpStatusCode the HTTP status code from the failed API call
     * @param cause the cause of the failure (typically a {@link CyodaHttpException})
     */
    public WorkflowExportException(String message, Integer httpStatusCode, Throwable cause) {
        super(message, cause);
        this.httpStatusCode = httpStatusCode;
    }
}

