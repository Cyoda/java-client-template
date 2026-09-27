package com.example.tests;

import com.example.application.entity.example_entity.version_1.ExampleEntity;
import com.example.application.processor.ExampleEntityProcessor;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.java_template.common.serializer.ProcessorSerializer;
import com.java_template.common.serializer.SerializerFactory;
import com.java_template.common.serializer.jackson.JacksonProcessorSerializer;
import com.java_template.common.service.EntityService;
import com.java_template.common.workflow.CyodaEventContext;
import com.java_template.common.workflow.OperationSpecification;
import io.cloudevents.v1.proto.CloudEvent;
import org.cyoda.cloud.api.event.common.DataPayload;
import org.cyoda.cloud.api.event.common.ModelSpec;
import org.cyoda.cloud.api.event.processing.EntityProcessorCalculationRequest;
import org.cyoda.cloud.api.event.processing.EntityProcessorCalculationResponse;
import org.cyoda.uuid.SimpleSystemClock;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for ExampleEntityProcessor
 * Tests processor logic, validation, and entity processing
 */
@DisplayName("ExampleEntityProcessor Tests")
class ExampleEntityProcessorTest {

    private ExampleEntityProcessor processor;
    private ObjectMapper objectMapper;
    private ProcessorSerializer serializer;

    @Mock
    private SerializerFactory serializerFactory;

    @Mock
    private EntityService entityService;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());
        serializer = new JacksonProcessorSerializer(objectMapper);

        when(serializerFactory.getDefaultProcessorSerializer()).thenReturn(serializer);

        processor = new ExampleEntityProcessor(serializerFactory, entityService);
    }

    @Test
    @DisplayName("Should support correct operation specification")
    void testSupports() {
        ModelSpec modelSpec = new ModelSpec().withName("ExampleEntity").withVersion(1);
        OperationSpecification.Processor opSpec = new OperationSpecification.Processor(
                modelSpec, "ExampleEntityProcessor", "DRAFT", "submit", "ExampleWorkflow");

        assertTrue(processor.supports(opSpec));
    }

    @Test
    @DisplayName("Should not support different operation name")
    void testDoesNotSupportDifferentOperation() {
        ModelSpec modelSpec = new ModelSpec().withName("ExampleEntity").withVersion(1);
        OperationSpecification.Processor opSpec = new OperationSpecification.Processor(
                modelSpec, "DifferentProcessor", "DRAFT", "submit", "ExampleWorkflow");

        assertFalse(processor.supports(opSpec));
    }

    @Test
    @DisplayName("Should process valid entity successfully")
    void testProcessValidEntity() {
        // Create valid entity
        ExampleEntity entity = new ExampleEntity();
        entity.setExampleId("TEST-001");
        entity.setName("Test Entity");
        entity.setAmount(100.0);
        entity.setQuantity(5);

        // Create request
        EntityProcessorCalculationRequest request = createRequest(entity);
        CyodaEventContext<EntityProcessorCalculationRequest> context =
                createContext(request);

        // Execute process
        EntityProcessorCalculationResponse response = processor.process(context);

        // Verify response
        assertNotNull(response);
        assertTrue(response.getSuccess());
        assertNotNull(response.getId());
        assertNotEquals(request.getId(), response.getId());
        assertEquals(request.getRequestId(), response.getRequestId());
        assertEquals(request.getEntityId(), response.getEntityId());
    }

    @Test
    @DisplayName("Should fail processing when entity is invalid")
    void testProcessInvalidEntity() {
        // Create invalid entity (missing exampleId)
        ExampleEntity entity = new ExampleEntity();
        entity.setName("Test Entity");
        entity.setAmount(100.0);

        // Create request
        EntityProcessorCalculationRequest request = createRequest(entity);
        CyodaEventContext<EntityProcessorCalculationRequest> context =
                createContext(request);

        // Execute process
        EntityProcessorCalculationResponse response = processor.process(context);

        // Verify response
        assertNotNull(response);
        assertFalse(response.getSuccess());
    }

    @Test
    @DisplayName("Should process entity with items and calculate totals")
    void testProcessEntityWithItems() {
        // Create entity with items
        ExampleEntity entity = new ExampleEntity();
        entity.setExampleId("TEST-002");
        entity.setName("Entity with Items");

        List<ExampleEntity.ExampleItem> items = new ArrayList<>();

        ExampleEntity.ExampleItem item1 = new ExampleEntity.ExampleItem();
        item1.setItemId("ITEM-001");
        item1.setPrice(10.0);
        item1.setQty(2);
        item1.setItemTotal(20.0);
        items.add(item1);

        ExampleEntity.ExampleItem item2 = new ExampleEntity.ExampleItem();
        item2.setItemId("ITEM-002");
        item2.setPrice(15.0);
        item2.setQty(3);
        item2.setItemTotal(45.0);
        items.add(item2);

        entity.setItems(items);

        // Create request
        EntityProcessorCalculationRequest request = createRequest(entity);
        CyodaEventContext<EntityProcessorCalculationRequest> context =
                createContext(request);

        // Execute process
        EntityProcessorCalculationResponse response = processor.process(context);

        // Verify response
        assertNotNull(response);
        assertTrue(response.getSuccess());

        // Verify the entity in the response has calculated amount
        assertNotNull(response.getPayload());
        assertNotNull(response.getPayload().getData());
    }

    @Test
    @DisplayName("Should handle entity with null items list")
    void testProcessEntityWithNullItems() {
        ExampleEntity entity = new ExampleEntity();
        entity.setExampleId("TEST-003");
        entity.setName("Entity without Items");
        entity.setItems(null);

        EntityProcessorCalculationRequest request = createRequest(entity);
        CyodaEventContext<EntityProcessorCalculationRequest> context =
                createContext(request);

        EntityProcessorCalculationResponse response = processor.process(context);

        assertNotNull(response);
        assertTrue(response.getSuccess());
    }

    @Test
    @DisplayName("Should handle entity with empty items list")
    void testProcessEntityWithEmptyItems() {
        ExampleEntity entity = new ExampleEntity();
        entity.setExampleId("TEST-004");
        entity.setName("Entity with Empty Items");
        entity.setItems(new ArrayList<>());

        EntityProcessorCalculationRequest request = createRequest(entity);
        CyodaEventContext<EntityProcessorCalculationRequest> context =
                createContext(request);

        EntityProcessorCalculationResponse response = processor.process(context);

        assertNotNull(response);
        assertTrue(response.getSuccess());
    }

    @Test
    @DisplayName("Should handle items with null itemTotal")
    void testProcessEntityWithNullItemTotals() {
        ExampleEntity entity = new ExampleEntity();
        entity.setExampleId("TEST-005");
        entity.setName("Entity with Null Item Totals");

        List<ExampleEntity.ExampleItem> items = new ArrayList<>();

        ExampleEntity.ExampleItem item1 = new ExampleEntity.ExampleItem();
        item1.setItemId("ITEM-001");
        item1.setItemTotal(null);  // Null total
        items.add(item1);

        ExampleEntity.ExampleItem item2 = new ExampleEntity.ExampleItem();
        item2.setItemId("ITEM-002");
        item2.setItemTotal(25.0);
        items.add(item2);

        entity.setItems(items);

        EntityProcessorCalculationRequest request = createRequest(entity);
        CyodaEventContext<EntityProcessorCalculationRequest> context =
                createContext(request);

        EntityProcessorCalculationResponse response = processor.process(context);

        assertNotNull(response);
        assertTrue(response.getSuccess());
    }

    @Test
    @DisplayName("Should populate response metadata correctly")
    void testResponseMetadata() {
        ExampleEntity entity = new ExampleEntity();
        entity.setExampleId("TEST-006");

        EntityProcessorCalculationRequest request = createRequest(entity);
        request.setId("custom-id-123");
        request.setRequestId("custom-req-456");
        UUID entityId = SimpleSystemClock.INSTANCE.uniqueTimeUUIDinMicros();
        request.setEntityId(entityId);

        CyodaEventContext<EntityProcessorCalculationRequest> context =
                createContext(request);

        EntityProcessorCalculationResponse response = processor.process(context);

        assertNotNull(response);
        assertNotNull(response.getId());
        assertNotEquals("custom-id-123", response.getId());
        assertEquals("custom-req-456", response.getRequestId());
        assertEquals(entityId, response.getEntityId());
    }

    @Test
    @DisplayName("Should handle entity with complete nested structures")
    void testProcessEntityWithCompleteStructure() {
        ExampleEntity entity = new ExampleEntity();
        entity.setExampleId("TEST-007");
        entity.setName("Complete Entity");
        entity.setAmount(100.0);
        entity.setQuantity(5);
        entity.setDescription("A complete test entity");
        entity.setCreatedAt(LocalDateTime.now());

        // Add contact with address
        ExampleEntity.ExampleAddress address = new ExampleEntity.ExampleAddress();
        address.setLine1("123 Main St");
        address.setCity("Springfield");
        address.setState("IL");
        address.setPostcode("62701");
        address.setCountry("USA");

        ExampleEntity.ExampleContact contact = new ExampleEntity.ExampleContact();
        contact.setName("John Doe");
        contact.setEmail("john@example.com");
        contact.setPhone("+1-555-1234");
        contact.setAddress(address);

        entity.setContact(contact);

        // Add items
        List<ExampleEntity.ExampleItem> items = new ArrayList<>();
        ExampleEntity.ExampleItem item = new ExampleEntity.ExampleItem();
        item.setItemId("ITEM-001");
        item.setItemName("Test Item");
        item.setPrice(20.0);
        item.setQty(5);
        item.setCategory("Test");
        item.setItemTotal(100.0);
        items.add(item);
        entity.setItems(items);

        EntityProcessorCalculationRequest request = createRequest(entity);
        CyodaEventContext<EntityProcessorCalculationRequest> context =
                createContext(request);

        EntityProcessorCalculationResponse response = processor.process(context);

        assertNotNull(response);
        assertTrue(response.getSuccess());
    }

    /**
     * Helper method to create a properly structured request with entity data
     */
    private EntityProcessorCalculationRequest createRequest(ExampleEntity entity) {
        EntityProcessorCalculationRequest request = new EntityProcessorCalculationRequest();
        request.setId("test-request-123");
        request.setRequestId("req-456");
        request.setEntityId(SimpleSystemClock.INSTANCE.uniqueTimeUUIDinMicros());
        request.setProcessorId("processor-123");
        request.setProcessorName("ExampleEntityProcessor");

        // Convert entity to JSON
        ObjectNode entityJson = objectMapper.valueToTree(entity);

        // Create metadata with technical ID
        ObjectNode metadataJson = objectMapper.createObjectNode();
        metadataJson.put("id", java.util.UUID.randomUUID().toString());
        metadataJson.put("state", "DRAFT");

        // Create payload with entity data and metadata
        DataPayload payload = new DataPayload();
        payload.setData(entityJson);
        payload.setMeta(metadataJson);

        request.setPayload(payload);

        return request;
    }

    /**
     * Helper method to create a CyodaEventContext for testing
     */
    private CyodaEventContext<EntityProcessorCalculationRequest> createContext(EntityProcessorCalculationRequest request) {
        return new CyodaEventContext<>() {
            @Override
            public CloudEvent getCloudEvent() {
                return mock(CloudEvent.class);
            }

            @Override
            public @NotNull EntityProcessorCalculationRequest getEvent() {
                return request;
            }
        };
    }
}

