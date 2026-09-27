package com.java_template.common.repository;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.java_template.common.auth.CyodaTokenSource;
import com.java_template.common.call.CalloutScope;
import com.java_template.common.call.CyodaCallContext;
import com.java_template.common.call.CyodaCallContexts;
import com.java_template.common.call.CyodaCallInterceptor;
import com.java_template.common.config.Config;
import com.java_template.common.config.CyodaObjectMapper;
import com.java_template.common.dto.EntityWithMetadata;
import com.java_template.common.dto.PageResult;
import com.java_template.common.exception.CyodaRetryableException;
import com.java_template.common.grpc.client.event_handling.CloudEventParser;
import com.java_template.common.service.EntityServiceImpl;
import com.java_template.common.workflow.CyodaEntity;
import com.java_template.common.workflow.OperationSpecification;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import org.cyoda.cloud.api.common.model.GroupConditionDto;
import org.cyoda.cloud.api.event.common.BaseEvent;
import org.cyoda.cloud.api.event.common.DataPayload;
import org.cyoda.cloud.api.event.common.Error;
import org.cyoda.cloud.api.event.common.ModelSpec;
import org.cyoda.cloud.api.event.search.EntityResponse;
import org.cyoda.cloud.api.event.search.EntitySnapshotSearchResponse;
import org.cyoda.cloud.api.event.search.SearchSnapshotStatus;
import org.cyoda.cloud.api.grpc.CloudEventsServiceGrpc;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ABOUTME: Every call CyodaRepository makes carries the operation's CyodaCallContext (credential and tx-token),
 * across every stage of a paged snapshot search, and the joined-scope rules (direct search only, 10 000 cap,
 * transaction-control parameters refused) hold before anything reaches the wire (spec §4.2, §4.4).
 */
class CyodaRepositoryContextTest {

    private final CyodaObjectMapper wireMapper = CyodaObjectMapper.standalone();
    private final ObjectMapper om = wireMapper.mapper();
    private RecordingCyodaServer server;
    private CyodaTokenSource tokens;
    private CyodaRepository repo;
    private final ModelSpec spec = new ModelSpec().withName("m").withVersion(1);
    private final GroupConditionDto all = new GroupConditionDto().operator(GroupConditionDto.OperatorEnum.AND).conditions(List.of());

    @BeforeEach
    void setUp() throws Exception {
        server = new RecordingCyodaServer(wireMapper);
        tokens = mock(CyodaTokenSource.class);
        when(tokens.bearerToken()).thenReturn(Optional.of("m2m-token"));
        var stub = CloudEventsServiceGrpc.newBlockingStub(server.channel).withInterceptors(new CyodaCallInterceptor(tokens));
        Config config = new Config();
        repo = new CyodaRepository(wireMapper, stub, server.builder, new CloudEventParser(wireMapper), config, tokens);
    }

    @AfterEach
    void tearDown() {
        repo.shutdownExecutor();
        server.close();
    }

    private EntityResponse entity(int i) {
        return new EntityResponse().withId(UUID.randomUUID().toString()).withSuccess(true)
                .withPayload(new DataPayload().withType("ENTITY").withData(om.createObjectNode().put("i", i)));
    }

    private static EntityResponse failedEnvelope(String message) {
        return new EntityResponse().withId(UUID.randomUUID().toString()).withSuccess(false)
                .withError(new Error().withCode("SERVER_ERROR").withMessage(message).withRetryable(true));
    }

    @Test
    void everyCallOfAPagedSnapshotSearchCarriesTheSameContext() {
        UUID snapshot = UUID.randomUUID();
        server.unary = ce -> switch (ce.getType()) {
            case "EntitySnapshotSearchRequest" -> new EntitySnapshotSearchResponse().withId("r1").withSuccess(true)
                    .withStatus(new SearchSnapshotStatus().withSnapshotId(snapshot).withEntitiesCount(2L)
                            .withStatus(SearchSnapshotStatus.Status.RUNNING));
            case "SnapshotGetStatusRequest" -> new EntitySnapshotSearchResponse().withId("r2").withSuccess(true)
                    .withStatus(new SearchSnapshotStatus().withSnapshotId(snapshot).withEntitiesCount(2L)
                            .withStatus(SearchSnapshotStatus.Status.SUCCESSFUL));
            default -> throw new IllegalStateException(ce.getType());
        };
        server.collection = ce -> List.of(entity(1), entity(2));
        CyodaCallContext ctx = CyodaCallContext.forward("user-token");

        repo.findAllByCriteria(ctx, spec, all, SearchAndRetrievalParams.builder().pageSize(10).pollIntervalMs(10).build()).join();

        assertThat(server.seen).extracting(RecordingCyodaServer.Seen::type)
                .containsExactly("EntitySnapshotSearchRequest", "SnapshotGetStatusRequest", "SnapshotGetRequest");
        assertThat(server.seen).allSatisfy(s -> {
            assertThat(s.authorization()).isEqualTo("Bearer user-token");
            assertThat(s.txToken()).isNull();
        });
    }

    @Test
    void insideAScopeSearchIsDirectAndJoined() {
        server.collection = ce -> List.of(entity(1));
        CyodaCallContext joined = CyodaCallContext.m2m().withTxToken("tx-1");

        var page = repo.findAllByCriteria(joined, spec, all, SearchAndRetrievalParams.defaults()).join();

        assertThat(page.data()).hasSize(1);
        assertThat(server.seen).singleElement().satisfies(s -> {
            assertThat(s.type()).isEqualTo("EntitySearchRequest");
            assertThat(s.txToken()).isEqualTo("tx-1");
            assertThat(s.authorization()).isEqualTo("Bearer m2m-token");
        });
    }

    @Test
    void insideAScopeFindAllIsDirectAndJoinedToo() {
        server.collection = ce -> List.of(entity(1), entity(2));
        CyodaCallContext joined = CyodaCallContext.m2m().withTxToken("tx-1");

        var page = repo.findAll(joined, spec, SearchAndRetrievalParams.defaults()).join();

        assertThat(page.data()).hasSize(2);
        assertThat(page.hasNext()).isFalse();
        assertThat(server.seen).singleElement().satisfies(s -> {
            assertThat(s.type()).isEqualTo("EntitySearchRequest");
            assertThat(s.txToken()).isEqualTo("tx-1");
        });
    }

    @Test
    void insideAScopeADirectPageThatHasMoreReportsANextPageInsteadOfTruncatingSilently() {
        // The server returns more than a page: the probe row tells the caller there is more, and asking for it
        // (page 1) is then refused loudly instead of the stream silently stopping after page 0.
        server.collection = ce -> List.of(entity(1), entity(2), entity(3));
        CyodaCallContext joined = CyodaCallContext.m2m().withTxToken("tx-1");

        PageResult<DataPayload> page = repo.findAll(joined, spec, SearchAndRetrievalParams.builder().pageSize(2).build()).join();

        assertThat(page.data()).hasSize(2);
        assertThat(page.hasNext()).isTrue();
        assertThatThrownBy(() -> repo.findAll(joined, spec, SearchAndRetrievalParams.builder().pageSize(2).pageNumber(1).build()))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("direct search");
    }

    @Test
    void insideAScopePagingBeyondTheDirectLimitIsRefused() {
        CyodaCallContext joined = CyodaCallContext.m2m().withTxToken("tx-1");

        assertThatThrownBy(() -> repo.findAllByCriteria(joined, spec, all,
                SearchAndRetrievalParams.builder().pageNumber(1).build()))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("direct search");
        assertThatThrownBy(() -> repo.findAllByCriteria(joined, spec, all,
                SearchAndRetrievalParams.builder().searchId(UUID.randomUUID()).build()))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("direct search");
        assertThatThrownBy(() -> repo.findAllByCriteria(joined, spec, all,
                SearchAndRetrievalParams.builder().pageSize(10_001).build()))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("10000");
        assertThatThrownBy(() -> repo.findAll(joined, spec,
                SearchAndRetrievalParams.builder().pageSize(10_001).inMemory(true).build()))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("10000");
        assertThat(server.seen).isEmpty();
    }

    @Test
    void insideAScopeTransactionControlParametersAreRefusedLocally() {
        CyodaCallContext joined = CyodaCallContext.m2m().withTxToken("tx-1");

        assertThatThrownBy(() -> repo.saveAll(joined, spec, List.of(om.createObjectNode()), 10, null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("transactionWindow");
        assertThatThrownBy(() -> repo.saveAll(joined, spec, List.of(om.createObjectNode()), null, 1000L))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("transactionTimeoutMs");
        assertThatThrownBy(() -> repo.updateAll(joined, List.of(om.createObjectNode().put("id", UUID.randomUUID().toString())), null, null, 1000L))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("transactionTimeoutMs");
        assertThatThrownBy(() -> repo.updateAll(joined, List.of(om.createObjectNode().put("id", UUID.randomUUID().toString())), null, 5, null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("transactionWindow");
        assertThat(server.seen).isEmpty();
    }

    @Test
    void aRejectedM2mTokenIsInvalidatedAndTheCallRetriedOnceWithAFreshOne() {
        when(tokens.bearerToken()).thenReturn(Optional.of("stale"), Optional.of("fresh"));
        AtomicInteger calls = new AtomicInteger();
        server.unary = ce -> {
            if (calls.getAndIncrement() == 0) {
                throw Status.UNAUTHENTICATED.withDescription("token expired").asRuntimeException();
            }
            return entity(7);
        };

        DataPayload payload = repo.findById(CyodaCallContext.m2m(), UUID.randomUUID(), (java.time.OffsetDateTime) null).join();

        assertThat(payload.getData().get("i").asInt()).isEqualTo(7);
        // exactly the token the server refused, so a token another thread fetched since is never discarded
        verify(tokens, times(1)).invalidate("stale");
        verify(tokens, never()).invalidate();
        assertThat(server.seen).extracting(RecordingCyodaServer.Seen::authorization)
                .containsExactly("Bearer stale", "Bearer fresh");
    }

    @Test
    void aRejectedForwardedTokenIsNeverRetried() {
        server.unary = ce -> {
            throw Status.UNAUTHENTICATED.withDescription("token expired").asRuntimeException();
        };

        assertThatThrownBy(() -> repo.findById(CyodaCallContext.forward("user-token"), UUID.randomUUID(), (java.time.OffsetDateTime) null).join())
                .isInstanceOf(CompletionException.class)
                .cause().isInstanceOf(StatusRuntimeException.class)
                .satisfies(e -> assertThat(((StatusRuntimeException) e).getStatus().getCode()).isEqualTo(Status.Code.UNAUTHENTICATED));
        verify(tokens, never()).invalidate();
        verify(tokens, never()).invalidate(anyString());
        assertThat(server.seen).hasSize(1);
    }

    @Test
    void aJoinedRetryableEnvelopeOnAStreamIsRetriedWithBackoffUnderTheSameContext() {
        AtomicInteger calls = new AtomicInteger();
        server.collection = ce -> calls.getAndIncrement() < 2
                ? List.<BaseEvent>of(entity(1), failedEnvelope("TOO_MANY_JOINED_REQUESTS: too many requests joined to tx"))
                : List.<BaseEvent>of(entity(1));
        CyodaCallContext joined = CyodaCallContext.m2m().withTxToken("tx-1");

        var page = repo.findAllByCriteria(joined, spec, all, SearchAndRetrievalParams.defaults()).join();

        assertThat(page.data()).hasSize(1);
        assertThat(server.seen).hasSize(3).allSatisfy(s -> {
            assertThat(s.type()).isEqualTo("EntitySearchRequest");
            assertThat(s.txToken()).isEqualTo("tx-1");
        });
    }

    @Test
    void aJoinedRetryableEnvelopeOnAUnaryCallGivesUpAfterThreeRetries() {
        server.unary = ce -> failedEnvelope("TRANSACTION_NODE_UNAVAILABLE: owner node unreachable");
        CyodaCallContext joined = CyodaCallContext.m2m().withTxToken("tx-1");

        assertThatThrownBy(() -> repo.findById(joined, UUID.randomUUID(), (java.time.OffsetDateTime) null).join())
                .isInstanceOf(CompletionException.class)
                .cause().isInstanceOf(CyodaRetryableException.class)
                .satisfies(e -> assertThat(((CyodaRetryableException) e).getErrorCode()).isEqualTo("TRANSACTION_NODE_UNAVAILABLE"));
        assertThat(server.seen).hasSize(4).allSatisfy(s -> assertThat(s.txToken()).isEqualTo("tx-1"));
    }

    @Test
    void aRetryableEnvelopeWhoseCodeIsNotJoinedRetryableIsNotRetried() {
        server.unary = ce -> failedEnvelope("CONFLICT: concurrent modification");

        assertThatThrownBy(() -> repo.findById(CyodaCallContext.m2m().withTxToken("tx-1"), UUID.randomUUID(), (java.time.OffsetDateTime) null).join())
                .isInstanceOf(CompletionException.class)
                .cause().isInstanceOf(CyodaRetryableException.class);
        assertThat(server.seen).hasSize(1);
    }

    // ---- fix round 1 ----

    /** A minimal entity for driving the real EntityServiceImpl over the recording server. */
    public static class Thing implements CyodaEntity {
        public int i;

        @Override
        public OperationSpecification getModelKey() {
            return new OperationSpecification.Entity(new ModelSpec().withName("m").withVersion(1), "m");
        }
    }

    private EntityServiceImpl service() {
        return new EntityServiceImpl(repo, wireMapper, new CyodaCallContexts(new Config()));
    }

    @Test
    void insideAScopeTheProcessorsSearchAsStreamReadsOneDirectPageAndDoesNotThrow() {
        server.collection = ce -> List.of(entity(1), entity(2), entity(3));

        List<Integer> seenValues;
        // The same parameters as ExampleEntityProcessor.processRelatedEntities.
        try (CalloutScope ignored = CalloutScope.open("tx-1");
             var stream = service().searchAsStream(spec, all, Thing.class,
                     SearchAndRetrievalParams.builder().pageSize(100).inMemory(true).build())) {
            seenValues = stream.map(e -> e.entity().i).toList();
        }

        assertThat(seenValues).containsExactly(1, 2, 3);
        assertThat(server.seen).singleElement().satisfies(s -> {
            assertThat(s.type()).isEqualTo("EntitySearchRequest");
            assertThat(s.txToken()).isEqualTo("tx-1");
        });
    }

    @Test
    void insideAScopeACallerSuppliedPointInTimeIsRefusedOnEveryReadPath() {
        CyodaCallContext joined = CyodaCallContext.m2m().withTxToken("tx-1");
        OffsetDateTime pit = OffsetDateTime.parse("2026-09-27T10:11:12.123456789Z");
        UUID id = UUID.randomUUID();

        assertThatThrownBy(() -> repo.findById(joined, id, pit))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("pointInTime");
        assertThatThrownBy(() -> repo.findAll(joined, spec, SearchAndRetrievalParams.builder().pointInTime(pit).build()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("pointInTime");
        assertThatThrownBy(() -> repo.findAllByCriteria(joined, spec, all,
                SearchAndRetrievalParams.builder().inMemory(true).pointInTime(pit).build()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("pointInTime");
        assertThatThrownBy(() -> repo.getEntityCount(joined, spec, pit))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("pointInTime");
        assertThatThrownBy(() -> repo.getEntityStatsByState(joined, spec, pit))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("pointInTime");
        assertThatThrownBy(() -> repo.getEntityStatsByState(joined, spec, List.of("DRAFT"), pit))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("pointInTime");
        assertThatThrownBy(() -> repo.getEntityChangesMetadata(joined, id, pit))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("pointInTime");
        assertThat(server.seen).isEmpty();
    }

    @Test
    void insideAScopeTheServiceRefusesACallerSuppliedPointInTimeOnGetByIdAndFindByBusinessId() {
        OffsetDateTime pit = OffsetDateTime.parse("2026-09-27T10:11:12.123456789Z");
        EntityServiceImpl service = service();

        try (CalloutScope ignored = CalloutScope.open("tx-1")) {
            assertThatThrownBy(() -> service.getById(UUID.randomUUID(), spec, Thing.class, pit))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("pointInTime");
            assertThatThrownBy(() -> service.findByBusinessId(spec, "B-1", "name", Thing.class, pit))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("pointInTime");
            assertThatThrownBy(() -> service.getEntityCount(spec, pit))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("pointInTime");
            assertThatThrownBy(() -> service.getEntityChangesMetadata(UUID.randomUUID(), pit))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("pointInTime");
        }
        assertThat(server.seen).isEmpty();
    }

    @Test
    void insideAScopeAFullPageAtTheDirectSearchLimitFailsBecauseMoreMayExist() {
        List<BaseEvent> atTheCap = new ArrayList<>(CyodaRepository.DIRECT_SEARCH_LIMIT);
        IntStream.range(0, CyodaRepository.DIRECT_SEARCH_LIMIT).forEach(i -> atTheCap.add(entity(i)));
        server.collection = ce -> atTheCap;
        CyodaCallContext joined = CyodaCallContext.m2m().withTxToken("tx-1");

        assertThatThrownBy(() -> repo.findAll(joined, spec,
                SearchAndRetrievalParams.builder().pageSize(CyodaRepository.DIRECT_SEARCH_LIMIT).build()).join())
                .isInstanceOf(CompletionException.class)
                .rootCause().isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("10000").hasMessageContaining("direct search");
    }

    @Test
    void insideAScopeAPageBelowTheDirectSearchLimitThatComesBackShortSucceeds() {
        server.collection = ce -> List.of(entity(1));
        CyodaCallContext joined = CyodaCallContext.m2m().withTxToken("tx-1");

        var page = repo.findAll(joined, spec,
                SearchAndRetrievalParams.builder().pageSize(CyodaRepository.DIRECT_SEARCH_LIMIT).build()).join();

        assertThat(page.data()).hasSize(1);
        assertThat(page.hasNext()).isFalse();
    }

    @Test
    void aSnapshotPollWhoseDelayedResubmissionIsRejectedAtShutdownFailsInsteadOfHanging() throws Exception {
        UUID snapshot = UUID.randomUUID();
        server.unary = ce -> new EntitySnapshotSearchResponse().withId("r").withSuccess(true)
                .withStatus(new SearchSnapshotStatus().withSnapshotId(snapshot).withEntitiesCount(0L)
                        .withStatus(SearchSnapshotStatus.Status.RUNNING));
        CompletableFuture<PageResult<DataPayload>> search = repo.findAllByCriteria(CyodaCallContext.m2m(), spec, all,
                SearchAndRetrievalParams.builder().pollIntervalMs(500).awaitLimitMs(60_000).build());
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (server.seen.stream().noneMatch(s -> s.type().equals("SnapshotGetStatusRequest")) && System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }

        repo.shutdownExecutor();

        assertThatThrownBy(() -> search.get(5, TimeUnit.SECONDS))
                .isInstanceOf(ExecutionException.class)
                .rootCause().hasMessageContaining("shut down");
    }
}
