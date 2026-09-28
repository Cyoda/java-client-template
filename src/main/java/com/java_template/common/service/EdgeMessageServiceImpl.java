package com.java_template.common.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.java_template.common.call.CyodaCallContext;
import com.java_template.common.call.CyodaCallContexts;
import com.java_template.common.config.Config;
import com.java_template.common.config.CyodaObjectMapper;
import com.java_template.common.exception.CyodaHttpException;
import com.java_template.common.util.Futures;
import com.java_template.common.util.HttpUtils;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * ABOUTME: Implementation of EdgeMessageService that retrieves EdgeMessage data
 * via the Cyoda HTTP API endpoint /message/{messageId}.
 *
 * <p>Every future is joined with {@link Futures#joinUnwrapped}, so failures leave the public methods as the
 * typed Cyoda exceptions (spec §4.4). A 404 ({@link CyodaHttpException} with status 404) is the documented
 * "not found" result of getMessageById/getMessageContent ({@code null}) and deleteMessage ({@code false});
 * TRANSACTION_NOT_FOUND, also a 404, is a {@code CyodaCalloutEndedException} and propagates.
 */
@Service
public class EdgeMessageServiceImpl implements EdgeMessageService {

    private static final Logger logger = LoggerFactory.getLogger(EdgeMessageServiceImpl.class);

    private final HttpUtils httpUtils;
    private final CyodaCallContexts callContexts;
    private final ObjectMapper objectMapper;
    private final String cyodaApiUrl;

    public EdgeMessageServiceImpl(
            HttpUtils httpUtils,
            CyodaCallContexts callContexts,
            CyodaObjectMapper mappers,
            Config configProperties
    ) {
        this.httpUtils = httpUtils;
        this.callContexts = callContexts;
        this.objectMapper = mappers.protocol();
        this.cyodaApiUrl = configProperties.getCyodaApiUrl();
    }

    @Override
    @Nullable
    public JsonNode getMessageById(@NotNull UUID messageId) {
        logger.debug("Retrieving EdgeMessage with ID: {}", messageId);

        CyodaCallContext ctx = callContexts.current();
        String path = String.format("message/%s", messageId);

        final ObjectNode response;
        try {
            response = Futures.joinUnwrapped(httpUtils.sendGetRequest(ctx, cyodaApiUrl, path));
        } catch (CyodaHttpException e) {
            if (e.status() == 404) {
                logger.debug("EdgeMessage not found with ID: {}", messageId);
                return null;
            }
            throw e;
        }
        requireSuccess(response, "retrieve EdgeMessage " + messageId);
        // HttpUtils wraps the response in: { "status": 200, "json": {...} }
        return response.get("json");
    }

    @Override
    @Nullable
    public JsonNode getMessageContent(@NotNull UUID messageId) throws JsonProcessingException {
        logger.debug("Retrieving EdgeMessage content for ID: {}", messageId);

        JsonNode message = getMessageById(messageId);

        // If message not found, return null
        if (message == null) {
            return null;
        }

        // EdgeMessage structure from OpenAPI spec:
        // {
        //   "header": { ... },
        //   "metaData": { ... },
        //   "content": "string"
        // }
        // The content field is a string that needs to be parsed as JSON

        if (!message.has("content")) {
            logger.debug("EdgeMessage {} has no content field", messageId);
            return null;
        }

        JsonNode contentField = message.get("content");

        // If content is null or missing, return null (valid state)
        if (contentField == null || contentField.isNull()) {
            logger.debug("EdgeMessage {} has null content", messageId);
            return null;
        }

        // If content is a string, parse it as JSON
        if (contentField.isTextual()) {
            String contentString = contentField.asText();
            JsonNode parsedContent = objectMapper.readTree(contentString);
            logger.debug("Successfully parsed EdgeMessage content for ID: {}", messageId);
            return parsedContent;
        }

        // If content is already a JSON object/array, return it directly
        logger.debug("EdgeMessage content is already parsed JSON for ID: {}", messageId);
        return contentField;
    }

    @Override
    @NotNull
    public UUID createMessage(
            @NotNull String subject,
            @NotNull JsonNode content,
            @Nullable ObjectNode metadata
    ) {
        logger.debug("Creating EdgeMessage with subject: {}", subject);

        CyodaCallContext ctx = callContexts.current();
        String path = String.format("message/new/%s", subject);

        // According to OpenAPI spec, the request body must have this structure:
        // {
        //   "payload": { ... actual content ... },
        //   "meta-data": { ... optional flat key-value pairs ... }
        // }
        // The "payload" field is required and contains the actual message content
        ObjectNode requestBody = objectMapper.createObjectNode();
        requestBody.set("payload", content);
        requestBody.set("meta-data", metadata);

        ObjectNode response = Futures.joinUnwrapped(httpUtils.sendPostRequest(ctx, cyodaApiUrl, path, requestBody));
        requireSuccess(response, "create EdgeMessage with subject '" + subject + "'");

        // Response format per OpenAPI spec:
        // [{
        //   "entityIds": ["8824c480-c166-11ee-bf9f-ae468cd3ed16"],
        //   "success": true
        // }]
        JsonNode body = response.get("json");
        JsonNode entityIds = body != null && body.isArray() && !body.isEmpty() ? body.get(0).get("entityIds") : null;
        if (entityIds == null || !entityIds.isArray() || entityIds.isEmpty()) {
            throw new IllegalStateException("EdgeMessage creation response missing entityIds array");
        }

        UUID messageId = UUID.fromString(entityIds.get(0).asText());
        logger.info("Successfully created EdgeMessage: {} with subject: {}", messageId, subject);
        return messageId;
    }

    @Override
    public boolean deleteMessage(@NotNull UUID messageId) {
        logger.debug("Deleting EdgeMessage with ID: {}", messageId);

        CyodaCallContext ctx = callContexts.current();
        String path = String.format("message/%s", messageId);

        final ObjectNode response;
        try {
            response = Futures.joinUnwrapped(httpUtils.sendDeleteRequest(ctx, cyodaApiUrl, path));
        } catch (CyodaHttpException e) {
            if (e.status() == 404) {
                logger.debug("EdgeMessage not found with ID: {}", messageId);
                return false;
            }
            throw e;
        }
        requireSuccess(response, "delete EdgeMessage " + messageId);
        logger.info("Successfully deleted EdgeMessage: {}", messageId);
        return true;
    }

    /**
     * HttpUtils throws on every 4xx/5xx (as the typed Cyoda exception), so only a 2xx or 3xx reaches here;
     * cyoda-go answers these endpoints with neither 3xx nor another non-2xx status.
     */
    private static void requireSuccess(ObjectNode response, String operation) {
        int statusCode = response.path("status").asInt();
        if (statusCode < 200 || statusCode >= 300) {
            throw new IllegalStateException(String.format("Failed to %s: unexpected HTTP %d", operation, statusCode));
        }
    }
}
