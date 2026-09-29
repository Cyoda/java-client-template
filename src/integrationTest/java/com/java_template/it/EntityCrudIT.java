package com.java_template.it;

import com.java_template.common.config.Config;
import com.java_template.common.dto.EntityWithMetadata;
import com.java_template.common.grpc.client.monitoring.ConnectionStateTracker;
import com.java_template.common.service.EntityService;
import com.java_template.it.support.ItThing;
import com.java_template.testing.cyoda.*;
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

@CyodaIntegrationTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class EntityCrudIT {

    @Autowired EntityService entityService;
    @Autowired Config config;
    @Autowired ConnectionStateTracker tracker;

    private String model;
    private ModelSpec spec;

    @BeforeAll
    void createModel() {
        CyodaAwait.memberReady(tracker, Duration.ofSeconds(20));
        model = "crud_it_" + UUID.randomUUID().toString().substring(0, 8);
        spec = new ModelSpec().withName(model).withVersion(1);
        CyodaModelSetup.createModel(CyodaTestEnvironment.rest(Profile.mockMemory()), model, 1, ItThing.sampleData(),
                WorkflowTemplating.load("/it-workflows/thing-workflow.json", config.getGrpcProcessorTag()));
    }

    @Test
    void createThenGet() {
        EntityWithMetadata<ItThing> created = entityService.create(ItThing.of(model, "one", 3));

        EntityWithMetadata<ItThing> read = entityService.getById(created.getId(), spec, ItThing.class);

        assertThat(read.entity().getName()).isEqualTo("one");
        assertThat(read.entity().getAmount()).isEqualTo(3);
        assertThat(read.getState()).isEqualTo("new");
    }

    @Test
    void bulkCreateAndGetAll() {
        String bulkModel = model;
        List<EntityWithMetadata<ItThing>> saved = entityService.save(List.of(
                ItThing.of(bulkModel, "b1", 1), ItThing.of(bulkModel, "b2", 2), ItThing.of(bulkModel, "b3", 3)));

        assertThat(saved).hasSize(3).allSatisfy(e -> assertThat(e.getId()).isNotNull());
        assertThat(entityService.findAll(spec, ItThing.class).data())
                .extracting(e -> e.entity().getName()).contains("b1", "b2", "b3");
    }

    @Test
    void loopbackUpdatePersistsData() {
        EntityWithMetadata<ItThing> created = entityService.create(ItThing.of(model, "before", 1));
        ItThing changed = created.entity().in(model);
        changed.setName("after");

        EntityWithMetadata<ItThing> updated = entityService.update(created.getId(), changed, null);

        assertThat(updated.entity().getName()).isEqualTo("after");
        assertThat(updated.getState()).isEqualTo("new");
    }

    @Test
    void updateWithTransitionRunsTheProcessor() {
        EntityWithMetadata<ItThing> created = entityService.create(ItThing.of(model, "p", 1));

        EntityWithMetadata<ItThing> processed = entityService.update(created.getId(), created.entity().in(model), "process");

        assertThat(processed.getState()).isEqualTo("processed");
        assertThat(processed.entity().getNote()).isEqualTo("processed");
    }

    @Test
    void deleteById() {
        EntityWithMetadata<ItThing> created = entityService.create(ItThing.of(model, "gone", 1));

        entityService.deleteById(created.getId());

        assertThatThrownBy(() -> entityService.getById(created.getId(), spec, ItThing.class))
                .hasStackTraceContaining("ENTITY_NOT_FOUND");
    }

    @Test
    void deleteAllReturnsTheCount() {
        String other = model + "_del";
        CyodaModelSetup.createModel(CyodaTestEnvironment.rest(Profile.mockMemory()), other, 1, ItThing.sampleData(),
                WorkflowTemplating.load("/it-workflows/thing-workflow.json", config.getGrpcProcessorTag()));
        entityService.save(List.of(ItThing.of(other, "x", 1), ItThing.of(other, "y", 2)));

        Integer deleted = entityService.deleteAll(new ModelSpec().withName(other).withVersion(1));

        assertThat(deleted).isEqualTo(2);
    }
}
