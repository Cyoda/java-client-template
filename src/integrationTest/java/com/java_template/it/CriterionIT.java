package com.java_template.it;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.java_template.common.config.Config;
import com.java_template.common.dto.EntityWithMetadata;
import com.java_template.common.grpc.client.monitoring.ConnectionStateTracker;
import com.java_template.common.service.EntityService;
import com.java_template.it.support.ItThing;
import com.java_template.testing.cyoda.*;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@CyodaIntegrationTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CriterionIT {

    @Autowired EntityService entityService;
    @Autowired Config config;
    @Autowired ConnectionStateTracker tracker;
    @Autowired ObjectMapper objectMapper;

    private String model;
    private CyodaRest rest;

    @BeforeAll
    void createModel() {
        CyodaAwait.memberReady(tracker, Duration.ofSeconds(20));
        model = "crit_it_" + UUID.randomUUID().toString().substring(0, 8);
        rest = CyodaTestEnvironment.rest(Profile.mockMemory());
        CyodaModelSetup.createModel(rest, model, 1, ItThing.sampleData(),
                WorkflowTemplating.load("/it-workflows/thing-workflow.json", config.getGrpcProcessorTag()));
    }

    @Test
    void aMatchingCriterionLetsTheTransitionThrough() {
        EntityWithMetadata<ItThing> created = entityService.create(ItThing.of(model, "big", 20));

        EntityWithMetadata<ItThing> approved = entityService.update(created.getId(), created.entity().in(model), "approve");

        assertThat(approved.getState()).isEqualTo("approved");
    }

    @Test
    void aRefusingCriterionAnswers400WithItsReasonOverRest() {
        EntityWithMetadata<ItThing> created = entityService.create(ItThing.of(model, "small", 5));

        CyodaRest.Response r = rest.put("entity/JSON/" + created.getId() + "/approve",
                objectMapper.valueToTree(created.entity()));

        assertThat(r.status()).isEqualTo(400);
        assertThat(r.raw()).contains("WORKFLOW_FAILED").contains("amount below 10");
    }

    @Test
    void aRefusingCriterionSurfacesCodeAndReasonThroughEntityService() {
        EntityWithMetadata<ItThing> created = entityService.create(ItThing.of(model, "small2", 5));

        assertThatThrownBy(() -> entityService.update(created.getId(), created.entity().in(model), "approve"))
                .hasStackTraceContaining("WORKFLOW_FAILED")
                .hasStackTraceContaining("amount below 10");
    }
}
