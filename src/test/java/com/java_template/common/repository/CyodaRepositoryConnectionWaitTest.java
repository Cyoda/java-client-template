package com.java_template.common.repository;

import com.java_template.common.auth.CyodaTokenSource;
import com.java_template.common.call.CyodaCallContext;
import com.java_template.common.call.CyodaCallInterceptor;
import com.java_template.common.config.Config;
import com.java_template.common.config.CyodaObjectMapper;
import com.java_template.common.dto.PageResult;
import com.java_template.common.exception.CyodaRetryableException;
import com.java_template.common.grpc.client.connection.ChannelReadiness;
import com.java_template.common.grpc.client.event_handling.CloudEventBuilder;
import com.java_template.common.grpc.client.event_handling.CloudEventParser;
import io.cloudevents.core.provider.EventFormatProvider;
import io.cloudevents.protobuf.ProtobufFormat;
import io.cloudevents.v1.proto.CloudEvent;
import io.grpc.ManagedChannel;
import io.grpc.Server;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.stub.StreamObserver;
import org.cyoda.cloud.api.common.model.GroupConditionDto;
import org.cyoda.cloud.api.event.common.DataPayload;
import org.cyoda.cloud.api.event.common.ModelSpec;
import org.cyoda.cloud.api.event.search.EntityResponse;
import org.cyoda.cloud.api.grpc.CloudEventsServiceGrpc;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * ABOUTME: An unjoined multi-reply (server-streaming) call waits at most grpc-call-deadline-ms for the connection
 * to Cyoda, then fails as unreachable; once started it runs with no total deadline (spec §4.5). The stubs keep
 * withWaitForReady, as in production.
 */
class CyodaRepositoryConnectionWaitTest {

    private static final long BOUND_MS = 300;

    private final CyodaObjectMapper mappers = CyodaObjectMapper.standalone();
    private final String name = "cyoda-wait-" + UUID.randomUUID();
    private final ModelSpec spec = new ModelSpec().withName("m").withVersion(1);
    private final GroupConditionDto all = new GroupConditionDto().operator(GroupConditionDto.OperatorEnum.AND).conditions(List.of());
    private ManagedChannel channel;
    private Server server;
    private CyodaRepository repo;

    /** Answers entitySearchCollection with {@code count} entities, {@code gapMs} apart. */
    private final class SlowCyoda extends CloudEventsServiceGrpc.CloudEventsServiceImplBase {
        volatile int count = 1;
        volatile long gapMs = 0;

        @Override
        public void entitySearchCollection(CloudEvent request, StreamObserver<CloudEvent> out) {
            try {
                for (int i = 0; i < count; i++) {
                    if (i > 0) {
                        Thread.sleep(gapMs);
                    }
                    EntityResponse entity = new EntityResponse().withId(UUID.randomUUID().toString()).withSuccess(true)
                            .withPayload(new DataPayload().withType("ENTITY")
                                    .withData(mappers.protocol().createObjectNode().put("i", i)));
                    out.onNext(CloudEvent.newBuilder().setId(UUID.randomUUID().toString()).setSource("urn:test")
                            .setSpecVersion("1.0").setType("EntityResponse")
                            .setTextData(mappers.protocol().writeValueAsString(entity)).build());
                }
                out.onCompleted();
            } catch (Exception e) {
                out.onError(e);
            }
        }
    }

    private final SlowCyoda cyoda = new SlowCyoda();

    @BeforeEach
    void setUp() {
        channel = InProcessChannelBuilder.forName(name).build();
        CyodaTokenSource tokens = mock(CyodaTokenSource.class);
        when(tokens.bearerToken()).thenReturn(Optional.of("m2m-token"));
        var stub = CloudEventsServiceGrpc.newBlockingStub(channel).withWaitForReady()
                .withInterceptors(new CyodaCallInterceptor(tokens));
        Config config = new Config();
        config.setGrpcCallDeadlineMs(BOUND_MS);
        CloudEventBuilder builder = new CloudEventBuilder(mappers,
                EventFormatProvider.getInstance().resolveFormat(ProtobufFormat.PROTO_CONTENT_TYPE), config);
        repo = new CyodaRepository(mappers, stub, builder, new CloudEventParser(mappers), config, tokens,
                new ChannelReadiness(channel));
    }

    @AfterEach
    void tearDown() throws Exception {
        repo.shutdownExecutor();
        channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
        if (server != null) {
            server.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    private void startServer() throws Exception {
        server = InProcessServerBuilder.forName(name).addService(cyoda).build().start();
    }

    private PageResult<DataPayload> search() {
        return repo.findAllByCriteria(CyodaCallContext.m2m(), spec, all,
                SearchAndRetrievalParams.builder().inMemory(true).build()).join();
    }

    private static void assertUnreachableWithinTheBound(Throwable thrown, long elapsedMs) {
        assertThat(thrown).isInstanceOf(CompletionException.class);
        assertThat(thrown.getCause()).isInstanceOf(CyodaRetryableException.class)
                .hasMessageContaining("Cyoda was unreachable for " + BOUND_MS + " ms");
        assertThat(((CyodaRetryableException) thrown.getCause()).getErrorCode()).isEqualTo("UNAVAILABLE");
        assertThat(elapsedMs).isGreaterThanOrEqualTo(BOUND_MS - 50).isLessThan(BOUND_MS + 2_000);
    }

    @Test
    void withNoServerTheStreamingCallFailsAfterAboutTheBound() {
        long start = System.nanoTime();
        Throwable thrown = catchThrowable(this::search);
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

        assertUnreachableWithinTheBound(thrown, elapsedMs);
    }

    @Test
    void afterTheServerGoesAwayTheStreamingCallFailsAfterAboutTheBound() throws Exception {
        startServer();
        assertThat(search().data()).hasSize(1);
        server.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);

        long start = System.nanoTime();
        Throwable thrown = catchThrowable(this::search);
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

        assertUnreachableWithinTheBound(thrown, elapsedMs);
    }

    @Test
    void withTheServerUpTheStreamingCallSucceeds() throws Exception {
        startServer();
        cyoda.count = 3;

        assertThat(search().data()).hasSize(3);
    }

    @Test
    void aSlowHealthyStreamLongerThanTheBoundIsNotCutOff() throws Exception {
        startServer();
        cyoda.count = 5;
        cyoda.gapMs = 150;

        long start = System.nanoTime();
        PageResult<DataPayload> result = search();
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

        assertThat(result.data()).hasSize(5);
        assertThat(elapsedMs).isGreaterThan(2 * BOUND_MS);
    }
}
