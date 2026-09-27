package com.java_template.common.grpc.client.connection;

import com.google.protobuf.InvalidProtocolBufferException;
import com.java_template.common.call.CyodaCallInterceptor;
import com.java_template.common.call.CyodaCallContexts;
import com.java_template.common.grpc.client.event_handling.CloudEventBuilder;
import com.java_template.common.grpc.client.event_handling.EventHandler;
import com.java_template.common.grpc.client.event_handling.EventSender;
import com.java_template.common.grpc.client.monitoring.ConnectionStateTracker;
import com.java_template.common.grpc.client.monitoring.EventTracker;
import com.java_template.common.grpc.client.monitoring.ObserverState;
import io.cloudevents.v1.proto.CloudEvent;
import io.grpc.ConnectivityState;
import io.grpc.ManagedChannel;
import io.grpc.stub.StreamObserver;
import org.cyoda.cloud.api.event.processing.CalculationMemberJoinEvent;
import org.cyoda.cloud.api.grpc.CloudEventsServiceGrpc;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import com.java_template.common.config.Config;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;


/**
 * ABOUTME: Central connection manager for gRPC streams handling connection lifecycle,
 * event routing, monitoring, and automatic reconnection with state tracking.
 */
@Component
public class ConnectionManager implements EventSender {
    private final Logger log = LoggerFactory.getLogger(this.getClass());

    private final EventHandler eventHandler;
    private final EventTracker eventTracker;
    private final ConnectionStateTracker connectionStateTracker;
    private final CloudEventBuilder eventBuilder;
    private final CloudEventsServiceGrpc.CloudEventsServiceStub cloudEventsServiceStub;
    private final ReconnectionStrategy reconnectionStrategy;
    private final GreetEventListener greetEventListener;
    private final ManagedChannel managedChannel;
    private final Config config;
    private final CyodaCallContexts callContexts;

    private StreamObserver<CloudEvent> streamObserver = null;

    public ConnectionManager(
            @Lazy final EventHandler eventHandler,
            final EventTracker eventTracker,
            final ConnectionStateTracker connectionStateTracker,
            final CloudEventBuilder eventBuilder,
            final CloudEventsServiceGrpc.CloudEventsServiceStub cloudEventsServiceStub,
            final ReconnectionStrategy reconnectionStrategy,
            final GreetEventListener greetEventListener,
            final ManagedChannel managedChannel,
            final Config config,
            final CyodaCallContexts callContexts
    ) {
        this.eventHandler = eventHandler;
        this.eventTracker = eventTracker;
        this.connectionStateTracker = connectionStateTracker;
        this.eventBuilder = eventBuilder;
        this.cloudEventsServiceStub = cloudEventsServiceStub;
        this.reconnectionStrategy = reconnectionStrategy;
        this.greetEventListener = greetEventListener;
        this.managedChannel = managedChannel;
        this.config = config;
        this.callContexts = callContexts;
    }

    private CloudEvent createJoinEvent(
            final String id,
            final Set<String> tags
    ) throws InvalidProtocolBufferException {
        return eventBuilder.buildEvent(
                new CalculationMemberJoinEvent().withId(id).withTags(tags.stream().toList())
        );
    }

    @PostConstruct
    private void init() {
        reconnectionStrategy.addIdleStateListener(this::onIdleStateChanged);
        initiateConnection();
    }

    @PreDestroy
    private void shutdown() {
        log.info("Stopping stream observer...");
        if (streamObserver != null) {
            streamObserver.onCompleted();
        }
        log.info("Stream observer stoped");
    }

    private CompletableFuture<StreamObserver<CloudEvent>> connect() {
        connectionStateTracker.trackObserverStateChange(ObserverState.CONNECTING);

        final var joinEventId = UUID.randomUUID().toString();
        final var greetPromise = new CompletableFuture<String>();
        greetEventListener.registerPendingGreetEvent(joinEventId, greetPromise);

        try {
            CloudEventStreamObserver ourObserver = new CloudEventStreamObserver(
                    eventHandler::handleEvent,
                    error -> {
                        connectionStateTracker.trackObserverStateChange(ObserverState.ERROR);
                        log.error("Stream observer error:", error);
                        requestReconnection();
                    },
                    () -> {
                        connectionStateTracker.trackObserverStateChange(ObserverState.DISCONNECTED);
                        log.info("Stream observer disconnected");
                        requestReconnection();
                    }
            );
            final var newObserver = cloudEventsServiceStub
                    .withOption(CyodaCallInterceptor.CONTEXT, callContexts.forMemberStream())
                    .startStreaming(ourObserver);

            connectionStateTracker.trackObserverStateChange(ObserverState.JOINING);

            final var joinEvent = createJoinEvent(joinEventId, eventHandler.getSupportedTags());
            sendEvent(newObserver, joinEvent);

            connectionStateTracker.trackObserverStateChange(ObserverState.AWAITS_GREET);

            return greetPromise.thenApply(acceptedJoinEvent -> newObserver)
                    .orTimeout(config.getHandshakeTimeoutMs(), TimeUnit.MILLISECONDS);
        } catch (InvalidProtocolBufferException e) {
            return CompletableFuture.failedFuture(e);
        }
    }

    @Override
    public void sendEvent(final CloudEvent event) {
        sendEvent(streamObserver, event);
    }

    private synchronized void sendEvent(
            final StreamObserver<CloudEvent> streamObserver,
            final CloudEvent event
    ) {
        eventTracker.trackEventSent(event);
        streamObserver.onNext(event);
        log.debug("Sent event '{}':'{}'", event.getType(), event.getId());
    }

    private void requestReconnection() {
        reconnectionStrategy.requestReconnection(this::initiateConnection);
    }

    private void initiateConnection() {

        if (connectionStateTracker.getLastObserverState().operational &&
                managedChannel.getState(false) != ConnectivityState.SHUTDOWN &&
                managedChannel.getState(false) != ConnectivityState.IDLE
        ) {
            log.info("Already operational. Initiate connection request ignored");
            return;
        }

        log.info("Attempting to establish a new stream...");
        connect().whenComplete((newObserver, error) -> {
            if (error == null && newObserver != null) {
                streamObserver = newObserver;
                reconnectionStrategy.reset();
                log.info("Stream successfully established");
            } else {
                log.error("Stream establishing failed. Scheduling reconnect", error);
                requestReconnection();
            }
        });
    }

    private void onIdleStateChanged(boolean isIdle) {
        if (isIdle) {
            connectionStateTracker.trackObserverStateChange(ObserverState.IDLE);
            log.warn("Connection manager entered IDLE state - no further reconnection attempts");
        }
    }

    public void resurrect() {
        log.info("Resurrection requested");
        reconnectionStrategy.resurrect(this::initiateConnection);
    }
}
