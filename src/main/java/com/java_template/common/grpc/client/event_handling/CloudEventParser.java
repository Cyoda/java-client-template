package com.java_template.common.grpc.client.event_handling;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.java_template.common.config.CyodaObjectMapper;
import com.java_template.common.exception.CyodaOperationException;
import io.cloudevents.v1.proto.CloudEvent;
import org.cyoda.cloud.api.event.common.BaseEvent;
import org.springframework.stereotype.Component;

/**
 * Parses CloudEvent gRPC responses into typed BaseEvent subclasses.
 * Throws CyodaOperationException if the response cannot be deserialized.
 */
@Component
public class CloudEventParser {

    private final ObjectMapper objectMapper;

    public CloudEventParser(final CyodaObjectMapper wireMapper) {
        this.objectMapper = wireMapper.mapper();
    }

    public <EVENT_TYPE extends BaseEvent> EVENT_TYPE parseCloudEvent(
            CloudEvent cloudEvent,
            Class<EVENT_TYPE> clazz
    ) {
        try {
            return objectMapper.readValue(cloudEvent.getTextData(), clazz);
        } catch (JsonProcessingException e) {
            throw new CyodaOperationException(
                    "PARSE_ERROR",
                    "Failed to parse Cyoda response: " + e.getMessage(),
                    false,
                    e
            );
        }
    }
}
