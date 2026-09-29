package com.java_template.it.support;

import com.java_template.common.serializer.CriterionSerializer;
import com.java_template.common.serializer.EvaluationOutcome;
import com.java_template.common.serializer.SerializerFactory;
import com.java_template.common.workflow.CyodaCriterion;
import com.java_template.common.workflow.CyodaEventContext;
import com.java_template.common.workflow.OperationSpecification;
import org.cyoda.cloud.api.event.processing.EntityCriteriaCalculationRequest;
import org.cyoda.cloud.api.event.processing.EntityCriteriaCalculationResponse;
import org.springframework.stereotype.Component;

/** Matches when amount >= 10; otherwise refuses with reason "amount below 10". */
@Component
public class ItFlagCriterion implements CyodaCriterion {

    private final CriterionSerializer serializer;

    public ItFlagCriterion(SerializerFactory serializerFactory) {
        this.serializer = serializerFactory.getDefaultCriteriaSerializer();
    }

    @Override
    public EntityCriteriaCalculationResponse check(CyodaEventContext<EntityCriteriaCalculationRequest> context) {
        return serializer.withRequest(context.getEvent())
                .evaluateEntity(ItThing.class, ctx -> ctx.entityWithMetadata().entity().getAmount() >= 10
                        ? EvaluationOutcome.success()
                        : EvaluationOutcome.fail("amount below 10"))
                .complete();
    }

    @Override
    public boolean supports(OperationSpecification spec) {
        return "ItFlagCriterion".equals(spec.operationName());
    }
}
