package com.java_template.common.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.java_template.common.config.CyodaObjectMapper;
import com.java_template.common.workflow.CyodaEntity;
import org.cyoda.cloud.api.event.common.DataPayload;
import org.cyoda.cloud.api.event.common.EntityMetadata;
import org.cyoda.cloud.api.event.common.ModelSpec;
import org.cyoda.cloud.api.event.entity.EntityTransactionResponse;
import org.jetbrains.annotations.NotNull;

import java.time.OffsetDateTime;
import java.util.*;

/**
 * ABOUTME: Generic wrapper that encapsulates both business entity and Cyoda metadata
 * using the Envelope/Wrapper pattern for clean API access to entity data and technical metadata.
 * JSON Structure:
 * {
 *   "entity": { ... business entity data ... },
 *   "metadata": { "id": "uuid", "state": "workflow_state", ... }
 * }
 * @param <T> The type of the business entity
 */
@SuppressWarnings("unused")
public record EntityWithMetadata<T extends CyodaEntity>(@JsonProperty("entity") T entity,
                                                        @JsonProperty("meta") EntityMetadata metadata) {

    // Constructor for internal use
    public EntityWithMetadata(T entity, EntityMetadata metadata) {
        this.entity = entity;
        this.metadata = metadata;
    }

    // Convenience methods for commonly accessed metadata fields

    /**
     * Gets the technical UUID of the entity.
     * @return the technical UUID, or null if metadata is not available
     */
    @JsonIgnore
    public UUID getId() {
        return metadata != null ? metadata.getId() : null;
    }

    /**
     * Gets the model specification containing entity name and version.
     * @return the ModelSpec, or null if metadata is not available
     */
    @JsonIgnore
    public ModelSpec getModelKey() {
        return metadata != null ? metadata.getModelKey() : null;
    }

    /**
     * Gets the current workflow state of the entity.
     * @return the workflow state, or null if metadata is not available
     */
    @JsonIgnore
    public String getState() {
        return metadata != null ? metadata.getState() : null;
    }

    /**
     * Gets the creation date of the entity.
     * @return the creation date, or null if metadata is not available
     */
    @JsonIgnore
    public OffsetDateTime getCreationDate() {
        return metadata != null ? metadata.getCreationDate() : null;
    }

    /**
     * Gets the transition name used for the latest save operation.
     * @return the transition name, or null if metadata is not available
     */
    @JsonIgnore
    public String getTransitionForLatestSave() {
        return metadata != null ? metadata.getTransitionForLatestSave() : null;
    }

    /**
     * Creates a new builder for constructing EntityWithMetadata instances.
     * @param <T> the entity type
     * @return a new Builder instance
     */
    public static <T extends CyodaEntity> Builder<T> builder() {
        return new Builder<>();
    }

    /**
     * Builder class for constructing EntityWithMetadata instances using the builder pattern.
     * @param <T> the entity type
     */
    public static class Builder<T extends CyodaEntity> {
        private T entity;
        private EntityMetadata metadata;

        /**
         * Sets the entity for this builder.
         * @param entity the business entity
         * @return this builder for chaining
         */
        public Builder<T> entity(T entity) {
            this.entity = entity;
            return this;
        }

        /**
         * Sets the metadata for this builder.
         * @param metadata the entity metadata
         * @return this builder for chaining
         */
        public Builder<T> metadata(EntityMetadata metadata) {
            this.metadata = metadata;
            return this;
        }

        /**
         * Builds the EntityWithMetadata instance.
         * @return a new EntityWithMetadata instance
         */
        public EntityWithMetadata<T> build() {
            return new EntityWithMetadata<>(entity, metadata);
        }
    }

    /**
     * Factory method for creating EntityWithMetadata from a DataPayload.
     * Used internally by serializers to convert request payloads to typed entities. The entity data is read with
     * the app's mapper ({@link CyodaObjectMapper#entities()}), the Cyoda metadata with the protocol mapper
     * ({@link CyodaObjectMapper#protocol()}).
     * @param <T> the entity type
     * @param payload the DataPayload containing entity data and metadata
     * @param entityClass the entity class for deserialization
     * @param mappers the framework's mappers
     * @return a new EntityWithMetadata instance
     */
    public static <T extends CyodaEntity> EntityWithMetadata<T> fromDataPayload(
            DataPayload payload,
            Class<T> entityClass,
            CyodaObjectMapper mappers) {

        T entity = mappers.entities().convertValue(payload.getData(), entityClass);
        EntityMetadata metadata = payload.getMeta() != null
                ? mappers.protocol().convertValue(payload.getMeta(), EntityMetadata.class)
                : new EntityMetadata();

        return new EntityWithMetadata<>(entity, metadata);
    }

    /**
     * Factory method for creating EntityWithMetadata from an EntityTransactionResponse.
     * Used for save/update operations to wrap the entity with transaction metadata.
     * @param <T> the entity type
     * @param response the EntityTransactionResponse from save/update operations
     * @param entity the entity that was saved/updated
     * @param objectMapper the ObjectMapper for JSON conversion
     * @return a new EntityWithMetadata instance with transaction metadata
     */
    public static <T extends CyodaEntity> EntityWithMetadata<T> fromTransactionResponse(
            EntityTransactionResponse response,
            T entity,
            ObjectMapper objectMapper) {

        // Create basic metadata from transaction info
        EntityMetadata metadata = new EntityMetadata();
        if (response.getTransactionInfo() != null && !response.getTransactionInfo().getEntityIds().isEmpty()) {
            metadata.setId(response.getTransactionInfo().getEntityIds().getFirst());
        }

        return new EntityWithMetadata<>(entity, metadata);
    }

    /**
     * Factory method for creating a list of EntityWithMetadata from a single EntityTransactionResponse.
     * Used for batch save operations to wrap multiple entities with their transaction metadata.
     * @param <T> the entity type
     * @param response the EntityTransactionResponse from batch save operations
     * @param entities the collection of entities that were saved
     * @param objectMapper the ObjectMapper for JSON conversion
     * @return a list of EntityWithMetadata instances with transaction metadata
     */
    public static <T extends CyodaEntity> List<EntityWithMetadata<T>> fromTransactionResponseList(
            EntityTransactionResponse response,
            Collection<T> entities,
            ObjectMapper objectMapper) {

        List<UUID> entityIds = response.getTransactionInfo() != null ?
                response.getTransactionInfo().getEntityIds() : List.of();

        return assembleEntitiesWithMetadata(entities, entityIds);
    }

    /**
     * Factory method for creating a list of EntityWithMetadata from multiple EntityTransactionResponses.
     * Used for batch update operations to wrap multiple entities with their transaction metadata.
     * @param <T> the entity type
     * @param responses the list of EntityTransactionResponse from batch update operations
     * @param entities the collection of entities that were updated
     * @param objectMapper the ObjectMapper for JSON conversion
     * @return a list of EntityWithMetadata instances with transaction metadata
     */
    public static <T extends CyodaEntity> List<EntityWithMetadata<T>> fromTransactionResponseList(
            List<EntityTransactionResponse> responses,
            Collection<T> entities,
            ObjectMapper objectMapper) {

        List<UUID> allEntityIds = new ArrayList<>();
        for (EntityTransactionResponse response : responses) {
            if (response.getTransactionInfo() != null) {
                allEntityIds.addAll(response.getTransactionInfo().getEntityIds());
            }
        }

        return assembleEntitiesWithMetadata(entities, allEntityIds);
    }

    private static <T extends CyodaEntity> @NotNull List<EntityWithMetadata<T>> assembleEntitiesWithMetadata(Collection<T> entities, List<UUID> allEntityIds) {
        List<T> entitiesList = new ArrayList<>(entities);
        List<EntityWithMetadata<T>> entityWithMetadatas = new ArrayList<>();

        for (int i = 0; i < entitiesList.size(); i++) {
            EntityMetadata metadata = new EntityMetadata();
            if (i < allEntityIds.size()) {
                metadata.setId(allEntityIds.get(i));
            }
            entityWithMetadatas.add(new EntityWithMetadata<>(entitiesList.get(i), metadata));
        }

        return entityWithMetadatas;
    }

}
