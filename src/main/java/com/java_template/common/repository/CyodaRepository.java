package com.java_template.common.repository;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Expiry;
import com.github.benmanes.caffeine.cache.LoadingCache;
import com.google.common.collect.Streams;
import com.google.protobuf.InvalidProtocolBufferException;
import com.java_template.common.config.Config;
import com.java_template.common.dto.PageResult;
import com.java_template.common.exception.CyodaOperationException;
import com.java_template.common.grpc.client.event_handling.CloudEventBuilder;
import com.java_template.common.grpc.client.event_handling.CloudEventParser;
import io.cloudevents.v1.proto.CloudEvent;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import org.cyoda.cloud.api.common.model.GroupConditionDto;
import org.cyoda.cloud.api.event.common.BaseEvent;
import org.cyoda.cloud.api.event.common.DataPayload;
import org.cyoda.cloud.api.event.common.ModelSpec;
import org.cyoda.cloud.api.event.entity.*;
import org.cyoda.cloud.api.event.search.*;
import org.cyoda.cloud.api.grpc.CloudEventsServiceGrpc;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Repository;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;


/**
 * ABOUTME: Concrete implementation of CrudRepository providing entity CRUD operations
 * through gRPC communication with the Cyoda platform backend services.
 */
@Repository
public class CyodaRepository implements CrudRepository {
    private final Logger logger = LoggerFactory.getLogger(this.getClass());

    private final ObjectMapper objectMapper;
    private final Config config;
    private final CloudEventsServiceGrpc.CloudEventsServiceBlockingStub cloudEventsServiceBlockingStub;
    private final CloudEventBuilder cloudEventBuilder;
    private final CloudEventParser cloudEventParser;

    /**
     * Cache for snapshot search results. Only caches searches with pointInTime.
     * Entries are evicted based on the snapshot's expirationDate.
     */
    private final LoadingCache<SearchCacheKey, CompletableFuture<SearchSnapshotStatus>> snapshotCache;

    public CyodaRepository(
            final ObjectMapper objectMapper,
            final CloudEventsServiceGrpc.CloudEventsServiceBlockingStub cloudEventsServiceBlockingStub,
            final CloudEventBuilder cloudEventBuilder,
            final CloudEventParser cloudEventParser,
            final Config config
    ) {
        this.objectMapper = objectMapper;
        this.cloudEventsServiceBlockingStub = cloudEventsServiceBlockingStub;
        this.cloudEventBuilder = cloudEventBuilder;
        this.cloudEventParser = cloudEventParser;
        this.config = config;

        // Initialize cache with expiry based on snapshot expiration
        this.snapshotCache = Caffeine.newBuilder()
                .expireAfter(new Expiry<SearchCacheKey, CompletableFuture<SearchSnapshotStatus>>() {
                    @Override
                    public long expireAfterCreate(SearchCacheKey key, CompletableFuture<SearchSnapshotStatus> value, long currentTime) {
                        return getExpirationDuration(value, currentTime);
                    }

                    @Override
                    public long expireAfterUpdate(SearchCacheKey key, CompletableFuture<SearchSnapshotStatus> value, long currentTime, long currentDuration) {
                        return getExpirationDuration(value, currentTime);
                    }

                    @Override
                    public long expireAfterRead(SearchCacheKey key, CompletableFuture<SearchSnapshotStatus> value, long currentTime, long currentDuration) {
                        return currentDuration;
                    }

                    private long getExpirationDuration(CompletableFuture<SearchSnapshotStatus> value, long currentTime) {
                        try {
                            SearchSnapshotStatus status = value.getNow(null);
                            if (status != null && status.getExpirationDate() != null) {
                                long expirationTime = status.getExpirationDate().getTime();
                                long currentTimeMillis = TimeUnit.NANOSECONDS.toMillis(currentTime);
                                long durationMillis = expirationTime - currentTimeMillis;
                                return durationMillis > 0 ? TimeUnit.MILLISECONDS.toNanos(durationMillis) : 0;
                            }
                        } catch (Exception e) {
                            logger.debug("Could not determine expiration time, using default", e);
                        }
                        // Default to 1 hour if we can't determine expiration
                        return TimeUnit.HOURS.toNanos(1);
                    }
                })
                .build(key -> {
                    if (key.pointInTime != null) {
                        return createSnapshotSearch(key.modelSpec, key.condition, key.pointInTime);
                    } else return null;
                });
    }

    private CloudEventsServiceGrpc.CloudEventsServiceBlockingStub blocking() {
        return cloudEventsServiceBlockingStub.withDeadlineAfter(config.getGrpcCallDeadlineMs(), TimeUnit.MILLISECONDS);
    }

    @Override
    public CompletableFuture<DataPayload> findById(@NotNull final UUID id) {
        return getById(id, null);
    }

    @Override
    public CompletableFuture<DataPayload> findById(@NotNull final UUID id, @Nullable final Date pointInTime) {
        return getById(id, pointInTime);
    }

    private CompletableFuture<DataPayload> getById(final UUID entityId, @Nullable final Date pointInTime) {
        return sendAndGet(
                req -> blocking().entitySearch(req),
                new EntityGetRequest().withId(UUID.randomUUID().toString())
                        .withEntityId(entityId)
                        .withPointInTime(pointInTime),
                EntityResponse.class
        ).thenApply(EntityResponse::getPayload);
    }

    @Override
    public CompletableFuture<PageResult<DataPayload>> findAllByCriteria(
            @NotNull final ModelSpec modelSpec,
            @NotNull final GroupConditionDto condition,
            @NotNull final SearchAndRetrievalParams params
    ) {
        return params.inMemory()
                ? findAllByConditionInMemory(modelSpec, params.pageSize(), condition, params.pointInTime())
                : findAllByCondition(modelSpec, params.pageSize(), params.pageNumber(), condition, params.pointInTime(), params.searchId(), params.awaitLimitMs(), params.pollIntervalMs());
    }

    private CompletableFuture<PageResult<DataPayload>> findAllByCondition(
            @NotNull final ModelSpec modelSpec,
            final int pageSize,
            final int pageNumber,
            @NotNull final GroupConditionDto condition,
            @Nullable final Date pointInTime,
            @Nullable final UUID searchId, int awaitLimitMs, int pollIntervalMs
    ) {
        CompletableFuture<SearchSnapshotStatus> snapshot;

        if (searchId == null) {
            // New search - create snapshot and don't use cache
            snapshot = createSnapshotSearch(modelSpec, condition, pointInTime);
        } else {
            // Existing search - use cache with searchId as key
            SearchCacheKey cacheKey = new SearchCacheKey(modelSpec, condition, pointInTime, searchId);
            snapshot = Optional.ofNullable(snapshotCache.get(cacheKey))
                    .orElseGet(() -> createSnapshotSearch(modelSpec, condition, pointInTime));
        }

        return snapshot.thenComposeAsync(snapshotInfo -> {
                    if (snapshotInfo.getSnapshotId() == null) {
                        throw new CyodaOperationException(
                                "MISSING_SNAPSHOT_ID",
                                "Snapshot search returned no snapshot ID",
                                false
                        );
                    }

                    // Use the snapshot ID from Cyoda as the search ID
                    UUID effectiveSearchId = snapshotInfo.getSnapshotId();

                    // Cache the snapshot for subsequent page requests
                    if (searchId == null && pointInTime != null) {
                        SearchCacheKey cacheKey = new SearchCacheKey(modelSpec, condition, pointInTime, effectiveSearchId);
                        snapshotCache.put(cacheKey, CompletableFuture.completedFuture(snapshotInfo));
                    }

                    return getSnapShotIdCompletableFuture(snapshotInfo, awaitLimitMs, pollIntervalMs)
                            .thenApply(snapshotId -> new SnapshotWithMetadata(snapshotId, snapshotInfo.getEntitiesCount(), effectiveSearchId));
                }).thenCompose(snapshotWithMetadata ->
                        getSearchResult(snapshotWithMetadata.snapshotId, pageSize, pageNumber)
                                .thenApply(data -> PageResult.of(
                                        snapshotWithMetadata.searchId,
                                        data,
                                        pageNumber,
                                        pageSize,
                                        snapshotWithMetadata.totalElements != null ? snapshotWithMetadata.totalElements : 0L
                                ))
                )
                .exceptionally(this::handleNotFoundOrThrowPageResult);
    }

    private record SnapshotWithMetadata(UUID snapshotId, Long totalElements, UUID searchId) {
    }

    @NotNull
    private CompletableFuture<UUID> getSnapShotIdCompletableFuture(SearchSnapshotStatus snapshotInfo, int awaitLimitMs, int pollIntervalMs) {
        // NOTE: To avoid redundant polling, if snapshot is already done
        if (SearchSnapshotStatus.Status.SUCCESSFUL.equals(snapshotInfo.getStatus())) {
            return CompletableFuture.completedFuture(snapshotInfo.getSnapshotId());
        }

        try {
            return waitForSearchCompletion(
                    snapshotInfo.getSnapshotId(),
                    awaitLimitMs,
                    pollIntervalMs
            ).thenApply(it -> snapshotInfo.getSnapshotId());
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private CompletableFuture<PageResult<DataPayload>> findAllByConditionInMemory(
            @NotNull final ModelSpec modelSpec,
            final int pageSize,
            @NotNull final GroupConditionDto condition,
            @Nullable final Date pointInTime
    ) {
        return sendAndGetCollection(
                req -> blocking().entitySearchCollection(req),
                new EntitySearchRequest().withId(generateEventId())
                        .withModel(modelSpec)
                        .withLimit(pageSize)
                        .withCondition(condition)
                        .withPointInTime(pointInTime),
                EntityResponse.class
        ).thenApply(entities -> {
            List<DataPayload> data = entities.map(EntityResponse::getPayload).toList();
            // In-memory searches don't have snapshot IDs, so searchId is null
            return PageResult.of(null, data, 0, pageSize, data.size());
        }).exceptionally(this::handleNotFoundOrThrowPageResult);
    }

    @Override
    public CompletableFuture<PageResult<DataPayload>> findAll(
            @NotNull final ModelSpec modelSpec,
            @NotNull final SearchAndRetrievalParams params
    ) {
        // Create an empty condition to match all entities
        GroupConditionDto matchAllCondition = new GroupConditionDto()
                .operator(GroupConditionDto.OperatorEnum.AND)
                .conditions(List.of());

        return params.inMemory()
                ? findAllByConditionInMemory(modelSpec, params.pageSize(), matchAllCondition, params.pointInTime())
                : findAllByCondition(modelSpec, params.pageSize(), params.pageNumber(), matchAllCondition, params.pointInTime(), params.searchId(), params.awaitLimitMs(), params.pollIntervalMs());
    }

    @Override
    public <ENTITY_TYPE> CompletableFuture<EntityTransactionResponse> save(
            @NotNull final ModelSpec modelSpec,
            @NotNull final ENTITY_TYPE entity
    ) {
        return saveNewEntities(modelSpec, entity);
    }

    @Override
    public <ENTITY_TYPE> CompletableFuture<EntityTransactionResponse> saveAll(
            @NotNull final ModelSpec modelSpec,
            @NotNull final Collection<ENTITY_TYPE> entities
    ) {
        return saveNewEntities(modelSpec, entities);
    }

    @Override
    public <ENTITY_TYPE> CompletableFuture<List<EntityTransactionResponse>> saveAll(
            @NotNull final ModelSpec modelSpec,
            @NotNull final Collection<ENTITY_TYPE> entities,
            @Nullable final Integer transactionWindow,
            @Nullable final Long transactionTimeoutMs
    ) {
        return saveNewEntitiesWithTransactionParams(modelSpec, entities, transactionWindow, transactionTimeoutMs);
    }

    @Override
    public CompletableFuture<EntityTransitionResponse> applyTransition(
            @NotNull final UUID entityId,
            @NotNull final String transitionName
    ) {
        return sendAndGet(
                req -> blocking().entityManage(req),
                new EntityTransitionRequest().withId(generateEventId())
                        .withEntityId(entityId)
                        .withTransition(transitionName),
                EntityTransitionResponse.class
        );
    }

    @Override
    public <ENTITY_TYPE> CompletableFuture<EntityTransactionResponse> update(
            @NotNull final UUID id,
            @NotNull final ENTITY_TYPE entity,
            @Nullable final String transition
    ) {
        return sendAndGet(
                req -> blocking().entityManage(req),
                new EntityUpdateRequest().withId(generateEventId())
                        .withDataFormat(config.getGrpcCommunicationDataFormat())
                        .withPayload(
                                new EntityUpdatePayload().withEntityId(id)
                                        .withData(objectMapper.valueToTree(entity))
                                        .withTransition(transition)
                        ),
                EntityTransactionResponse.class
        );
    }

    @Override
    public <ENTITY_TYPE> CompletableFuture<List<EntityTransactionResponse>> updateAll(
            @NotNull final Collection<ENTITY_TYPE> entities,
            @Nullable final String transition
    ) {
        return updateAll(entities, transition, null, null);
    }

    @Override
    public <ENTITY_TYPE> CompletableFuture<List<EntityTransactionResponse>> updateAll(
            @NotNull final Collection<ENTITY_TYPE> entities,
            @Nullable final String transition,
            @Nullable final Integer transactionWindow,
            @Nullable final Long transactionTimeoutMs
    ) {
        final var entitiesByIds = entities.stream()
                .map(objectMapper::valueToTree)
                .map(entity -> (JsonNode) entity)
                .collect(Collectors.toMap(
                                entity -> UUID.fromString(entity.get("id").asText()),
                                entity -> entity
                        )
                );

        return sendAndGetCollection(
                req -> blocking().entityManageCollection(req),
                new EntityUpdateCollectionRequest().withId(generateEventId())
                        .withDataFormat(config.getGrpcCommunicationDataFormat())
                        .withTransactionWindow(transactionWindow)
                        .withTransactionTimeoutMs(transactionTimeoutMs)
                        .withPayloads(entitiesByIds.entrySet()
                                .stream()
                                .map(entity -> new EntityUpdatePayload().withTransition(transition)
                                        .withEntityId(entity.getKey())
                                        .withData(entity.getValue()))
                                .toList()),
                EntityTransactionResponse.class
        ).thenApply(Stream::toList);
    }

    @Override
    public CompletableFuture<EntityDeleteResponse> deleteById(@NotNull final UUID id) {
        return deleteEntity(id);
    }

    @Override
    public CompletableFuture<List<EntityDeleteAllResponse>> deleteAll(
            @NotNull final ModelSpec modelSpec
    ) {
        return deleteAllByModel(modelSpec);
    }

    private <RESPONSE_PAYLOAD_TYPE extends BaseEvent> CompletableFuture<RESPONSE_PAYLOAD_TYPE> sendAndGet(
            final Function<CloudEvent, CloudEvent> apiCall,
            final BaseEvent baseEvent,
            final Class<RESPONSE_PAYLOAD_TYPE> responsePayloadType
    ) {
        try {
            final CloudEvent requestEvent = cloudEventBuilder.buildEvent(baseEvent);
            return CompletableFuture.supplyAsync(() -> {
                        logger.debug("Sending event: {}", requestEvent);
                        CloudEvent cloudEvent = requestAndGetOrThrow(apiCall, requestEvent);
                        logger.debug("Received event: {}", cloudEvent);
                        return cloudEvent;
                    })
                    .thenApply(response -> cloudEventParser.parseCloudEvent(response, responsePayloadType))
                    .thenApply(this::validateResponse);
        } catch (InvalidProtocolBufferException e) {
            throw new RuntimeException(e);
        }
    }

    private <RESPONSE_PAYLOAD_TYPE extends BaseEvent> CompletableFuture<Stream<RESPONSE_PAYLOAD_TYPE>> sendAndGetCollection(
            final Function<CloudEvent, Iterator<CloudEvent>> apiCall,
            final BaseEvent baseEvent,
            final Class<RESPONSE_PAYLOAD_TYPE> responsePayloadClass
    ) {
        try {
            final var requestEvent = cloudEventBuilder.buildEvent(baseEvent);
            return CompletableFuture.supplyAsync(() -> requestAndGetOrThrow(apiCall, requestEvent))
                    .thenApply(response -> processCollection(Streams.stream(response), responsePayloadClass));
        } catch (InvalidProtocolBufferException e) {
            throw new RuntimeException(e);
        }
    }

    private <RESPONSE_PAYLOAD_TYPE extends BaseEvent> Stream<RESPONSE_PAYLOAD_TYPE> processCollection(
            final Stream<CloudEvent> stream,
            final Class<RESPONSE_PAYLOAD_TYPE> payloadType
    ) {
        // Filter null CloudEvent entries that may arrive from the gRPC streaming iterator
        return stream.filter(Objects::nonNull)
                .map(elm -> cloudEventParser.parseCloudEvent(elm, payloadType))
                .map(this::validateResponse);
    }

    private <RESPONSE_PAYLOAD_TYPE> RESPONSE_PAYLOAD_TYPE requestAndGetOrThrow(
            final Function<CloudEvent, RESPONSE_PAYLOAD_TYPE> apiCall,
            final CloudEvent requestEvent
    ) {
        try {
            return apiCall.apply(requestEvent);
        } catch (Exception e) {
            throw new CompletionException(e);
        }
    }

    /**
     * Validates a Cyoda response: logs any warnings, then throws if the operation failed.
     * Package-private to allow direct unit testing without mocking the full gRPC pipeline.
     */
    <T extends BaseEvent> T validateResponse(T response) {
        if (response.getWarnings() != null && !response.getWarnings().isEmpty()) {
            response.getWarnings().forEach(w -> logger.warn("Cyoda warning: {}", w));
        }
        if (Boolean.FALSE.equals(response.getSuccess())) {
            org.cyoda.cloud.api.event.common.Error error = response.getError();
            String code = error != null ? error.getCode() : "UNKNOWN";
            String message = error != null ? error.getMessage() : "Operation failed with no error details";
            boolean retryable = error != null ? Optional.ofNullable(error.getRetryable()).orElse(false) : false;
            throw new CyodaOperationException(code, message, retryable);
        }
        return response;
    }

    private <PAYLOAD_TYPE> CompletableFuture<EntityTransactionResponse> saveNewEntities(
            @NotNull final ModelSpec modelSpec,
            @NotNull final PAYLOAD_TYPE entities
    ) {
        return sendAndGet(
                req -> blocking().entityManage(req),
                new EntityCreateRequest().withId(generateEventId())
                        .withDataFormat(config.getGrpcCommunicationDataFormat())
                        .withPayload(new EntityCreatePayload().withData(objectMapper.valueToTree(entities))
                                .withModel(modelSpec)
                        ),
                EntityTransactionResponse.class
        );
    }

    private <PAYLOAD_TYPE> CompletableFuture<List<EntityTransactionResponse>> saveNewEntitiesWithTransactionParams(
            @NotNull final ModelSpec modelSpec,
            @NotNull final PAYLOAD_TYPE entities,
            @Nullable final Integer transactionWindow,
            @Nullable final Long transactionTimeoutMs
    ) {
        // Convert entities to collection if it's not already
        Collection<?> entityCollection = (entities instanceof Collection)
                ? (Collection<?>) entities
                : List.of(entities);

        List<EntityCreatePayload> payloads = entityCollection.stream()
                .map(entity -> new EntityCreatePayload()
                        .withData(objectMapper.valueToTree(entity))
                        .withModel(modelSpec))
                .toList();

        return sendAndGetCollection(
                req -> blocking().entityManageCollection(req),
                new EntityCreateCollectionRequest().withId(generateEventId())
                        .withDataFormat(config.getGrpcCommunicationDataFormat())
                        .withTransactionWindow(transactionWindow)
                        .withTransactionTimeoutMs(transactionTimeoutMs)
                        .withPayloads(payloads),
                EntityTransactionResponse.class
        ).thenApply(Stream::toList);
    }

    private CompletableFuture<EntityDeleteResponse> deleteEntity(@NotNull final UUID id) {
        return sendAndGet(
                req -> blocking().entityManage(req),
                new EntityDeleteRequest().withId(generateEventId()).withEntityId(id),
                EntityDeleteResponse.class
        );
    }

    private CompletableFuture<List<EntityDeleteAllResponse>> deleteAllByModel(
            @NotNull final ModelSpec modelSpec
    ) {
        return sendAndGetCollection(
                req -> blocking().entityManageCollection(req),
                new EntityDeleteAllRequest().withId(generateEventId())
                        .withModel(modelSpec),
                EntityDeleteAllResponse.class
        ).thenApply(Stream::toList);
    }

    private String generateEventId() {
        return UUID.randomUUID().toString();
    }

    private CompletableFuture<SearchSnapshotStatus> createSnapshotSearch(
            final ModelSpec modelSpec,
            final GroupConditionDto condition,
            @Nullable final Date pointInTime
    ) {
        return sendAndGet(
                req -> blocking().entitySearch(req),
                new EntitySnapshotSearchRequest().withId(generateEventId())
                        .withModel(modelSpec)
                        .withCondition(condition)
                        .withPointInTime(pointInTime),
                EntitySnapshotSearchResponse.class
        ).thenApply(EntitySnapshotSearchResponse::getStatus);
    }

    private CompletableFuture<SearchSnapshotStatus.Status> waitForSearchCompletion(
            @NotNull final UUID snapshotId,
            final long awaitLimitMillis,
            final long intervalMillis
    ) throws IOException {
        final var startTime = System.currentTimeMillis();
        return pollSnapshotStatus(snapshotId, startTime, awaitLimitMillis, intervalMillis);
    }

    private CompletableFuture<SearchSnapshotStatus.Status> pollSnapshotStatus(
            @NotNull final UUID snapshotId,
            final long startTime,
            final long awaitLimitMillis,
            final long intervalMillis
    ) throws IOException {
        logger.debug("Polling snapshot: {}", snapshotId);
        return getSnapshotStatus(snapshotId).thenCompose(snapshotStatus -> {
            if (SearchSnapshotStatus.Status.SUCCESSFUL.equals(snapshotStatus)) {
                logger.debug("Snapshot is ready!");
                return CompletableFuture.completedFuture(snapshotStatus);
            }
            if (!SearchSnapshotStatus.Status.RUNNING.equals(snapshotStatus)) {
                return CompletableFuture.failedFuture(
                        new RuntimeException("Snapshot search failed: " + snapshotStatus)
                );
            }

            final var elapsedTime = System.currentTimeMillis() - startTime;
            if (elapsedTime > awaitLimitMillis) {
                return CompletableFuture.failedFuture(
                        new TimeoutException("Timeout exceeded after " + awaitLimitMillis + " ms"));
            }

            return CompletableFuture.runAsync(
                    () -> {
                    },
                    CompletableFuture.delayedExecutor(intervalMillis, TimeUnit.MILLISECONDS)
            ).thenCompose(ignored -> {
                try {
                    return pollSnapshotStatus(snapshotId, startTime, awaitLimitMillis, intervalMillis);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });
        });
    }

    private CompletableFuture<SearchSnapshotStatus.Status> getSnapshotStatus(@NotNull final UUID snapshotId) {
        return sendAndGet(
                req -> blocking().entitySearch(req),
                new SnapshotGetStatusRequest().withId(generateEventId()).withSnapshotId(snapshotId),
                EntitySnapshotSearchResponse.class
        ).thenApply(EntitySnapshotSearchResponse::getStatus)
                .thenApply(SearchSnapshotStatus::getStatus);
    }

    private CompletableFuture<List<DataPayload>> getSearchResult(
            @NotNull final UUID snapshotId,
            final int pageSize,
            final int pageNumber
    ) {
        return sendAndGetCollection(
                req -> blocking().entitySearchCollection(req),
                new SnapshotGetRequest().withId(generateEventId())
                        .withSnapshotId(snapshotId)
                        .withPageSize(pageSize)
                        .withPageNumber(pageNumber),
                EntityResponse.class
        ).thenApply(entities -> entities
                .map(EntityResponse::getPayload)
                .toList()
        );
    }

    private <ENTITY_TYPE> PageResult<ENTITY_TYPE> handleNotFoundOrThrowPageResult(final Throwable exception) {
        if (isNotFound(exception)) {
            logger.warn("Not found happened", exception);
            return PageResult.of(null, Collections.emptyList(), 1, 0, 0L);
        }
        final var cause = exception instanceof CompletionException ? exception.getCause() : exception;
        throw new CompletionException("Unhandled error", cause);
    }

    private boolean isNotFound(final Throwable exception) {
        return exception instanceof StatusRuntimeException ex && ex.getStatus().getCode().equals(Status.Code.NOT_FOUND);
    }

    @Override
    public CompletableFuture<Long> getEntityCount(@NotNull final ModelSpec modelSpec) {
        return getEntityCount(modelSpec, null);
    }

    @Override
    public CompletableFuture<Long> getEntityCount(@NotNull final ModelSpec modelSpec, @Nullable final Date pointInTime) {
        return sendAndGetCollection(
                req -> blocking().entitySearchCollection(req),
                new EntityStatsGetRequest()
                        .withId(generateEventId())
                        .withPointInTime(pointInTime)
                        .withModel(modelSpec),
                EntityStatsResponse.class
        ).thenApply(statsStream -> statsStream
                .filter(stat -> modelSpec.getName().equals(stat.getModelName()) &&
                        modelSpec.getVersion().equals(stat.getModelVersion()))
                .findFirst()
                .map(EntityStatsResponse::getCount)
                .orElse(0L)
        );
    }

    @Override
    public CompletableFuture<Map<String, Long>> getEntityStatsByState(@NotNull final ModelSpec modelSpec) {
        return getEntityStatsByState(modelSpec, Collections.emptyList(), null);
    }

    @Override
    public CompletableFuture<Map<String, Long>> getEntityStatsByState(
            @NotNull final ModelSpec modelSpec,
            @Nullable final Date pointInTime
    ) {
        return getEntityStatsByState(modelSpec, Collections.emptyList(), pointInTime);
    }

    @Override
    public CompletableFuture<Map<String, Long>> getEntityStatsByState(
            @NotNull final ModelSpec modelSpec,
            @NotNull final List<String> states,
            @Nullable final Date pointInTime
    ) {
        return sendAndGetCollection(
                req -> blocking().entitySearchCollection(req),
                new EntityStatsByStateGetRequest()
                        .withId(generateEventId())
                        .withModel(modelSpec)
                        .withStates(states)
                        .withPointInTime(pointInTime),
                EntityStatsByStateResponse.class
        ).thenApply(statsStream -> statsStream
                .filter(stat -> modelSpec.getName().equals(stat.getModelName()) &&
                        modelSpec.getVersion().equals(stat.getModelVersion()))
                .collect(Collectors.toMap(
                        EntityStatsByStateResponse::getState,
                        EntityStatsByStateResponse::getCount
                ))
        );
    }

    @Override
    public CompletableFuture<List<org.cyoda.cloud.api.event.common.EntityChangeMeta>> getEntityChangesMetadata(
            @NotNull final UUID entityId,
            @Nullable final Date pointInTime
    ) {
        return sendAndGetCollection(
                req -> blocking().entitySearchCollection(req),
                new EntityChangesMetadataGetRequest()
                        .withId(generateEventId())
                        .withEntityId(entityId)
                        .withPointInTime(pointInTime),
                EntityChangesMetadataResponse.class
        ).thenApply(responseStream -> responseStream
                .map(EntityChangesMetadataResponse::getChangeMeta)
                .toList()
        );
    }

    /**
     * Cache key for snapshot searches. Combines model spec, condition, point in time, and search ID.
     */
    private record SearchCacheKey(ModelSpec modelSpec, GroupConditionDto condition, Date pointInTime, UUID searchId) {}

}