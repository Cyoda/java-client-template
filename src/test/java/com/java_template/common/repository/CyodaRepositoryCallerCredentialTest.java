package com.java_template.common.repository;

import com.java_template.common.auth.AuthenticatedCallerGuard;
import com.java_template.common.auth.AuthenticatedCallerGuardTestSupport;
import com.java_template.common.auth.CyodaTokenSource;
import com.java_template.common.config.Config;
import com.java_template.common.config.CyodaObjectMapper;
import com.java_template.common.exception.CyodaCredentialException;
import com.java_template.common.grpc.client.ClientAuthorizationInterceptor;
import com.java_template.common.grpc.client.event_handling.CloudEventBuilder;
import com.java_template.common.grpc.client.event_handling.CloudEventParser;
import io.cloudevents.v1.proto.CloudEvent;
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
import org.cyoda.cloud.api.common.model.GroupConditionDto;
import org.cyoda.cloud.api.event.common.BaseEvent;
import org.cyoda.cloud.api.event.common.DataPayload;
import org.cyoda.cloud.api.event.common.ModelSpec;
import org.cyoda.cloud.api.event.search.EntityResponse;
import org.cyoda.cloud.api.grpc.CloudEventsServiceGrpc;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * ABOUTME: CyodaRepository runs each gRPC call on a pool thread; the calling thread's SecurityContext must reach
 * the auth interceptor there, so a call made for an authenticated user is refused (UNAUTHENTICATED) instead of
 * silently running with the M2M token.
 */
class CyodaRepositoryCallerCredentialTest {

    private static final Metadata.Key<String> AUTHORIZATION = Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER);

    private final List<String> authorizationHeaders = new CopyOnWriteArrayList<>();
    private Server server;
    private ManagedChannel channel;
    private CyodaRepository repository;

    @BeforeEach
    void setUp() throws Exception {
        String name = InProcessServerBuilder.generateName();
        ServerInterceptor recordAuthorization = new ServerInterceptor() {
            @Override
            public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
                    ServerCall<ReqT, RespT> call, Metadata headers, ServerCallHandler<ReqT, RespT> next) {
                authorizationHeaders.add(String.valueOf(headers.get(AUTHORIZATION)));
                return next.startCall(call, headers);
            }
        };
        server = InProcessServerBuilder.forName(name).directExecutor()
                .addService(ServerInterceptors.intercept(new CloudEventsServiceGrpc.CloudEventsServiceImplBase() {
                    @Override
                    public void entitySearch(CloudEvent request, StreamObserver<CloudEvent> response) {
                        response.onNext(CloudEvent.getDefaultInstance());
                        response.onCompleted();
                    }

                    @Override
                    public void entitySearchCollection(CloudEvent request, StreamObserver<CloudEvent> response) {
                        response.onCompleted();
                    }
                }, recordAuthorization))
                .build().start();
        channel = InProcessChannelBuilder.forName(name).directExecutor().build();

        CyodaTokenSource guarded = new CyodaTokenSource() {
            @Override public Optional<String> bearerToken() {
                AuthenticatedCallerGuard.refuseM2mForAuthenticatedUser();
                return Optional.of("m2m");
            }
            @Override public void invalidate() { }
        };
        var stub = CloudEventsServiceGrpc.newBlockingStub(channel)
                .withInterceptors(new ClientAuthorizationInterceptor(guarded));
        CloudEventBuilder builder = mock(CloudEventBuilder.class);
        when(builder.buildEvent(any(BaseEvent.class))).thenReturn(CloudEvent.getDefaultInstance());
        CloudEventParser parser = mock(CloudEventParser.class);
        when(parser.parseCloudEvent(any(), eq(EntityResponse.class)))
                .thenReturn(new EntityResponse().withPayload(new DataPayload()));
        Config config = new Config();
        repository = new CyodaRepository(CyodaObjectMapper.standalone(), stub, builder, parser, config);
    }

    @AfterEach
    void tearDown() throws Exception {
        SecurityContextHolder.clearContext();
        channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
        server.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
    }

    @Test
    void aUnaryCallForAnAuthenticatedUserIsRefused() {
        SecurityContextHolder.getContext().setAuthentication(
                AuthenticatedCallerGuardTestSupport.jwtUser());

        assertThatThrownBy(() -> repository.findById(UUID.randomUUID()).join())
                .isInstanceOf(CompletionException.class)
                .rootCause()
                .isInstanceOf(CyodaCredentialException.class);
        assertThat(authorizationHeaders).isEmpty();
    }

    @Test
    void aStreamingCallForAnAuthenticatedUserIsRefused() {
        SecurityContextHolder.getContext().setAuthentication(
                AuthenticatedCallerGuardTestSupport.jwtUser());
        GroupConditionDto all = new GroupConditionDto().operator(GroupConditionDto.OperatorEnum.AND).conditions(List.of());

        assertThatThrownBy(() -> repository.findAllByCriteria(new ModelSpec().withName("thing").withVersion(1), all,
                SearchAndRetrievalParams.builder().inMemory(true).build()).join())
                .satisfies(e -> assertThat(unauthenticated(e)).isTrue());
        assertThat(authorizationHeaders).isEmpty();
    }

    @Test
    void aCallWithoutAnAuthenticatedUserCarriesTheM2mToken() {
        repository.findById(UUID.randomUUID()).join();

        assertThat(authorizationHeaders).containsExactly("Bearer m2m");
    }

    @Test
    void thePoolThreadDoesNotKeepTheCallersContext() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(
                AuthenticatedCallerGuardTestSupport.jwtUser());
        assertThatThrownBy(() -> repository.findById(UUID.randomUUID()).join());

        // The same pool, from a thread without a user: the earlier caller's context must be gone.
        SecurityContextHolder.clearContext();
        for (int i = 0; i < 20; i++) {
            repository.findById(UUID.randomUUID()).join();
        }
        assertThat(authorizationHeaders).hasSize(20).allMatch("Bearer m2m"::equals);
    }

    private static boolean unauthenticated(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof StatusRuntimeException sre && sre.getStatus().getCode() == Status.Code.UNAUTHENTICATED) {
                return true;
            }
        }
        return false;
    }
}
