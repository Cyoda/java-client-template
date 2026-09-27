package com.java_template.common.repository;

import com.java_template.common.config.Config;
import com.java_template.common.config.CyodaObjectMapper;
import com.java_template.common.grpc.client.event_handling.CloudEventBuilder;
import io.cloudevents.core.provider.EventFormatProvider;
import io.cloudevents.protobuf.ProtobufFormat;
import io.cloudevents.v1.proto.CloudEvent;
import io.grpc.Context;
import io.grpc.Contexts;
import io.grpc.ManagedChannel;
import io.grpc.Metadata;
import io.grpc.Server;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.ServerInterceptors;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.stub.StreamObserver;
import org.cyoda.cloud.api.event.common.BaseEvent;
import org.cyoda.cloud.api.grpc.CloudEventsServiceGrpc;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * In-process CloudEventsService that records every call's headers and answers from functions. An answer
 * function may throw a {@link StatusRuntimeException} to fail the call with that status.
 */
final class RecordingCyodaServer extends CloudEventsServiceGrpc.CloudEventsServiceImplBase implements AutoCloseable {

    record Seen(String method, String type, String authorization, String txToken) {}

    static final Metadata.Key<String> AUTH = Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER);
    static final Metadata.Key<String> TX = Metadata.Key.of("tx-token", Metadata.ASCII_STRING_MARSHALLER);

    final List<Seen> seen = new CopyOnWriteArrayList<>();
    final CloudEventBuilder builder;
    private final CyodaObjectMapper wireMapper;
    volatile Function<CloudEvent, BaseEvent> unary = ce -> { throw new IllegalStateException("no unary answer for " + ce.getType()); };
    volatile Function<CloudEvent, List<BaseEvent>> collection = ce -> List.of();

    private final String name = "cyoda-" + UUID.randomUUID();
    private final Server server;
    final ManagedChannel channel;

    RecordingCyodaServer(CyodaObjectMapper wireMapper) throws Exception {
        this.wireMapper = wireMapper;
        builder = new CloudEventBuilder(wireMapper, EventFormatProvider.getInstance().resolveFormat(ProtobufFormat.PROTO_CONTENT_TYPE), new Config());
        ServerInterceptor capture = new ServerInterceptor() {
            @Override
            public <Q, R> ServerCall.Listener<Q> interceptCall(ServerCall<Q, R> call, Metadata headers, ServerCallHandler<Q, R> next) {
                return Contexts.interceptCall(Context.current().withValue(HEADERS, headers), call, headers, next);
            }
        };
        server = InProcessServerBuilder.forName(name).directExecutor()
                .addService(ServerInterceptors.intercept(this, capture)).build().start();
        channel = InProcessChannelBuilder.forName(name).directExecutor().build();
    }

    private void record(String method, CloudEvent ce) {
        Metadata h = HEADERS.get();
        seen.add(new Seen(method, ce.getType(), h.get(AUTH), h.get(TX)));
    }

    @Override
    public void entitySearch(CloudEvent request, StreamObserver<CloudEvent> out) {
        record("entitySearch", request);
        answer(out, () -> List.of(unary.apply(request)));
    }

    @Override
    public void entitySearchCollection(CloudEvent request, StreamObserver<CloudEvent> out) {
        record("entitySearchCollection", request);
        answer(out, () -> collection.apply(request));
    }

    @Override
    public void entityManage(CloudEvent request, StreamObserver<CloudEvent> out) {
        record("entityManage", request);
        answer(out, () -> List.of(unary.apply(request)));
    }

    @Override
    public void entityManageCollection(CloudEvent request, StreamObserver<CloudEvent> out) {
        record("entityManageCollection", request);
        answer(out, () -> collection.apply(request));
    }

    private void answer(StreamObserver<CloudEvent> out, Supplier<List<BaseEvent>> events) {
        try {
            for (BaseEvent e : events.get()) {
                out.onNext(response(e));
            }
            out.onCompleted();
        } catch (StatusRuntimeException e) {
            out.onError(e);
        } catch (Exception e) {
            out.onError(Status.INTERNAL.withDescription(e.toString()).withCause(e).asRuntimeException());
        }
    }

    /** As cyoda-go answers: the event's JSON in the CloudEvent's text_data, which CloudEventParser reads. */
    private CloudEvent response(BaseEvent event) throws Exception {
        return CloudEvent.newBuilder()
                .setId(UUID.randomUUID().toString())
                .setSource("urn:test:recording-cyoda-server")
                .setSpecVersion("1.0")
                .setType(event.getClass().getSimpleName())
                .setTextData(wireMapper.mapper().writeValueAsString(event))
                .build();
    }

    @Override
    public void close() {
        channel.shutdownNow();
        server.shutdownNow();
    }

    /** Request headers, attached to the gRPC Context by the capturing interceptor. */
    private static final Context.Key<Metadata> HEADERS = Context.key("recorded-headers");
}
