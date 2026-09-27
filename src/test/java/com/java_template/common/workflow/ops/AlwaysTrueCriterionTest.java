package com.java_template.common.workflow.ops;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.java_template.common.config.CyodaJackson;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.java_template.common.config.Config;
import com.java_template.common.serializer.CriterionSerializer;
import com.java_template.common.serializer.SerializerFactory;
import com.java_template.common.serializer.jackson.JacksonCriterionSerializer;
import com.java_template.common.workflow.CyodaEventContext;
import com.java_template.common.workflow.OperationSpecification;
import io.cloudevents.v1.proto.CloudEvent;
import org.cyoda.cloud.api.event.common.DataPayload;
import org.cyoda.cloud.api.event.common.ModelSpec;
import org.cyoda.cloud.api.event.processing.EntityCriteriaCalculationRequest;
import org.cyoda.cloud.api.event.processing.EntityCriteriaCalculationResponse;
import org.cyoda.uuid.SimpleSystemClock;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class AlwaysTrueCriterionTest {
    public static final @NotNull UUID ENTITY_ID = SimpleSystemClock.INSTANCE.uniqueTimeUUIDinMicros();
    JacksonCriterionSerializer criterionSerializer = new JacksonCriterionSerializer(CyodaJackson.configure(new ObjectMapper()));
    SerializerFactory serializerFactory = new SerializerFactory(List.of(), List.of((CriterionSerializer) criterionSerializer));

    @Test
    void testSupports() {
        // Given
        Config config = mock(Config.class);
        AlwaysTrueCriterion criterion = new AlwaysTrueCriterion(serializerFactory, config);
        ModelSpec modeKey = new ModelSpec();
        modeKey.setName("model");
        modeKey.setVersion(1);
        OperationSpecification.Criterion opsSpec = new OperationSpecification.Criterion(
                modeKey,
                "AlwaysTrueCriterion",
                "state",
                "transition",
                "workflow"
        );

        // When
        boolean supports = criterion.supports(opsSpec);

        // Then
        assertTrue(supports);

        // Given
        opsSpec = new OperationSpecification.Criterion(
                modeKey,
                "xxx",
                "state",
                "transition",
                "workflow"
        );

        supports = criterion.supports(opsSpec);

        // Then
        assertFalse(supports);
    }


    @Test
    void testCheck() {
        // Given
        Config config = mock(Config.class);
        AlwaysTrueCriterion criterion = new AlwaysTrueCriterion(serializerFactory, config);

        CyodaEventContext<EntityCriteriaCalculationRequest> context = getEventContext();

        // When
        EntityCriteriaCalculationResponse response = criterion.check(context);

        // Then
        assertTrue(response.getMatches());
        assertNotNull(response.getId());
        assertNotEquals("123", response.getId());
        assertEquals(ENTITY_ID, response.getEntityId());
        assertTrue(response.getSuccess());
    }

    @NotNull
    private static CyodaEventContext<EntityCriteriaCalculationRequest> getEventContext() {
        EntityCriteriaCalculationRequest request = new EntityCriteriaCalculationRequest();
        request.setId("123");
        request.setRequestId("123");
        request.setEntityId(ENTITY_ID);

        // Add a proper payload to avoid extraction errors
        DataPayload payload = new DataPayload();
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode data = mapper.createObjectNode();
        data.put("test", "data");
        payload.setData(data);
        request.setPayload(payload);

        return new CyodaEventContext<>() {
            @Override
            public CloudEvent getCloudEvent() {
                return mock(CloudEvent.class);
            }

            @Override
            public @NotNull EntityCriteriaCalculationRequest getEvent() {
                return request;
            }
        };
    }
}