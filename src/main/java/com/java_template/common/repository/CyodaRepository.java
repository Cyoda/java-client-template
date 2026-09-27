package com.java_template.common.repository;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Expiry;
import com.google.protobuf.InvalidProtocolBufferException;
import com.java_template.common.auth.CyodaTokenSource;
import com.java_template.common.call.CyodaCallContext;
import com.java_template.common.call.CyodaCallInterceptor;
import com.java_template.common.call.CyodaGrpcCalls;
import com.java_template.common.config.Config;
import com.java_template.common.config.CyodaObjectMapper;
import com.java_template.common.dto.PageResult;
import com.java_template.common.exception.CyodaErrors;
import com.java_template.common.exception.CyodaOperationException;
import com.java_template.common.grpc.client.event_handling.CloudEventBuilder;
import com.java_template.common.grpc.client.event_handling.CloudEventParser;
import com.java_template.common.grpc.client.event_handling.CloudEvents;
import io.cloudevents.v1.proto.CloudEvent;
import io.grpc.Context;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import jakarta.annotation.PreDestroy;
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
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BiFunction;
import java.util.stream.Collectors;
import java.util.stream.Stream;


/**
 * ABOUTME: Concrete implementation of CrudRepository providing entity CRUD operations
 * through gRPC communication with the Cyoda platform backend services.
 *
 * <p>Every call carries the operation's {@link CyodaCallContext} as the {@link CyodaCallInterceptor#CONTEXT}
 * call option, through every stage of a multi-step search, and runs under {@link CyodaGrpcCalls#call}'s retry
 * rules. Blocking stub calls, and the poll and delay stages of a snapshot search, run on a dedicated
 * virtual-thread-per-task executor (spec §4.5). Inside a callout scope ({@code ctx.isJoined()}) search is a
 * direct search capped at {@link #DIRECT_SEARCH_LIMIT} and transaction-control parameters are refused (§4.4).
 */
@Repository
public class CyodaRepository implements CrudRepository {
    private final Logger logger = LoggerFactory.getLogger(this.getClass());

    /** Most entities one direct search returns; inside a callout scope that is the most one read can see. */
    public static final int DIRECT_SEARCH_LIMIT = 10_000;

    /** How long {@link #shutdownExecutor()} lets in-flight calls finish before interrupting them. */
    private static final long EXECUTOR_SHUTDOWN_GRACE_SECONDS = 5;

    private final ObjectMapper objectMapper;
    private final Config config;
    private final CloudEventsServiceGrpc.CloudEventsServiceBlockingStub cloudEventsServiceBlockingStub;
    private final CloudEventBuilder cloudEventBuilder;
    private final CloudEventParser cloudEventParser;
    private final CyodaTokenSource tokenSource;

    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    /** Fallback lifetime for a cached snapshot whose expiration date can't be determined. */
    static final long DEFAULT_SNAPSHOT_CACHE_TTL_NANOS = TimeUnit.HOURS.toNanos(1);

    /** Upper bound on cached snapshot statuses, so many distinct searches cannot grow the cache without limit. */
    static final long SNAPSHOT_CACHE_MAX_SIZE = 1000;

    /**
     * Cache of completed (never RUNNING) snapshot search statuses, keyed by the call context's fingerprint
     * and model/condition/pointInTime/searchId, so one caller's snapshot is never served to a caller with a
     * different credential or transaction. Nothing is cached inside a callout scope. Entries are evicted
     * based on the snapshot's expirationDate, or by size beyond {@link #SNAPSHOT_CACHE_MAX_SIZE} (an evicted
     * entry is re-read from cyoda-go on the next page).
     */
    private final Cache<SearchCacheKey, CompletableFuture<SearchSnapshotStatus>> snapshotCache;

    public CyodaRepository(
            final CyodaObjectMapper wireMapper,
            final CloudEventsServiceGrpc.CloudEventsServiceBlockingStub cloudEventsServiceBlockingStub,
            final CloudEventBuilder cloudEventBuilder,
            final CloudEventParser cloudEventParser,
            final Config config,
            final CyodaTokenSource tokenSource
    ) {
        this.objectMapper = wireMapper.mapper();
        this.cloudEventsServiceBlockingStub = cloudEventsServiceBlockingStub;
        this.cloudEventBuilder = cloudEventBuilder;
        this.cloudEventParser = cloudEventParser;
        this.config = config;
        this.tokenSource = tokenSource;

        // Initialize cache with expiry based on the snapshot's own expirationDate (wall-clock).
        // This is a plain Cache, not a LoadingCache: entries are populated explicitly, in
        // findAllByCondition, only once a snapshot's status is known to be final (SUCCESSFUL) -
        // never a still-RUNNING creation response. There is no automatic loader, since that used
        // to create a brand-new, unrelated snapshot search on a cache miss instead of reading the
        // existing snapshot a caller's searchId names.
        this.snapshotCache = Caffeine.newBuilder()
                .maximumSize(SNAPSHOT_CACHE_MAX_SIZE)
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


    /**
     * Lets in-flight calls finish, then interrupts what is left: an interrupted blocking stub call is
     * cancelled, so a call waiting on a server that has already gone cannot hang shutdown.
     */
    @PreDestroy
    void shutdownExecutor() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(EXECUTOR_SHUTDOWN_GRACE_SECONDS, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Stub for unary calls: it carries the call context and grpc-call-deadline-ms (spec §4.5, "every unary
     * call"). Built per attempt, so a retried call gets a fresh deadline.
     */
    private CloudEventsServiceGrpc.CloudEventsServiceBlockingStub unary(final CyodaCallContext ctx) {
        return cloudEventsServiceBlockingStub
                .withOption(CyodaCallInterceptor.CONTEXT, ctx)
                .withDeadlineAfter(config.getGrpcCallDeadlineMs(), TimeUnit.MILLISECONDS);
    }

    /**
     * Stub for the server-streaming entityManageCollection/entitySearchCollection calls: it carries the call
     * context but no deadline, since their duration grows with the result size, and a deadline would cut a
     * long but healthy stream short.
     */
    private CloudEventsServiceGrpc.CloudEventsServiceBlockingStub streaming(final CyodaCallContext ctx) {
        return cloudEventsServiceBlockingStub.withOption(CyodaCallInterceptor.CONTEXT, ctx);
    }

    @Override
    public CompletableFuture<DataPayload> findById(@NotNull final CyodaCallContext ctx, @NotNull final UUID id) {
        return getById(ctx, id, null);
    }

    @Override
    public CompletableFuture<DataPayload> findById(
            @NotNull final CyodaCallContext ctx,
            @NotNull final UUID id,
            @Nullable final OffsetDateTime pointInTime
    ) {
        return getById(ctx, id, pointInTime);
    }

    private CompletableFuture<DataPayload> getById(
            final CyodaCallContext ctx,
            final UUID entityId,
            @Nullable final OffsetDateTime pointInTime
    ) {
        rejectPointInTimeWhenJoined(ctx, pointInTime);
        return sendAndGet(
                ctx,
                (stub, req) -> stub.entitySearch(req),
                new EntityGetRequest().withId(UUID.randomUUID().toString())
                        .withEntityId(entityId)
                        .withPointInTime(pointInTime),
                EntityResponse.class
        ).thenApply(EntityResponse::getPayload);
    }

    @Override
    public CompletableFuture<PageResult<DataPayload>> findAllByCriteria(
            @NotNull final CyodaCallContext ctx,
            @NotNull final ModelSpec modelSpec,
            @NotNull final GroupConditionDto condition,
            @NotNull final SearchAndRetrievalParams params
    ) {
        if (ctx.isJoined()) {
            rejectPointInTimeWhenJoined(ctx, params.pointInTime());
            requireDirectSearchable(params);
            return findAllByConditionInScope(ctx, modelSpec, params.pageSize(), condition);
        }
        OffsetDateTime pointInTime = params.pointInTime();
        return params.inMemory()
                ? findAllByConditionInMemory(ctx, modelSpec, params.pageSize(), condition, pointInTime)
                : findAllByCondition(ctx, modelSpec, params.pageSize(), params.pageNumber(), condition, pointInTime, params.searchId(), params.awaitLimitMs(), params.pollIntervalMs());
    }

    /**
     * Async snapshot search runs detached from the transaction, so inside a scope only a direct search sees
     * the cascade's writes (spec §4.4). A read it cannot serve fails loudly rather than returning a detached
     * snapshot that misses them.
     */
    private static void requireDirectSearchable(final SearchAndRetrievalParams params) {
        if (params.pageNumber() > 0 || params.searchId() != null) {
            throw new IllegalStateException("inside a callout scope search runs as a direct search (async snapshots do not "
                    + "see the joined transaction's writes): only page 0 can be read");
        }
        if (params.pageSize() > DIRECT_SEARCH_LIMIT) {
            throw new IllegalStateException("inside a callout scope a direct search returns at most " + DIRECT_SEARCH_LIMIT
                    + " entities; requested " + params.pageSize());
        }
    }

    private CompletableFuture<PageResult<DataPayload>> findAllByCondition(
            @NotNull final CyodaCallContext ctx,
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
            finalStatus = createSnapshotSearch(ctx, modelSpec, condition, pointInTime)
                    .thenCompose(created -> resolveFinalStatus(ctx, created, awaitLimitMs, pollIntervalMs));
        } else {
            // A searchId IS the snapshotId of an already-created search (see effectiveSearchId
            // below). Reuse its cached completed status if we have one; otherwise read that same
            // snapshot by ID. Never create a second, unrelated snapshot search for a page request.
            SearchCacheKey cacheKey = new SearchCacheKey(ctx.fingerprint(), modelSpec, condition, pointInTime, searchId);
            CompletableFuture<SearchSnapshotStatus> cached = snapshotCache.getIfPresent(cacheKey);
            finalStatus = cached != null
                    ? cached
                    : getSnapshotStatus(ctx, searchId).thenCompose(fetched -> resolveFinalStatus(ctx, fetched, awaitLimitMs, pollIntervalMs));
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

                    // Cache the final (completed) status for this caller's subsequent page requests.
                    if (!ctx.isJoined()) {
                        SearchCacheKey cacheKey = new SearchCacheKey(ctx.fingerprint(), modelSpec, condition, pointInTime, effectiveSearchId);
                        snapshotCache.put(cacheKey, CompletableFuture.completedFuture(status));
                    }

                    return getSearchResult(ctx, effectiveSearchId, pageSize, pageNumber)
                            .thenApply(data -> PageResult.of(
                                    effectiveSearchId,
                                    data,
                                    pageNumber,
                                    pageSize,
                                    status.getEntitiesCount() != null ? status.getEntitiesCount() : 0L
                            ));
                }, executor)
                .exceptionally(this::handleNotFoundOrThrowPageResult);
    }

    /**
     * Resolves the final ({@code SUCCESSFUL}) status for a snapshot search, polling if needed.
     * If {@code snapshotInfo} is already {@code SUCCESSFUL} - a fast/synchronous search, or one
     * just fetched fresh by ID - its count is used directly without any extra round trip.
     */
    @NotNull
    private CompletableFuture<SearchSnapshotStatus> resolveFinalStatus(
            @NotNull final CyodaCallContext ctx,
            @NotNull final SearchSnapshotStatus snapshotInfo,
            final int awaitLimitMs,
            final int pollIntervalMs
    ) {
        if (SearchSnapshotStatus.Status.SUCCESSFUL.equals(snapshotInfo.getStatus())) {
            return CompletableFuture.completedFuture(snapshotInfo);
        }

        try {
            return waitForSearchCompletion(
                    ctx,
                    snapshotInfo.getSnapshotId(),
                    awaitLimitMs,
                    pollIntervalMs
            );
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private CompletableFuture<PageResult<DataPayload>> findAllByConditionInMemory(
            @NotNull final CyodaCallContext ctx,
            @NotNull final ModelSpec modelSpec,
            final int pageSize,
            @NotNull final GroupConditionDto condition,
            @Nullable final OffsetDateTime pointInTime
    ) {
        return directSearch(ctx, modelSpec, pageSize, condition, pointInTime)
                // In-memory searches don't have snapshot IDs, so searchId is null
                .thenApply(data -> PageResult.of(null, data, 0, pageSize, data.size()))
                .exceptionally(this::handleNotFoundOrThrowPageResult);
    }

    /**
     * Page 0 of a direct search inside a callout scope (never at a point in time: see
     * {@link #rejectPointInTimeWhenJoined}). One entity more than the page is asked for: if it arrives, the page
     * reports a next page ({@code totalElements} is then a lower bound), and asking for that page fails in
     * {@link #requireDirectSearchable} instead of a stream silently ending after page 0. A page of
     * {@link #DIRECT_SEARCH_LIMIT} leaves no room for that probe, so a full one fails with
     * {@link IllegalStateException}: whether more entities exist cannot be told.
     */
    private CompletableFuture<PageResult<DataPayload>> findAllByConditionInScope(
            @NotNull final CyodaCallContext ctx,
            @NotNull final ModelSpec modelSpec,
            final int pageSize,
            @NotNull final GroupConditionDto condition
    ) {
        final boolean probed = pageSize < DIRECT_SEARCH_LIMIT;
        final int limit = probed ? pageSize + 1 : DIRECT_SEARCH_LIMIT;
        return directSearch(ctx, modelSpec, limit, condition, null)
                .thenApply(data -> {
                    if (!probed && data.size() >= DIRECT_SEARCH_LIMIT) {
                        throw new IllegalStateException("inside a callout scope a direct search returns at most "
                                + DIRECT_SEARCH_LIMIT + " entities, and this one returned " + data.size()
                                + ": more may exist, and a direct search cannot read them");
                    }
                    return data.size() > pageSize
                            ? PageResult.of(null, List.copyOf(data.subList(0, pageSize)), 0, pageSize, data.size())
                            : PageResult.of(null, data, 0, pageSize, data.size());
                })
                .exceptionally(this::handleNotFoundOrThrowPageResult);
    }

    private CompletableFuture<List<DataPayload>> directSearch(
            @NotNull final CyodaCallContext ctx,
            @NotNull final ModelSpec modelSpec,
            final int limit,
            @NotNull final GroupConditionDto condition,
            @Nullable final OffsetDateTime pointInTime
    ) {
        return sendAndGetCollection(
                ctx,
                (stub, req) -> stub.entitySearchCollection(req),
                new EntitySearchRequest().withId(generateEventId())
                        .withModel(modelSpec)
                        .withLimit(limit)
                        .withCondition(condition)
                        .withPointInTime(pointInTime),
                EntityResponse.class
        ).thenApply(entities -> entities.map(EntityResponse::getPayload).toList());
    }

    @Override
    public CompletableFuture<PageResult<DataPayload>> findAll(
            @NotNull final CyodaCallContext ctx,
            @NotNull final ModelSpec modelSpec,
            @NotNull final SearchAndRetrievalParams params
    ) {
        // Create an empty condition to match all entities
        GroupConditionDto matchAllCondition = new GroupConditionDto()
                .operator(GroupConditionDto.OperatorEnum.AND)
                .conditions(List.of());

        if (ctx.isJoined()) {
            rejectPointInTimeWhenJoined(ctx, params.pointInTime());
            requireDirectSearchable(params);
            return findAllByConditionInScope(ctx, modelSpec, params.pageSize(), matchAllCondition);
        }
        OffsetDateTime pointInTime = params.pointInTime();
        return params.inMemory()
                ? findAllByConditionInMemory(ctx, modelSpec, params.pageSize(), matchAllCondition, pointInTime)
                : findAllByCondition(ctx, modelSpec, params.pageSize(), params.pageNumber(), matchAllCondition, pointInTime, params.searchId(), params.awaitLimitMs(), params.pollIntervalMs());
    }

    @Override
    public <ENTITY_TYPE> CompletableFuture<EntityTransactionResponse> save(
            @NotNull final CyodaCallContext ctx,
            @NotNull final ModelSpec modelSpec,
            @NotNull final ENTITY_TYPE entity
    ) {
        return saveNewEntities(ctx, modelSpec, entity);
    }

    @Override
    public <ENTITY_TYPE> CompletableFuture<EntityTransactionResponse> saveAll(
            @NotNull final CyodaCallContext ctx,
            @NotNull final ModelSpec modelSpec,
            @NotNull final Collection<ENTITY_TYPE> entities
    ) {
        return saveNewEntities(ctx, modelSpec, entities);
    }

    @Override
    public <ENTITY_TYPE> CompletableFuture<List<EntityTransactionResponse>> saveAll(
            @NotNull final CyodaCallContext ctx,
            @NotNull final ModelSpec modelSpec,
            @NotNull final Collection<ENTITY_TYPE> entities,
            @Nullable final Integer transactionWindow,
            @Nullable final Long transactionTimeoutMs
    ) {
        rejectTransactionControlWhenJoined(ctx, transactionWindow, transactionTimeoutMs);
        return saveNewEntitiesWithTransactionParams(ctx, modelSpec, entities, transactionWindow, transactionTimeoutMs);
    }

    /**
     * A point-in-time read is not part of the contract inside an open transaction (spec clarification 3): a
     * request joined to a callout's transaction reads that transaction's latest view. Refused loudly, before
     * sending, rather than silently dropped. The framework's own reloads inside a scope pass no point in time.
     */
    private static void rejectPointInTimeWhenJoined(final CyodaCallContext ctx, @Nullable final OffsetDateTime pointInTime) {
        if (ctx.isJoined() && pointInTime != null) {
            throw new IllegalArgumentException("pointInTime is refused on a request joined to a callout's transaction: "
                    + "point-in-time reads are not supported inside a callout's transaction (spec clarification 3); "
                    + "read without pointInTime to see the transaction's latest view");
        }
    }

    /** cyoda refuses transaction-control parameters on a joined request (spec §4.4); refuse them before sending. */
    private static void rejectTransactionControlWhenJoined(
            final CyodaCallContext ctx,
            @Nullable final Integer transactionWindow,
            @Nullable final Long transactionTimeoutMs
    ) {
        if (!ctx.isJoined()) {
            return;
        }
        if (transactionWindow != null) {
            throw new IllegalArgumentException("transactionWindow is refused on a request joined to a callout's transaction");
        }
        if (transactionTimeoutMs != null) {
            throw new IllegalArgumentException("transactionTimeoutMs is refused on a request joined to a callout's transaction");
        }
    }

    @Override
    public CompletableFuture<EntityTransitionResponse> applyTransition(
            @NotNull final CyodaCallContext ctx,
            @NotNull final UUID entityId,
            @NotNull final String transitionName
    ) {
        return sendAndGet(
                ctx,
                (stub, req) -> stub.entityManage(req),
                new EntityTransitionRequest().withId(generateEventId())
                        .withEntityId(entityId)
                        .withTransition(transitionName),
                EntityTransitionResponse.class
        );
    }

    @Override
    public <ENTITY_TYPE> CompletableFuture<EntityTransactionResponse> update(
            @NotNull final CyodaCallContext ctx,
            @NotNull final UUID id,
            @NotNull final ENTITY_TYPE entity,
            @Nullable final String transition
    ) {
        return sendAndGet(
                ctx,
                (stub, req) -> stub.entityManage(req),
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
            @NotNull final CyodaCallContext ctx,
            @NotNull final Collection<ENTITY_TYPE> entities,
            @Nullable final String transition
    ) {
        return updateAll(ctx, entities, transition, null, null);
    }

    @Override
    public <ENTITY_TYPE> CompletableFuture<List<EntityTransactionResponse>> updateAll(
            @NotNull final CyodaCallContext ctx,
            @NotNull final Collection<ENTITY_TYPE> entities,
            @Nullable final String transition,
            @Nullable final Integer transactionWindow,
            @Nullable final Long transactionTimeoutMs
    ) {
        rejectTransactionControlWhenJoined(ctx, transactionWindow, transactionTimeoutMs);
        final var entitiesByIds = entities.stream()
                .map(objectMapper::valueToTree)
                .map(entity -> (JsonNode) entity)
                .collect(Collectors.toMap(
                                entity -> UUID.fromString(entity.get("id").asText()),
                                entity -> entity
                        )
                );

        return sendAndGetCollection(
                ctx,
                (stub, req) -> stub.entityManageCollection(req),
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
    public CompletableFuture<EntityDeleteResponse> deleteById(@NotNull final CyodaCallContext ctx, @NotNull final UUID id) {
        return deleteEntity(ctx, id);
    }

    @Override
    public CompletableFuture<List<EntityDeleteAllResponse>> deleteAll(
            @NotNull final CyodaCallContext ctx,
            @NotNull final ModelSpec modelSpec
    ) {
        return deleteAllByModel(ctx, modelSpec);
    }

    /**
     * One unary call under {@link CyodaGrpcCalls#call}'s retry rules, on the virtual-thread executor. The
     * attempt lets a raw gRPC {@code StatusRuntimeException} propagate unmapped (so a rejected M2M token is
     * retried), and an envelope failure surfaces as the typed exception {@link #validateResponse} maps it to
     * (so {@code JOINED_RETRYABLE} codes are retried).
     */
    private <RESPONSE_PAYLOAD_TYPE extends BaseEvent> CompletableFuture<RESPONSE_PAYLOAD_TYPE> sendAndGet(
            final CyodaCallContext ctx,
            final BiFunction<CloudEventsServiceGrpc.CloudEventsServiceBlockingStub, CloudEvent, CloudEvent> apiCall,
            final BaseEvent baseEvent,
            final Class<RESPONSE_PAYLOAD_TYPE> responsePayloadType
    ) {
        final CloudEvent requestEvent = build(baseEvent);
        return CompletableFuture.supplyAsync(() -> CyodaGrpcCalls.call(ctx, tokenSource, () -> {
            logger.debug("Sending event: {}", CloudEvents.describe(requestEvent));
            CloudEvent response = apiCall.apply(unary(ctx), requestEvent);
            logger.debug("Received event: {}", CloudEvents.describe(response));
            return validateResponse(cloudEventParser.parseCloudEvent(response, responsePayloadType), ctx.isJoined());
        }), executor);
    }

    /**
     * One server-streaming call under {@link CyodaGrpcCalls#call}'s retry rules, on the virtual-thread
     * executor. The stream is read to the end inside the attempt, so an envelope failure anywhere in it
     * surfaces in (and can retry) the call that caused it; an attempt abandoned part-way is cancelled, so a
     * retry never leaves the previous stream open.
     *
     * <p>Retrying the collection writes (entityManageCollection: saveAll, updateAll, deleteAll) is safe only
     * because every code this path retries is refused by cyoda-go before anything is applied. Verified in the
     * cyoda-go v0.9 sources:
     * <ul>
     *   <li>{@code UNAUTHENTICATED}: the auth interceptor rejects the call before any handler runs.</li>
     *   <li>{@code TOO_MANY_JOINED_REQUESTS}: emitted only by {@code tooManyJoinedRequests()} in
     *       {@code internal/domain/txjoin/txjoin.go}, from {@code Joiner.CheckRoom} (which
     *       {@code internal/grpc/txroute_interceptor.go} asks before it even receives the request message) and
     *       from the lock gate in {@code Joiner.RunVerified}, which refuses before the handler runs ("it still
     *       mutates nothing"). A peer-forwarded call meets the same admission on the owning node. The help
     *       topic {@code errors/TOO_MANY_JOINED_REQUESTS.md}: "The refused callback changes nothing".</li>
     *   <li>{@code TRANSACTION_NODE_UNAVAILABLE}: on gRPC, emitted only by {@code classifyRouteErr} in
     *       {@code txroute_interceptor.go} when {@code proxy.ResolveNodeInfo} finds the token's owner dead or
     *       unknown, before the call is forwarded or handled. A forward that fails after reaching the owner is
     *       not mapped to this code (it surfaces as a generic envelope or a raw gRPC status, not retried here).
     *       On REST, by contrast, the reverse proxy's ErrorHandler answers 503 TRANSACTION_NODE_UNAVAILABLE,
     *       possibly after the owner applied the write, which is why no REST path retries it.</li>
     * </ul>
     * If cyoda-go ever emits either joined code after applying part of a collection, this retry must go.
     */
    private <RESPONSE_PAYLOAD_TYPE extends BaseEvent> CompletableFuture<Stream<RESPONSE_PAYLOAD_TYPE>> sendAndGetCollection(
            final CyodaCallContext ctx,
            final BiFunction<CloudEventsServiceGrpc.CloudEventsServiceBlockingStub, CloudEvent, Iterator<CloudEvent>> apiCall,
            final BaseEvent baseEvent,
            final Class<RESPONSE_PAYLOAD_TYPE> responsePayloadClass
    ) {
        final CloudEvent requestEvent = build(baseEvent);
        return CompletableFuture.supplyAsync(() -> CyodaGrpcCalls.call(ctx, tokenSource, () -> {
            Context.CancellableContext attempt = Context.current().withCancellation();
            Context previous = attempt.attach();
            try {
                List<RESPONSE_PAYLOAD_TYPE> all = new ArrayList<>();
                Iterator<CloudEvent> responses = apiCall.apply(streaming(ctx), requestEvent);
                while (responses.hasNext()) {
                    CloudEvent response = responses.next();
                    // Filter null CloudEvent entries that may arrive from the gRPC streaming iterator
                    if (response != null) {
                        all.add(validateResponse(cloudEventParser.parseCloudEvent(response, responsePayloadClass), ctx.isJoined()));
                    }
                }
                return all.stream();
            } finally {
                attempt.detach(previous);
                attempt.cancel(null);
            }
        }), executor);
    }

    private CloudEvent build(final BaseEvent event) {
        try {
            return cloudEventBuilder.buildEvent(event);
        } catch (InvalidProtocolBufferException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Validates a Cyoda response: logs any warnings, then throws if the operation failed.
     * Package-private to allow direct unit testing without mocking the full gRPC pipeline.
     */
    <T extends BaseEvent> T validateResponse(T response, boolean joined) {
        if (response.getWarnings() != null && !response.getWarnings().isEmpty()) {
            response.getWarnings().forEach(w -> logger.warn("Cyoda warning: {}", w));
        }
        if (Boolean.FALSE.equals(response.getSuccess())) {
            throw CyodaErrors.fromGrpcEnvelope(response.getError(), joined);
        }
        return response;
    }

    private <PAYLOAD_TYPE> CompletableFuture<EntityTransactionResponse> saveNewEntities(
            @NotNull final CyodaCallContext ctx,
            @NotNull final ModelSpec modelSpec,
            @NotNull final PAYLOAD_TYPE entities
    ) {
        return sendAndGet(
                ctx,
                (stub, req) -> stub.entityManage(req),
                new EntityCreateRequest().withId(generateEventId())
                        .withDataFormat(config.getGrpcCommunicationDataFormat())
                        .withPayload(new EntityCreatePayload().withData(objectMapper.valueToTree(entities))
                                .withModel(modelSpec)
                        ),
                EntityTransactionResponse.class
        );
    }

    private <PAYLOAD_TYPE> CompletableFuture<List<EntityTransactionResponse>> saveNewEntitiesWithTransactionParams(
            @NotNull final CyodaCallContext ctx,
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
                ctx,
                (stub, req) -> stub.entityManageCollection(req),
                new EntityCreateCollectionRequest().withId(generateEventId())
                        .withDataFormat(config.getGrpcCommunicationDataFormat())
                        .withTransactionWindow(transactionWindow)
                        .withTransactionTimeoutMs(transactionTimeoutMs)
                        .withPayloads(payloads),
                EntityTransactionResponse.class
        ).thenApply(Stream::toList);
    }

    private CompletableFuture<EntityDeleteResponse> deleteEntity(@NotNull final CyodaCallContext ctx, @NotNull final UUID id) {
        return sendAndGet(
                ctx,
                (stub, req) -> stub.entityManage(req),
                new EntityDeleteRequest().withId(generateEventId()).withEntityId(id),
                EntityDeleteResponse.class
        );
    }

    private CompletableFuture<List<EntityDeleteAllResponse>> deleteAllByModel(
            @NotNull final CyodaCallContext ctx,
            @NotNull final ModelSpec modelSpec
    ) {
        return sendAndGetCollection(
                ctx,
                (stub, req) -> stub.entityManageCollection(req),
                new EntityDeleteAllRequest().withId(generateEventId())
                        .withModel(modelSpec),
                EntityDeleteAllResponse.class
        ).thenApply(Stream::toList);
    }

    private String generateEventId() {
        return UUID.randomUUID().toString();
    }

    private CompletableFuture<SearchSnapshotStatus> createSnapshotSearch(
            final CyodaCallContext ctx,
            final ModelSpec modelSpec,
            final GroupConditionDto condition,
            @Nullable final OffsetDateTime pointInTime
    ) {
        return sendAndGet(
                ctx,
                (stub, req) -> stub.entitySearch(req),
                new EntitySnapshotSearchRequest().withId(generateEventId())
                        .withModel(modelSpec)
                        .withCondition(condition)
                        .withPointInTime(pointInTime),
                EntitySnapshotSearchResponse.class
        ).thenApply(EntitySnapshotSearchResponse::getStatus);
    }

    private CompletableFuture<SearchSnapshotStatus> waitForSearchCompletion(
            @NotNull final CyodaCallContext ctx,
            @NotNull final UUID snapshotId,
            final long awaitLimitMillis,
            final long intervalMillis
    ) throws IOException {
        final var startTime = System.currentTimeMillis();
        return pollSnapshotStatus(ctx, snapshotId, startTime, awaitLimitMillis, intervalMillis);
    }

    private CompletableFuture<SearchSnapshotStatus> pollSnapshotStatus(
            @NotNull final CyodaCallContext ctx,
            @NotNull final UUID snapshotId,
            final long startTime,
            final long awaitLimitMillis,
            final long intervalMillis
    ) throws IOException {
        logger.debug("Polling snapshot: {}", snapshotId);
        return getSnapshotStatus(ctx, snapshotId).thenCompose(snapshotStatus -> {
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

            return pollDelay(intervalMillis).thenCompose(ignored -> {
                try {
                    return pollSnapshotStatus(ctx, snapshotId, startTime, awaitLimitMillis, intervalMillis);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });
        });
    }

    /**
     * Completes after {@code intervalMillis}, on the executor. The delayed hand-off to the executor happens on
     * the JDK's delay scheduler, which swallows a rejection: if the repository has been shut down meanwhile,
     * the delay completes exceptionally instead, so the poll fails rather than leaving a caller's join() hanging.
     */
    private CompletableFuture<Void> pollDelay(final long intervalMillis) {
        final CompletableFuture<Void> delay = new CompletableFuture<>();
        final Executor resubmit = task -> {
            try {
                executor.execute(task);
            } catch (RejectedExecutionException rejected) {
                IllegalStateException shutDown =
                        new IllegalStateException("CyodaRepository is shut down: the snapshot poll was abandoned");
                shutDown.addSuppressed(rejected);
                delay.completeExceptionally(shutDown);
            }
        };
        CompletableFuture.delayedExecutor(intervalMillis, TimeUnit.MILLISECONDS, resubmit).execute(() -> delay.complete(null));
        return delay;
    }

    private CompletableFuture<SearchSnapshotStatus> getSnapshotStatus(
            @NotNull final CyodaCallContext ctx,
            @NotNull final UUID snapshotId
    ) {
        return sendAndGet(
                ctx,
                (stub, req) -> stub.entitySearch(req),
                new SnapshotGetStatusRequest().withId(generateEventId()).withSnapshotId(snapshotId),
                EntitySnapshotSearchResponse.class
        ).thenApply(EntitySnapshotSearchResponse::getStatus);
    }

    private CompletableFuture<List<DataPayload>> getSearchResult(
            @NotNull final CyodaCallContext ctx,
            @NotNull final UUID snapshotId,
            final int pageSize,
            final int pageNumber
    ) {
        return sendAndGetCollection(
                ctx,
                (stub, req) -> stub.entitySearchCollection(req),
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
    public CompletableFuture<Long> getEntityCount(@NotNull final CyodaCallContext ctx, @NotNull final ModelSpec modelSpec) {
        return getEntityCount(ctx, modelSpec, null);
    }

    @Override
    public CompletableFuture<Long> getEntityCount(
            @NotNull final CyodaCallContext ctx,
            @NotNull final ModelSpec modelSpec,
            @Nullable final OffsetDateTime pointInTime
    ) {
        rejectPointInTimeWhenJoined(ctx, pointInTime);
        return sendAndGetCollection(
                ctx,
                (stub, req) -> stub.entitySearchCollection(req),
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
    public CompletableFuture<Map<String, Long>> getEntityStatsByState(@NotNull final CyodaCallContext ctx, @NotNull final ModelSpec modelSpec) {
        return getEntityStatsByState(ctx, modelSpec, Collections.emptyList(), null);
    }

    @Override
    public CompletableFuture<Map<String, Long>> getEntityStatsByState(
            @NotNull final CyodaCallContext ctx,
            @NotNull final ModelSpec modelSpec,
            @Nullable final OffsetDateTime pointInTime
    ) {
        return getEntityStatsByState(ctx, modelSpec, Collections.emptyList(), pointInTime);
    }

    @Override
    public CompletableFuture<Map<String, Long>> getEntityStatsByState(
            @NotNull final CyodaCallContext ctx,
            @NotNull final ModelSpec modelSpec,
            @NotNull final List<String> states,
            @Nullable final OffsetDateTime pointInTime
    ) {
        rejectPointInTimeWhenJoined(ctx, pointInTime);
        return sendAndGetCollection(
                ctx,
                (stub, req) -> stub.entitySearchCollection(req),
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
            @NotNull final CyodaCallContext ctx,
            @NotNull final UUID entityId,
            @Nullable final OffsetDateTime pointInTime
    ) {
        rejectPointInTimeWhenJoined(ctx, pointInTime);
        return sendAndGetCollection(
                ctx,
                (stub, req) -> stub.entitySearchCollection(req),
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
     * Cache key for snapshot searches: the call context's fingerprint (credential kind, a SHA-256 of a
     * forwarded token, and of a tx-token; never a raw token), model spec, condition, point in time and
     * search ID.
     */
    private record SearchCacheKey(
            String contextFingerprint,
            ModelSpec modelSpec,
            GroupConditionDto condition,
            OffsetDateTime pointInTime,
            UUID searchId
    ) {}

}
