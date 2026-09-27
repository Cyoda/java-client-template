package com.java_template.it.support;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.TextNode;
import com.java_template.common.dto.EntityWithMetadata;
import com.java_template.common.repository.SearchAndRetrievalParams;
import com.java_template.common.serializer.ProcessorSerializer;
import com.java_template.common.serializer.SerializerFactory;
import com.java_template.common.service.EntityService;
import com.java_template.common.service.WorkflowService;
import com.java_template.common.workflow.CyodaEventContext;
import com.java_template.common.workflow.CyodaProcessor;
import com.java_template.common.workflow.OperationSpecification;
import org.cyoda.cloud.api.common.model.GroupConditionDto;
import org.cyoda.cloud.api.common.model.SimpleConditionDto;
import org.cyoda.cloud.api.event.common.ModelSpec;
import org.cyoda.cloud.api.event.processing.EntityProcessorCalculationRequest;
import org.cyoda.cloud.api.event.processing.EntityProcessorCalculationResponse;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * amount = remaining cascade depth: creates a child (amount-1, ref = parent id) and fires "cascade" on it.
 * note switches: "fail-after-cascade" throws after the child write; "admin-inside" imports a workflow
 * for model "<model>_admin"; "timeout-param" tries a save with transaction-control parameters.
 */
@Component
public class ItCascadeProcessor implements CyodaProcessor {

    public final Map<UUID, Integer> childrenSeenBySearch = new ConcurrentHashMap<>();
    public final Set<UUID> timeoutParamRejected = ConcurrentHashMap.newKeySet();

    private final ProcessorSerializer serializer;
    private final EntityService entityService;
    private final WorkflowService workflowService;
    private final ObjectMapper om = new ObjectMapper();

    public ItCascadeProcessor(SerializerFactory f, EntityService entityService, WorkflowService workflowService) {
        this.serializer = f.getDefaultProcessorSerializer();
        this.entityService = entityService;
        this.workflowService = workflowService;
    }

    @Override
    public EntityProcessorCalculationResponse process(CyodaEventContext<EntityProcessorCalculationRequest> context) {
        return serializer.withRequest(context.getEvent()).toEntityWithMetadata(ItThing.class).map(c -> {
            EntityWithMetadata<ItThing> parent = c.entityResponse();
            String model = parent.getModelKey().getName();
            ModelSpec spec = new ModelSpec().withName(model).withVersion(1);
            ItThing thing = parent.entity().in(model);
            String note = thing.getNote();

            if (thing.getAmount() > 0) {
                ItThing child = ItThing.of(model, thing.getName() + ">", thing.getAmount() - 1);
                child.setRef(parent.getId().toString());
                EntityWithMetadata<ItThing> created = entityService.create(child);
                entityService.update(created.getId(), created.entity().in(model), "cascade");
                var byRef = new GroupConditionDto().operator(GroupConditionDto.OperatorEnum.AND).conditions(List.of(
                        new SimpleConditionDto().jsonPath("$.ref")
                                .operatorType(SimpleConditionDto.OperatorTypeEnum.EQUALS)
                                .value(TextNode.valueOf(parent.getId().toString()))));
                childrenSeenBySearch.put(parent.getId(),
                        entityService.search(spec, byRef, ItThing.class, SearchAndRetrievalParams.defaults()).data().size());
            }
            if ("admin-inside".equals(note)) {
                try {
                    var wf = om.readTree(getClass().getResourceAsStream("/it-workflows/thing-workflow.json"));
                    workflowService.importWorkflows(new ModelSpec().withName(model + "_admin").withVersion(1),
                            om.createArrayNode().add(com.java_template.testing.cyoda.WorkflowTemplating.applyTag(wf, "unused-tag")), "REPLACE");
                } catch (java.io.IOException e) {
                    throw new IllegalStateException(e);
                }
            }
            if ("timeout-param".equals(note)) {
                try {
                    entityService.save(List.of(ItThing.of(model, "never", 0)), 10, 1000L);
                } catch (IllegalArgumentException expected) {
                    timeoutParamRejected.add(parent.getId());
                }
            }
            if ("fail-after-cascade".equals(note)) {
                throw new IllegalStateException("fail-after-cascade");
            }
            thing.setNote("cascaded");
            return parent;
        }).complete();
    }

    @Override
    public boolean supports(OperationSpecification spec) {
        return "ItCascadeProcessor".equals(spec.operationName());
    }
}
