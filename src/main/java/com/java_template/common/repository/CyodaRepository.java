package com.java_template.common.repository;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Expiry;
import com.google.common.collect.Streams;
import com.google.protobuf.InvalidProtocolBufferException;
import com.java_template.common.config.Config;
import com.java_template.common.config.CyodaObjectMapper;
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
import java.time.Duration;
import java.time.OffsetDateTime;
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

    /** Fallback lifetime for a cached snapshot whose expiration date can't be determined. */
    static final long DEFAULT_SNAPSHOT_CACHE_TTL_NANOS = TimeUnit.HOURS.toNanos(1);

    /**
     * Cache of completed (never RUNNING) snapshot search statuses, keyed by model/condition/
     * pointInTime/searchId. Entries are evicted based on the snapshot's expirationDate.
     */
    private final Cache<SearchCacheKey, CompletableFuture<SearchSnapshotStatus>> snapshotCache;

    public CyodaRepository(
            final CyodaObjectMapper wireMapper,
            final CloudEventsServiceGrpc.CloudEventsServiceBlockingStub cloudEventsServiceBlockingStub,
            final CloudEventBuilder cloudEventBuilder,
            final CloudEventParser cloudEventParser,
            final Config config
    ) {
        this.objectMapper = wireMapper.mapper();
        this.cloudEventsServiceBlockingStub = cloudEventsServiceBlockingStub;
        this.cloudEventBuilder = cloudEventBuilder;
        this.cloudEventParser = cloudEventParser;
        this.config = config;

        // Initialize cache with expiry based on the snapshot's own expirationDate (wall-clock).
        // This is a plain Cache, not a LoadingCache: entries are populated explicitly, in
        // findAllByCondition, only once a snapshot's status is known to be final (SUCCESSFUL) -
        // never a still-RUNNING creation response. There is no automatic loader, since that used
        // to create a brand-new, unrelated snapshot search on a cache miss instead of reading the
        // existing snapshot a caller's searchId names.
        this.snapshotCache = Caffeine.newBuilder()
                .expireAfter(new Expiry<SearchCacheKey, CompletableFuture<SearchSnapshotStatus>>() {
                    @Override
                    public long expireAfterCreate(SearchCacheKey key, CompletableFuture<SearchSnapshotStatus> value, long currentTime) {
                        return getExpirationDuration(value);
                    }

                    @Override
                    public long expireAfterUpdate(SearchCacheKey key, CompletableFuture<SearchSnapshotStatus> value, long currentTime, long currentDuration) {
                        return getExpirationDuration(value);
                    }

                    @Override
                    public long expireAfterRead(SearchCacheKey key, CompletableFuture<SearchSnapshotStatus> value, long currentTime, long currentDuration) {
                        return currentDuration;
                    }

                    private long getExpirationDuration(CompletableFuture<SearchSnapshotStatus> value) {
                        try {
                            SearchSnapshotStatus status = value.getNow(null);
                            OffsetDateTime expirationDate = status != null ? status.getExpirationDate() : null;
                            return remainingNanosUntil(expirationDate, OffsetDateTime.now());
                        } catch (Exception e) {
                            logger.debug("Could not determine expiration time, using default", e);
                            return DEFAULT_SNAPSHOT_CACHE_TTL_NANOS;
                        }
                    }
                })
                .build();
    }

    /**
     * Remaining nanoseconds from {@code now} (wall-clock) until {@code expirationDate}, floored at
     * 0 for an already-expired snapshot. Falls back to {@link #DEFAULT_SNAPSHOT_CACHE_TTL_NANOS}
     * when {@code expirationDate} is unknown. Caffeine's {@code Expiry} callbacks receive a
     * ticker time ({@code System.nanoTime()}-based, not epoch) that cannot be compared against an
     * epoch-millis value directly, so this deliberately ignores that ticker time and only uses
     * wall-clock instants. Package-private so it can be unit tested directly.
     */
    static long remainingNanosUntil(@Nullable final OffsetDateTime expirationDate, @NotNull final OffsetDateTime now) {
        if (expirationDate == null) {
            return DEFAULT_SNAPSHOT_CACHE_TTL_NANOS;
        }
        return Math.max(0L, Duration.between(now, expirationDate).toNanos());
    }

    private CloudEventsServiceGrpc.CloudEventsServiceBlockingStub blocking() {
        return cloudEventsServiceBlockingStub.withDeadlineAfter(config.getGrpcCallDeadlineMs(), TimeUnit.MILLISECONDS);
    }

    @Override
    public CompletableFuture<DataPayload> findById(@NotNull final UUID id) {
        return getById(id, null);
    }

    @Override
    public CompletableFuture<DataPayload> findById(@NotNull final UUID id, @Nullable final OffsetDateTime pointInTime) {
        return getById(id, pointInTime);
    }

    private CompletableFuture<DataPayload> getById(final UUID entityId, @Nullable final OffsetDateTime pointInTime) {
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
        OffsetDateTime pointInTime = params.pointInTime();
        return params.inMemory()
                ? findAllByConditionInMemory(modelSpec, params.pageSize(), condition, pointInTime)
                : findAllByCondition(modelSpec, params.pageSize(), params.pageNumber(), condition, pointInTime, params.searchId(), params.awaitLimitMs(), params.pollIntervalMs());
    }

    private CompletableFuture<PageResult<DataPayload>> findAllByCondition(
            @NotNull final ModelSpec modelSpec,
            final int pageSize,
            final int pageNumber,
            @NotNull final GroupConditionDto condition,
            @Nullable final OffsetDateTime pointInTime,
            @Nullable final UUID searchId, int awaitLimitMs, int pollIntervalMs
    ) {
        CompletableFuture<SearchSnapshotStatus> finalStatus;

        if (searchId == null) {
            // New search - create a snapshot and wait for it to reach a final status.
            finalStatus = createSnapshotSearch(modelSpec, condition, pointInTime)
                    .thenCompose(created -> resolveFinalStatus(created, awaitLimitMs, pollIntervalMs));
        } else {
            // A searchId IS the snapshotId of an already-created search (see effectiveSearchId
            // below). Reuse its cached completed status if we have one; otherwise read that same
            // snapshot by ID. Never create a second, unrelated snapshot search for a page request.
            SearchCacheKey cacheKey = new SearchCacheKey(modelSpec, condition, pointInTime, searchId);
            CompletableFuture<SearchSnapshotStatus> cached = snapshotCache.getIfPresent(cacheKey);
            finalStatus = cached != null
                    ? cached
                    : getSnapshotStatus(searchId).thenCompose(fetched -> resolveFinalStatus(fetched, awaitLimitMs, pollIntervalMs));
        }

        return finalStatus.thenComposeAsync(status -> {
                    if (status.getSnapshotId() == null) {
                        throw new CyodaOperationException(
                                "MISSING_SNAPSHOT_ID",
                                "Snapshot search returned no snapshot ID",
                                false
                        );
                    }

                    // Use the snapshot ID from Cyoda as the search ID
                    UUID effectiveSearchId = status.getSnapshotId();

                    // Cache the final (completed) status for subsequent page requests.
                    SearchCacheKey cacheKey = new SearchCacheKey(modelSpec, condition, pointInTime, effectiveSearchId);
                    snapshotCache.put(cacheKey, CompletableFuture.completedFuture(status));

                    return getSearchResult(effectiveSearchId, pageSize, pageNumber)
                            .thenApply(data -> PageResult.of(
                                    effectiveSearchId,
                                    data,
                                    pageNumber,
                                    pageSize,
                                    status.getEntitiesCount() != null ? status.getEntitiesCount() : 0L
                            ));
                })
                .exceptionally(this::handleNotFoundOrThrowPageResult);
    }

    /**
     * Resolves the final ({@code SUCCESSFUL}) status for a snapshot search, polling if needed.
     * If {@code snapshotInfo} is already {@code SUCCESSFUL} - a fast/synchronous search, or one
     * just fetched fresh by ID - its count is used directly without any extra round trip.
     */
    @NotNull
    private CompletableFuture<SearchSnapshotStatus> resolveFinalStatus(
            @NotNull final SearchSnapshotStatus snapshotInfo, final int awaitLimitMs, final int pollIntervalMs
    ) {
        if (SearchSnapshotStatus.Status.SUCCESSFUL.equals(snapshotInfo.getStatus())) {
            return CompletableFuture.completedFuture(snapshotInfo);
        }

        try {
            return waitForSearchCompletion(
                    snapshotInfo.getSnapshotId(),
                    awaitLimitMs,
                    pollIntervalMs
            );
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private CompletableFuture<PageResult<DataPayload>> findAllByConditionInMemory(
            @NotNull final ModelSpec modelSpec,
            final int pageSize,
            @NotNull final GroupConditionDto condition,
            @Nullable final OffsetDateTime pointInTime
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

        OffsetDateTime pointInTime = params.pointInTime();
        return params.inMemory()
                ? findAllByConditionInMemory(modelSpec, params.pageSize(), matchAllCondition, pointInTime)
                : findAllByCondition(modelSpec, params.pageSize(), params.pageNumber(), matchAllCondition, pointInTime, params.searchId(), params.awaitLimitMs(), params.pollIntervalMs());
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
            @Nullable final OffsetDateTime pointInTime
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

    private CompletableFuture<SearchSnapshotStatus> waitForSearchCompletion(
            @NotNull final UUID snapshotId,
            final long awaitLimitMillis,
            final long intervalMillis
    ) throws IOException {
        final var startTime = System.currentTimeMillis();
        return pollSnapshotStatus(snapshotId, startTime, awaitLimitMillis, intervalMillis);
    }

    private CompletableFuture<SearchSnapshotStatus> pollSnapshotStatus(
            @NotNull final UUID snapshotId,
            final long startTime,
            final long awaitLimitMillis,
            final long intervalMillis
    ) throws IOException {
        logger.debug("Polling snapshot: {}", snapshotId);
        return getSnapshotStatus(snapshotId).thenCompose(snapshotStatus -> {
            if (SearchSnapshotStatus.Status.SUCCESSFUL.equals(snapshotStatus.getStatus())) {
                logger.debug("Snapshot is ready!");
                return CompletableFuture.completedFuture(snapshotStatus);
            }
            if (!SearchSnapshotStatus.Status.RUNNING.equals(snapshotStatus.getStatus())) {
                return CompletableFuture.failedFuture(
                        new RuntimeException("Snapshot search failed: " + snapshotStatus.getStatus())
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

    private CompletableFuture<SearchSnapshotStatus> getSnapshotStatus(@NotNull final UUID snapshotId) {
        return sendAndGet(
                req -> blocking().entitySearch(req),
                new SnapshotGetStatusRequest().withId(generateEventId()).withSnapshotId(snapshotId),
                EntitySnapshotSearchResponse.class
        ).thenApply(EntitySnapshotSearchResponse::getStatus);
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
    public CompletableFuture<Long> getEntityCount(@NotNull final ModelSpec modelSpec, @Nullable final OffsetDateTime pointInTime) {
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
            @Nullable final OffsetDateTime pointInTime
    ) {
        return getEntityStatsByState(modelSpec, Collections.emptyList(), pointInTime);
    }

    @Override
    public CompletableFuture<Map<String, Long>> getEntityStatsByState(
            @NotNull final ModelSpec modelSpec,
            @NotNull final List<String> states,
            @Nullable final OffsetDateTime pointInTime
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
            @Nullable final OffsetDateTime pointInTime
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
    private record SearchCacheKey(ModelSpec modelSpec, GroupConditionDto condition, OffsetDateTime pointInTime, UUID searchId) {}

}