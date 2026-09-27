package com.java_template.common.repository;

import com.java_template.common.auth.CyodaTokenSource;
import com.java_template.common.call.CyodaCallContext;
import com.java_template.common.call.CyodaCallInterceptor;
import com.java_template.common.config.Config;
import com.java_template.common.config.CyodaObjectMapper;
import com.java_template.common.grpc.client.event_handling.CloudEventParser;
import org.cyoda.cloud.api.common.model.GroupConditionDto;
import org.cyoda.cloud.api.event.common.DataPayload;
import org.cyoda.cloud.api.event.common.ModelSpec;
import org.cyoda.cloud.api.event.search.EntityResponse;
import org.cyoda.cloud.api.event.search.EntitySnapshotSearchResponse;
import org.cyoda.cloud.api.event.search.SearchSnapshotStatus;
import org.cyoda.cloud.api.grpc.CloudEventsServiceGrpc;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * ABOUTME: CyodaRepository runs its calls, and the continuations of a snapshot search (cache hits, status polls,
 * page fetches), on pool threads. Every one of them must carry the operation's own CyodaCallContext, captured
 * once on the caller's thread: never a pool thread's leftover, and never another caller's cached snapshot.
 */
class CyodaRepositoryCallerCredentialTest {

    private final CyodaObjectMapper wireMapper = CyodaObjectMapper.standalone();
    private final ModelSpec model = new ModelSpec().withName("thing").withVersion(1);
    private final GroupConditionDto all = new GroupConditionDto().operator(GroupConditionDto.OperatorEnum.AND).conditions(List.of());
    private RecordingCyodaServer server;
    private CyodaRepository repository;

    @BeforeEach
    void setUp() throws Exception {
        server = new RecordingCyodaServer(wireMapper);
        CyodaTokenSource tokens = mock(CyodaTokenSource.class);
        when(tokens.bearerToken()).thenReturn(Optional.of("m2m"));
        var stub = CloudEventsServiceGrpc.newBlockingStub(server.channel).withInterceptors(new CyodaCallInterceptor(tokens));
        Config config = new Config();
        config.setGrpcCallDeadlineMs(10_000);
        repository = new CyodaRepository(wireMapper, stub, server.builder, new CloudEventParser(wireMapper), config, tokens);
    }

    @AfterEach
    void tearDown() {
        repository.shutdownExecutor();
        server.close();
    }

    private static EntitySnapshotSearchResponse snapshot(SearchSnapshotStatus.Status status, UUID snapshotId) {
        return new EntitySnapshotSearchResponse().withId(UUID.randomUUID().toString()).withSuccess(true)
                .withStatus(new SearchSnapshotStatus().withStatus(status).withSnapshotId(snapshotId)
                        .withEntitiesCount(0L).withExpirationDate(OffsetDateTime.now().plusHours(1)));
    }

    private EntityResponse entity() {
        return new EntityResponse().withId(UUID.randomUUID().toString()).withSuccess(true)
                .withPayload(new DataPayload().withType("ENTITY").withData(wireMapper.mapper().createObjectNode()));
    }

    /**
     * A page request whose searchId is in the snapshot cache fetches its page as a continuation on a pool
     * thread. That fetch carries the page request's own context.
     */
    @Test
    void aCachedSnapshotPageCarriesTheOperationsOwnContext() {
        UUID snapshotId = UUID.randomUUID();
        server.unary = ce -> snapshot(SearchSnapshotStatus.Status.SUCCESSFUL, snapshotId);
        CyodaCallContext alice = CyodaCallContext.forward("alice-token");
        repository.findAllByCriteria(alice, model, all, SearchAndRetrievalParams.builder().pageSize(10).build()).join();
        assertThat(server.seen).extracting(RecordingCyodaServer.Seen::type)
                .containsExactly("EntitySnapshotSearchRequest", "SnapshotGetRequest");
        server.seen.clear();

        repository.findAllByCriteria(alice, model, all,
                SearchAndRetrievalParams.builder().pageSize(10).pageNumber(1).searchId(snapshotId).build()).join();

        // A cache hit: no status call, only the page fetch, and it carries alice's token.
        assertThat(server.seen).singleElement().satisfies(s -> {
            assertThat(s.type()).isEqualTo("SnapshotGetRequest");
            assertThat(s.authorization()).isEqualTo("Bearer alice-token");
        });
    }

    /**
     * The snapshot cache is keyed by the context's fingerprint: another caller's page request for the same
     * searchId is not served from the first caller's cached status, and every call it makes carries its own
     * credential.
     */
    @Test
    void aCachedSnapshotIsNeverReusedForAnotherCredential() {
        UUID snapshotId = UUID.randomUUID();
        server.unary = ce -> snapshot(SearchSnapshotStatus.Status.SUCCESSFUL, snapshotId);
        repository.findAllByCriteria(CyodaCallContext.forward("alice-token"), model, all,
                SearchAndRetrievalParams.builder().pageSize(10).build()).join();
        server.seen.clear();

        repository.findAllByCriteria(CyodaCallContext.m2m(), model, all,
                SearchAndRetrievalParams.builder().pageSize(10).pageNumber(1).searchId(snapshotId).build()).join();

        assertThat(server.seen).extracting(RecordingCyodaServer.Seen::type)
                .containsExactly("SnapshotGetStatusRequest", "SnapshotGetRequest");
        assertThat(server.seen).allSatisfy(s -> assertThat(s.authorization()).isEqualTo("Bearer m2m"));
    }

    /**
     * A snapshot still RUNNING is polled after a delay, on a pool thread; every poll and the final page fetch
     * carry the operation's own context.
     */
    @Test
    void everyPollContinuationCarriesTheOperationsContext() {
        UUID snapshotId = UUID.randomUUID();
        AtomicInteger statusCalls = new AtomicInteger();
        server.unary = ce -> switch (ce.getType()) {
            case "EntitySnapshotSearchRequest" -> snapshot(SearchSnapshotStatus.Status.RUNNING, snapshotId);
            case "SnapshotGetStatusRequest" -> snapshot(statusCalls.incrementAndGet() < 2
                    ? SearchSnapshotStatus.Status.RUNNING : SearchSnapshotStatus.Status.SUCCESSFUL, snapshotId);
            default -> throw new IllegalStateException(ce.getType());
        };

        repository.findAllByCriteria(CyodaCallContext.forward("alice-token"), model, all,
                SearchAndRetrievalParams.builder().pageSize(10).pollIntervalMs(10).build()).join();

        // create, two status polls, one page fetch
        assertThat(server.seen).extracting(RecordingCyodaServer.Seen::type).containsExactly(
                "EntitySnapshotSearchRequest", "SnapshotGetStatusRequest", "SnapshotGetStatusRequest", "SnapshotGetRequest");
        assertThat(server.seen).allSatisfy(s -> assertThat(s.authorization()).isEqualTo("Bearer alice-token"));
    }

    @Test
    void aPoolThreadNeverKeepsAnEarlierOperationsContext() {
        server.unary = ce -> entity();
        repository.findById(CyodaCallContext.forward("alice-token"), UUID.randomUUID(), (OffsetDateTime) null).join();
        server.seen.clear();

        for (int i = 0; i < 20; i++) {
            repository.findById(CyodaCallContext.m2m(), UUID.randomUUID(), (OffsetDateTime) null).join();
        }

        assertThat(server.seen).hasSize(20).allSatisfy(s -> assertThat(s.authorization()).isEqualTo("Bearer m2m"));
    }
}
