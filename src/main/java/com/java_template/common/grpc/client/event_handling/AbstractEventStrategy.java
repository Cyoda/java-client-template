package com.java_template.common.grpc.client.event_handling;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.java_template.common.workflow.CyodaContextFactory;
import com.java_template.common.workflow.CyodaEventContext;
import com.java_template.common.workflow.OperationFactory;
import com.java_template.common.workflow.OperationSpecification;
import io.cloudevents.v1.proto.CloudEvent;
import org.cyoda.cloud.api.event.common.BaseEvent;
import org.cyoda.cloud.api.event.common.EntityMetadata;
import org.cyoda.cloud.api.event.common.Error;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * ABOUTME: Abstract base class for event handling strategies that provides common functionality
 * for processing CloudEvents. Subclasses implement specific behavior for different event types.
 */
public abstract class AbstractEventStrategy<
        TRequest extends BaseEvent,
        TResponse extends BaseEvent,
        TOperation extends OperationSpecification
        > implements EventHandlingStrategy<TResponse> {

    private static final Logger logger = LoggerFactory.getLogger(AbstractEventStrategy.class);

    protected final OperationFactory operationFactory;
    protected final ObjectMapper objectMapper;
    protected final CyodaContextFactory eventContextFactory;

    protected AbstractEventStrategy(
            OperationFactory operationFactory,
            ObjectMapper objectMapper,
            CyodaContextFactory eventContextFactory
    ) {
        this.operationFactory = operationFactory;
        this.objectMapper = objectMapper;
        this.eventContextFactory = eventContextFactory;
    }

    /**
     * Handles the given CloudEvent and returns the result.
     * Exception handling strategy is to catch all exceptions and return an error response.
     *
     * @param cloudEvent the CloudEvent to handle
     * @return TResponse
     */
    @Override
    public TResponse handleEvent(@NotNull CloudEvent cloudEvent) {
        String cloudEventType = cloudEvent.getType();
        logger.debug("[IN] Received event {}: \n{}", cloudEventType, cloudEvent.getTextData());

        CyodaEventContext<TRequest> context;
        try {
            context = eventContextFactory.createCyodaEventContext(cloudEvent, getRequestClass());
        } catch (JsonProcessingException e) {
            logger.error("JsonProcessingException when parsing CloudEvent into {}: {}", getRequestClass().getSimpleName(), cloudEvent, e);
            return returnErrorResponseFor(cloudEvent, e);
        }

        TRequest request = context.getEvent();
        try {

            TOperation operation = createOperationSpecification(request);
            String operationName = operation.operationName();

            logger.debug("Running {} {}: {}", operation.getClass().getSimpleName(), cloudEventType, operationName);

            return executeOperation(operation, request, context);

        } catch (Exception e) {
            logger.error("Error handling event: {}", cloudEvent, e);
            return returnErrorResponseFor(request, e);
        }
    }

    protected TResponse returnErrorResponseFor(TRequest request, Exception e) {
        TResponse errorResponse = createErrorResponse();
        errorResponse.setId(java.util.UUID.randomUUID().toString());
        errorResponse.setSuccess(false);
        setRequestIdInErrorResponse(errorResponse, requestIdOf(request));
        setEntityIdInErrorResponse(errorResponse, request);
        Error error = new Error();
        error.setMessage(e.getMessage());
        error.setCode("GENERAL_ERROR");
        errorResponse.setError(error);
        enrichErrorResponse(errorResponse);
        return errorResponse;
    }

    /**
     * Handles JsonProcessingException with error recovery.
     * Attempts to recover the requestId from potentially corrupted JSON.
     */
    protected TResponse returnErrorResponseFor(CloudEvent cloudEvent, JsonProcessingException e) {
        TResponse errorResponse = createErrorResponse();
        errorResponse.setId(java.util.UUID.randomUUID().toString());
        recoverEntityIdFromCloudEvent(cloudEvent).ifPresent(entityId -> setRecoveredEntityId(errorResponse, entityId));

        RequestIdRecoveryResult recoveryResult = recoverRequestIdFromCloudEvent(cloudEvent);
        if (recoveryResult.requestId().isPresent()) {
            String requestId = recoveryResult.requestId().get();
            setRequestIdInErrorResponse(errorResponse, requestId);
            logger.info("Set requestId {} in error response via recovery mechanism on corrupted JSON", requestId);
        } else {
            logger.warn("Could not recover requestId for error response: {}", recoveryResult.error());
        }

        errorResponse.setSuccess(false);
        Error error = new Error();
        error.setMessage(e.getMessage());
        error.setCode("JSON_PROCESSING_ERROR");
        errorResponse.setError(error);
        enrichErrorResponse(errorResponse);
        return errorResponse;
    }


    protected EntityMetadata parseForModelKey(JsonNode meta) throws JsonProcessingException {
        return objectMapper.treeToValue(meta, EntityMetadata.class);
    }

    /**
     * ABOUTME: Attempts to recover the requestId from a CloudEvent when JSON parsing fails.
     * Uses string-based regex patterns to search for the requestId field in potentially corrupted JSON.
     *
     * @param cloudEvent the CloudEvent containing potentially corrupted JSON data
     * @return RequestIdRecoveryResult containing the requestId if found and any error message
     */
    public static RequestIdRecoveryResult recoverRequestIdFromCloudEvent(CloudEvent cloudEvent) {
        if (cloudEvent == null) {
            return new RequestIdRecoveryResult(Optional.empty(), "CloudEvent is null, cannot recover requestId");
        }

        String textData = cloudEvent.getTextData();
        if (textData.trim().isEmpty()) {
            return new RequestIdRecoveryResult(Optional.empty(), "CloudEvent text data is empty, cannot recover requestId");
        }

        // Pattern to match "requestId" field with various quote styles and whitespace
        // Matches: "requestId": "value", 'requestId': 'value', "requestId":"value", etc.
        Pattern requestIdPattern = Pattern.compile(
                "[\"']?requestId[\"']?\\s*:\\s*[\"']([^\"'\\s,}]+)[\"']?",
                Pattern.CASE_INSENSITIVE
        );

        Matcher matcher = requestIdPattern.matcher(textData);
        if (matcher.find()) {
            String requestId = matcher.group(1);
            return new RequestIdRecoveryResult(Optional.of(requestId), null);
        }

        // Fallback: try to find any UUID-like pattern near "requestId" text
        // This handles cases where quotes might be corrupted but the value is still readable
        Pattern fallbackPattern = Pattern.compile(
                "requestId[^a-zA-Z0-9-]*([a-fA-F0-9]{8}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{12})",
                Pattern.CASE_INSENSITIVE
        );

        Matcher fallbackMatcher = fallbackPattern.matcher(textData);
        if (fallbackMatcher.find()) {
            String requestId = fallbackMatcher.group(1);
            return new RequestIdRecoveryResult(Optional.of(requestId), null);
        }

        // Final fallback: look for any string value after "requestId" that looks like an identifier
        Pattern generalPattern = Pattern.compile(
                "requestId[^a-zA-Z0-9-]*([a-zA-Z0-9][a-zA-Z0-9-_]{2,})",
                Pattern.CASE_INSENSITIVE
        );

        Matcher generalMatcher = generalPattern.matcher(textData);
        if (generalMatcher.find()) {
            String requestId = generalMatcher.group(1);
            return new RequestIdRecoveryResult(Optional.of(requestId), null);
        }

        return new RequestIdRecoveryResult(Optional.empty(), "Could not recover requestId from CloudEvent text data. No matching patterns found.");
    }

    /**
     * ABOUTME: Attempts to recover the entityId from a CloudEvent when JSON parsing fails.
     * Uses a string-based regex pattern to search for a UUID-shaped entityId field in
     * potentially corrupted JSON.
     *
     * @param cloudEvent the CloudEvent containing potentially corrupted JSON data
     * @return the recovered entityId, if any
     */
    public static Optional<String> recoverEntityIdFromCloudEvent(CloudEvent cloudEvent) {
        if (cloudEvent == null || cloudEvent.getTextData() == null || cloudEvent.getTextData().isBlank()) {
            return Optional.empty();
        }
        Matcher m = Pattern.compile(
                "[\"']?entityId[\"']?\\s*:\\s*[\"']?([a-fA-F0-9]{8}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{12})")
                .matcher(cloudEvent.getTextData());
        return m.find() ? Optional.of(m.group(1)) : Optional.empty();
    }

    /**
     * Adds additional error information to the response.
     */
    protected void enrichErrorResponse(TResponse errorResponse) {
        // No-op by default, can be overridden by subclasses
    }

    /**
     * Result record for request ID recovery operations containing the recovered ID and any error message.
     */
    public record RequestIdRecoveryResult(Optional<String> requestId, String error) { }

    // Abstract methods that subclasses must implement

    /**
     * Gets the request class for event context creation.
     */
    protected abstract Class<TRequest> getRequestClass();

    /**
     * Creates the operation specification from the request.
     */
    protected abstract TOperation createOperationSpecification(TRequest request) throws JsonProcessingException;

    /**
     * Executes the operation and returns the response future.
     */
    protected abstract TResponse executeOperation(TOperation operation, TRequest request, CyodaEventContext<TRequest> context);

    /**
     * Creates a new error response instance.
     */
    protected abstract TResponse createErrorResponse();

    /**
     * Sets the requestId in the error response.
     */
    protected abstract void setRequestIdInErrorResponse(TResponse errorResponse, String requestId);

    /** The originating callout's requestId, which the answer must echo (not the CloudEvent payload id). */
    protected abstract String requestIdOf(TRequest request);

    protected abstract void setEntityIdInErrorResponse(TResponse errorResponse, TRequest request);

    protected abstract void setRecoveredEntityId(TResponse errorResponse, String entityId);

}
