package com.java_template.common.service;

import com.fasterxml.jackson.databind.JsonNode;
import org.cyoda.cloud.api.event.common.ModelSpec;
import org.jetbrains.annotations.NotNull;

/**
 * Workflow administration over REST. Errors are thrown as the typed Cyoda exceptions
 * (com.java_template.common.exception, e.g. CyodaHttpException, CyodaAccessDeniedException), never wrapped in a
 * CompletionException. The one exception is {@link #exportWorkflows}, which keeps its documented
 * {@link com.java_template.common.exception.WorkflowExportException} contract, with the typed Cyoda exception as
 * its cause.
 */
public interface WorkflowService {

    /**
     * Exports a model's workflows: GET model/{name}/{version}/workflow/export.
     *
     * @throws com.java_template.common.exception.WorkflowExportException on any failure; its cause is the typed
     *         Cyoda exception the call raised
     */
    JsonNode exportWorkflows(
            @NotNull String entityName,
            @NotNull Integer modelVersion
    );

    /**
     * Imports workflows for a model: POST model/{name}/{version}/workflow/import. Never joins a transaction.
     * A refusal (any status of 400 or above) is thrown as the typed Cyoda exception, e.g. CyodaHttpException.
     */
    JsonNode importWorkflows(@NotNull ModelSpec modelSpec, @NotNull JsonNode workflows, @NotNull String importMode);

}
