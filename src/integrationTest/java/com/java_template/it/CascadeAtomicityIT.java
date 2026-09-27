package com.java_template.it;

import com.fasterxml.jackson.databind.node.TextNode;
import com.java_template.common.config.Config;
import com.java_template.common.dto.EntityWithMetadata;
import com.java_template.common.grpc.client.monitoring.ConnectionStateTracker;
import com.java_template.common.repository.SearchAndRetrievalParams;
import com.java_template.common.service.EntityService;
import com.java_template.it.support.ItCascadeProcessor;
import com.java_template.it.support.ItThing;
import com.java_template.testing.cyoda.*;
import org.cyoda.cloud.api.common.model.GroupConditionDto;
import org.cyoda.cloud.api.common.model.SimpleConditionDto;
import org.cyoda.cloud.api.event.common.ModelSpec;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Spec §6.2: a processor's own EntityService writes join the callout's transaction and share its fate. */
@CyodaIntegrationTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CascadeAtomicityIT {

    @Autowired EntityService entityService;
    @Autowired Config config;
    @Autowired ConnectionStateTracker tracker;
    @Autowired ItCascadeProcessor cascade;

    private String model;
    private ModelSpec spec;
    private CyodaRest rest;

    @BeforeAll
    void createModel() {
        CyodaAwait.memberReady(tracker, Duration.ofSeconds(20));
        model = "cascade_it_" + UUID.randomUUID().toString().substring(0, 8);
        spec = new ModelSpec().withName(model).withVersion(1);
        rest = CyodaTestEnvironment.rest(Profile.mockMemory());
        CyodaModelSetup.createModel(rest, model, 1, ItThing.sampleData(),
                WorkflowTemplating.load("/it-workflows/thing-workflow.json", config.getGrpcProcessorTag()));
    }

    private List<EntityWithMetadata<ItThing>> childrenOf(UUID parentId) {
        var byRef = new GroupConditionDto().operator(GroupConditionDto.OperatorEnum.AND).conditions(List.of(
                new SimpleConditionDto().jsonPath("$.ref").operatorType(SimpleConditionDto.OperatorTypeEnum.EQUALS)
                        .value(TextNode.valueOf(parentId.toString()))));
        return entityService.search(spec, byRef, ItThing.class, SearchAndRetrievalParams.builder().inMemory(true).build()).data();
    }

    @Test
    void theJoinedChildCommitsWithTheParentAndIsVisibleToSearchInsideTheScope() {
        EntityWithMetadata<ItThing> parent = entityService.create(ItThing.of(model, "p", 1));

        EntityWithMetadata<ItThing> done = entityService.update(parent.getId(), parent.entity().in(model), "cascade");

        assertThat(done.getState()).isEqualTo("cascaded");
        assertThat(childrenOf(parent.getId())).singleElement().satisfies(c -> assertThat(c.getState()).isEqualTo("cascaded"));
        assertThat(cascade.childrenSeenBySearch.get(parent.getId())).isEqualTo(1);
    }

    @Test
    void aFailureAfterTheCascadeRollsBackParentAndChild() {
        ItThing p = ItThing.of(model, "f", 1);
        p.setNote("fail-after-cascade");
        EntityWithMetadata<ItThing> parent = entityService.create(p);

        assertThatThrownBy(() -> entityService.update(parent.getId(), parent.entity().in(model), "cascade"))
                .hasStackTraceContaining("fail-after-cascade");

        assertThat(childrenOf(parent.getId())).isEmpty();
        assertThat(entityService.getById(parent.getId(), spec, ItThing.class).getState()).isEqualTo("new");
    }

    @Test
    void aModelAdminCallInsideTheProcessorCarriesNoTokenAndSucceeds() {
        rest.post("model/import/JSON/SAMPLE_DATA/" + model + "_admin/1", ItThing.sampleData()).requireSuccess();
        ItThing p = ItThing.of(model, "a", 0);
        p.setNote("admin-inside");
        EntityWithMetadata<ItThing> parent = entityService.create(p);

        EntityWithMetadata<ItThing> done = entityService.update(parent.getId(), parent.entity().in(model), "cascade");

        assertThat(done.getState()).isEqualTo("cascaded");
        assertThat(rest.get("model/" + model + "_admin/1/workflow/export").requireSuccess().raw()).contains("it-thing");
    }

    @Test
    void transactionControlParametersAreRejectedLocallyInsideTheScope() {
        ItThing p = ItThing.of(model, "t", 0);
        p.setNote("timeout-param");
        EntityWithMetadata<ItThing> parent = entityService.create(p);

        EntityWithMetadata<ItThing> done = entityService.update(parent.getId(), parent.entity().in(model), "cascade");

        assertThat(done.getState()).isEqualTo("cascaded");
        assertThat(cascade.timeoutParamRejected).contains(parent.getId());
    }
}
