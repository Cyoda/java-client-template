package com.java_template.common.workflow;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.java_template.common.auth.CloudEventAuthContext;
import com.java_template.common.config.CyodaObjectMapper;
import io.cloudevents.v1.proto.CloudEvent;
import org.cyoda.cloud.api.event.common.BaseEvent;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * ABOUTME: Factory component for creating CyodaEventContext instances from CloudEvent
 * and BaseEvent data for workflow processing and criteria evaluation.
 */
@Component
public class CyodaContextFactory {

    private static final Logger log = LoggerFactory.getLogger(CyodaContextFactory.class);
    /** Bounds the memory spent remembering unknown authtype values; beyond it every value logs at DEBUG. */
    static final int MAX_TRACKED_UNKNOWN_AUTH_TYPES = 64;

    private final ObjectMapper objectMapper;
    private final Set<String> warnedUnknownAuthTypes = ConcurrentHashMap.newKeySet();

    public CyodaContextFactory(CyodaObjectMapper mappers) {
        this.objectMapper = mappers.protocol();
    }

    public <T extends BaseEvent> CyodaEventContext<T> createCyodaEventContext(
            CloudEvent cloudEvent,
            Class<T> eventClass
    )  throws JsonProcessingException {
        var authType = cloudEvent.getAttributesMap().get("authtype");
        if (authType != null && !CloudEventAuthContext.AUTH_TYPES.contains(authType.getCeString())) {
            logUnknownAuthType(cloudEvent.getId(), authType.getCeString());
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

    /** WARN the first time a distinct unknown value is seen, DEBUG afterwards, so a busy stream does not flood the log. */
    private void logUnknownAuthType(String eventId, String authType) {
        boolean firstSighting = warnedUnknownAuthTypes.size() < MAX_TRACKED_UNKNOWN_AUTH_TYPES
                && warnedUnknownAuthTypes.add(authType);
        if (firstSighting) {
            log.warn("CloudEvent {} carries unknown authtype '{}' (expected one of {}); further events with it are logged at DEBUG",
                    eventId, authType, CloudEventAuthContext.AUTH_TYPES);
        } else {
            log.debug("CloudEvent {} carries unknown authtype '{}' (expected one of {})",
                    eventId, authType, CloudEventAuthContext.AUTH_TYPES);
        }
    }
}
