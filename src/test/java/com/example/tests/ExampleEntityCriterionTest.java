package com.example.tests;

import com.example.application.criterion.ExampleEntityCriterion;
import com.example.application.entity.example_entity.version_1.ExampleEntity;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for ExampleEntityCriterion
 * Tests criterion validation logic and response handling
 */
@DisplayName("ExampleEntityCriterion Tests")
class ExampleEntityCriterionTest {

    private ExampleEntityCriterion criterion;
    private ObjectMapper objectMapper;

    @Mock
    private SerializerFactory serializerFactory;

    AutoCloseable autoCloseable;

    @BeforeEach
    void setUp() {
        autoCloseable = MockitoAnnotations.openMocks(this);
        objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());
        CriterionSerializer serializer = new JacksonCriterionSerializer(objectMapper);

        when(serializerFactory.getDefaultCriteriaSerializer()).thenReturn(serializer);

        criterion = new ExampleEntityCriterion(serializerFactory);
    }

    @AfterEach
    void tearDown() throws Exception {
        autoCloseable.close();
    }

    @Test
    @DisplayName("Should support correct operation specification")
    void testSupports() {
        ModelSpec modelSpec = new ModelSpec().withName("ExampleEntity").withVersion(1);
        OperationSpecification.Criterion opSpec = new OperationSpecification.Criterion(
                modelSpec, "ExampleEntityCriterion", "DRAFT", "submit", "ExampleWorkflow");

        assertTrue(criterion.supports(opSpec));
    }

    @Test
    @DisplayName("Should not support different operation name")
    void testDoesNotSupportDifferentOperation() {
        ModelSpec modelSpec = new ModelSpec().withName("ExampleEntity").withVersion(1);
        OperationSpecification.Criterion opSpec = new OperationSpecification.Criterion(
                modelSpec, "DifferentCriterion", "DRAFT", "submit", "ExampleWorkflow");

        assertFalse(criterion.supports(opSpec));
    }

    @Test
    @DisplayName("Should pass validation for valid entity")
    void testCheckWithValidEntity() {
        // Create valid entity
        ExampleEntity entity = new ExampleEntity();
        entity.setExampleId("TEST-001");
        entity.setName("Test Entity");
        entity.setAmount(100.0);

        // Create request with valid entity
        EntityCriteriaCalculationRequest request = createRequest(entity);
        CyodaEventContext<EntityCriteriaCalculationRequest> context = createContext(request);

        // Execute check
        EntityCriteriaCalculationResponse response = criterion.check(context);

        // Verify response
        assertNotNull(response);
        assertTrue(response.getSuccess());
        assertNotNull(response.getId());
        assertNotEquals(request.getId(), response.getId());
        assertEquals(request.getRequestId(), response.getRequestId());
        assertEquals(request.getEntityId(), response.getEntityId());
    }

    @Test
    @DisplayName("Should fail validation when exampleId is null")
    void testCheckWithInvalidEntity() {
        // Create invalid entity (missing exampleId)
        ExampleEntity entity = new ExampleEntity();
        entity.setName("Test Entity");
        entity.setAmount(100.0);

        // Create request with invalid entity
        EntityCriteriaCalculationRequest request = createRequest(entity);
        CyodaEventContext<EntityCriteriaCalculationRequest> context = createContext(request);

        // Execute check
        EntityCriteriaCalculationResponse response = criterion.check(context);

        // Verify response
        assertNotNull(response);
        assertTrue(response.getSuccess()); // Evaluation succeeded
        assertFalse(response.getMatches()); // But entity doesn't match criteria
        assertNotNull(response.getReason());
        assertTrue(response.getReason().contains("Entity is not valid"));
    }

    @Test
    @DisplayName("Should return error when entity data is null")
    void testCheckWithNullEntity() {
        // Create request with null entity data
        EntityCriteriaCalculationRequest request = new EntityCriteriaCalculationRequest();
        request.setId("test-request-123");
        request.setRequestId("req-456");
        request.setEntityId(SimpleSystemClock.INSTANCE.uniqueTimeUUIDinMicros());

        DataPayload payload = new DataPayload();
        payload.setData(null);
        request.setPayload(payload);

        CyodaEventContext<EntityCriteriaCalculationRequest> context = createContext(request);

        // Execute check
        EntityCriteriaCalculationResponse response = criterion.check(context);

        // Verify response - null data causes an error, not a validation failure
        assertNotNull(response);
        assertFalse(response.getSuccess()); // Error occurred during deserialization
        assertNotNull(response.getError());
    }

    @Test
    @DisplayName("Should handle entity with all fields populated")
    void testCheckWithCompleteEntity() {
        // Create entity with all fields
        ExampleEntity entity = new ExampleEntity();
        entity.setExampleId("TEST-001");
        entity.setName("Complete Entity");
        entity.setAmount(250.75);
        entity.setQuantity(10);
        entity.setDescription("A complete test entity");

        // Add items
        ExampleEntity.ExampleItem item = new ExampleEntity.ExampleItem();
        item.setItemId("ITEM-001");
        item.setItemName("Test Item");
        item.setPrice(25.0);
        item.setQty(10);
        item.setItemTotal(250.0);
        entity.setItems(java.util.List.of(item));

        // Add contact
        ExampleEntity.ExampleContact contact = new ExampleEntity.ExampleContact();
        contact.setName("John Doe");
        contact.setEmail("john@example.com");
        entity.setContact(contact);

        // Create request
        EntityCriteriaCalculationRequest request = createRequest(entity);
        CyodaEventContext<EntityCriteriaCalculationRequest> context = createContext(request);

        // Execute check
        EntityCriteriaCalculationResponse response = criterion.check(context);

        // Verify response
        assertNotNull(response);
        assertTrue(response.getSuccess());
        assertTrue(response.getMatches()); // Entity is valid
    }

    @Test
    @DisplayName("Should handle entity with minimal required fields")
    void testCheckWithMinimalEntity() {
        // Create entity with only required field
        ExampleEntity entity = new ExampleEntity();
        entity.setExampleId("TEST-MINIMAL");

        // Create request
        EntityCriteriaCalculationRequest request = createRequest(entity);
        CyodaEventContext<EntityCriteriaCalculationRequest> context = createContext(request);

        // Execute check
        EntityCriteriaCalculationResponse response = criterion.check(context);

        // Verify response
        assertNotNull(response);
        assertTrue(response.getSuccess());
        assertTrue(response.getMatches()); // Entity is valid
    }

    @Test
    @DisplayName("Should populate response with request metadata")
    void testResponseMetadata() {
        ExampleEntity entity = new ExampleEntity();
        entity.setExampleId("TEST-001");

        EntityCriteriaCalculationRequest request = createRequest(entity);
        request.setId("custom-id-123");
        request.setRequestId("custom-req-456");
        UUID entityId = SimpleSystemClock.INSTANCE.uniqueTimeUUIDinMicros();
        request.setEntityId(entityId);

        CyodaEventContext<EntityCriteriaCalculationRequest> context = createContext(request);

        EntityCriteriaCalculationResponse response = criterion.check(context);

        assertNotNull(response);
        assertNotNull(response.getId());
        assertNotEquals("custom-id-123", response.getId());
        assertEquals("custom-req-456", response.getRequestId());
        assertEquals(entityId, response.getEntityId());
        assertTrue(response.getMatches()); // Entity is valid
    }

    @Test
    @DisplayName("Should handle empty exampleId as valid")
    void testCheckWithEmptyExampleId() {
        ExampleEntity entity = new ExampleEntity();
        entity.setExampleId("");  // Empty string, not null

        EntityCriteriaCalculationRequest request = createRequest(entity);
        CyodaEventContext<EntityCriteriaCalculationRequest> context = createContext(request);

        EntityCriteriaCalculationResponse response = criterion.check(context);

        // Empty string is still set, so isValid returns true
        // This tests the actual behavior of the entity's isValid method
        assertNotNull(response);
        assertTrue(response.getSuccess());
        assertTrue(response.getMatches()); // Empty string is not null, so it's valid
    }

    /**
     * Helper method to create a properly structured request with entity data
     */
    private EntityCriteriaCalculationRequest createRequest(ExampleEntity entity) {
        EntityCriteriaCalculationRequest request = new EntityCriteriaCalculationRequest();
        request.setId("test-request-123");
        request.setRequestId("req-456");
        request.setEntityId(SimpleSystemClock.INSTANCE.uniqueTimeUUIDinMicros());

        // Convert entity to JSON
        ObjectNode entityJson = objectMapper.valueToTree(entity);

        // Create payload with entity data
        DataPayload payload = new DataPayload();
        payload.setData(entityJson);

        request.setPayload(payload);

        return request;
    }

    /**
     * Helper method to create a CyodaEventContext for testing
     */
    private CyodaEventContext<EntityCriteriaCalculationRequest> createContext(EntityCriteriaCalculationRequest request) {
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

