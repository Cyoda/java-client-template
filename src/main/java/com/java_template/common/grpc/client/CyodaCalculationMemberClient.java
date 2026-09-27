package com.java_template.common.grpc.client;

import com.google.protobuf.InvalidProtocolBufferException;
import com.java_template.common.config.Config;
import com.java_template.common.grpc.client.event_handling.CloudEventBuilder;
import com.java_template.common.grpc.client.event_handling.EventHandler;
import com.java_template.common.grpc.client.event_handling.EventHandlingStrategy;
import com.java_template.common.grpc.client.event_handling.EventSender;
import com.java_template.common.grpc.client.event_handling.CloudEvents;
import io.cloudevents.v1.proto.CloudEvent;
import org.cyoda.cloud.api.event.common.BaseEvent;
import org.cyoda.cloud.api.event.common.CloudEventType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;


/**
 * ABOUTME: Main gRPC client for Cyoda calculation member communication providing
 * event handling, connection management, and bidirectional streaming capabilities.
 */
@Component
class CyodaCalculationMemberClient implements EventHandler {
    private final Logger log = LoggerFactory.getLogger(this.getClass());

    private final EventSender eventSender;
    private final EventExecutionRouter eventExecutionRouter;
    private final CloudEventBuilder eventBuilder;
    private final List<EventHandlingStrategy<? extends BaseEvent>> eventHandlingStrategies;
    private final Config config;

    CyodaCalculationMemberClient(
            @Lazy final EventSender eventSender,
            final EventExecutionRouter eventExecutionRouter,
            final CloudEventBuilder eventBuilder,
            final List<EventHandlingStrategy<? extends BaseEvent>> eventHandlingStrategies,
            final Config config
    ) {
        this.eventSender = eventSender;
        this.eventExecutionRouter = eventExecutionRouter;
        this.eventBuilder = eventBuilder;
        this.eventHandlingStrategies = eventHandlingStrategies;
        this.config = config;
    }

    @Override
    public void handleEvent(final CloudEvent cloudEvent) {
        // Determine event type BEFORE submitting to thread pool for proper routing
        final CloudEventType cloudEventType;
        try {
            cloudEventType = CloudEventType.fromValue(cloudEvent.getType());
        } catch (Exception e) {
            log.error("Failed to parse CloudEventType from event: {}", CloudEvents.describe(cloudEvent), e);
            return;
        }

        // Route to appropriate thread pool based on event type
        eventExecutionRouter.routeAndExecute(cloudEventType, () -> {
            try {
                log.debug(
                        "[IN] Received event {}: \n{}",
                        cloudEventType,
                        cloudEvent.getTextData()
                );

                final var strategy = eventHandlingStrategies.stream()
                        .filter(it -> it.supports(cloudEventType))
                        .findFirst()
                        .orElse(null);

                if (strategy == null) {
                    log.error("No handler strategy found for event {}", cloudEventType);
                    return;
                }

                log.debug(
                        "Using strategy '{}' for event type '{}'",
                        strategy.getClass().getSimpleName(),
                        cloudEventType
                );

                // The contract on handleEvent is that it does not throw Exceptions,
                // but handles them internally and returns an error response.
                final var response = strategy.handleEvent(cloudEvent);
                if (response != null) {
                    sendEvent(response);
                } else {
                    log.debug(
                            "Nothing to respond for event '{}':'{}'",
                            cloudEventType,
                            cloudEvent.getId()
                    );
                }

            } catch (Exception e) {
                log.error("Error processing event: {}", CloudEvents.describe(cloudEvent), e);
            }
        });
    }

    @Override
    public Set<String> getSupportedTags() {
        return Set.of(config.getGrpcProcessorTag());
    }

    private void sendEvent(final BaseEvent event) {
        final CloudEvent cloudEvent;
        try {
            cloudEvent = eventBuilder.buildEvent(event);
        } catch (InvalidProtocolBufferException e) {
            // TODO: Define the strategy for handling serialization errors.
            //  For now we just log it.
            log.error("Failed to parse cloud event", e);
            return;
        }

        if (event.getSuccess()) {
            log.debug("[OUT] Sending event {}, success: {}", cloudEvent.getType(), event.getSuccess());
        } else {
            log.warn("[OUT] Sending event {}, success: {}", cloudEvent.getType(), event.getSuccess());
        }

        eventSender.sendEvent(cloudEvent);
    }
}
