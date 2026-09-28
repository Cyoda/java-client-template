package com.java_template.common.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.java_template.common.auth.CyodaTokenSource;
import com.java_template.common.call.CalloutScope;
import com.java_template.common.call.CyodaCallContexts;
import com.java_template.common.config.Config;
import com.java_template.common.config.CyodaObjectMapper;
import com.java_template.common.dto.EntityWithMetadata;
import com.java_template.common.grpc.client.event_handling.CloudEventBuilder;
import com.java_template.common.grpc.client.event_handling.CloudEventParser;
import com.java_template.common.repository.CyodaRepository;
import com.java_template.common.repository.SearchAndRetrievalParams;
import com.java_template.common.workflow.CyodaEntity;
import com.java_template.common.workflow.OperationSpecification;
import io.cloudevents.v1.proto.CloudEvent;
import org.cyoda.cloud.api.common.model.GroupConditionDto;
import org.cyoda.cloud.api.event.common.BaseEvent;
import org.cyoda.cloud.api.event.common.DataPayload;
import org.cyoda.cloud.api.event.common.EntityMetadata;
import org.cyoda.cloud.api.event.common.ModelSpec;
import org.cyoda.cloud.api.event.search.EntityResponse;
import org.cyoda.cloud.api.event.search.EntitySearchRequest;
import org.cyoda.cloud.api.grpc.CloudEventsServiceGrpc;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Inside a callout scope, EntityService.streamAll/searchAsStream run ONE direct search at
 * {@link CyodaRepository#DIRECT_SEARCH_LIMIT} (spec §4.4: a paged read beyond 10 000 throws), whatever the
 * caller's pageSize: a stream is not cut short at pageSize. A real EntityServiceImpl over a real CyodaRepository;
 * only the gRPC stub is mocked, and it honours the request's limit as cyoda-go does.
 */
class EntityServiceScopedStreamTest {

    private static final ModelSpec MODEL = new ModelSpec().withName("thing").withVersion(1);

    private final CloudEventsServiceGrpc.CloudEventsServiceBlockingStub stub = mock(CloudEventsServiceGrpc.CloudEventsServiceBlockingStub.class);
    private final CloudEventBuilder cloudEventBuilder = mock(CloudEventBuilder.class);
    private final CloudEventParser cloudEventParser = mock(CloudEventParser.class);
    private final AtomicReference<BaseEvent> lastRequest = new AtomicReference<>();
    private final List<EntitySearchRequest> searches = new ArrayList<>();
    private final ObjectMapper om = CyodaObjectMapper.standalone().mapper();
    private int matches;
    private EntityServiceImpl service;

    @BeforeEach
    void setUp() throws Exception {
        Config config = new Config();
        CyodaObjectMapper wireMapper = CyodaObjectMapper.standalone();
        CyodaRepository repository = new CyodaRepository(wireMapper, stub, cloudEventBuilder, cloudEventParser, config,
                mock(CyodaTokenSource.class));
        service = new EntityServiceImpl(repository, wireMapper, new CyodaCallContexts(config));

        lenient().when(stub.withOption(any(), any())).thenReturn(stub);
        lenient().when(stub.withDeadlineAfter(anyLong(), any())).thenReturn(stub);
        when(cloudEventBuilder.buildEvent(any(BaseEvent.class))).thenAnswer(inv -> {
            lastRequest.set(inv.getArgument(0));
            return CloudEvent.getDefaultInstance();
        });
        lenient().when(stub.entitySearchCollection(any())).thenAnswer(inv -> {
            EntitySearchRequest request = (EntitySearchRequest) lastRequest.get();
            searches.add(request);
            int returned = Math.min(matches, request.getLimit());
            return Collections.nCopies(returned, CloudEvent.getDefaultInstance()).iterator();
        });
        DataPayload payload = new DataPayload();
        payload.setData(om.createObjectNode().put("name", "n"));
        EntityMetadata meta = new EntityMetadata();
        meta.setId(UUID.randomUUID());
        meta.setState("ACTIVE");
        meta.setCreationDate(OffsetDateTime.now());
        payload.setMeta(om.valueToTree(meta));
        lenient().when(cloudEventParser.parseCloudEvent(any(), eq(EntityResponse.class)))
                .thenAnswer(inv -> new EntityResponse().withPayload(payload));
    }

    private static GroupConditionDto all() {
        return new GroupConditionDto().operator(GroupConditionDto.OperatorEnum.AND).conditions(List.of());
    }

    @Test
    void joinedStreamAllOver250MatchesReturnsAllOfThemInOneDirectSearch() {
        matches = 250;

        long streamed;
        try (CalloutScope ignored = CalloutScope.open("tx-1");
             Stream<EntityWithMetadata<Thing>> stream = service.streamAll(MODEL, Thing.class, SearchAndRetrievalParams.defaults())) {
            streamed = stream.toList().size(); // traverses: count() could answer from the SIZED estimate
        }

        assertThat(streamed).isEqualTo(250);
        assertThat(searches).hasSize(1);
        assertThat(searches.getFirst().getLimit()).isEqualTo(CyodaRepository.DIRECT_SEARCH_LIMIT);
        verify(stub, times(1)).entitySearchCollection(any());
        verify(stub, never()).entitySearch(any());
    }

    @Test
    void joinedSearchAsStreamIgnoresPageSizeAndReadsEveryMatchInOneDirectSearch() {
        matches = 250;

        long streamed;
        try (CalloutScope ignored = CalloutScope.open("tx-1");
             Stream<EntityWithMetadata<Thing>> stream = service.searchAsStream(MODEL, all(), Thing.class,
                     SearchAndRetrievalParams.builder().pageSize(10).inMemory(true).build())) {
            streamed = stream.toList().size(); // traverses: count() could answer from the SIZED estimate
        }

        assertThat(streamed).isEqualTo(250);
        assertThat(searches).hasSize(1);
        assertThat(searches.getFirst().getLimit()).isEqualTo(CyodaRepository.DIRECT_SEARCH_LIMIT);
    }

    @Test
    void joinedStreamOfJustUnderTheCapIsReadWhole() {
        matches = CyodaRepository.DIRECT_SEARCH_LIMIT - 1;

        long streamed;
        try (CalloutScope ignored = CalloutScope.open("tx-1");
             Stream<EntityWithMetadata<Thing>> stream = service.streamAll(MODEL, Thing.class, SearchAndRetrievalParams.defaults())) {
            streamed = stream.toList().size(); // traverses: count() could answer from the SIZED estimate
        }

        assertThat(streamed).isEqualTo(CyodaRepository.DIRECT_SEARCH_LIMIT - 1);
        assertThat(searches).hasSize(1);
    }

    @Test
    void joinedStreamOverTheCapFailsLoudlyBeforeAnyEntityIsStreamed() {
        matches = CyodaRepository.DIRECT_SEARCH_LIMIT + 5;

        try (CalloutScope ignored = CalloutScope.open("tx-1")) {
            assertThatThrownBy(() -> service.streamAll(MODEL, Thing.class, SearchAndRetrievalParams.defaults()))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining(String.valueOf(CyodaRepository.DIRECT_SEARCH_LIMIT));
            assertThatThrownBy(() -> service.searchAsStream(MODEL, all(), Thing.class, SearchAndRetrievalParams.defaults()))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining(String.valueOf(CyodaRepository.DIRECT_SEARCH_LIMIT));
        }
        assertThat(searches).hasSize(2);
    }

    @Test
    void joinedStreamAtExactlyTheCapFailsLoudlySinceMoreCannotBeRuledOut() {
        matches = CyodaRepository.DIRECT_SEARCH_LIMIT;

        try (CalloutScope ignored = CalloutScope.open("tx-1")) {
            assertThatThrownBy(() -> service.streamAll(MODEL, Thing.class, SearchAndRetrievalParams.defaults()))
                    .isInstanceOf(IllegalStateException.class);
        }
    }

    /**
     * cyoda-go defines pointInTime as a historical read of committed state that ignores the
     * transaction's own uncommitted writes (cyoda help crud; plugins/memory/entity_store.go
     * GetAsAt), so a caller-supplied pointInTime inside a joined stream is passed through to
     * cyoda unchanged rather than refused locally.
     */
    @Test
    void joinedStreamPassesACallerSuppliedPointInTimeThrough() {
        matches = 1;
        OffsetDateTime pit = OffsetDateTime.now();

        try (CalloutScope ignored = CalloutScope.open("tx-1");
             Stream<EntityWithMetadata<Thing>> stream = service.streamAll(MODEL, Thing.class,
                     SearchAndRetrievalParams.builder().pointInTime(pit).build())) {
            assertThat(stream.toList()).hasSize(1);
        }

        assertThat(searches).singleElement()
                .satisfies(request -> assertThat(request.getPointInTime()).isEqualTo(pit));
    }

    /** A minimal entity for deserialising the stubbed payloads. */
    public static class Thing implements CyodaEntity {
        public String name;

        @Override
        public OperationSpecification getModelKey() {
            return new OperationSpecification.Entity(MODEL, "thing");
        }
    }
}
