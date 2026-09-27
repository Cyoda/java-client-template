package com.java_template.common.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.NotNull;

import java.util.UUID;

/**
 * ABOUTME: Service interface for retrieving and creating EdgeMessage data via HTTP API.
 * EdgeMessages are accessed through the Cyoda HTTP API, not through the entity service.
 *
 * <p>Failures are thrown as the typed Cyoda exceptions (com.java_template.common.exception), never wrapped in a
 * CompletionException: CyodaCalloutEndedException (stop working on this request), CyodaRetryableException,
 * CyodaAccessDeniedException, and otherwise CyodaHttpException carrying the HTTP status and cyoda error code.
 */
public interface EdgeMessageService {

    /**
     * Retrieve an EdgeMessage by its ID
     *
     * @param messageId The UUID of the EdgeMessage to retrieve
     * @return JsonNode containing the EdgeMessage data with header, metaData, and content fields, or null if not found (404)
     * @throws com.java_template.common.exception.CyodaOperationException if retrieval fails (typed, see the class Javadoc)
     */
    @Nullable
    JsonNode getMessageById(@NotNull UUID messageId);

    /**
     * Retrieve only the content field from an EdgeMessage
     *
     * @param messageId The UUID of the EdgeMessage to retrieve
     * @return JsonNode containing only the content field of the EdgeMessage, or null if message not found or content is missing/null
     * @throws JsonProcessingException if the content cannot be parsed as JSON
     * @throws com.java_template.common.exception.CyodaOperationException if retrieval fails (typed, see the class Javadoc)
     */
    @Nullable
    JsonNode getMessageContent(@NotNull UUID messageId) throws JsonProcessingException;

    /**
     * Create a new EdgeMessage with the given subject and content
     *
     * @param subject The subject/type of the EdgeMessage (e.g., "loan-tape-upload")
     * @param content The content payload as a JsonNode
     * @param metadata Optional metadata as a ObjectNode
     * @return The UUID of the created EdgeMessage
     * @throws com.java_template.common.exception.CyodaOperationException if creation fails (typed, see the class Javadoc)
     * @throws IllegalStateException if the response carries no entityIds
     */
    @NotNull
    UUID createMessage(@NotNull String subject, @NotNull JsonNode content,
                       @Nullable ObjectNode metadata);

    /**
     * Delete an EdgeMessage by its ID
     *
     * @param messageId The UUID of the EdgeMessage to delete
     * @return true if the message was deleted, false if it was not found (404)
     * @throws com.java_template.common.exception.CyodaOperationException if deletion fails (typed, see the class Javadoc)
     */
    boolean deleteMessage(@NotNull UUID messageId);
}

