package com.java_template.it;

import com.java_template.common.auth.CloudEventAuthContext;
import com.java_template.common.config.Config;
import com.java_template.common.dto.EntityWithMetadata;
import com.java_template.common.grpc.client.monitoring.ConnectionStateTracker;
import com.java_template.common.service.EntityService;
import com.java_template.it.support.ItAuthProbeProcessor;
import com.java_template.it.support.ItThing;
import com.java_template.testing.cyoda.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Spec §6.2: the processor sees the mock IAM principal as data. cyoda-go's mock principal (harness env
 * CYODA_IAM_MOCK_KIND=user, CYODA_IAM_MOCK_ROLES=ROLE_ADMIN,ROLE_M2M; id mock-user-001) reaches the callout as
 * authtype/authid/authclaims via internal/grpc/cloudevent.go AttachAuthContext.
 */
@CyodaIntegrationTest
class AuthContextPlumbingIT {

    @Autowired EntityService entityService;
    @Autowired Config config;
    @Autowired ConnectionStateTracker tracker;
    @Autowired ItAuthProbeProcessor probe;

    @Test
    void theProcessorSeesTheMockPrincipalAsData() {
        CyodaAwait.memberReady(tracker, Duration.ofSeconds(20));
        String model = "auth_it_" + UUID.randomUUID().toString().substring(0, 8);
        CyodaModelSetup.createModel(CyodaTestEnvironment.rest(Profile.mockMemory()), model, 1, ItThing.sampleData(),
                WorkflowTemplating.load("/it-workflows/thing-workflow.json", config.getGrpcProcessorTag()));
        EntityWithMetadata<ItThing> created = entityService.create(ItThing.of(model, "a", 1));

        EntityWithMetadata<ItThing> probed = entityService.update(created.getId(), created.entity().in(model), "probe");

        assertThat(probed.getState()).isEqualTo("probed");
        CloudEventAuthContext auth = probe.seen.get(created.getId());
        assertThat(auth).isNotNull();
        assertThat(auth.type()).isEqualTo(CloudEventAuthContext.Type.USER);
        assertThat(auth.id()).isEqualTo("mock-user-001");
        assertThat(auth.roles()).containsExactlyInAnyOrder("ROLE_ADMIN", "ROLE_M2M");
        assertThat(auth.requireRole("ROLE_ADMIN")).isTrue();
        assertThat(auth.requireRole("ADMIN")).isFalse();
    }
}
