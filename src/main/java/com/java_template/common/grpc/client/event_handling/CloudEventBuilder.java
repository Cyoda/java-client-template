package com.java_template.common.grpc.client.event_handling;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.protobuf.InvalidProtocolBufferException;
import com.java_template.common.config.Config;
import com.java_template.common.config.CyodaObjectMapper;
import io.cloudevents.core.data.PojoCloudEventData;
import io.cloudevents.core.format.EventFormat;
import io.cloudevents.v1.proto.CloudEvent;
import java.net.URI;
import java.util.UUID;
import org.cyoda.cloud.api.event.common.BaseEvent;
import org.springframework.stereotype.Component;

/**
 * ABOUTME: Component for building CloudEvent instances from BaseEvent objects
 * with proper serialization, metadata, and protocol buffer formatting.
 */

@Component
public class CloudEventBuilder {

    private final ObjectMapper objectMapper;
    private final EventFormat eventFormat;
    private final Config config;

    public CloudEventBuilder(
            final CyodaObjectMapper mappers,
            final EventFormat eventFormat,
            final Config config
    ) {
        this.objectMapper = mappers.protocol();
        this.eventFormat = eventFormat;
        this.config = config;
    }

    public CloudEvent buildEvent(final BaseEvent event) throws InvalidProtocolBufferException {
        // TODO: Do we really need to CloudEvent -> serialize -> parseFrom ???
        return CloudEvent.parseFrom(
                eventFormat.serialize(
                        io.cloudevents.core.builder.CloudEventBuilder.v1()
                                .withSource(URI.create(config.getEventSourceUri()))
                                .withType(event.getClass().getSimpleName())
                                .withId(UUID.randomUUID().toString())
                                .withData(PojoCloudEventData.wrap(event, this::mapEvent))
                                .build()
                )
        );
    }

    private byte[] mapEvent(final BaseEvent eventData) {
        try {
            return objectMapper.writeValueAsBytes(eventData);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Error serializing event data", e);
        }
    }

}
