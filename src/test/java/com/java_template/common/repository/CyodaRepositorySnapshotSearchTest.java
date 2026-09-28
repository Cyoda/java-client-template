package com.java_template.common.repository;

import com.java_template.common.auth.CyodaTokenSource;
import com.java_template.common.call.CyodaCallContext;
import com.java_template.common.config.CyodaObjectMapper;
import com.java_template.common.config.Config;
import com.java_template.common.dto.PageResult;
import com.java_template.common.grpc.client.event_handling.CloudEventBuilder;
import com.java_template.common.grpc.client.event_handling.CloudEventParser;
import io.cloudevents.v1.proto.CloudEvent;
import org.cyoda.cloud.api.common.model.GroupConditionDto;
import org.cyoda.cloud.api.event.common.BaseEvent;
import org.cyoda.cloud.api.event.common.DataPayload;
import org.cyoda.cloud.api.event.common.ModelSpec;
import org.cyoda.cloud.api.event.search.EntitySnapshotSearchRequest;
import org.cyoda.cloud.api.event.search.EntitySnapshotSearchResponse;
import org.cyoda.cloud.api.event.search.SearchSnapshotStatus;
import org.cyoda.cloud.api.event.search.SnapshotGetStatusRequest;
import org.cyoda.cloud.api.grpc.CloudEventsServiceGrpc;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.OffsetDateTime;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Covers the snapshot-search bug found by SearchIT.pagedSnapshotSearchAcrossThreePages():
 * {@code CyodaRepository} used to read {@code entitiesCount} from the pre-poll snapshot-creation
 * response (typically 0, since the async job had barely started) instead of the completed
 * {@code SUCCESSFUL} status, and a page request carrying an existing {@code searchId} with an
 * empty cache silently created a brand-new snapshot search instead of reading the one the
 * {@code searchId} names.
 */
@ExtendWith(MockitoExtension.class)
class CyodaRepositorySnapshotSearchTest {

    @Spy CyodaObjectMapper mappers = CyodaObjectMapper.standalone();
    @Mock CloudEventsServiceGrpc.CloudEventsServiceBlockingStub stub;
    @Mock CloudEventBuilder cloudEventBuilder;
    @Mock CloudEventParser cloudEventParser;
    @Mock Config config;
    @Mock CyodaTokenSource tokenSource;

    @InjectMocks CyodaRepository repository;

    private final ModelSpec modelSpec = new ModelSpec().withName("thing").withVersion(1);
    private final CyodaCallContext ctx = CyodaCallContext.forward("user-token");
    private final GroupConditionDto condition = new GroupConditionDto()
            .operator(GroupConditionDto.OperatorEnum.AND).conditions(List.of());

    private void stubGrpcPlumbing() throws Exception {
        lenient().when(config.getGrpcCallDeadlineMs()).thenReturn(120_000L);
        lenient().when(stub.withOption(any(), any())).thenReturn(stub);
        lenient().when(stub.withDeadlineAfter(anyLong(), any())).thenReturn(stub);
        lenient().when(cloudEventBuilder.buildEvent(any(BaseEvent.class))).thenReturn(CloudEvent.getDefaultInstance());
        lenient().when(stub.entitySearch(any())).thenReturn(CloudEvent.getDefaultInstance());
        // Page contents are not the focus of these tests: always answer with an empty page.
        lenient().when(stub.entitySearchCollection(any())).thenReturn(Collections.emptyIterator());
    }

    private static SearchSnapshotStatus status(SearchSnapshotStatus.Status statusValue, UUID snapshotId, Long entitiesCount) {
        return new SearchSnapshotStatus().withStatus(statusValue).withSnapshotId(snapshotId).withEntitiesCount(entitiesCount)
                .withExpirationDate(OffsetDateTime.now().plusHours(1));
    }

    private static EntitySnapshotSearchResponse response(SearchSnapshotStatus status) {
        return (EntitySnapshotSearchResponse) new EntitySnapshotSearchResponse().withStatus(status).withSuccess(true);
    }

    private SearchAndRetrievalParams params(int pageNumber, UUID searchId) {
        return SearchAndRetrievalParams.builder()
                .pageSize(100).pageNumber(pageNumber).searchId(searchId).awaitLimitMs(1000).pollIntervalMs(5).build();
    }

    @Test
    void newSearchWaitsForCompletionAndReportsTheFinalEntityCount() throws Exception {
        stubGrpcPlumbing();
        UUID snapshotId = UUID.randomUUID();
        SearchSnapshotStatus running = status(SearchSnapshotStatus.Status.RUNNING, snapshotId, 0L);
        SearchSnapshotStatus done = status(SearchSnapshotStatus.Status.SUCCESSFUL, snapshotId, 250L);

        when(cloudEventParser.parseCloudEvent(any(CloudEvent.class), eq(EntitySnapshotSearchResponse.class)))
                .thenReturn(response(running), response(done));

        PageResult<DataPayload> page = repository.findAllByCriteria(ctx, modelSpec, condition, params(0, null))
                .get(5, TimeUnit.SECONDS);

        assertEquals(250L, page.totalElements());
        assertEquals(snapshotId, page.searchId());

        ArgumentCaptor<BaseEvent> captor = ArgumentCaptor.forClass(BaseEvent.class);
        verify(cloudEventBuilder, atLeastOnce()).buildEvent(captor.capture());
        long creationCalls = captor.getAllValues().stream().filter(e -> e instanceof EntitySnapshotSearchRequest).count();
        long statusPolls = captor.getAllValues().stream().filter(e -> e instanceof SnapshotGetStatusRequest).count();
        assertEquals(1, creationCalls, "exactly one snapshot creation for a brand-new search");
        assertEquals(1, statusPolls, "exactly one poll, which observes the SUCCESSFUL status");
    }

    @Test
    void pageRequestWithSearchIdAndEmptyCacheFetchesTheExistingSnapshotAndNeverCreatesANewOne() throws Exception {
        stubGrpcPlumbing();
        UUID snapshotId = UUID.randomUUID();
        SearchSnapshotStatus done = status(SearchSnapshotStatus.Status.SUCCESSFUL, snapshotId, 250L);

        when(cloudEventParser.parseCloudEvent(any(CloudEvent.class), eq(EntitySnapshotSearchResponse.class)))
                .thenReturn(response(done));

        PageResult<DataPayload> page = repository.findAllByCriteria(ctx, modelSpec, condition, params(1, snapshotId))
                .get(5, TimeUnit.SECONDS);

        assertEquals(250L, page.totalElements());
        assertEquals(snapshotId, page.searchId());

        ArgumentCaptor<BaseEvent> captor = ArgumentCaptor.forClass(BaseEvent.class);
        verify(cloudEventBuilder, atLeastOnce()).buildEvent(captor.capture());
        assertTrue(captor.getAllValues().stream().anyMatch(e -> e instanceof SnapshotGetStatusRequest req
                        && snapshotId.equals(req.getSnapshotId())),
                "a page request for an existing searchId must fetch that snapshot's status");
        assertTrue(captor.getAllValues().stream().noneMatch(e -> e instanceof EntitySnapshotSearchRequest),
                "an existing searchId must never trigger a brand-new snapshot search");
    }

    @Test
    void secondPageWithSameSearchIdReusesTheCachedCompletedStatus() throws Exception {
        stubGrpcPlumbing();
        UUID snapshotId = UUID.randomUUID();
        SearchSnapshotStatus done = status(SearchSnapshotStatus.Status.SUCCESSFUL, snapshotId, 250L);

        // Only one status response is stubbed. If the second page re-fetched the snapshot's
        // status instead of reusing the cached completed one, Mockito would keep returning this
        // same canned response too (thenReturn without vararg repeats the last value), so this
        // assertion instead pins down the CALL COUNT: exactly once, from the first page only.
        when(cloudEventParser.parseCloudEvent(any(CloudEvent.class), eq(EntitySnapshotSearchResponse.class)))
                .thenReturn(response(done));

        repository.findAllByCriteria(ctx, modelSpec, condition, params(0, snapshotId)).get(5, TimeUnit.SECONDS);
        PageResult<DataPayload> second = repository.findAllByCriteria(ctx, modelSpec, condition, params(1, snapshotId))
                .get(5, TimeUnit.SECONDS);

        assertEquals(250L, second.totalElements());
        verify(cloudEventParser, times(1)).parseCloudEvent(any(CloudEvent.class), eq(EntitySnapshotSearchResponse.class));
    }

    @Test
    void aCachedSnapshotStatusIsNeverServedToACallerWithADifferentContext() throws Exception {
        stubGrpcPlumbing();
        UUID snapshotId = UUID.randomUUID();
        SearchSnapshotStatus done = status(SearchSnapshotStatus.Status.SUCCESSFUL, snapshotId, 250L);
        when(cloudEventParser.parseCloudEvent(any(CloudEvent.class), eq(EntitySnapshotSearchResponse.class)))
                .thenReturn(response(done));

        repository.findAllByCriteria(ctx, modelSpec, condition, params(0, snapshotId)).get(5, TimeUnit.SECONDS);
        repository.findAllByCriteria(CyodaCallContext.forward("another-user"), modelSpec, condition, params(1, snapshotId))
                .get(5, TimeUnit.SECONDS);
        repository.findAllByCriteria(CyodaCallContext.m2m(), modelSpec, condition, params(1, snapshotId))
                .get(5, TimeUnit.SECONDS);

        // Each distinct caller reads the snapshot's status itself (under its own credential): three reads.
        verify(cloudEventParser, times(3)).parseCloudEvent(any(CloudEvent.class), eq(EntitySnapshotSearchResponse.class));

        // The first caller's own next page is still served from its cache entry.
        repository.findAllByCriteria(ctx, modelSpec, condition, params(1, snapshotId)).get(5, TimeUnit.SECONDS);
        verify(cloudEventParser, times(3)).parseCloudEvent(any(CloudEvent.class), eq(EntitySnapshotSearchResponse.class));
    }

    @Test
    void expiryMathUsesWallClockRemainingUntilExpiration() {
        OffsetDateTime now = OffsetDateTime.parse("2026-01-01T00:00:00Z");
        OffsetDateTime inTenMinutes = now.plusMinutes(10);

        long nanos = CyodaRepository.remainingNanosUntil(inTenMinutes, now);

        assertEquals(TimeUnit.MINUTES.toNanos(10), nanos);
    }

    @Test
    void expiryMathFloorsAtZeroForAnAlreadyExpiredSnapshot() {
        OffsetDateTime now = OffsetDateTime.parse("2026-01-01T00:00:00Z");
        OffsetDateTime inThePast = now.minusMinutes(5);

        assertEquals(0L, CyodaRepository.remainingNanosUntil(inThePast, now));
    }

    @Test
    void expiryMathFallsBackToADefaultWhenExpirationDateIsMissing() {
        assertEquals(TimeUnit.HOURS.toNanos(1), CyodaRepository.remainingNanosUntil(null, OffsetDateTime.now()));
    }
}
