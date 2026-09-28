package com.java_template.common.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.java_template.common.call.CyodaCallContext;
import com.java_template.common.call.CyodaCallContexts;
import com.java_template.common.config.Config;
import com.java_template.common.config.CyodaObjectMapper;
import com.java_template.common.exception.CyodaHttpException;
import com.java_template.common.exception.WorkflowExportException;
import com.java_template.common.util.Futures;
import com.java_template.common.util.HttpUtils;
import org.cyoda.cloud.api.event.common.ModelSpec;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;


@Service
public class WorkflowServiceImpl implements WorkflowService {

    private static final Logger logger = LoggerFactory.getLogger(WorkflowServiceImpl.class);

    private final HttpUtils httpUtils;
    private final CyodaCallContexts callContexts;
    private final ObjectMapper objectMapper;
    private final Config config;

    /**
     * Constructor for WorkflowServiceImpl with dependency injection.
     *
     * @param httpUtils HTTP utility component for making REST API calls
     * @param callContexts builds the per-call {@link CyodaCallContext} (spec §4.2)
     * @param mappers the framework's mappers (workflow JSON uses the protocol mapper)
     * @param config Configuration component for application settings
     */
    public WorkflowServiceImpl(
            final HttpUtils httpUtils,
            final CyodaCallContexts callContexts,
            final CyodaObjectMapper mappers,
            final Config config
    ) {
        this.httpUtils = httpUtils;
        this.callContexts = callContexts;
        this.objectMapper = mappers.protocol();
        this.config = config;
    }

    @Override
    public JsonNode exportWorkflows(
            @NotNull final String entityName,
            @NotNull final Integer modelVersion
    ) {
        logger.debug("Exporting workflows for entity: {} (version: {})", entityName, modelVersion);

        try {
            CyodaCallContext ctx = callContexts.current();

            // Construct API path: model/{entityName}/{modelVersion}/workflow/export
            String exportPath = String.format("model/%s/%d/workflow/export", entityName, modelVersion);
            logger.debug("Using export endpoint: {}", exportPath);

            // Make HTTP GET request to Cyoda API. HttpUtils throws a typed CyodaHttpException for
            // every status >= 400 (caught below), so a successful response here is always 2xx/3xx.
            ObjectNode response = Futures.joinUnwrapped(httpUtils.sendGetRequest(ctx, config.getCyodaApiUrl(), exportPath));
            logger.info("Successfully exported workflows for entity: {} (version: {})", entityName, modelVersion);
            return response.get("json");
        } catch (WorkflowExportException e) {
            // Re-throw WorkflowExportException without logging (will be logged by controller)
            throw e;
        } catch (CyodaHttpException e) {
            if (e.status() == 404) {
                String errorMsg = String.format(
                    "Entity model not found: %s (version %d). Please verify the entity name and version.",
                    entityName, modelVersion
                );
                throw new WorkflowExportException(errorMsg, e.status(), e);
            }
            String errorMsg = String.format(
                "Failed to export workflows for entity %s (version %d). Status code: %d",
                entityName, modelVersion, e.status()
            );
            throw new WorkflowExportException(errorMsg, e.status(), e);
        } catch (Exception e) {
            // Wrap unexpected exceptions
            String errorMsg = String.format(
                "Unexpected error exporting workflows for entity %s (version %d): %s",
                entityName, modelVersion, e.getMessage()
            );
            throw new WorkflowExportException(errorMsg, e);
        }
    }

    @Override
    public JsonNode importWorkflows(
            @NotNull final ModelSpec modelSpec,
            @NotNull final JsonNode workflows,
            @NotNull final String importMode
    ) {
        CyodaCallContext ctx = callContexts.current();
        ObjectNode body = objectMapper.createObjectNode();
        body.put("importMode", importMode);
        body.set("workflows", workflows);
        String path = String.format("model/%s/%d/workflow/import", modelSpec.getName(), modelSpec.getVersion());
        return Futures.joinUnwrapped(httpUtils.sendPostRequest(ctx, config.getCyodaApiUrl(), path, body));
    }

}

