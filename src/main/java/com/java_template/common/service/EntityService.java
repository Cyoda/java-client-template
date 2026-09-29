package com.java_template.common.service;

import com.java_template.common.dto.EntityWithMetadata;
import com.java_template.common.dto.PageResult;
import com.java_template.common.repository.SearchAndRetrievalParams;
import com.java_template.common.util.PointInTime;
import com.java_template.common.workflow.CyodaEntity;
import org.cyoda.cloud.api.common.model.GroupConditionDto;
import org.cyoda.cloud.api.event.common.EntityChangeMeta;
import org.cyoda.cloud.api.event.common.ModelSpec;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * ABOUTME: Core entity service interface providing CRUD operations and search capabilities
 * for Cyoda entities with performance-optimized method selection guidance.

 * METHOD SELECTION GUIDE:

 * FOR SINGLE ENTITY RETRIEVAL:
 * - Use getById() when you have the technical UUID (fastest, most efficient)
 * - Use findByBusinessId() when you have a business identifier (e.g., "CART-123", "PAY-456")

 * FOR BULK RETRIEVAL:
 * - Use findAll() for paginated retrieval of all entities (returns PageResult with searchId)
 * - Use streamAll() for streaming all entities (memory-efficient, auto-pagination)
 * - Use search() for paginated retrieval with conditions (returns PageResult with searchId)
 * - Use searchAsStream() for streaming entities with conditions (memory-efficient, auto-pagination)

 * FOR MUTATIONS:
 * - Use create() for new entities
 * - Use update() for existing entities with technical UUID
 * - Use updateByBusinessId() for existing entities with business identifier

 * PERFORMANCE NOTES:
 * - Technical UUID operations are fastest (direct lookup)
 * - Business ID operations require field search (slower)
 * - Paginated methods (findAll/search) support searchId for efficient multipage retrieval
 * - Streaming methods automatically handle pagination and are memory-efficient for large datasets
 * - Set inMemory=true in search operations for small result sets.

 * POINT IN TIME:
 * - Every pointInTime parameter has an OffsetDateTime overload that keeps full (nanosecond) precision;
 *   use it, e.g. with EntityChangeMeta.getTimeOfChange(). The java.util.Date overloads are kept for
 *   compatibility and delegate after converting to UTC; a Date truncates to milliseconds, so reading
 *   as-at a truncated change time can return the version before that change.
 * - Passing a bare null literal is ambiguous between the two overloads; cast it, e.g. (OffsetDateTime) null,
 *   or call the overload without pointInTime.
 */
public interface EntityService {

    // ========================================
    // PRIMARY RETRIEVAL METHODS (Use These)
    // ========================================

    /**
     * Get entity by technical UUID (FASTEST - use when you have the UUID)
     *
     * @param entityId Technical UUID from EntityWithMetadata.getMetadata().getId()
     * @param modelSpec Model specification containing name and version
     * @param entityClass Entity class type for deserialization
     * @return EntityWithMetadata with entity and metadata
     */
    <T extends CyodaEntity> EntityWithMetadata<T> getById(
            @NotNull UUID entityId,
            @NotNull ModelSpec modelSpec,
            @NotNull Class<T> entityClass
    );

    /**
     * Get entity by technical UUID at a specific point in time (FASTEST - use when you have the UUID)
     *
     * @param entityId Technical UUID from EntityWithMetadata.getMetadata().getId()
     * @param modelSpec Model specification containing name and version
     * @param entityClass Entity class type for deserialization
     * @param pointInTime Point in time to retrieve the entity as-at, at full precision (null for current state)
     * @return EntityWithMetadata with entity and metadata
     */
    <T extends CyodaEntity> EntityWithMetadata<T> getById(
            @NotNull UUID entityId,
            @NotNull ModelSpec modelSpec,
            @NotNull Class<T> entityClass,
            @Nullable OffsetDateTime pointInTime
    );

    /**
     * Millisecond {@link Date} variant of {@link #getById(UUID, ModelSpec, Class, OffsetDateTime)}.
     * Prefer the OffsetDateTime overload; see the class notes on point in time.
     */
    default <T extends CyodaEntity> EntityWithMetadata<T> getById(
            @NotNull UUID entityId,
            @NotNull ModelSpec modelSpec,
            @NotNull Class<T> entityClass,
            @Nullable Date pointInTime
    ) {
        return getById(entityId, modelSpec, entityClass, PointInTime.toOffsetDateTime(pointInTime));
    }

    /**
     * Find entity by business identifier (MEDIUM SPEED - use for user-facing IDs)
     * Examples: cartId="CART-123", paymentId="PAY-456", orderId="ORD-789"
     *
     * @param modelSpec Model specification containing name and version
     * @param businessId Business identifier value (e.g., "CART-123")
     * @param businessIdField Field name containing the business ID (e.g., "cartId")
     * @param entityClass Entity class type for deserialization
     * @return EntityWithMetadata with entity and metadata, or null if not found
     */
    <T extends CyodaEntity> EntityWithMetadata<T> findByBusinessId(
            @NotNull ModelSpec modelSpec,
            @NotNull String businessId,
            @NotNull String businessIdField,
            @NotNull Class<T> entityClass
    );

    /**
     * Find entity by business identifier at a specific point in time (MEDIUM SPEED - use for user-facing IDs)
     * Examples: cartId="CART-123", paymentId="PAY-456", orderId="ORD-789"
     *
     * @param modelSpec Model specification containing name and version
     * @param businessId Business identifier value (e.g., "CART-123")
     * @param businessIdField Field name containing the business ID (e.g., "cartId")
     * @param entityClass Entity class type for deserialization
     * @param pointInTime Point in time to retrieve the entity as-at, at full precision (null for current state)
     * @return EntityWithMetadata with entity and metadata, or null if not found
     */
    <T extends CyodaEntity> EntityWithMetadata<T> findByBusinessId(
            @NotNull ModelSpec modelSpec,
            @NotNull String businessId,
            @NotNull String businessIdField,
            @NotNull Class<T> entityClass,
            @Nullable OffsetDateTime pointInTime
    );

    /**
     * Millisecond {@link Date} variant of
     * {@link #findByBusinessId(ModelSpec, String, String, Class, OffsetDateTime)}.
     * Prefer the OffsetDateTime overload; see the class notes on point in time.
     */
    default <T extends CyodaEntity> EntityWithMetadata<T> findByBusinessId(
            @NotNull ModelSpec modelSpec,
            @NotNull String businessId,
            @NotNull String businessIdField,
            @NotNull Class<T> entityClass,
            @Nullable Date pointInTime
    ) {
        return findByBusinessId(modelSpec, businessId, businessIdField, entityClass,
                PointInTime.toOffsetDateTime(pointInTime));
    }

    /**
     * Find entity by business identifier, returning null on any exception (MEDIUM SPEED)
     * This method wraps findByBusinessId and catches all exceptions, returning null instead.
     * Use this when you want to check for entity existence without handling exceptions.
     *
     * @param modelSpec Model specification containing name and version
     * @param businessId Business identifier value (e.g., "CART-123")
     * @param businessIdField Field name containing the business ID (e.g., "cartId")
     * @param entityClass Entity class type for deserialization
     * @return EntityWithMetadata with entity and metadata, or null if not found or on error
     */
    <T extends CyodaEntity> EntityWithMetadata<T> findByBusinessIdOrNull(
            @NotNull ModelSpec modelSpec,
            @NotNull String businessId,
            @NotNull String businessIdField,
            @NotNull Class<T> entityClass
    );

    /**
     * Find entity by composite business key (MEDIUM SPEED - use for multi-field unique identifiers)
     * Searches for an entity using multiple field values that together form a unique business identifier.
     *
     * Example: Finding a LoanTapeItem by dataset_id + loan_id
     * <pre>{@code
     * Map<String, Function<LoanTapeItem, Object>> extractors = Map.of(
     *     "datasetId", LoanTapeItem::getDatasetId,
     *     "loanId", LoanTapeItem::getLoanId
     * );
     * EntityWithMetadata<LoanTapeItem> result = entityService.findByCompositeKey(
     *     modelSpec, entity, extractors, LoanTapeItem.class
     * );
     * }</pre>
     *
     * @param modelSpec Model specification containing name and version
     * @param entity Entity instance with populated business key fields
     * @param businessIdExtractors Map of field names to functions that extract business key field values
     * @param entityClass Entity class type for deserialization
     * @return EntityWithMetadata with entity and metadata, or null if not found
     */
    <T extends CyodaEntity> EntityWithMetadata<T> findByCompositeKey(
            @NotNull ModelSpec modelSpec,
            @NotNull T entity,
            @NotNull java.util.Map<String, java.util.function.Function<T, Object>> businessIdExtractors,
            @NotNull Class<T> entityClass
    );

    /**
     * Find entity by composite business key, returning null on any exception (MEDIUM SPEED)
     * This method wraps findByCompositeKey and catches all exceptions, returning null instead.
     * Use this when you want to check for entity existence without handling exceptions.
     *
     * @param modelSpec Model specification containing name and version
     * @param entity Entity instance with populated business key fields
     * @param businessIdExtractors Map of field names to functions that extract business key field values
     * @param entityClass Entity class type for deserialization
     * @return EntityWithMetadata with entity and metadata, or null if not found or on error
     */
    <T extends CyodaEntity> EntityWithMetadata<T> findByCompositeKeyOrNull(
            @NotNull ModelSpec modelSpec,
            @NotNull T entity,
            @NotNull java.util.Map<String, java.util.function.Function<T, Object>> businessIdExtractors,
            @NotNull Class<T> entityClass
    );

    /**
     * Get all entities with pagination support using PageResult.
     * Returns pagination metadata including searchId for subsequent page requests.
     * Pass the searchId from a previous PageResult to read further pages of that same server-side
     * snapshot; no new search is started. The snapshot expires on the server: an expired or unknown
     * searchId fails (the call throws) rather than silently re-running the search, so start a new
     * search (searchId null) in that case.
     *
     * @param modelSpec Model specification containing name and version
     * @param entityClass Entity class type for deserialization
     * @param params Search and retrieval parameters
     * @return PageResult with entities, pagination metadata, and searchId
     */
    <T extends CyodaEntity> PageResult<EntityWithMetadata<T>> findAll(
            @NotNull ModelSpec modelSpec,
            @NotNull Class<T> entityClass,
            @NotNull SearchAndRetrievalParams params
    );

    default <T extends CyodaEntity> PageResult<EntityWithMetadata<T>> findAll(
            @NotNull ModelSpec modelSpec,
            @NotNull Class<T> entityClass
    ) {
        return findAll(modelSpec, entityClass, SearchAndRetrievalParams.defaults());
    }

    /**
     * Stream all entities for memory-efficient processing.
     * Automatically handles pagination internally and streams results.
     * The stream MUST be closed after use (use try-with-resources).
     *
     * @param modelSpec Model specification containing name and version
     * @param entityClass Entity class type for deserialization
     * @param params Search and retrieval parameters (pageSize, pointInTime, etc.)
     * @return Stream of EntityWithMetadata (must be closed after use)
     */
    <T extends CyodaEntity> Stream<EntityWithMetadata<T>> streamAll(
            @NotNull ModelSpec modelSpec,
            @NotNull Class<T> entityClass,
            @NotNull SearchAndRetrievalParams params
    );

    /**
     * Search entities with conditions using pagination support with PageResult.
     * Returns pagination metadata including searchId for subsequent page requests.
     * Pass the searchId from a previous PageResult to read further pages of that same server-side
     * snapshot; no new search is started. The snapshot expires on the server: an expired or unknown
     * searchId fails (the call throws) rather than silently re-running the search, so start a new
     * search (searchId null) in that case.
     *
     * @param modelSpec Model specification containing name and version
     * @param condition Search condition (use SearchConditionBuilder.group())
     * @param entityClass Entity class type for deserialization
     * @param params Search and retrieval parameters
     * @return PageResult with entities, pagination metadata, and searchId
     */
    <T extends CyodaEntity> PageResult<EntityWithMetadata<T>> search(
            @NotNull ModelSpec modelSpec,
            @NotNull GroupConditionDto condition,
            @NotNull Class<T> entityClass,
            @NotNull SearchAndRetrievalParams params
    );

    default <T extends CyodaEntity> PageResult<EntityWithMetadata<T>> search(
            @NotNull ModelSpec modelSpec,
            @NotNull GroupConditionDto condition,
            @NotNull Class<T> entityClass
    ) {
        return search(modelSpec, condition, entityClass, SearchAndRetrievalParams.defaults());
    }

    /**
     * Stream entities by condition for memory-efficient processing.
     * Automatically handles pagination internally and streams results.
     * The stream MUST be closed after use (use try-with-resources).
     *
     * @param modelSpec Model specification containing name and version
     * @param condition Search condition (use SearchConditionBuilder.group())
     * @param entityClass Entity class type for deserialization
     * @param params Search and retrieval parameters (pageSize, inMemory, pointInTime, etc.)
     * @return Stream of EntityWithMetadata (must be closed after use)
     */
    <T extends CyodaEntity> Stream<EntityWithMetadata<T>> searchAsStream(
            @NotNull ModelSpec modelSpec,
            @NotNull GroupConditionDto condition,
            @NotNull Class<T> entityClass,
            @NotNull SearchAndRetrievalParams params
    );

    /**
     * Get total count of entities for a model (FAST - for pagination metadata)
     * Uses Cyoda's entity statistics API.
     *
     * @param modelSpec Model specification containing name and version
     * @return Total count of entities
     */
    long getEntityCount(@NotNull ModelSpec modelSpec);

    /**
     * Get total count of entities for a model at a specific point in time (FAST - for pagination metadata)
     * Uses Cyoda's entity statistics API.
     *
     * @param modelSpec Model specification containing name and version
     * @param pointInTime Point in time to retrieve entity count as-at, at full precision (null for current state)
     * @return Total count of entities
     */
    long getEntityCount(@NotNull ModelSpec modelSpec, @Nullable OffsetDateTime pointInTime);

    /**
     * Millisecond {@link Date} variant of {@link #getEntityCount(ModelSpec, OffsetDateTime)}.
     * Prefer the OffsetDateTime overload; see the class notes on point in time.
     */
    default long getEntityCount(@NotNull ModelSpec modelSpec, @Nullable Date pointInTime) {
        return getEntityCount(modelSpec, PointInTime.toOffsetDateTime(pointInTime));
    }

    /**
     * Get entity statistics grouped by workflow state (FAST - uses index tables)
     * Returns a map where keys are state names and values are entity counts for each state.
     * Uses Cyoda's entity statistics API.
     *
     * @param modelSpec Model specification containing name and version
     * @return Map of state names to entity counts
     */
    java.util.Map<String, Long> getEntityStatsByState(@NotNull ModelSpec modelSpec);

    /**
     * Get entity statistics grouped by workflow state at a specific point in time (FAST - uses index tables)
     * Returns a map where keys are state names and values are entity counts for each state.
     * Uses Cyoda's entity statistics API.
     *
     * @param modelSpec Model specification containing name and version
     * @param pointInTime Point in time to retrieve statistics as-at, at full precision (null for current state)
     * @return Map of state names to entity counts
     */
    java.util.Map<String, Long> getEntityStatsByState(
            @NotNull ModelSpec modelSpec,
            @Nullable OffsetDateTime pointInTime
    );

    /**
     * Millisecond {@link Date} variant of {@link #getEntityStatsByState(ModelSpec, OffsetDateTime)}.
     * Prefer the OffsetDateTime overload; see the class notes on point in time.
     */
    default java.util.Map<String, Long> getEntityStatsByState(
            @NotNull ModelSpec modelSpec,
            @Nullable Date pointInTime
    ) {
        return getEntityStatsByState(modelSpec, PointInTime.toOffsetDateTime(pointInTime));
    }

    /**
     * Get entity statistics for specific workflow states (FAST - uses index tables)
     * Returns a map where keys are state names and values are entity counts for each state.
     * Only the specified states will be included in the result.
     * Uses Cyoda's entity statistics API.
     *
     * @param modelSpec Model specification containing name and version
     * @param states List of state names to get statistics for
     * @param pointInTime Point in time to retrieve statistics as-at, at full precision (null for current state)
     * @return Map of state names to entity counts
     */
    java.util.Map<String, Long> getEntityStatsByState(
            @NotNull ModelSpec modelSpec,
            @NotNull java.util.List<String> states,
            @Nullable OffsetDateTime pointInTime
    );

    /**
     * Millisecond {@link Date} variant of {@link #getEntityStatsByState(ModelSpec, List, OffsetDateTime)}.
     * Prefer the OffsetDateTime overload; see the class notes on point in time.
     */
    default java.util.Map<String, Long> getEntityStatsByState(
            @NotNull ModelSpec modelSpec,
            @NotNull java.util.List<String> states,
            @Nullable Date pointInTime
    ) {
        return getEntityStatsByState(modelSpec, states, PointInTime.toOffsetDateTime(pointInTime));
    }

    // ========================================
    // PRIMARY MUTATION METHODS (Use These)
    // ========================================

    /**
     * Save a new entity (CREATE operation)
     *
     * @param entity New entity to save
     * @return EntityWithMetadata with saved entity and metadata (including technical UUID)
     */
    <T extends CyodaEntity> EntityWithMetadata<T> create(@NotNull T entity);

    /**
     * Update existing entity by technical UUID (FASTEST - use when you have UUID)
     *
     * @param entityId Technical UUID from EntityWithMetadata.getMetadata().getId()
     * @param entity Updated entity data
     * @param transition Optional workflow transition name (null to stay in same state)
     * @return EntityWithMetadata with updated entity and metadata
     */
    <T extends CyodaEntity> EntityWithMetadata<T> update(
            @NotNull UUID entityId,
            @NotNull T entity,
            @Nullable String transition
    );

    /**
     * Update existing entity by business identifier (MEDIUM SPEED)
     *
     * @param entity Updated entity data (must contain business ID)
     * @param businessIdField Field name containing the business ID (e.g., "cartId")
     * @param transition Optional workflow transition name (null to stay in same state)
     * @return EntityWithMetadata with updated entity and metadata
     */
    <T extends CyodaEntity> EntityWithMetadata<T> updateByBusinessId(
            @NotNull T entity,
            @NotNull String businessIdField,
            @Nullable String transition
    );

    /**
     * Delete entity by technical UUID (FASTEST)
     *
     * @param entityId Technical UUID to delete
     * @return UUID of deleted entity
     */
    UUID deleteById(@NotNull UUID entityId);

    /**
     * Delete entity by business identifier (MEDIUM SPEED)
     *
     * @param modelSpec Model specification containing name and version
     * @param businessId Business identifier value
     * @param businessIdField Field name containing the business ID
     * @param entityClass Entity class type for deserialization
     * @return true if deleted, false if not found
     */
    <T extends CyodaEntity> boolean deleteByBusinessId(
            @NotNull ModelSpec modelSpec,
            @NotNull String businessId,
            @NotNull String businessIdField,
            @NotNull Class<T> entityClass
    );

    // ========================================
    // BATCH OPERATIONS (Use Sparingly)
    // ========================================

    /**
     * Save multiple entities in batch
     *
     * @param entities Collection of entities to save
     * @return List of EntityWithMetadata with saved entities and metadata
     */
    <T extends CyodaEntity> List<EntityWithMetadata<T>> save(@NotNull Collection<T> entities);

    /**
     * Save multiple entities in batch with transaction control parameters
     *
     * @param entities Collection of entities to save
     * @param transactionWindow Maximum number of entities per transaction (null for default)
     * @param transactionTimeoutMs Transaction timeout in milliseconds (null for default)
     * @return List of EntityWithMetadata with saved entities and metadata
     */
    <T extends CyodaEntity> List<EntityWithMetadata<T>> save(
            @NotNull Collection<T> entities,
            @Nullable Integer transactionWindow,
            @Nullable Long transactionTimeoutMs
    );

    /**
     * Update multiple entities in batch
     *
     * @param entities Collection of entities to update (must have id field)
     * @param transition Optional workflow transition name (null to stay in same state)
     * @return List of EntityWithMetadata with updated entities and metadata
     */
    <T extends CyodaEntity> List<EntityWithMetadata<T>> updateAll(
            @NotNull Collection<T> entities,
            @Nullable String transition
    );

    /**
     * Update multiple entities in batch with transaction control parameters
     *
     * @param entities Collection of entities to update (must have id field)
     * @param transition Optional workflow transition name (null to stay in same state)
     * @param transactionWindow Maximum number of entities per transaction (null for default)
     * @param transactionTimeoutMs Transaction timeout in milliseconds (null for default)
     * @return List of EntityWithMetadata with updated entities and metadata
     */
    <T extends CyodaEntity> List<EntityWithMetadata<T>> updateAll(
            @NotNull Collection<T> entities,
            @Nullable String transition,
            @Nullable Integer transactionWindow,
            @Nullable Long transactionTimeoutMs
    );

    /**
     * Delete all entities of a type (DANGEROUS - use with caution)
     *
     * @param modelSpec Model specification containing name and version
     * @return Number of entities deleted
     */
    Integer deleteAll(@NotNull ModelSpec modelSpec);

    // ========================================
    // METADATA OPERATIONS
    // ========================================

    /**
     * Get entity change history metadata
     * Retrieves metadata about all changes made to an entity over time.
     *
     * @param entityId Technical UUID of the entity
     * @return List of EntityChangeMeta with change history information
     */
    List<EntityChangeMeta> getEntityChangesMetadata(@NotNull UUID entityId);

    /**
     * Get entity change history metadata at a specific point in time
     * Retrieves metadata about all changes made to an entity up to a specific point in time.
     *
     * @param entityId Technical UUID of the entity
     * @param pointInTime Point in time to retrieve changes up to, at full precision (null for all changes)
     * @return List of EntityChangeMeta with change history information
     */
    List<EntityChangeMeta> getEntityChangesMetadata(
            @NotNull UUID entityId,
            @Nullable OffsetDateTime pointInTime
    );

    /**
     * Millisecond {@link Date} variant of {@link #getEntityChangesMetadata(UUID, OffsetDateTime)}.
     * Prefer the OffsetDateTime overload; see the class notes on point in time.
     */
    default List<EntityChangeMeta> getEntityChangesMetadata(
            @NotNull UUID entityId,
            @Nullable Date pointInTime
    ) {
        return getEntityChangesMetadata(entityId, PointInTime.toOffsetDateTime(pointInTime));
    }

}