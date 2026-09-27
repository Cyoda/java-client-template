package com.java_template.common.serializer.jackson;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.java_template.common.config.CyodaObjectMapper;
import com.java_template.common.dto.EntityWithMetadata;
import com.java_template.common.serializer.SerializerEnum;
import com.java_template.common.serializer.ResponseBuilder;
import com.java_template.common.serializer.CriterionSerializer;
import com.java_template.common.workflow.CyodaEntity;
import org.cyoda.cloud.api.event.processing.EntityCriteriaCalculationRequest;
import org.jetbrains.annotations.NotNull;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * ABOUTME: Jackson-based implementation of CriterionSerializer providing JSON serialization
 * and deserialization for workflow criterion request and response handling.
 */
@Component
public class JacksonCriterionSerializer extends BaseJacksonSerializer<EntityCriteriaCalculationRequest>
        implements CriterionSerializer {

    /** Spring's constructor: payloads are read and written with the framework's wire mapper. */
    @Autowired
    public JacksonCriterionSerializer(CyodaObjectMapper wireMapper) {
        this(wireMapper.mapper());
    }

    /**
     * For tests and manual wiring. Pass a mapper that meets the cyoda-go contract, e.g.
     * {@code CyodaObjectMapper.standalone().mapper()} or {@code CyodaJackson.configure(new ObjectMapper())}.
     */
    public JacksonCriterionSerializer(ObjectMapper objectMapper) {
        super(objectMapper);
    }

    /**
     * Extracts a typed entity from the request payload and wraps it in EntityWithMetadata.
     * Uses Jackson ObjectMapper for JSON deserialization.
     * @param <T> the entity type
     * @param request the criterion calculation request
     * @param clazz the entity class for deserialization
     * @return EntityWithMetadata containing the entity and metadata
     */
    @Override
    public <T extends CyodaEntity> EntityWithMetadata<T> extractEntityWithMetadata(EntityCriteriaCalculationRequest request, Class<T> clazz) {
        return EntityWithMetadata.fromDataPayload(request.getPayload(), clazz, objectMapper);
    }

    /**
     * Extracts raw JSON payload from the request.
     * @param request the criterion calculation request
     * @return JsonNode containing the payload data
     */
    @Override
    public JsonNode extractPayload(EntityCriteriaCalculationRequest request) {
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
     * Creates a response builder for criterion responses.
     * @param request the criterion calculation request
     * @return CriterionResponseBuilder for building responses
     */
    @Override
    public ResponseBuilder.CriterionResponseBuilder responseBuilder(EntityCriteriaCalculationRequest request) {
        return ResponseBuilder.forCriterion(request);
    }

    /**
     * Gets the serializer type identifier.
     * @return the serializer type ("jackson")
     */
    @Override
    public String getType() {
        return SerializerEnum.JACKSON.getType();
    }

    /**
     * Validates the criterion calculation request.
     * @param request the request to validate
     * @throws IllegalArgumentException if the request or its payload is null
     */
    @Override
    protected void validateRequest(@NotNull EntityCriteriaCalculationRequest request) {
        if (request.getPayload() == null) {
            throw new IllegalArgumentException("Request payload cannot be null");
        }
        if (request.getPayload().getData() == null) {
            throw new IllegalArgumentException("Request payload data cannot be null");
        }
    }
}
