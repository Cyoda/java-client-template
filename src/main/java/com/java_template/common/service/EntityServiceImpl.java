package com.java_template.common.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.java_template.common.call.CyodaCallContext;
import com.java_template.common.call.CyodaCallContexts;
import com.java_template.common.config.CyodaObjectMapper;
import com.java_template.common.dto.EntityWithMetadata;
import com.java_template.common.dto.PageResult;
import com.java_template.common.repository.CrudRepository;
import com.java_template.common.repository.SearchAndRetrievalParams;
import com.java_template.common.util.Futures;
import com.java_template.common.workflow.CyodaEntity;
import org.cyoda.cloud.api.common.model.*;
import org.cyoda.cloud.api.event.common.DataPayload;
import org.cyoda.cloud.api.event.common.EntityChangeMeta;
import org.cyoda.cloud.api.event.common.ModelSpec;
import org.cyoda.cloud.api.event.entity.EntityDeleteAllResponse;
import org.cyoda.cloud.api.event.entity.EntityDeleteResponse;
import org.cyoda.cloud.api.event.entity.EntityTransactionResponse;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.*;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

/**
 * ABOUTME: Implementation of EntityService providing concrete CRUD operations
 * and search functionality backed by CrudRepository and Cyoda platform integration.
 *
 * <p>Each public method builds its {@link CyodaCallContext} exactly once, with
 * {@link CyodaCallContexts#current()}, and passes it to every repository call the operation makes,
 * including the later pages of a stream (spec §4.2). Inside a callout scope, the entity written by
 * create/update/save/updateAll is reloaded as the joined transaction's latest view, without a point in
 * time (spec clarification 3).
 *
 * <p>Every repository future is joined with {@link Futures#joinUnwrapped}, so failures leave the public
 * methods as the typed Cyoda exceptions (spec §4.4), never wrapped in a CompletionException.
 */
@Service
public class EntityServiceImpl implements EntityService {

    private final Logger logger = LoggerFactory.getLogger(this.getClass());

    private final CrudRepository repository;
    private final ObjectMapper objectMapper;
    private final CyodaCallContexts callContexts;

    public EntityServiceImpl(
            final CrudRepository repository,
            final CyodaObjectMapper wireMapper,
            final CyodaCallContexts callContexts
    ) {
        this.repository = repository;
        this.objectMapper = wireMapper.mapper();
        this.callContexts = callContexts;
    }

    // ========================================
    // PRIMARY RETRIEVAL METHODS IMPLEMENTATION
    // ========================================

    @Override
    public <T extends CyodaEntity> EntityWithMetadata<T> getById(
            @NotNull final UUID entityId,
            @NotNull final ModelSpec modelSpec,
            @NotNull final Class<T> entityClass
    ) {
        return getById(callContexts.current(), entityId, modelSpec, entityClass, null);
    }

    /**
     * Reload by exact point in time. Also used by create()/update()/updateByBusinessId()/updateAll(),
     * which pass {@link EntityChangeMeta#getTimeOfChange()} untouched so the reload sees exactly the
     * transaction just committed (a Date round-trip would truncate it to milliseconds).
     */
    @Override
    public <T extends CyodaEntity> EntityWithMetadata<T> getById(
            @NotNull final UUID entityId,
            @NotNull final ModelSpec modelSpec,
            @NotNull final Class<T> entityClass,
            @Nullable final OffsetDateTime pointInTime
    ) {
        return getById(callContexts.current(), entityId, modelSpec, entityClass, pointInTime);
    }

    private <T extends CyodaEntity> EntityWithMetadata<T> getById(
            final CyodaCallContext ctx,
            final UUID entityId,
            final ModelSpec modelSpec,
            final Class<T> entityClass,
            @Nullable final OffsetDateTime pointInTime
    ) {
        DataPayload payload = Futures.joinUnwrapped(repository.findById(ctx, entityId, pointInTime));
        return EntityWithMetadata.fromDataPayload(payload, entityClass, objectMapper);
    }

    @Override
    public <T extends CyodaEntity> EntityWithMetadata<T> findByBusinessId(
            @NotNull final ModelSpec modelSpec,
            @NotNull final String businessId,
            @NotNull final String businessIdField,
            @NotNull final Class<T> entityClass
    ) {
        return findByBusinessId(callContexts.current(), modelSpec, businessId, businessIdField, entityClass, null);
    }

    @Override
    public <T extends CyodaEntity> EntityWithMetadata<T> findByBusinessId(
            @NotNull final ModelSpec modelSpec,
            @NotNull final String businessId,
            @NotNull final String businessIdField,
            @NotNull final Class<T> entityClass,
            @Nullable final OffsetDateTime pointInTime
    ) {
        return findByBusinessId(callContexts.current(), modelSpec, businessId, businessIdField, entityClass, pointInTime);
    }

    private <T extends CyodaEntity> EntityWithMetadata<T> findByBusinessId(
            final CyodaCallContext ctx,
            final ModelSpec modelSpec,
            final String businessId,
            final String businessIdField,
            final Class<T> entityClass,
            @Nullable final OffsetDateTime pointInTime
    ) {
        SimpleConditionDto simpleCondition = new SimpleConditionDto()
                .jsonPath("$." + businessIdField)
                .operatorType(SimpleConditionDto.OperatorTypeEnum.EQUALS)
                .value(objectMapper.valueToTree(businessId));

        GroupConditionDto condition = new GroupConditionDto()
                .operator(GroupConditionDto.OperatorEnum.AND)
                .conditions(List.of(simpleCondition));

        PageResult<EntityWithMetadata<T>> result = search(
                ctx,
                modelSpec,
                condition,
                entityClass,
                SearchAndRetrievalParams.builder()
                        .pageSize(1)
                        .pageNumber(0)
                        .pointInTime(pointInTime)
                        .inMemory(true)
                        .build()
        );

        return result.data().isEmpty() ? null : result.data().getFirst();
    }

    @Override
    public <T extends CyodaEntity> EntityWithMetadata<T> findByBusinessIdOrNull(
            @NotNull final ModelSpec modelSpec,
            @NotNull final String businessId,
            @NotNull final String businessIdField,
            @NotNull final Class<T> entityClass
    ) {
        // null means "no match" only: not-found is an empty result, and every failure propagates
        // (already unwrapped from CompletionException by Futures.joinUnwrapped).
        return findByBusinessId(callContexts.current(), modelSpec, businessId, businessIdField, entityClass, null);
    }

    @Override
    public <T extends CyodaEntity> EntityWithMetadata<T> findByCompositeKey(
            @NotNull final ModelSpec modelSpec,
            @NotNull final T entity,
            @NotNull final Map<String, java.util.function.Function<T, Object>> businessIdExtractors,
            @NotNull final Class<T> entityClass
    ) {
        return findByCompositeKey(callContexts.current(), modelSpec, entity, businessIdExtractors, entityClass);
    }

    private <T extends CyodaEntity> EntityWithMetadata<T> findByCompositeKey(
            final CyodaCallContext ctx,
            final ModelSpec modelSpec,
            final T entity,
            final Map<String, java.util.function.Function<T, Object>> businessIdExtractors,
            final Class<T> entityClass
    ) {
        // Build a list of SimpleConditions for each business key field
        List<GroupConditionDtoAllOfConditions> simpleConditions = new ArrayList<>();

        for (Map.Entry<String, java.util.function.Function<T, Object>> entry : businessIdExtractors.entrySet()) {
            String fieldName = entry.getKey();
            java.util.function.Function<T, Object> extractor = entry.getValue();
            Object value = extractor.apply(entity);

            SimpleConditionDto condition = new SimpleConditionDto()
                    .jsonPath("$." + fieldName)
                    .operatorType(SimpleConditionDto.OperatorTypeEnum.EQUALS)
                    .value(objectMapper.valueToTree(value));

            simpleConditions.add(condition);
        }

        // Combine all conditions with AND operator
        GroupConditionDto groupCondition = new GroupConditionDto()
                .operator(GroupConditionDto.OperatorEnum.AND)
                .conditions(simpleConditions);

        // Search with the composite condition
        PageResult<EntityWithMetadata<T>> result = search(
                ctx, modelSpec, groupCondition, entityClass,
                SearchAndRetrievalParams.builder().pageSize(1).pageNumber(0).inMemory(true).build()
        );

        return result.data().isEmpty() ? null : result.data().getFirst();
    }

    @Override
    public <T extends CyodaEntity> EntityWithMetadata<T> findByCompositeKeyOrNull(
            @NotNull final ModelSpec modelSpec,
            @NotNull final T entity,
            @NotNull final Map<String, java.util.function.Function<T, Object>> businessIdExtractors,
            @NotNull final Class<T> entityClass
    ) {
        // null means "no match" only: not-found is an empty result, and every failure propagates
        // (already unwrapped from CompletionException by Futures.joinUnwrapped).
        return findByCompositeKey(callContexts.current(), modelSpec, entity, businessIdExtractors, entityClass);
    }

    @Override
    public <T extends CyodaEntity> PageResult<EntityWithMetadata<T>> findAll(
            @NotNull final ModelSpec modelSpec,
            @NotNull final Class<T> entityClass,
            @NotNull final SearchAndRetrievalParams params
    ) {
        return findAll(callContexts.current(), modelSpec, entityClass, params);
    }

    private <T extends CyodaEntity> PageResult<EntityWithMetadata<T>> findAll(
            final CyodaCallContext ctx,
            final ModelSpec modelSpec,
            final Class<T> entityClass,
            final SearchAndRetrievalParams params
    ) {
        PageResult<DataPayload> pageResult = Futures.joinUnwrapped(repository.findAll(
                ctx,
                modelSpec,
                params
        ));

        return toEntities(pageResult, entityClass);
    }

    @Override
    public long getEntityCount(@NotNull final ModelSpec modelSpec) {
        return Futures.joinUnwrapped(repository.getEntityCount(callContexts.current(), modelSpec, null));
    }

    @Override
    public long getEntityCount(@NotNull final ModelSpec modelSpec, @Nullable final OffsetDateTime pointInTime) {
        return Futures.joinUnwrapped(repository.getEntityCount(callContexts.current(), modelSpec, pointInTime));
    }

    @Override
    public Map<String, Long> getEntityStatsByState(@NotNull final ModelSpec modelSpec) {
        return Futures.joinUnwrapped(repository.getEntityStatsByState(callContexts.current(), modelSpec, (OffsetDateTime) null));
    }

    @Override
    public Map<String, Long> getEntityStatsByState(
            @NotNull final ModelSpec modelSpec,
            @Nullable final OffsetDateTime pointInTime
    ) {
        return Futures.joinUnwrapped(repository.getEntityStatsByState(callContexts.current(), modelSpec, pointInTime));
    }

    @Override
    public Map<String, Long> getEntityStatsByState(
            @NotNull final ModelSpec modelSpec,
            @NotNull final List<String> states,
            @Nullable final OffsetDateTime pointInTime
    ) {
        return Futures.joinUnwrapped(repository.getEntityStatsByState(callContexts.current(), modelSpec, states, pointInTime));
    }

    /** Every page, including those fetched lazily as the stream is consumed, uses the context of this call. */
    @Override
    public <T extends CyodaEntity> Stream<EntityWithMetadata<T>> streamAll(
            @NotNull final ModelSpec modelSpec,
            @NotNull final Class<T> entityClass,
            @NotNull final SearchAndRetrievalParams params
    ) {
        final CyodaCallContext ctx = callContexts.current();
        // Fetch first page to get total size upfront
        PageResult<EntityWithMetadata<T>> firstPage = findAll(
                ctx,
                modelSpec,
                entityClass,
                SearchAndRetrievalParams.builder()
                        .pageSize(params.pageSize())
                        .pageNumber(0)
                        .pointInTime(params.pointInTime())
                        .awaitLimitMs(params.awaitLimitMs())
                        .pollIntervalMs(params.pollIntervalMs())
                        .build()
        );

        return StreamSupport.stream(
                new PaginatedSpliterator<>(
                        firstPage,
                        (pageNumber, searchId) -> findAll(
                                ctx,
                                modelSpec,
                                entityClass,
                                SearchAndRetrievalParams.builder()
                                        .pageSize(params.pageSize())
                                        .pageNumber(pageNumber)
                                        .pointInTime(params.pointInTime())
                                        .searchId(searchId)
                                        .awaitLimitMs(params.awaitLimitMs())
                                        .pollIntervalMs(params.pollIntervalMs())
                                        .build()
                        )
                ),
                false
        );
    }

    @Override
    public <T extends CyodaEntity> PageResult<EntityWithMetadata<T>> search(
            @NotNull final ModelSpec modelSpec,
            @NotNull final GroupConditionDto condition,
            @NotNull final Class<T> entityClass,
            @NotNull final SearchAndRetrievalParams params
    ) {
        return search(callContexts.current(), modelSpec, condition, entityClass, params);
    }

    private <T extends CyodaEntity> PageResult<EntityWithMetadata<T>> search(
            final CyodaCallContext ctx,
            final ModelSpec modelSpec,
            final GroupConditionDto condition,
            final Class<T> entityClass,
            final SearchAndRetrievalParams params
    ) {
        PageResult<DataPayload> pageResult = Futures.joinUnwrapped(repository.findAllByCriteria(
                ctx,
                modelSpec,
                condition,
                params
        ));

        return toEntities(pageResult, entityClass);
    }

    private <T extends CyodaEntity> PageResult<EntityWithMetadata<T>> toEntities(
            final PageResult<DataPayload> pageResult,
            final Class<T> entityClass
    ) {
        List<EntityWithMetadata<T>> entities = pageResult.data().stream()
                .filter(Objects::nonNull)
                .map(payload -> EntityWithMetadata.fromDataPayload(payload, entityClass, objectMapper))
                .toList();

        return PageResult.of(
                pageResult.searchId(),
                entities,
                pageResult.pageNumber(),
                pageResult.pageSize(),
                pageResult.totalElements()
        );
    }

    /** Every page, including those fetched lazily as the stream is consumed, uses the context of this call. */
    @Override
    public <T extends CyodaEntity> Stream<EntityWithMetadata<T>> searchAsStream(
            @NotNull final ModelSpec modelSpec,
            @NotNull final GroupConditionDto condition,
            @NotNull final Class<T> entityClass,
            @NotNull final SearchAndRetrievalParams params
    ) {
        final CyodaCallContext ctx = callContexts.current();
        // Fetch first page to get total size upfront
        PageResult<EntityWithMetadata<T>> firstPage = search(
                ctx,
                modelSpec,
                condition,
                entityClass,
                SearchAndRetrievalParams.builder()
                        .pageSize(params.pageSize())
                        .pageNumber(0)
                        .pointInTime(params.pointInTime())
                        .inMemory(params.inMemory())
                        .awaitLimitMs(params.awaitLimitMs())
                        .pollIntervalMs(params.pollIntervalMs())
                        .build()
        );

        return StreamSupport.stream(
                new PaginatedSpliterator<>(
                        firstPage,
                        (pageNumber, searchId) -> search(
                                ctx,
                                modelSpec,
                                condition,
                                entityClass,
                                SearchAndRetrievalParams.builder()
                                        .pageSize(params.pageSize())
                                        .pageNumber(pageNumber)
                                        .pointInTime(params.pointInTime())
                                        .searchId(searchId)
                                        .inMemory(params.inMemory())
                                        .awaitLimitMs(params.awaitLimitMs())
                                        .pollIntervalMs(params.pollIntervalMs())
                                        .build()
                        )
                ),
                false
        );
    }



    // ========================================
    // PRIMARY MUTATION METHODS IMPLEMENTATION
    // ========================================

    @Override
    public <T extends CyodaEntity> EntityWithMetadata<T> create(@NotNull final T entity) {
        return create(callContexts.current(), entity);
    }

    private <T extends CyodaEntity> EntityWithMetadata<T> create(final CyodaCallContext ctx, final T entity) {
        ModelSpec modelSpec = entity.getModelKey().modelKey();

        EntityTransactionResponse response = Futures.joinUnwrapped(repository.save(ctx, modelSpec, objectMapper.valueToTree(entity)));

        // Extract entity ID and transaction ID from response
        UUID entityId = response.getTransactionInfo().getEntityIds().getFirst();
        UUID transactionId = response.getTransactionInfo().getTransactionId();

        @SuppressWarnings("unchecked")
        Class<T> entityClass = (Class<T>) entity.getClass();
        if (ctx.isJoined()) {
            // Inside the joined transaction its latest view is the right one (spec clarification 3).
            return getById(ctx, entityId, modelSpec, entityClass, null);
        }

        // Get entity changes metadata to find the exact timeOfChange for this transaction
        List<EntityChangeMeta> changes = getEntityChangesMetadata(ctx, entityId, null);

        // Find the change metadata for this specific transaction
        EntityChangeMeta changeMeta = changes.stream()
                .filter(meta -> transactionId.equals(meta.getTransactionId()))
                .findFirst()
                .orElseThrow(() -> new RuntimeException("Transaction metadata not found for transaction: " + transactionId));

        // Reload entity at the exact point in time when it was saved
        return getById(ctx, entityId, modelSpec, entityClass, changeMeta.getTimeOfChange());
    }

    @Override
    public <T extends CyodaEntity> EntityWithMetadata<T> updateByBusinessId(
            @NotNull final T entity,
            @NotNull final String businessIdField,
            @Nullable final String transition
    ) {
        return updateByBusinessId(callContexts.current(), entity, businessIdField, transition);
    }

    /** The reload after the write, including the joined-scope rule, is {@link #update(CyodaCallContext, UUID, CyodaEntity, String)}'s. */
    private <T extends CyodaEntity> EntityWithMetadata<T> updateByBusinessId(
            final CyodaCallContext ctx,
            final T entity,
            final String businessIdField,
            @Nullable final String transition
    ) {
        // First find the entity by business ID to get its technical UUID
        Class<? extends CyodaEntity> entityClass = entity.getClass();

        // Get business ID value from entity using reflection-like approach
        String businessIdValue = getBusinessIdValue(entity, businessIdField);

        // Extract model info from entity
        ModelSpec modelSpec = entity.getModelKey().modelKey();

        EntityWithMetadata<? extends CyodaEntity> existingEntity =
                findByBusinessId(ctx, modelSpec, businessIdValue, businessIdField, entityClass, null);
        if (existingEntity == null) {
            throw new RuntimeException("Entity not found with business ID: " + businessIdValue);
        }

        UUID technicalId = existingEntity.metadata().getId();

        // Now update using technical ID
        return update(ctx, technicalId, entity, transition);
    }

    private <T extends CyodaEntity> @NotNull String getBusinessIdValue(T entity, String businessIdField) {
        // Use Jackson to convert entity to JsonNode and extract the field
        var entityNode = objectMapper.valueToTree(entity);
        var fieldValue = entityNode.get(businessIdField);
        if (fieldValue == null) {
            String entityString;
            try {
                entityString = objectMapper.writeValueAsString(entityNode);
            } catch (JsonProcessingException e) {
                entityString = "cannot convert entity to JSON: " + e.getMessage();
            }
            throw new IllegalStateException("Business ID value is null for field: " + businessIdField +
                    " for entity " + entityString);
        }
        return fieldValue.asText();
    }

    @Override
    public UUID deleteById(@NotNull final UUID entityId) {
        return deleteById(callContexts.current(), entityId);
    }

    private UUID deleteById(final CyodaCallContext ctx, final UUID entityId) {
        EntityDeleteResponse response = Futures.joinUnwrapped(repository.deleteById(ctx, entityId));
        return response.getEntityId();
    }

    @Override
    public <T extends CyodaEntity> boolean deleteByBusinessId(
            @NotNull final ModelSpec modelSpec,
            @NotNull final String businessId,
            @NotNull final String businessIdField,
            @NotNull final Class<T> entityClass
    ) {
        CyodaCallContext ctx = callContexts.current();
        // First find the entity to get its technical ID
        EntityWithMetadata<T> entityResponse = findByBusinessId(ctx, modelSpec, businessId, businessIdField, entityClass, null);
        if (entityResponse == null) {
            return false;
        }

        UUID entityId = entityResponse.metadata().getId();
        deleteById(ctx, entityId);
        return true;
    }

    @Override
    public Integer deleteAll(@NotNull final ModelSpec modelSpec) {
        List<EntityDeleteAllResponse> results = Futures.joinUnwrapped(repository.deleteAll(callContexts.current(), modelSpec));
        return results.stream()
                .map(EntityDeleteAllResponse::getNumDeleted)
                .reduce(0, Integer::sum);
    }

    @Override
    public <T extends CyodaEntity> List<EntityWithMetadata<T>> save(@NotNull final Collection<T> entities) {
        return save(callContexts.current(), entities, null, null);
    }

    @Override
    public <T extends CyodaEntity> List<EntityWithMetadata<T>> save(
            @NotNull final Collection<T> entities,
            @Nullable final Integer transactionWindow,
            @Nullable final Long transactionTimeoutMs
    ) {
        return save(callContexts.current(), entities, transactionWindow, transactionTimeoutMs);
    }

    private <T extends CyodaEntity> List<EntityWithMetadata<T>> save(
            final CyodaCallContext ctx,
            final Collection<T> entities,
            @Nullable final Integer transactionWindow,
            @Nullable final Long transactionTimeoutMs
    ) {
        if (entities.isEmpty()) {
            return List.of();
        }

        T firstEntity = entities.iterator().next();
        ModelSpec modelSpec = firstEntity.getModelKey().modelKey();

        List<EntityTransactionResponse> responses = Futures.joinUnwrapped(repository.saveAll(
                ctx, modelSpec, entities, transactionWindow, transactionTimeoutMs));

        return responses.stream().flatMap(response -> {
            // Extract entity IDs and transaction ID from response
            List<UUID> entityIds = response.getTransactionInfo() != null
                    ? response.getTransactionInfo().getEntityIds()
                    : List.of();
            UUID transactionId = response.getTransactionInfo().getTransactionId();

            @SuppressWarnings("unchecked")
            Class<T> entityClass = (Class<T>) firstEntity.getClass();

            // For each entity, get its change metadata and reload at the exact point in time
            return entityIds.stream()
                    .map(entityId -> {
                        if (ctx.isJoined()) {
                            // Inside the joined transaction its latest view is the right one (spec clarification 3).
                            return getById(ctx, entityId, modelSpec, entityClass, null);
                        }

                        // Get entity changes metadata to find the exact timeOfChange for this transaction
                        List<EntityChangeMeta> changes = getEntityChangesMetadata(ctx, entityId, null);

                        // Find the change metadata for this specific transaction
                        EntityChangeMeta changeMeta = changes.stream()
                                .filter(meta -> transactionId.equals(meta.getTransactionId()))
                                .findFirst()
                                .orElseThrow(() -> new RuntimeException("Transaction metadata not found for transaction: " + transactionId));

                        // Reload entity at the exact point in time when it was saved
                        return getById(ctx, entityId, modelSpec, entityClass, changeMeta.getTimeOfChange());
                    });
        }).toList();

    }


    @Override
    public <T extends CyodaEntity> EntityWithMetadata<T> update(
            @NotNull final UUID entityId,
            @NotNull final T entity,
            @Nullable final String transition
    ) {
        return update(callContexts.current(), entityId, entity, transition);
    }

    private <T extends CyodaEntity> EntityWithMetadata<T> update(
            final CyodaCallContext ctx,
            final UUID entityId,
            final T entity,
            @Nullable final String transition
    ) {
        ModelSpec modelSpec = entity.getModelKey().modelKey();

        EntityTransactionResponse response = Futures.joinUnwrapped(repository.update(ctx, entityId, objectMapper.valueToTree(entity), transition));

        @SuppressWarnings("unchecked")
        Class<T> entityClass = (Class<T>) entity.getClass();
        if (ctx.isJoined()) {
            // Inside the joined transaction its latest view is the right one (spec clarification 3).
            return getById(ctx, entityId, modelSpec, entityClass, null);
        }

        // Extract transaction ID from response
        UUID transactionId = response.getTransactionInfo().getTransactionId();

        // Get entity changes metadata to find the exact timeOfChange for this transaction
        List<EntityChangeMeta> changes = getEntityChangesMetadata(ctx, entityId, null);

        // Find the change metadata for this specific transaction
        EntityChangeMeta changeMeta = changes.stream()
                .filter(meta -> transactionId.equals(meta.getTransactionId()))
                .findFirst()
                .orElseGet(() -> {
                    logger.warn("Transaction metadata not found for transaction: {}. " +
                            "The entity is unchanged. Falling back to last change metadata.", transactionId);

                    // Sanity check: verify that the last element has the maximum transactionId
                    return getLatestChange(changes);
                });

        // Reload entity at the exact point in time when it was updated
        return getById(ctx, entityId, modelSpec, entityClass, changeMeta.getTimeOfChange());
    }

    @NotNull
    private EntityChangeMeta getLatestChange(List<EntityChangeMeta> changes) {
        return changes.stream()
                .max((c1, c2) -> {
                    UUID id1 = c1.getTransactionId();
                    UUID id2 = c2.getTransactionId();
                    if (id1 == null && id2 == null) return 0;
                    if (id1 == null) return -1;
                    if (id2 == null) return 1;
                    return id1.compareTo(id2);
                })
                .orElseGet(changes::getFirst);
    }

    @Override
    public <T extends CyodaEntity> List<EntityWithMetadata<T>> updateAll(
            @NotNull final Collection<T> entities,
            @Nullable final String transition
    ) {
        return updateAll(callContexts.current(), entities, transition, null, null);
    }

    @Override
    public <T extends CyodaEntity> List<EntityWithMetadata<T>> updateAll(
            @NotNull final Collection<T> entities,
            @Nullable final String transition,
            @Nullable final Integer transactionWindow,
            @Nullable final Long transactionTimeoutMs
    ) {
        return updateAll(callContexts.current(), entities, transition, transactionWindow, transactionTimeoutMs);
    }

    private <T extends CyodaEntity> List<EntityWithMetadata<T>> updateAll(
            final CyodaCallContext ctx,
            final Collection<T> entities,
            @Nullable final String transition,
            @Nullable final Integer transactionWindow,
            @Nullable final Long transactionTimeoutMs
    ) {
        if (entities.isEmpty()) {
            return List.of();
        }

        T firstEntity = entities.iterator().next();
        ModelSpec modelSpec = firstEntity.getModelKey().modelKey();

        List<EntityTransactionResponse> responses = Futures.joinUnwrapped(repository.updateAll(
                ctx,
                objectMapper.convertValue(entities, new TypeReference<>() {}),
                transition,
                transactionWindow,
                transactionTimeoutMs
        ));

        @SuppressWarnings("unchecked")
        Class<T> entityClass = (Class<T>) firstEntity.getClass();

        // For each response, extract entity IDs and transaction ID, then reload at exact point in time
        return responses.stream()
                .filter(response -> response.getTransactionInfo() != null)
                .flatMap(response -> {
                    UUID transactionId = response.getTransactionInfo().getTransactionId();
                    List<UUID> entityIds = response.getTransactionInfo().getEntityIds();

                    return entityIds.stream()
                            .map(entityId -> {
                                if (ctx.isJoined()) {
                                    // Inside the joined transaction its latest view is the right one (spec clarification 3).
                                    return getById(ctx, entityId, modelSpec, entityClass, null);
                                }

                                // Get entity changes metadata to find the exact timeOfChange for this transaction
                                List<EntityChangeMeta> changes = getEntityChangesMetadata(ctx, entityId, null);

                                // Find the change metadata for this specific transaction
                                EntityChangeMeta changeMeta = changes.stream()
                                        .filter(meta -> transactionId.equals(meta.getTransactionId()))
                                        .findFirst()
                                        .orElseGet(() -> {
                                            logger.warn("Transaction metadata not found for transaction: {}. " +
                                                    "The entity is unchanged. Falling back to last change metadata.", transactionId);

                                            // Sanity check: verify that the last element has the maximum transactionId
                                            return getLatestChange(changes);
                                        });

                                // Reload entity at the exact point in time when it was updated
                                return getById(ctx, entityId, modelSpec, entityClass, changeMeta.getTimeOfChange());
                            });
                })
                .toList();
    }

    // ========================================
    // METADATA OPERATIONS IMPLEMENTATION
    // ========================================

    @Override
    public List<EntityChangeMeta> getEntityChangesMetadata(@NotNull final UUID entityId) {
        return getEntityChangesMetadata(callContexts.current(), entityId, null);
    }

    @Override
    public List<EntityChangeMeta> getEntityChangesMetadata(
            @NotNull final UUID entityId,
            @Nullable final OffsetDateTime pointInTime
    ) {
        return getEntityChangesMetadata(callContexts.current(), entityId, pointInTime);
    }

    private List<EntityChangeMeta> getEntityChangesMetadata(
            final CyodaCallContext ctx,
            final UUID entityId,
            @Nullable final OffsetDateTime pointInTime
    ) {
        return Futures.joinUnwrapped(repository.getEntityChangesMetadata(ctx, entityId, pointInTime));
    }

    // ========================================
    // INNER CLASSES
    // ========================================

    /**
     * Functional interface for fetching pages of entities.
     */
    @FunctionalInterface
    private interface PageFetcher<T extends CyodaEntity> {
        PageResult<EntityWithMetadata<T>> fetchPage(int pageNumber, UUID searchId);
    }

    /**
     * Unified spliterator for paginated retrieval of entities.
     * Supports both findAll and search operations through a PageFetcher function.
     * Accepts the first page result to enable accurate size estimation from the start.
     */
    private static class PaginatedSpliterator<T extends CyodaEntity> implements Spliterator<EntityWithMetadata<T>> {
        private final PageFetcher<T> pageFetcher;
        private final long totalElements;

        private UUID searchId;
        private int currentPage;
        private Iterator<EntityWithMetadata<T>> currentIterator;
        private boolean hasMore;
        private long processedElements = 0;

        PaginatedSpliterator(PageResult<EntityWithMetadata<T>> firstPage, PageFetcher<T> pageFetcher) {
            this.pageFetcher = pageFetcher;
            this.totalElements = firstPage.totalElements();
            this.searchId = firstPage.searchId();
            this.currentIterator = firstPage.data().iterator();
            this.currentPage = 1; // Next page to fetch is page 1 (0-based, so second page)
            this.hasMore = firstPage.hasNext();
        }

        @Override
        public boolean tryAdvance(java.util.function.Consumer<? super EntityWithMetadata<T>> action) {
            // Fetch next page if needed
            if (currentIterator == null || !currentIterator.hasNext()) {
                // Don't try to fetch if we know there are no more pages
                if (!hasMore) {
                    return false;
                }
                if (!fetchNextPage()) {
                    return false;
                }
            }

            // Process next item
            if (currentIterator.hasNext()) {
                action.accept(currentIterator.next());
                processedElements++;
                return true;
            }

            return false;
        }

        private boolean fetchNextPage() {
            PageResult<EntityWithMetadata<T>> pageResult = pageFetcher.fetchPage(currentPage, searchId);

            if (pageResult.data().isEmpty()) {
                hasMore = false;
                return false;
            }

            // Update state for next page
            searchId = pageResult.searchId();
            currentIterator = pageResult.data().iterator();
            currentPage++;
            hasMore = pageResult.hasNext();

            return true;
        }

        @Override
        public Spliterator<EntityWithMetadata<T>> trySplit() {
            return null;
        }

        @Override
        public long estimateSize() {
            // Return accurate remaining count since we know total from first page
            return totalElements - processedElements;
        }

        @Override
        public int characteristics() {
            // SIZED is now valid because we know the total size from the first page
            return ORDERED | NONNULL | SIZED;
        }
    }

}