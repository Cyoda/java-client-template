package com.java_template.it.support;

import com.java_template.common.auth.CloudEventAuthContext;
import com.java_template.common.serializer.ProcessorSerializer;
import com.java_template.common.serializer.SerializerFactory;
import com.java_template.common.workflow.CyodaEventContext;
import com.java_template.common.workflow.CyodaProcessor;
import com.java_template.common.workflow.OperationSpecification;
import org.cyoda.cloud.api.event.processing.EntityProcessorCalculationRequest;
import org.cyoda.cloud.api.event.processing.EntityProcessorCalculationResponse;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Records the callout's auth context (authtype/authid/authclaims as data) per entity id. */
@Component
public class ItAuthProbeProcessor implements CyodaProcessor {

    public final Map<UUID, CloudEventAuthContext> seen = new ConcurrentHashMap<>();
    private final ProcessorSerializer serializer;

    public ItAuthProbeProcessor(SerializerFactory f) {
        this.serializer = f.getDefaultProcessorSerializer();
    }

    @Override
    public EntityProcessorCalculationResponse process(CyodaEventContext<EntityProcessorCalculationRequest> context) {
        CloudEventAuthContext auth = context.authContext();
        return serializer.withRequest(context.getEvent()).toEntityWithMetadata(ItThing.class).map(c -> {
            seen.put(c.entityResponse().getId(), auth);
            return c.entityResponse();
        }).complete();
    }

    @Override
    public boolean supports(OperationSpecification spec) {
        return "ItAuthProbeProcessor".equals(spec.operationName());
    }
}
