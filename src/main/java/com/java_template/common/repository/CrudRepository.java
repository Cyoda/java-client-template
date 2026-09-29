package com.java_template.common.repository;

import com.java_template.common.dto.PageResult;
import org.cyoda.cloud.api.common.model.GroupConditionDto;
import org.cyoda.cloud.api.event.common.DataPayload;
import org.cyoda.cloud.api.event.common.ModelSpec;
import org.cyoda.cloud.api.event.entity.EntityDeleteAllResponse;
import org.cyoda.cloud.api.event.entity.EntityDeleteResponse;
import org.cyoda.cloud.api.event.entity.EntityTransactionResponse;
import org.cyoda.cloud.api.event.entity.EntityTransitionResponse;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;


/**
 * ABOUTME: Repository interface defining CRUD operations for entity management
 * with asynchronous CompletableFuture-based API and Cyoda platform integration.
 */
public interface CrudRepository {

    /**
     * Deletes an entity by its unique identifier.
     *
     * @param id the unique identifier of the entity to delete
     * @return CompletableFuture containing the delete response
     */
    CompletableFuture<EntityDeleteResponse> deleteById(@NotNull UUID id);

    /**
     * Deletes all entities matching the specified model specification.
     *
     * @param modelSpec the model specification defining which entities to delete
     * @return CompletableFuture containing list of delete responses
     */
    CompletableFuture<List<EntityDeleteAllResponse>> deleteAll(
            @NotNull ModelSpec modelSpec
    );

    /**
     * Finds all entities matching the model specification with pagination support.
     *
     * @param modelSpec the model specification to match
     * @param params search and retrieval parameters
     * @return CompletableFuture containing paginated results
     */
    CompletableFuture<PageResult<DataPayload>> findAll(
            @NotNull ModelSpec modelSpec,
            @NotNull SearchAndRetrievalParams params
    );

    /**
     * Finds an entity by its unique identifier.
     *
     * @param id the unique identifier of the entity
     * @return CompletableFuture containing the entity data payload
     */
    CompletableFuture<DataPayload> findById(@NotNull UUID id);

    /**
     * Finds an entity by its unique identifier at a specific point in time.
     *
     * @param id the unique identifier of the entity
     * @param pointInTime timestamp for historical data retrieval
     * @return CompletableFuture containing the entity data payload
     */
    CompletableFuture<DataPayload> findById(@NotNull UUID id, @Nullable OffsetDateTime pointInTime);

    /**
     * Gets the count of entities matching the model specification. This is a fast operation,
     * on index tables
     *
     * @param modelSpec the model specification to match
     * @return CompletableFuture containing the entity count
     */
    CompletableFuture<Long> getEntityCount(@NotNull ModelSpec modelSpec);

    /**
     * Gets the count of entities matching the model specification at a specific point in time. This is a fast operation,
     * on index tables
     *
     * @param modelSpec the model specification to match
     * @param pointInTime timestamp for historical data retrieval
     * @return CompletableFuture containing the entity count
     */
    CompletableFuture<Long> getEntityCount(@NotNull ModelSpec modelSpec, @Nullable OffsetDateTime pointInTime);

    /**
     * Gets entity statistics grouped by workflow state. This is a fast operation on index tables.
     * Returns a map where keys are state names and values are entity counts for each state.
     *
     * @param modelSpec the model specification to match
     * @return CompletableFuture containing a map of state names to entity counts
     */
    CompletableFuture<java.util.Map<String, Long>> getEntityStatsByState(@NotNull ModelSpec modelSpec);

    /**
     * Gets entity statistics grouped by workflow state at a specific point in time.
     * This is a fast operation on index tables.
     * Returns a map where keys are state names and values are entity counts for each state.
     *
     * @param modelSpec the model specification to match
     * @param pointInTime timestamp for historical data retrieval
     * @return CompletableFuture containing a map of state names to entity counts
     */
    CompletableFuture<java.util.Map<String, Long>> getEntityStatsByState(
            @NotNull ModelSpec modelSpec,
            @Nullable OffsetDateTime pointInTime
    );

    /**
     * Gets entity statistics for specific workflow states. This is a fast operation on index tables.
     * Returns a map where keys are state names and values are entity counts for each state.
     * Only the specified states will be included in the result.
     *
     * @param modelSpec the model specification to match
     * @param states list of state names to get statistics for
     * @param pointInTime optional timestamp for historical data retrieval
     * @return CompletableFuture containing a map of state names to entity counts
     */
    CompletableFuture<java.util.Map<String, Long>> getEntityStatsByState(
            @NotNull ModelSpec modelSpec,
            @NotNull List<String> states,
            @Nullable OffsetDateTime pointInTime
    );

    /**
     * Retrieves metadata about entity changes for a specific entity.
     *
     * @param entityId the unique identifier of the entity
     * @param pointInTime optional timestamp for historical metadata retrieval
     * @return CompletableFuture containing list of entity change metadata
     */
    CompletableFuture<List<org.cyoda.cloud.api.event.common.EntityChangeMeta>> getEntityChangesMetadata(
            @NotNull UUID entityId,
            @Nullable OffsetDateTime pointInTime
    );

    /**
     * Finds all entities matching the model specification and criteria with pagination support.
     *
     * @param modelSpec the model specification to match
     * @param criteria the group condition criteria to apply
     * @param params search and retrieval parameters
     * @return CompletableFuture containing paginated results
     */
    CompletableFuture<PageResult<DataPayload>> findAllByCriteria(
            @NotNull ModelSpec modelSpec,
            @NotNull GroupConditionDto criteria,
            @NotNull SearchAndRetrievalParams params
    );

    /**
     * Saves a new entity with the specified model specification.
     *
     * @param <T> the entity type
     * @param modelSpec the model specification for the entity
     * @param entity the entity to save
     * @return CompletableFuture containing the transaction response
     */
    <T> CompletableFuture<EntityTransactionResponse> save(
            @NotNull ModelSpec modelSpec,
            @NotNull T entity
    );

    /**
     * Saves multiple entities with the specified model specification.
     *
     * @param <T> the entity type
     * @param modelSpec the model specification for the entities
     * @param entity the collection of entities to save
     * @return CompletableFuture containing the transaction response
     */
    <T> CompletableFuture<EntityTransactionResponse> saveAll(
            @NotNull ModelSpec modelSpec,
            @NotNull Collection<T> entity
    );

    /**
     * Saves multiple entities with the specified model specification and transaction settings.
     *
     * @param <T> the entity type
     * @param modelSpec the model specification for the entities
     * @param entities the collection of entities to save
     * @param transactionWindow optional transaction window size
     * @param transactionTimeoutMs optional transaction timeout in milliseconds
     * @return CompletableFuture containing the transaction responses
     */
    <T> CompletableFuture<List<EntityTransactionResponse>> saveAll(
            @NotNull ModelSpec modelSpec,
            @NotNull Collection<T> entities,
            @Nullable Integer transactionWindow,
            @Nullable Long transactionTimeoutMs
    );

    /**
     * Updates an existing entity by its unique identifier.
     *
     * @param <T> the entity type
     * @param id the unique identifier of the entity to update
     * @param entity the updated entity data
     * @param transition optional state transition to apply
     * @return CompletableFuture containing the transaction response
     */
    <T> CompletableFuture<EntityTransactionResponse> update(
            @NotNull UUID id,
            @NotNull T entity,
            @Nullable String transition
    );

    /**
     * Updates multiple entities with optional state transition.
     *
     * @param <T> the entity type
     * @param entities the collection of entities to update
     * @param transition optional state transition to apply
     * @return CompletableFuture containing list of transaction responses
     */
    <T> CompletableFuture<List<EntityTransactionResponse>> updateAll(
            @NotNull Collection<T> entities,
            @Nullable String transition
    );

    /**
     * Updates multiple entities with optional state transition and transaction settings.
     *
     * @param <T> the entity type
     * @param entities the collection of entities to update
     * @param transition optional state transition to apply
     * @param transactionWindow optional transaction window size
     * @param transactionTimeoutMs optional transaction timeout in milliseconds
     * @return CompletableFuture containing list of transaction responses
     */
    <T> CompletableFuture<List<EntityTransactionResponse>> updateAll(
            @NotNull Collection<T> entities,
            @Nullable String transition,
            @Nullable Integer transactionWindow,
            @Nullable Long transactionTimeoutMs
    );

    /**
     * Applies a state transition to an entity.
     *
     * @param entityId the unique identifier of the entity
     * @param transitionName the name of the transition to apply
     * @return CompletableFuture containing the transition response
     */
    CompletableFuture<EntityTransitionResponse> applyTransition(@NotNull UUID entityId, @NotNull String transitionName);
}