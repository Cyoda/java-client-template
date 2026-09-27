package com.java_template.common.service;

import com.fasterxml.jackson.databind.JsonNode;
import org.cyoda.cloud.api.event.common.ModelSpec;
import org.jetbrains.annotations.NotNull;

public interface WorkflowService {

    JsonNode exportWorkflows(
            @NotNull String entityName,
            @NotNull Integer modelVersion
    );

    /** Imports workflows for a model: POST model/{name}/{version}/workflow/import. Never joins a transaction. */
    JsonNode importWorkflows(@NotNull ModelSpec modelSpec, @NotNull JsonNode workflows, @NotNull String importMode);

}

