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
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * amount = remaining cascade depth: creates a child (amount-1, ref = parent id) and fires "cascade" on it.
 * note switches: "fail-after-cascade" throws after the child write; "admin-inside" imports a workflow
 * for model "<model>_admin"; "timeout-param" tries two saves, one per transaction-control parameter, and
 * records each refusal's message.
 * <p>
 * Leaf barrier (test-only, keyed by model, so other tests and reruns are unaffected). When a test registers a
 * latch for its model, every leaf (amount == 0) counts down and then waits for all the other leaves before it
 * answers. Every level of every chain then holds a callout thread at the same moment, so peak demand is
 * exactly chains × (depth + 1) threads. With a bounded pool smaller than that, the missing leaves can never be
 * scheduled, and the barrier times out for certain, not by chance. With one virtual thread per task it opens.
 */
@Component
public class ItCascadeProcessor implements CyodaProcessor {

    static final long LEAF_BARRIER_TIMEOUT_SECONDS = 30;

    public final Map<UUID, Integer> childrenSeenBySearch = new ConcurrentHashMap<>();
    public final Map<UUID, List<String>> transactionControlRejections = new ConcurrentHashMap<>();
    public final Map<String, CountDownLatch> leafBarriers = new ConcurrentHashMap<>();

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
            } else {
                awaitLeafBarrier(model);
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
                List<String> refusals = transactionControlRejections.computeIfAbsent(
                        parent.getId(), k -> new CopyOnWriteArrayList<>());
                try {
                    entityService.save(List.of(ItThing.of(model, "never-window", 0)), 10, null);
                } catch (IllegalArgumentException expected) {
                    refusals.add(expected.getMessage());
                }
                try {
                    entityService.save(List.of(ItThing.of(model, "never-timeout", 0)), null, 1000L);
                } catch (IllegalArgumentException expected) {
                    refusals.add(expected.getMessage());
                }
            }
            if ("fail-after-cascade".equals(note)) {
                throw new IllegalStateException("fail-after-cascade");
            }
            thing.setNote("cascaded");
            return parent;
        }).complete();
    }

    private void awaitLeafBarrier(String model) {
        CountDownLatch barrier = leafBarriers.get(model);
        if (barrier == null) {
            return;
        }
        barrier.countDown();
        try {
            if (!barrier.await(LEAF_BARRIER_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                throw new IllegalStateException("leaf barrier for " + model + " timed out after "
                        + LEAF_BARRIER_TIMEOUT_SECONDS + " s with " + barrier.getCount()
                        + " leaves missing: callout threads exhausted?");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted at the leaf barrier for " + model, e);
        }
    }

    @Override
    public boolean supports(OperationSpecification spec) {
        return "ItCascadeProcessor".equals(spec.operationName());
    }
}
