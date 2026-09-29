package com.java_template.it;

import com.java_template.common.config.Config;
import com.java_template.common.dto.EntityWithMetadata;
import com.java_template.common.grpc.client.monitoring.ConnectionStateTracker;
import com.java_template.common.service.EntityService;
import com.java_template.it.support.ItRecordingProcessor;
import com.java_template.it.support.ItThing;
import com.java_template.testing.cyoda.*;
import org.cyoda.cloud.api.event.common.ModelSpec;
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
class ProcessorIT {

    @Autowired EntityService entityService;
    @Autowired Config config;
    @Autowired ConnectionStateTracker tracker;
    @Autowired ItRecordingProcessor recorder;

    private String model;
    private ModelSpec spec;

    @BeforeAll
    void createModel() {
        CyodaAwait.memberReady(tracker, Duration.ofSeconds(20));
        model = "proc_it_" + UUID.randomUUID().toString().substring(0, 8);
        spec = new ModelSpec().withName(model).withVersion(1);
        CyodaModelSetup.createModel(CyodaTestEnvironment.rest(Profile.mockMemory()), model, 1, ItThing.sampleData(),
                WorkflowTemplating.load("/it-workflows/thing-workflow.json", config.getGrpcProcessorTag()));
    }

    @Test
    void theProcessorsChangePersistsWithTheTransition() {
        EntityWithMetadata<ItThing> created = entityService.create(ItThing.of(model, "p", 1));

        entityService.update(created.getId(), created.entity().in(model), "process");

        EntityWithMetadata<ItThing> read = entityService.getById(created.getId(), spec, ItThing.class);
        assertThat(read.getState()).isEqualTo("processed");
        assertThat(read.entity().getNote()).isEqualTo("processed");
        assertThat(recorder.hasProcessed(created.getId())).isTrue();
    }

    @Test
    void aFailingProcessorRollsTheTransitionBackAndSurfacesTheError() {
        EntityWithMetadata<ItThing> created = entityService.create(ItThing.of(model, "f", 1));

        assertThatThrownBy(() -> entityService.update(created.getId(), created.entity().in(model), "fail"))
                .hasStackTraceContaining("it-failure");

        EntityWithMetadata<ItThing> read = entityService.getById(created.getId(), spec, ItThing.class);
        assertThat(read.getState()).isEqualTo("new");
        assertThat(read.entity().getNote()).isEmpty();
    }
}
