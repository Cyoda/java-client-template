package com.java_template.common.serializer.jackson;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.java_template.common.config.CyodaJackson;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.java_template.common.dto.EntityWithMetadata;
import com.java_template.common.workflow.CyodaEntity;
import com.java_template.common.workflow.OperationSpecification;
import org.cyoda.cloud.api.event.common.DataPayload;
import org.cyoda.cloud.api.event.common.EntityMetadata;
import org.cyoda.cloud.api.event.common.ModelSpec;
import org.cyoda.cloud.api.event.processing.EntityProcessorCalculationRequest;
import org.cyoda.uuid.SimpleSystemClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for JacksonProcessorSerializer, focusing on entity extraction
 * and metadata handling functionality.
 */
class JacksonProcessorSerializerTest {

    public static final UUID ENTITY_ID = SimpleSystemClock.INSTANCE.uniqueTimeUUIDinMicros();
    private ObjectMapper objectMapper;
    private JacksonProcessorSerializer serializer;

    @BeforeEach
    void setUp() {
        // The protocol mapper's settings (JavaTimeModule for the OffsetDateTime event fields, RFC 3339 text
        // dates, blank processor type handler), used here for the test's own DTOs and as the entity mapper.
        objectMapper = CyodaJackson.configure(new ObjectMapper());
        serializer = new JacksonProcessorSerializer(objectMapper);
    }

    @Test
    @DisplayName("extractEntity should properly extract entity and metadata from payload")
    void testExtractEntityWithMetadataWithMetadata() {
        // Given - Create request with metadata in payload
        EntityProcessorCalculationRequest request = createRequestWithMetadata();

        // When
        EntityWithMetadata<TestEntity> entityWithMetadata = serializer.extractEntityWithMetadata(request, TestEntity.class);

        // Then
        assertNotNull(entityWithMetadata);
        assertNotNull(entityWithMetadata.entity());
        assertNotNull(entityWithMetadata.metadata());

        // Verify entity data
        TestEntity entity = entityWithMetadata.entity();
        assertEquals(123L, entity.getId());
        assertEquals("Fluffy", entity.getName());
        assertEquals("available", entity.getStatus());

        // Verify metadata
        assertEquals(ENTITY_ID, entityWithMetadata.metadata().getId());
        assertEquals("ACTIVE", entityWithMetadata.metadata().getState());
        assertNotNull(entityWithMetadata.metadata().getCreationDate());
    }

    @Test
    @DisplayName("extractEntity should handle payload without metadata gracefully")
    void testExtractEntityWithMetadataWithoutMetadata() {
        // Given - Create request without metadata
        EntityProcessorCalculationRequest request = createRequestWithoutMetadata();

        // When
        EntityWithMetadata<TestEntity> entityWithMetadata = serializer.extractEntityWithMetadata(request, TestEntity.class);

        // Then
        assertNotNull(entityWithMetadata);
        assertNotNull(entityWithMetadata.entity());
        assertNotNull(entityWithMetadata.metadata()); // Should still have metadata object, but empty

        // Verify entity data
        TestEntity entity = entityWithMetadata.entity();
        assertEquals(123L, entity.getId());
        assertEquals("Fluffy", entity.getName());
        assertEquals("available", entity.getStatus());

        // Metadata should be empty since no meta was provided
        assertNull(entityWithMetadata.metadata().getId());
        assertNull(entityWithMetadata.metadata().getState());
    }

    @Test
    @DisplayName("extractEntity should propagate JsonProcessingException for invalid entity data")
    void testExtractEntityWithMetadataWithInvalidData() {
        // Given - Create request with invalid entity data
        EntityProcessorCalculationRequest request = new EntityProcessorCalculationRequest();
        request.setEntityId(SimpleSystemClock.INSTANCE.uniqueTimeUUIDinMicros());

        // Create invalid payload that cannot be converted to TestEntity
        ObjectNode invalidData = objectMapper.createObjectNode();
        invalidData.put("invalidField", "invalidValue");
        invalidData.put("anotherInvalidField", 999);

        DataPayload payload = new DataPayload();
        payload.setData(invalidData);
        request.setPayload(payload);

        // When & Then - Should propagate the exception instead of catching it
        assertThrows(RuntimeException.class, () -> serializer.extractEntityWithMetadata(request, TestEntity.class));
    }

    @Test
    @DisplayName("extractEntity should handle null payload gracefully")
    void testExtractEntityWithMetadataWithNullPayload() {
        // Given - Create request with null payload
        EntityProcessorCalculationRequest request = new EntityProcessorCalculationRequest();
        request.setEntityId(SimpleSystemClock.INSTANCE.uniqueTimeUUIDinMicros());
        request.setPayload(null);

        // When & Then - Should throw exception for null payload
        assertThrows(Exception.class, () -> serializer.extractEntityWithMetadata(request, TestEntity.class));
    }

    private EntityProcessorCalculationRequest createRequestWithMetadata() {
        EntityProcessorCalculationRequest request = new EntityProcessorCalculationRequest();
        request.setId("test-request-123");
        request.setEntityId(ENTITY_ID);

        // Create test payload with both data and metadata
        ObjectNode testData = objectMapper.createObjectNode();
        testData.put("id", 123L);
        testData.put("name", "Fluffy");
        testData.put("status", "available");

        ObjectNode testMeta = objectMapper.createObjectNode();
        testMeta.put("id", ENTITY_ID.toString());
        testMeta.put("state", "ACTIVE");
        testMeta.put("creationDate", "2023-01-01T00:00:00Z");

        DataPayload payload = new DataPayload();
        payload.setData(testData);
        payload.setMeta(testMeta);
        request.setPayload(payload);

        return request;
    }

    private EntityProcessorCalculationRequest createRequestWithoutMetadata() {
        EntityProcessorCalculationRequest request = new EntityProcessorCalculationRequest();
        request.setId("test-request-123");
        request.setEntityId(SimpleSystemClock.INSTANCE.uniqueTimeUUIDinMicros());

        // Create test payload with only data, no metadata
        ObjectNode testData = objectMapper.createObjectNode();
        testData.put("id", 123L);
        testData.put("name", "Fluffy");
        testData.put("status", "available");

        DataPayload payload = new DataPayload();
        payload.setData(testData);
        // Note: not setting payload.setMeta() - it will be null
        request.setPayload(payload);

        return request;
    }

    // Test entity class
    @SuppressWarnings({"LombokGetterMayBeUsed", "LombokSetterMayBeUsed", "unused"})
    static class TestEntity implements CyodaEntity {
        private Long id;
        private String name;
        private String status;

        public TestEntity() {}

        public Long getId() { return id; }
        public void setId(Long id) { this.id = id; }

        public String getName() { return name; }
        public void setName(String name) { this.name = name; }

        public String getStatus() { return status; }
        public void setStatus(String status) { this.status = status; }

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
