package com.java_template.it.support;

import com.java_template.common.serializer.ProcessorSerializer;
import com.java_template.common.serializer.SerializerFactory;
import com.java_template.common.workflow.CyodaEventContext;
import com.java_template.common.workflow.CyodaProcessor;
import com.java_template.common.workflow.OperationSpecification;
import org.cyoda.cloud.api.event.processing.EntityProcessorCalculationRequest;
import org.cyoda.cloud.api.event.processing.EntityProcessorCalculationResponse;
import org.springframework.stereotype.Component;

@Component
public class ItFailingProcessor implements CyodaProcessor {

    private final ProcessorSerializer serializer;

    public ItFailingProcessor(SerializerFactory serializerFactory) {
        this.serializer = serializerFactory.getDefaultProcessorSerializer();
    }

    @Override
    public EntityProcessorCalculationResponse process(CyodaEventContext<EntityProcessorCalculationRequest> context) {
        return serializer.withRequest(context.getEvent())
                .toEntityWithMetadata(ItThing.class)
                .map(ctx -> {
                    throw new IllegalStateException("it-failure");
                })
                .complete();
    }

    @Override
    public boolean supports(OperationSpecification spec) {
        return "ItFailingProcessor".equals(spec.operationName());
    }
}
