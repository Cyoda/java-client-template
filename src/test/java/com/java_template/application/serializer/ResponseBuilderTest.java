package com.java_template.application.serializer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.java_template.common.serializer.ResponseBuilder;
import com.java_template.common.workflow.CyodaEntity;
import com.java_template.common.workflow.OperationSpecification;
import org.cyoda.cloud.api.event.common.EntityMetadata;
import org.cyoda.cloud.api.event.common.ModelSpec;
import org.cyoda.cloud.api.event.processing.EntityCriteriaCalculationRequest;
import org.cyoda.cloud.api.event.processing.EntityCriteriaCalculationResponse;
import org.cyoda.cloud.api.event.processing.EntityProcessorCalculationRequest;
import org.cyoda.cloud.api.event.processing.EntityProcessorCalculationResponse;
import org.cyoda.uuid.SimpleSystemClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * ABOUTME: Tests for the simplified ResponseBuilder API to ensure type safety and correct functionality.
 * Verifies that the new flat structure provides the expected fluent interface behavior.
 */
class ResponseBuilderTest {

    public static final UUID ENTITY_ID = SimpleSystemClock.INSTANCE.uniqueTimeUUIDinMicros();
    private EntityCriteriaCalculationRequest criteriaRequest;
    private EntityProcessorCalculationRequest processorRequest;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        criteriaRequest = mock(EntityCriteriaCalculationRequest.class);
        processorRequest = mock(EntityProcessorCalculationRequest.class);
        objectMapper = new ObjectMapper();

        // Setup common mock behavior
        when(criteriaRequest.getId()).thenReturn("criteria-123");
        when(criteriaRequest.getRequestId()).thenReturn("req-456");
        when(criteriaRequest.getEntityId()).thenReturn(ENTITY_ID);

        when(processorRequest.getId()).thenReturn("processor-123");
        when(processorRequest.getRequestId()).thenReturn("req-456");
        when(processorRequest.getEntityId()).thenReturn(ENTITY_ID);
        when(processorRequest.getPayload()).thenReturn(mock(org.cyoda.cloud.api.event.common.DataPayload.class));
    }

    @Test
    @DisplayName("CriterionResponseBuilder should create successful match response")
    void testCriterionBuilderSuccessfulMatch() {
        // Given
        ResponseBuilder.CriterionResponseBuilder builder = ResponseBuilder.forCriterion(criteriaRequest);

        // When
        EntityCriteriaCalculationResponse response = builder
                .withMatch()
                .build();

        // Then
        assertNotNull(response);
        assertNotNull(response.getId());
        assertNotEquals("criteria-123", response.getId());
        assertEquals("req-456", response.getRequestId());
        assertEquals(ENTITY_ID, response.getEntityId());
        assertTrue(response.getSuccess());
        assertTrue(response.getMatches());
    }

    @Test
    @DisplayName("CriterionResponseBuilder should create successful non-match response")
    void testCriterionBuilderSuccessfulNonMatch() {
        // Given
        ResponseBuilder.CriterionResponseBuilder builder = ResponseBuilder.forCriterion(criteriaRequest);

        // When
        EntityCriteriaCalculationResponse response = builder
                .withNonMatch()
                .build();

        // Then
        assertNotNull(response);
        assertTrue(response.getSuccess());
        assertFalse(response.getMatches());
    }

    @Test
    @DisplayName("CriterionResponseBuilder should create error response")
    void testCriterionBuilderError() {
        // Given
        ResponseBuilder.CriterionResponseBuilder builder = ResponseBuilder.forCriterion(criteriaRequest);

        // When
        EntityCriteriaCalculationResponse response = builder
                .withError("TEST_ERROR", "Test error message")
                .build();

        // Then
        assertNotNull(response);
        assertFalse(response.getSuccess());
        assertFalse(response.getMatches());
    }

    @Test
    @DisplayName("ProcessorResponseBuilder should create successful response with JSON data")
    void testProcessorBuilderSuccessWithJsonData() {
        // Given
        ResponseBuilder.ProcessorResponseBuilder builder = ResponseBuilder.forProcessor(processorRequest);
        ObjectNode testData = objectMapper.createObjectNode();
        testData.put("test", "value");

        // When
        EntityProcessorCalculationResponse response = builder
                .withSuccess(testData)
                .build();

        // Then
        assertNotNull(response);
        assertNotNull(response.getId());
        assertNotEquals("processor-123", response.getId());
        assertTrue(response.getSuccess());
        assertNotNull(response.getPayload());
    }

    @Test
    @DisplayName("ProcessorResponseBuilder should create successful response with entity")
    void testProcessorBuilderSuccessWithEntity() {
        // Given
        ResponseBuilder.ProcessorResponseBuilder builder = ResponseBuilder.forProcessor(processorRequest);
        TestEntity entity = new TestEntity("test-id", "test-name");

        // When
        EntityProcessorCalculationResponse response = builder
                .withSuccess(entity, this::convertEntityToJson)
                .build();

        // Then
        assertNotNull(response);
        assertTrue(response.getSuccess());
        assertNotNull(response.getPayload());
    }

    @Test
    @DisplayName("ProcessorResponseBuilder should create error response")
    void testProcessorBuilderError() {
        // Given
        ResponseBuilder.ProcessorResponseBuilder builder = ResponseBuilder.forProcessor(processorRequest);

        // When
        EntityProcessorCalculationResponse response = builder
                .withError("PROC_ERROR", "Processing failed")
                .withAdditionalErrorDetails("Additional context")
                .build();

        // Then
        assertNotNull(response);
        assertFalse(response.getSuccess());
    }

    @Test
    void responsesCarryAFreshIdAndTheRequestAndEntityIds() {
        UUID entityId = UUID.randomUUID();
        EntityCriteriaCalculationRequest criteriaRequest = new EntityCriteriaCalculationRequest()
                .withId("req-event-1").withRequestId("r-1").withEntityId(entityId);

        EntityCriteriaCalculationResponse criteria = ResponseBuilder.forCriterion(criteriaRequest).withMatch().build();

        assertThat(criteria.getId()).isNotBlank().isNotEqualTo("req-event-1");
        assertThat(criteria.getRequestId()).isEqualTo("r-1");
        assertThat(criteria.getEntityId()).isEqualTo(criteriaRequest.getEntityId());
        assertThat(criteria.getMatches()).isTrue();
    }

    @Test
    @DisplayName("Factory methods should return correct builder types")
    void testFactoryMethods() {
        // When
        ResponseBuilder.CriterionResponseBuilder criterionBuilder = ResponseBuilder.forCriterion(criteriaRequest);
        ResponseBuilder.ProcessorResponseBuilder processorBuilder = ResponseBuilder.forProcessor(processorRequest);

        // Then
        assertNotNull(criterionBuilder);
        assertNotNull(processorBuilder);
        assertInstanceOf(ResponseBuilder.CriterionResponseBuilder.class, criterionBuilder);
        assertInstanceOf(ResponseBuilder.ProcessorResponseBuilder.class, processorBuilder);
    }

    // Helper method for entity conversion
    private JsonNode convertEntityToJson(TestEntity entity) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("id", entity.getId());
        node.put("name", entity.getName());
        return node;
    }

    // Test entity class
    @SuppressWarnings({"ClassCanBeRecord", "LombokGetterMayBeUsed"})
    private static class TestEntity implements CyodaEntity {
        private final String id;
        private final String name;

        public TestEntity(String id, String name) {
            this.id = id;
            this.name = name;
        }

        public String getId() { return id; }
        public String getName() { return name; }

        @Override
        public boolean isValid(EntityMetadata metadata) { return true; }

        @Override
        public OperationSpecification getModelKey() {
            ModelSpec modelSpec = new ModelSpec();
            modelSpec.setName("test-entity");
            modelSpec.setVersion(1);
            return new OperationSpecification.Entity(modelSpec, "test-entity");
        }
    }
}
