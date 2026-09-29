package com.java_template.common.serializer.jackson;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.java_template.common.config.CyodaObjectMapper;
import com.java_template.common.dto.EntityWithMetadata;
import com.java_template.common.serializer.SerializerEnum;
import com.java_template.common.serializer.ResponseBuilder;
import com.java_template.common.serializer.ProcessorSerializer;
import com.java_template.common.workflow.CyodaEntity;
import org.cyoda.cloud.api.event.processing.EntityProcessorCalculationRequest;
import org.jetbrains.annotations.NotNull;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * ABOUTME: Jackson-based implementation of ProcessorSerializer providing JSON serialization
 * and deserialization for workflow processor request and response handling.
 */
@Component
public class JacksonProcessorSerializer extends BaseJacksonSerializer<EntityProcessorCalculationRequest>
        implements ProcessorSerializer {

    /** Spring's constructor: entity data with the app's mapper, Cyoda metadata with the protocol mapper. */
    @Autowired
    public JacksonProcessorSerializer(CyodaObjectMapper mappers) {
        super(mappers);
    }

    /**
     * For tests and manual wiring: {@code entityMapper} converts the entities (as the app's primary mapper does
     * under Spring), and Cyoda metadata uses the framework's protocol mapper.
     */
    public JacksonProcessorSerializer(ObjectMapper entityMapper) {
        this(CyodaObjectMapper.of(entityMapper));
    }

    /**
     * Extracts a typed entity from the request payload and wraps it in EntityWithMetadata.
     * Uses Jackson ObjectMapper for JSON deserialization.
     * @param <T> the entity type
     * @param request the processor calculation request
     * @param clazz the entity class for deserialization
     * @return EntityWithMetadata containing the entity and metadata
     */
    @Override
    public <T extends CyodaEntity> EntityWithMetadata<T> extractEntityWithMetadata(EntityProcessorCalculationRequest request, Class<T> clazz) {
        return EntityWithMetadata.fromDataPayload(request.getPayload(), clazz, mappers);
    }

    /**
     * Extracts raw JSON payload from the request.
     * @param request the processor calculation request
     * @return JsonNode containing the payload data
     */
    @Override
    public JsonNode extractPayload(EntityProcessorCalculationRequest request) {
        return super.extractPayload(request, req -> req.getPayload().getData());
    }

    /**
     * Converts a CyodaEntity to JsonNode using Jackson ObjectMapper.
     * @param <T> the entity type
     * @param entity the entity to convert
     * @return JsonNode representation of the entity
     */
    @Override
    public <T extends CyodaEntity> JsonNode entityToJsonNode(T entity) {
        return super.entityToJsonNode(entity);
    }

    /**
     * Creates a response builder for processor responses.
     * @param request the processor calculation request
     * @return ProcessorResponseBuilder for building responses
     */
    @Override
    public ResponseBuilder.ProcessorResponseBuilder responseBuilder(EntityProcessorCalculationRequest request) {
        return ResponseBuilder.forProcessor(request);
    }

    /**
     * Gets the serializer type identifier.
     * @return the serializer type ("jackson")
     */
    @Override
    public String getType() {
        return SerializerEnum.JACKSON.getType();
    }

    @Override
    protected void validateRequest(@NotNull EntityProcessorCalculationRequest request) {
        if (request.getPayload() == null) {
            throw new IllegalArgumentException("Request payload cannot be null");
        }
        if (request.getPayload().getData() == null) {
            throw new IllegalArgumentException("Request payload data cannot be null");
        }
    }
}
