package com.java_template.common.workflow;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.java_template.common.auth.CloudEventAuthContext;
import io.cloudevents.v1.proto.CloudEvent;
import org.cyoda.cloud.api.event.common.BaseEvent;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * ABOUTME: Factory component for creating CyodaEventContext instances from CloudEvent
 * and BaseEvent data for workflow processing and criteria evaluation.
 */
@Component
public class CyodaContextFactory {

    private static final Logger log = LoggerFactory.getLogger(CyodaContextFactory.class);

    private final ObjectMapper objectMapper;

    public CyodaContextFactory(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public <T extends BaseEvent> CyodaEventContext<T> createCyodaEventContext(
            CloudEvent cloudEvent,
            Class<T> eventClass
    )  throws JsonProcessingException {
        var authType = cloudEvent.getAttributesMap().get("authtype");
        if (authType != null && !CloudEventAuthContext.AUTH_TYPES.contains(authType.getCeString())) {
            log.warn("CloudEvent {} carries unknown authtype '{}' (expected one of {})",
                    cloudEvent.getId(), authType.getCeString(), CloudEventAuthContext.AUTH_TYPES);
        }
        T event = objectMapper.readValue(cloudEvent.getTextData(), eventClass);

        return new CyodaEventContext<T>() {
            @Override
            public CloudEvent getCloudEvent() {
                return cloudEvent;
            }

            @Override
            public @NotNull T getEvent() {
                return event;
            }
        };
    }


}
