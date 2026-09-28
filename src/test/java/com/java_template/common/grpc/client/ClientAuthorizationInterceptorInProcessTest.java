package com.java_template.common.grpc.client;

import com.java_template.common.auth.AuthenticatedCallerGuard;
import com.java_template.common.auth.CyodaTokenSource;
import com.java_template.common.exception.CyodaCredentialException;
import io.cloudevents.v1.proto.CloudEvent;
import io.grpc.ManagedChannel;
import io.grpc.Server;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.stub.StreamObserver;
import org.cyoda.cloud.api.grpc.CloudEventsServiceGrpc;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * ABOUTME: Drives ClientAuthorizationInterceptor through real gRPC stubs over an in-process channel,
 * so a failed token fetch is proven to surface as UNAUTHENTICATED rather than a stub-side IllegalStateException.
 */
class ClientAuthorizationInterceptorInProcessTest {

    private final AtomicInteger serverCalls = new AtomicInteger();
    private Server server;
    private ManagedChannel channel;

    @BeforeEach
    void startServer() throws Exception {
        String name = InProcessServerBuilder.generateName();
        server = InProcessServerBuilder.forName(name).directExecutor()
                .addService(new CloudEventsServiceGrpc.CloudEventsServiceImplBase() {
                    @Override
                    public void entityManage(CloudEvent request, StreamObserver<CloudEvent> response) {
                        serverCalls.incrementAndGet();
                        response.onNext(CloudEvent.getDefaultInstance());
                        response.onCompleted();
                    }

                    @Override
                    public void entitySearchCollection(CloudEvent request, StreamObserver<CloudEvent> response) {
                        serverCalls.incrementAndGet();
                        response.onNext(CloudEvent.getDefaultInstance());
                        response.onCompleted();
                    }

                    @Override
                    public StreamObserver<CloudEvent> startStreaming(StreamObserver<CloudEvent> response) {
                        serverCalls.incrementAndGet();
                        return new StreamObserver<>() {
                            @Override public void onNext(CloudEvent value) { }
                            @Override public void onError(Throwable t) { }
                            @Override public void onCompleted() { response.onCompleted(); }
                        };
                    }
                })
                .build().start();
        channel = InProcessChannelBuilder.forName(name).directExecutor().build();
    }

    @AfterEach
    void stopServer() throws Exception {
        channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
        server.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
        SecurityContextHolder.clearContext();
    }

    private static CyodaTokenSource failingTokenSource() {
        return new CyodaTokenSource() {
            @Override public Optional<String> bearerToken() { throw new IllegalStateException("token endpoint down"); }
            @Override public void invalidate() { }
        };
    }

    /** Like Authentication: refuses the M2M token while an authenticated user is on the thread. */
    private static CyodaTokenSource guardedTokenSource() {
        return new CyodaTokenSource() {
            @Override public Optional<String> bearerToken() {
                AuthenticatedCallerGuard.refuseM2mForAuthenticatedUser();
                return Optional.of("tok");
            }
            @Override public void invalidate() { }
        };
    }

    private static CyodaTokenSource fixedTokenSource() {
        return new CyodaTokenSource() {
            @Override public Optional<String> bearerToken() { return Optional.of("tok"); }
            @Override public void invalidate() { }
        };
    }

    @Test
    void blockingUnaryCallFailsUnauthenticatedWhenNoTokenCanBeObtained() {
        var stub = CloudEventsServiceGrpc.newBlockingStub(channel)
                .withInterceptors(new ClientAuthorizationInterceptor(failingTokenSource()));

        assertThatThrownBy(() -> stub.entityManage(CloudEvent.getDefaultInstance()))
                .isInstanceOfSatisfying(StatusRuntimeException.class, e -> {
                    assertThat(e.getStatus().getCode()).isEqualTo(Status.Code.UNAUTHENTICATED);
                    // The status description is generic; the token source's message stays in the cause, for
                    // server-side logs only.
                    assertThat(e.getStatus().getDescription()).isEqualTo("failed to obtain a Cyoda token");
                    assertThat(e.getStatus().getCause()).hasMessage("token endpoint down");
                });
        assertThat(serverCalls).hasValue(0);
    }

    @Test
    void blockingServerStreamingCallFailsUnauthenticatedWhenNoTokenCanBeObtained() {
        var stub = CloudEventsServiceGrpc.newBlockingStub(channel)
                .withInterceptors(new ClientAuthorizationInterceptor(failingTokenSource()));

        assertThatThrownBy(() -> {
            Iterator<CloudEvent> it = stub.entitySearchCollection(CloudEvent.getDefaultInstance());
            it.hasNext();
        }).isInstanceOfSatisfying(StatusRuntimeException.class,
                e -> assertThat(e.getStatus().getCode()).isEqualTo(Status.Code.UNAUTHENTICATED));
        assertThat(serverCalls).hasValue(0);
    }

    @Test
    void bidiStreamReportsUnauthenticatedToItsObserverWhenNoTokenCanBeObtained() throws Exception {
        var stub = CloudEventsServiceGrpc.newStub(channel)
                .withInterceptors(new ClientAuthorizationInterceptor(failingTokenSource()));
        CompletableFuture<Throwable> error = new CompletableFuture<>();

        StreamObserver<CloudEvent> requests = stub.startStreaming(new StreamObserver<>() {
            @Override public void onNext(CloudEvent value) { }
            @Override public void onError(Throwable t) { error.complete(t); }
            @Override public void onCompleted() { error.complete(null); }
        });
        requests.onNext(CloudEvent.getDefaultInstance());
        requests.onCompleted();

        assertThat(error.get(5, TimeUnit.SECONDS))
                .isInstanceOfSatisfying(StatusRuntimeException.class,
                        e -> assertThat(e.getStatus().getCode()).isEqualTo(Status.Code.UNAUTHENTICATED));
        assertThat(serverCalls).hasValue(0);
    }

    @Test
    void unaryCallSucceedsWithAToken() {
        var stub = CloudEventsServiceGrpc.newBlockingStub(channel)
                .withInterceptors(new ClientAuthorizationInterceptor(fixedTokenSource()));

        assertThat(stub.entityManage(CloudEvent.getDefaultInstance())).isNotNull();
        assertThat(serverCalls).hasValue(1);
    }

    @Test
    void aCallFromAnAuthenticatedUsersThreadFailsUnauthenticatedAndNeverReachesTheServer() {
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated("alice", "pw", List.of()));
        var stub = CloudEventsServiceGrpc.newBlockingStub(channel)
                .withInterceptors(new ClientAuthorizationInterceptor(guardedTokenSource()));

        assertThatThrownBy(() -> stub.entityManage(CloudEvent.getDefaultInstance()))
                .isInstanceOfSatisfying(StatusRuntimeException.class, e -> {
                    assertThat(e.getStatus().getCode()).isEqualTo(Status.Code.UNAUTHENTICATED);
                    assertThat(e.getStatus().getCause()).isInstanceOf(CyodaCredentialException.class);
                });
        assertThat(serverCalls).hasValue(0);
    }

    @Test
    void aCallWithoutAnAuthenticatedUserStillCarriesTheM2mToken() {
        var stub = CloudEventsServiceGrpc.newBlockingStub(channel)
                .withInterceptors(new ClientAuthorizationInterceptor(guardedTokenSource()));

        assertThat(stub.entityManage(CloudEvent.getDefaultInstance())).isNotNull();
        assertThat(serverCalls).hasValue(1);
    }
}
