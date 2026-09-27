package com.java_template.common.grpc.client.event_handling;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.java_template.common.workflow.*;
import org.cyoda.cloud.api.event.common.CloudEventType;
import org.cyoda.cloud.api.event.common.EntityMetadata;
import org.cyoda.cloud.api.event.processing.EntityProcessorCalculationRequest;
import org.cyoda.cloud.api.event.processing.EntityProcessorCalculationResponse;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Component;


/**
 * ABOUTME: Event handling strategy for processing entity processor calculation events
 * with workflow processor execution and response generation.
 */
@Component
public class ProcessorEventStrategy extends AbstractEventStrategy<
        EntityProcessorCalculationRequest,
        EntityProcessorCalculationResponse,
        OperationSpecification.Processor
        > {

    public ProcessorEventStrategy(
            OperationFactory operationFactory,
            ObjectMapper objectMapper,
            CyodaContextFactory eventContextFactory
    ) {
        super(operationFactory, objectMapper, eventContextFactory);
    }

    @Override
    protected Class<EntityProcessorCalculationRequest> getRequestClass() {
        return EntityProcessorCalculationRequest.class;
    }

    @Override
    protected OperationSpecification.Processor createOperationSpecification(
            EntityProcessorCalculationRequest request
    ) throws JsonProcessingException {
        EntityMetadata entityMetadata = parseForModelKey(request.getPayload().getMeta());
        return OperationSpecification.create(request, entityMetadata);
    }

    @Override
    protected EntityProcessorCalculationResponse executeOperation(
            OperationSpecification.Processor operation,
            EntityProcessorCalculationRequest request,
            CyodaEventContext<EntityProcessorCalculationRequest> context
    ) {
        // Get the processor that supports this OperationSpecification
        CyodaProcessor processor = operationFactory.getProcessorForModel(operation);
        return processor.process(context);
    }

    @Override
    protected EntityProcessorCalculationResponse createErrorResponse() {
        return new EntityProcessorCalculationResponse();
    }

    @Override
    protected void setRequestIdInErrorResponse(
            EntityProcessorCalculationResponse errorResponse,
            String requestId
    ) {
        errorResponse.setRequestId(requestId);
    }

    @Override
    public boolean supports(@NotNull CloudEventType eventType) {
        return CloudEventType.ENTITY_PROCESSOR_CALCULATION_REQUEST.equals(eventType);
    }

}
