package com.java_template.common.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.java_template.common.auth.CyodaTokenSource;
import com.java_template.common.config.Config;
import com.java_template.common.config.CyodaObjectMapper;
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
 * via the Cyoda HTTP API endpoint /message/get/{messageId}.
 */
@Service
public class EdgeMessageServiceImpl implements EdgeMessageService {

    private static final Logger logger = LoggerFactory.getLogger(EdgeMessageServiceImpl.class);

    private final HttpUtils httpUtils;
    private final CyodaTokenSource tokenSource;
    private final ObjectMapper objectMapper;
    private final String cyodaApiUrl;

    public EdgeMessageServiceImpl(
            HttpUtils httpUtils,
            CyodaTokenSource tokenSource,
            CyodaObjectMapper wireMapper,
            Config configProperties
    ) {
        this.httpUtils = httpUtils;
        this.tokenSource = tokenSource;
        this.objectMapper = wireMapper.mapper();
        this.cyodaApiUrl = configProperties.getCyodaApiUrl();
    }

    @Override
    @Nullable
    public JsonNode getMessageById(@NotNull UUID messageId) {
        logger.debug("Retrieving EdgeMessage with ID: {}", messageId);

        // Get OAuth2 access token
        String token = tokenSource.bearerToken().orElse(null);

        // Construct API path: message/get/{messageId}
        String path = String.format("message/%s", messageId);
        logger.debug("Using EdgeMessage endpoint: {}", path);

        // Make HTTP GET request to Cyoda API
        ObjectNode response = httpUtils.sendGetRequest(token, cyodaApiUrl, path).join();
        int statusCode = response.get("status").asInt();

        if (statusCode == 200) {
            // HttpUtils wraps response in: { "status": 200, "json": {...} }
            JsonNode body = response.get("json");
            logger.debug("Successfully retrieved EdgeMessage: {}", messageId);
            return body;
        } else if (statusCode == 404) {
            logger.debug("EdgeMessage not found with ID: {}", messageId);
            return null;
        } else {
            throw new IllegalStateException(
                    String.format(
                            "Failed to retrieve EdgeMessage %s: HTTP %d - %s",
                            messageId,
                            statusCode,
                            response.has("json") ? response.get("json").asText() : "Unknown error"
                    )
            );
        }
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

        try {
            // Get OAuth2 access token
            String token = tokenSource.bearerToken().orElse(null);

            // Construct API path: message/new/{subject}
            String path = String.format("message/new/%s", subject);
            logger.debug("Using EdgeMessage creation endpoint: {}", path);

            // According to OpenAPI spec, the request body must have this structure:
            // {
            //   "payload": { ... actual content ... },
            //   "meta-data": { ... optional flat key-value pairs ... }
            // }
            // The "payload" field is required and contains the actual message content
            ObjectNode requestBody = objectMapper.createObjectNode();
            requestBody.set("payload", content);
            requestBody.set("meta-data", metadata);

            // Make HTTP POST request to Cyoda API
            // The API expects Content-Type and Content-Length headers which are set by httpUtils
            ObjectNode response = httpUtils.sendPostRequest(token, cyodaApiUrl, path, requestBody).join();
            int statusCode = response.get("status").asInt();

            if (statusCode == 200 || statusCode == 201) {
                // Response format per OpenAPI spec:
                // [{
                //   "entityIds": ["8824c480-c166-11ee-bf9f-ae468cd3ed16"],
                //   "success": true
                // }]
                // HttpUtils wraps response in: { "status": 200, "json": {...} }
                ArrayNode body = (ArrayNode) response.get("json");
                JsonNode entityIds = body.get(0).get("entityIds");

                if (entityIds == null || !entityIds.isArray() || entityIds.isEmpty()) {
                    throw new IllegalStateException("EdgeMessage creation response missing entityIds array");
                }

                String messageIdStr = entityIds.get(0).asText();
                UUID messageId = UUID.fromString(messageIdStr);
                logger.info("Successfully created EdgeMessage: {} with subject: {}", messageId, subject);
                return messageId;
            } else {
                throw new IllegalStateException(
                        String.format(
                                "Failed to create EdgeMessage: HTTP %d - %s",
                                statusCode,
                                response.has("json") ? response.get("json").asText() : "Unknown error"
                        )
                );
            }
        } catch (Exception e) {
            throw new IllegalStateException("Failed to create EdgeMessage with subject '" + subject + "': " + e.getMessage(), e);
        }
    }

    @Override
    public boolean deleteMessage(@NotNull UUID messageId) {
        logger.debug("Deleting EdgeMessage with ID: {}", messageId);

        try {
            // Get OAuth2 access token
            String token = tokenSource.bearerToken().orElse(null);

            // Construct API path: message/{messageId}
            String path = String.format("message/%s", messageId);
            logger.debug("Using EdgeMessage deletion endpoint: {}", path);

            // Make HTTP DELETE request to Cyoda API
            ObjectNode response = httpUtils.sendDeleteRequest(token, cyodaApiUrl, path).join();
            int statusCode = response.get("status").asInt();

            if (statusCode == 200 || statusCode == 204) {
                logger.info("Successfully deleted EdgeMessage: {}", messageId);
                return true;
            } else if (statusCode == 404) {
                logger.debug("EdgeMessage not found with ID: {}", messageId);
                return false;
            } else {
                throw new IllegalStateException(
                        String.format(
                                "Failed to delete EdgeMessage %s: HTTP %d - %s",
                                messageId,
                                statusCode,
                                response.has("json") ? response.get("json").asText() : "Unknown error"
                        )
                );
            }
        } catch (Exception e) {
            throw new IllegalStateException("Failed to delete EdgeMessage " + messageId + ": " + e.getMessage(), e);
        }
    }
}

