package com.java_template.common.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.java_template.common.config.CyodaObjectMapper;
import com.java_template.common.workflow.CyodaEntity;
import org.cyoda.cloud.api.event.common.DataPayload;
import org.cyoda.cloud.api.event.common.EntityMetadata;
import org.cyoda.cloud.api.event.common.ModelSpec;

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

}
