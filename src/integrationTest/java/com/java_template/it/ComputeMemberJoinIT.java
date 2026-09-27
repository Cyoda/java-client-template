package com.java_template.it;

import com.java_template.common.config.Config;
import com.java_template.common.dto.EntityWithMetadata;
import com.java_template.common.grpc.client.monitoring.ConnectionStateTracker;
import com.java_template.common.service.EntityService;
import com.java_template.it.support.ItRecordingProcessor;
import com.java_template.it.support.ItThing;
import com.java_template.testing.cyoda.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@CyodaIntegrationTest(profile = CyodaProfiles.KEEPALIVE_SHORT)
class ComputeMemberJoinIT {

    @Autowired ConnectionStateTracker tracker;
    @Autowired EntityService entityService;
    @Autowired Config config;
    @Autowired ItRecordingProcessor recorder;

    @Test
    void staysJoinedAcrossIdlePeriodsLongerThanTheEvictionWindow() throws Exception {
        CyodaAwait.memberReady(tracker, Duration.ofSeconds(20));
        String model = "join_it_" + UUID.randomUUID().toString().substring(0, 8);
        CyodaModelSetup.createModel(CyodaTestEnvironment.rest(CyodaProfiles.byName(CyodaProfiles.KEEPALIVE_SHORT)),
                model, 1, ItThing.sampleData(), WorkflowTemplating.load("/it-workflows/thing-workflow.json", config.getGrpcProcessorTag()));

        Thread.sleep(10_000); // more than 3× the 3 s eviction window of this profile

        EntityWithMetadata<ItThing> created = entityService.create(ItThing.of(model, "a", 1));
        EntityWithMetadata<ItThing> processed = entityService.update(created.getId(), created.entity().in(model), "process");

        assertThat(processed.getState()).isEqualTo("processed");
        assertThat(processed.entity().getNote()).isEqualTo("processed");
        assertThat(recorder.hasProcessed(created.getId())).isTrue();
    }
}
