package com.java_template.common.grpc.client.event_handling;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.java_template.common.config.CyodaObjectMapper;
import com.java_template.common.workflow.*;
import org.cyoda.cloud.api.event.common.CloudEventType;
import org.cyoda.cloud.api.event.common.EntityMetadata;
import org.cyoda.cloud.api.event.processing.EntityCriteriaCalculationRequest;
import org.cyoda.cloud.api.event.processing.EntityCriteriaCalculationResponse;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Component;


/**
 * ABOUTME: Event handling strategy for processing criteria calculation events
 * with workflow criterion execution and response generation.
 */
@Component
public class CriteriaEventStrategy extends AbstractEventStrategy<
        EntityCriteriaCalculationRequest,
        EntityCriteriaCalculationResponse,
        OperationSpecification.Criterion
        > {

    public CriteriaEventStrategy(
            OperationFactory operationFactory,
            CyodaObjectMapper wireMapper,
            CyodaContextFactory eventContextFactory
    ) {
        super(operationFactory, wireMapper, eventContextFactory);
    }

    @Override
    protected Class<EntityCriteriaCalculationRequest> getRequestClass() {
        return EntityCriteriaCalculationRequest.class;
    }

    @Override
    protected OperationSpecification.Criterion createOperationSpecification(
            EntityCriteriaCalculationRequest request
    ) throws JsonProcessingException {
        EntityMetadata modelKey = parseForModelKey(request.getPayload().getMeta());
        return OperationSpecification.create(request, modelKey);
    }

    @Override
    protected EntityCriteriaCalculationResponse executeOperation(
            OperationSpecification.Criterion operation,
            EntityCriteriaCalculationRequest request,
            CyodaEventContext<EntityCriteriaCalculationRequest> context
    ) {
        // Get a criterion checker that supports this OperationSpecification
        CyodaCriterion cyodaCriterion = operationFactory.getCriteriaForModel(operation);
        // Delegate directly to criteria checker - it handles its own serialization
        return cyodaCriterion.check(context);
    }

    @Override
    protected EntityCriteriaCalculationResponse createErrorResponse() {
        return new EntityCriteriaCalculationResponse();
    }

    @Override
    protected void setRequestIdInErrorResponse(
            EntityCriteriaCalculationResponse errorResponse,
            String requestId
    ) {
        errorResponse.setRequestId(requestId);
    }

    @Override
    protected String requestIdOf(EntityCriteriaCalculationRequest request) {
        return request.getRequestId();
    }

    @Override
    protected void setEntityIdInErrorResponse(EntityCriteriaCalculationResponse errorResponse,
                                              EntityCriteriaCalculationRequest request) {
        errorResponse.setEntityId(request.getEntityId());
    }

    @Override
    protected void setRecoveredEntityId(EntityCriteriaCalculationResponse errorResponse, String entityId) {
        errorResponse.setEntityId(java.util.UUID.fromString(entityId));
    }

    @Override
    protected void enrichErrorResponse(EntityCriteriaCalculationResponse errorResponse) {
        errorResponse.setMatches(false);
    }

    @Override
    public boolean supports(@NotNull CloudEventType eventType) {
        return CloudEventType.ENTITY_CRITERIA_CALCULATION_REQUEST.equals(eventType);
    }

}
