package com.java_template.it.support;

import com.java_template.common.serializer.ProcessorSerializer;
import com.java_template.common.serializer.SerializerFactory;
import com.java_template.common.workflow.CyodaEventContext;
import com.java_template.common.workflow.CyodaProcessor;
import com.java_template.common.workflow.OperationSpecification;
import org.cyoda.cloud.api.event.processing.EntityProcessorCalculationRequest;
import org.cyoda.cloud.api.event.processing.EntityProcessorCalculationResponse;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class ItRecordingProcessor implements CyodaProcessor {

    private final ProcessorSerializer serializer;
    private final Set<UUID> processed = ConcurrentHashMap.newKeySet();

    public ItRecordingProcessor(SerializerFactory serializerFactory) {
        this.serializer = serializerFactory.getDefaultProcessorSerializer();
    }

    @Override
    public EntityProcessorCalculationResponse process(CyodaEventContext<EntityProcessorCalculationRequest> context) {
        return serializer.withRequest(context.getEvent())
                .toEntityWithMetadata(ItThing.class)
                .map(ctx -> {
                    ctx.entityResponse().entity().setNote("processed");
                    processed.add(ctx.entityResponse().getId());
                    return ctx.entityResponse();
                })
                .complete();
    }

    @Override
    public boolean supports(OperationSpecification spec) {
        return "ItRecordingProcessor".equals(spec.operationName());
    }

    public boolean hasProcessed(UUID entityId) {
        return processed.contains(entityId);
    }
}
