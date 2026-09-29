package com.java_template.application.serializer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.java_template.common.config.CyodaJackson;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.java_template.common.dto.EntityWithMetadata;
import com.java_template.common.serializer.ErrorInfo;
import com.java_template.common.serializer.ProcessorSerializer;
import com.java_template.common.serializer.jackson.JacksonProcessorSerializer;
import com.java_template.common.workflow.CyodaEntity;
import com.java_template.common.workflow.OperationSpecification;
import org.cyoda.cloud.api.event.common.DataPayload;
import org.cyoda.cloud.api.event.common.EntityMetadata;
import org.cyoda.cloud.api.event.common.ModelSpec;
import org.cyoda.cloud.api.event.processing.EntityProcessorCalculationRequest;
import org.cyoda.cloud.api.event.processing.EntityProcessorCalculationResponse;
import org.cyoda.uuid.SimpleSystemClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.function.BiFunction;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ABOUTME: Unit tests for ProcessingChain testing the actual assembly and logic
 * without mocks, using real JacksonProcessorSerializer to test the complete chain behavior.
 */
class ProcessingChainTest {

    public static final UUID ENTITY_ID = SimpleSystemClock.INSTANCE.uniqueTimeUUIDinMicros();
    private ObjectMapper objectMapper;
    private JacksonProcessorSerializer serializer;
    private EntityProcessorCalculationRequest request;
    private ObjectNode testPayload;

    // Test entity for processing chain tests
    @SuppressWarnings({"LombokGetterMayBeUsed"}) // not here.
    static class TestEntity implements CyodaEntity {
        private Long id;
        private String name;
        private String status;

        @SuppressWarnings("unused") // Needed by Jackson
        public TestEntity() {}

        public TestEntity(Long id, String name, String status) {
            this.id = id;
            this.name = name;
            this.status = status;
        }

        public Long getId() { return id; }
        public String getName() { return name; }
        public String getStatus() { return status; }

        @Override
        public OperationSpecification getModelKey() {
            ModelSpec modelSpec = new ModelSpec();
            modelSpec.setName("test-entity");
            modelSpec.setVersion(1);
            return new OperationSpecification.Entity(modelSpec, "test-entity");
        }

        @Override
        public boolean isValid(EntityMetadata metadata) {
            return id != null && name != null && !name.trim().isEmpty();
        }
    }

    @BeforeEach
    void setUp() {
        // The protocol mapper's settings (JavaTimeModule for the OffsetDateTime event fields, RFC 3339 text
        // dates, blank processor type handler), used here for the test's own DTOs and as the entity mapper.
        objectMapper = CyodaJackson.configure(new ObjectMapper());
        serializer = new JacksonProcessorSerializer(objectMapper);

        // Create test request with real data
        request = new EntityProcessorCalculationRequest();
        request.setId("test-request-123");
        request.setRequestId("req-456");
        request.setEntityId(ENTITY_ID);
        request.setProcessorId("processor-123");
        request.setProcessorName("TestProcessor");

        // Create test payload
        testPayload = objectMapper.createObjectNode();
        testPayload.put("id", 123L);
        testPayload.put("name", "Fluffy");
        testPayload.put("status", "available");

        DataPayload payload = new DataPayload();
        payload.setData(testPayload);
        request.setPayload(payload);
    }

    @Test
    @DisplayName("ProcessingChain should initialize with extracted payload")
    void testProcessingChainInitialization() {
        // When
        ProcessorSerializer.ProcessingChain chain = serializer.withRequest(request);

        // Then
        assertNotNull(chain);
        assertInstanceOf(ProcessorSerializer.ProcessingChainImpl.class, chain);
    }

    @Test
    @DisplayName("ProcessingChain map should transform JSON payload")
    void testMapTransformation() {
        // Given
        Function<ProcessorSerializer.ProcessorExecutionContext, JsonNode> mapper = context -> {
            JsonNode payload = context.payload();
            ObjectNode result = objectMapper.createObjectNode();
            result.put("transformedId", payload.get("id").asLong() * 2);
            result.put("transformedName", "Transformed: " + payload.get("name").asText());
            return result;
        };

        // When
        EntityProcessorCalculationResponse response = serializer.withRequest(request)
                .map(mapper)
                .complete();

        // Then
        assertNotNull(response);
        assertTrue(response.getSuccess());
        assertNull(response.getError());

        JsonNode resultData = response.getPayload().getData();
        assertEquals(246L, resultData.get("transformedId").asLong());
        assertEquals("Transformed: Fluffy", resultData.get("transformedName").asText());
    }

    @Test
    @DisplayName("ProcessingChain should handle map transformation error")
    void testMapTransformationError() {
        // Given
        Function<ProcessorSerializer.ProcessorExecutionContext, JsonNode> faultyMapper = context -> {
            throw new RuntimeException("Transformation failed");
        };

        // When
        EntityProcessorCalculationResponse response = serializer.withRequest(request)
                .map(faultyMapper)
                .complete();

        // Then
        assertNotNull(response);
        assertFalse(response.getSuccess());
    }

    @Test
    @DisplayName("ProcessingChain should handle multiple chained transformations")
    void testChainedTransformations() {
        // Given
        Function<ProcessorSerializer.ProcessorExecutionContext, JsonNode> firstMapper = context -> {
            JsonNode payload = context.payload();
            ObjectNode result = objectMapper.createObjectNode();
            result.put("step1", "First transformation");
            result.put("originalId", payload.get("id").asLong());
            return result;
        };

        Function<ProcessorSerializer.ProcessorExecutionContext, JsonNode> secondMapper = context -> {
            JsonNode payload = context.payload();
            ObjectNode result = objectMapper.createObjectNode();
            result.put("step2", "Second transformation");
            result.put("previousStep", payload.get("step1").asText());
            result.put("finalId", payload.get("originalId").asLong() * 10);
            return result;
        };

        // When
        EntityProcessorCalculationResponse response = serializer.withRequest(request)
                .map(firstMapper)
                .map(secondMapper)
                .complete();

        // Then
        assertNotNull(response);
        assertTrue(response.getSuccess());

        JsonNode resultData = response.getPayload().getData();
        assertEquals("Second transformation", resultData.get("step2").asText());
        assertEquals("First transformation", resultData.get("previousStep").asText());
        assertEquals(1230L, resultData.get("finalId").asLong());
    }

    @Test
    @DisplayName("ProcessingChain should handle error in first transformation and skip subsequent ones")
    void testErrorPropagationInChain() {
        // Given
        Function<ProcessorSerializer.ProcessorExecutionContext, JsonNode> faultyMapper = context -> {
            throw new RuntimeException("First transformation failed");
        };

        Function<ProcessorSerializer.ProcessorExecutionContext, JsonNode> secondMapper = context -> {
            // This should never be called
            ObjectNode result = objectMapper.createObjectNode();
            result.put("shouldNotBeReached", true);
            return result;
        };

        // When
        EntityProcessorCalculationResponse response = serializer.withRequest(request)
                .map(faultyMapper)
                .map(secondMapper) // Should not execute
                .complete();

        // Then
        assertNotNull(response);
        assertFalse(response.getSuccess());
    }

    @Test
    @DisplayName("ProcessingChain should use custom error handler")
    void testCustomErrorHandler() {
        // Given
        BiFunction<Throwable, JsonNode, ErrorInfo> customErrorHandler =
                (error, data) -> new ErrorInfo("CUSTOM_ERROR", "Custom: " + error.getMessage());

        Function<ProcessorSerializer.ProcessorExecutionContext, JsonNode> faultyMapper = context -> {
            throw new RuntimeException("Processing failed");
        };

        // When
        EntityProcessorCalculationResponse response = serializer.withRequest(request)
                .withErrorHandler(customErrorHandler)
                .map(faultyMapper)
                .complete();

        // Then
        assertNotNull(response);
        assertFalse(response.getSuccess());
    }

    @Test
    @DisplayName("ProcessingChain toEntityResponse should extract entity and switch to entity response flow")
    void testToEntityWithMetadataTransition() {
        // When
        ProcessorSerializer.EntityProcessingChain<TestEntity> entityChain = serializer.withRequest(request)
                .toEntityWithMetadata(TestEntity.class);

        // Then
        assertNotNull(entityChain);
        assertInstanceOf(ProcessorSerializer.EntityProcessingChainImpl.class, entityChain);
    }

    @Test
    @DisplayName("EntityProcessingChain should transform entity")
    void testEntityTransformation() {
        // Given
        Function<ProcessorSerializer.ProcessorEntityResponseExecutionContext<TestEntity>, EntityWithMetadata<TestEntity>> entityMapper = context -> {
            EntityWithMetadata<TestEntity> entityResponse = context.entityResponse();
            TestEntity entity = entityResponse.entity();
            TestEntity transformedEntity = new TestEntity(entity.getId(), entity.getName().toUpperCase(), "processed");
            return new EntityWithMetadata<>(transformedEntity, entityResponse.metadata());
        };

        // When
        EntityProcessorCalculationResponse response = serializer.withRequest(request)
                .toEntityWithMetadata(TestEntity.class)
                .map(entityMapper)
                .complete();

        // Then
        assertNotNull(response);
        assertTrue(response.getSuccess());

        JsonNode resultData = response.getPayload().getData();
        assertEquals(123L, resultData.get("id").asLong());
        assertEquals("FLUFFY", resultData.get("name").asText());
        assertEquals("processed", resultData.get("status").asText());
    }

    @Test
    @DisplayName("EntityProcessingChain should handle entity validation")
    void testEntityValidation() {
        // Given
        Function<EntityWithMetadata<TestEntity>, Boolean> validator = entityResponse -> {
            TestEntity entity = entityResponse.entity();
            return entity.getId() != null && entity.getId() > 100;
        };

        Function<ProcessorSerializer.ProcessorEntityResponseExecutionContext<TestEntity>, EntityWithMetadata<TestEntity>> processor = context -> {
            EntityWithMetadata<TestEntity> entityResponse = context.entityResponse();
            TestEntity entity = entityResponse.entity();
            TestEntity transformedEntity = new TestEntity(entity.getId(), entity.getName(), "validated");
            return new EntityWithMetadata<>(transformedEntity, entityResponse.metadata());
        };

        // When
        EntityProcessorCalculationResponse response = serializer.withRequest(request)
                .toEntityWithMetadata(TestEntity.class)
                .validate(validator)
                .map(processor)
                .complete();

        // Then
        assertNotNull(response);
        assertTrue(response.getSuccess());

        JsonNode resultData = response.getPayload().getData();
        assertEquals("validated", resultData.get("status").asText());
    }

    @Test
    @DisplayName("EntityProcessingChain should handle validation failure")
    void testEntityValidationFailure() {
        // Given
        Function<EntityWithMetadata<TestEntity>, Boolean> validator = entityResponse -> {
            TestEntity entity = entityResponse.entity();
            return entity.getId() > 1000; // Will fail
        };

        // When
        EntityProcessorCalculationResponse response = serializer.withRequest(request)
                .toEntityWithMetadata(TestEntity.class)
                .validate(validator)
                .complete();

        // Then
        assertNotNull(response);
        assertFalse(response.getSuccess());
    }

    @Test
    @DisplayName("EntityProcessingChain should handle validation failure with custom message")
    void testEntityValidationFailureWithCustomMessage() {
        // Given
        Function<EntityWithMetadata<TestEntity>, Boolean> validator = entityResponse -> {
            TestEntity entity = entityResponse.entity();
            return entity.getName().length() > 10;
        };
        String customMessage = "Entity name must be longer than 10 characters";

        // When
        EntityProcessorCalculationResponse response = serializer.withRequest(request)
                .toEntityWithMetadata(TestEntity.class)
                .validate(validator, customMessage)
                .complete();

        // Then
        assertNotNull(response);
        assertFalse(response.getSuccess());
    }

    @Test
    @DisplayName("EntityProcessingChain should handle toJsonFlow transition")
    void testToJsonFlowTransition() {
        // Given
        Function<ProcessorSerializer.ProcessorEntityResponseExecutionContext<TestEntity>, EntityWithMetadata<TestEntity>> entityProcessor = context -> {
            EntityWithMetadata<TestEntity> entityResponse = context.entityResponse();
            TestEntity entity = entityResponse.entity();
            TestEntity transformedEntity = new TestEntity(entity.getId(), entity.getName().toUpperCase(), "processed");
            return new EntityWithMetadata<>(transformedEntity, entityResponse.metadata());
        };

        Function<EntityWithMetadata<TestEntity>, JsonNode> entityResponseToJson = entityResponse -> {
            TestEntity entity = entityResponse.entity();
            ObjectNode result = objectMapper.createObjectNode();
            result.put("entityId", entity.getId());
            result.put("entityName", entity.getName());
            result.put("entityStatus", entity.getStatus());
            result.put("convertedFromEntity", true);
            // Include metadata information
            if (entityResponse.metadata() != null && entityResponse.metadata().getId() != null) {
                result.put("technicalId", entityResponse.metadata().getId().toString());
            }
            return result;
        };

        Function<ProcessorSerializer.ProcessorExecutionContext, JsonNode> jsonProcessor = context -> {
            JsonNode payload = context.payload();
            ObjectNode enhanced = payload.deepCopy();
            enhanced.put("jsonProcessingStep", "completed");
            return enhanced;
        };

        // When
        EntityProcessorCalculationResponse response = serializer.withRequest(request)
                .toEntityWithMetadata(TestEntity.class)
                .map(entityProcessor)
                .toJsonFlow(entityResponseToJson)
                .map(jsonProcessor)
                .complete();

        // Then
        assertNotNull(response);
        assertTrue(response.getSuccess());

        JsonNode resultData = response.getPayload().getData();
        assertEquals(123L, resultData.get("entityId").asLong());
        assertEquals("FLUFFY", resultData.get("entityName").asText());
        assertEquals("processed", resultData.get("entityStatus").asText());
        assertTrue(resultData.get("convertedFromEntity").asBoolean());
        assertEquals("completed", resultData.get("jsonProcessingStep").asText());
    }

    @Test
    @DisplayName("EntityProcessingChain should handle complete with custom converter")
    void testEntityCompleteWithCustomConverter() {
        // Given
        Function<ProcessorSerializer.ProcessorEntityResponseExecutionContext<TestEntity>, EntityWithMetadata<TestEntity>> entityProcessor = context -> {
            EntityWithMetadata<TestEntity> entityResponse = context.entityResponse();
            TestEntity entity = entityResponse.entity();
            TestEntity transformedEntity = new TestEntity(entity.getId(), entity.getName().toUpperCase(), "processed");
            return new EntityWithMetadata<>(transformedEntity, entityResponse.metadata());
        };


        // When
        EntityProcessorCalculationResponse response = serializer.withRequest(request)
                .toEntityWithMetadata(TestEntity.class)
                .map(entityProcessor)
                .complete(entityResponse -> {
                    TestEntity entity = entityResponse.entity();
                    ObjectNode result = objectMapper.createObjectNode();
                    result.put("customId", entity.getId());
                    result.put("customName", "Custom: " + entity.getName());
                    result.put("customStatus", entity.getStatus());
                    result.put("customProcessed", true);
                    return result;
                });

        // Then
        assertNotNull(response);
        assertTrue(response.getSuccess());

        JsonNode resultData = response.getPayload().getData();
        assertEquals(123L, resultData.get("customId").asLong());
        assertEquals("Custom: FLUFFY", resultData.get("customName").asText());
        assertEquals("processed", resultData.get("customStatus").asText());
        assertTrue(resultData.get("customProcessed").asBoolean());
    }

    @Test
    @DisplayName("EntityProcessingChain should handle entity transformation error")
    void testEntityTransformationError() {
        // Given
        Function<ProcessorSerializer.ProcessorEntityResponseExecutionContext<TestEntity>, EntityWithMetadata<TestEntity>> faultyMapper = context -> {
            throw new RuntimeException("Entity transformation failed");
        };

        // When
        EntityProcessorCalculationResponse response = serializer.withRequest(request)
                .toEntityWithMetadata(TestEntity.class)
                .map(faultyMapper)
                .complete();

        // Then
        assertNotNull(response);
        assertFalse(response.getSuccess());
    }

    @Test
    @DisplayName("EntityProcessingChain should handle toJsonFlow conversion error")
    void testToJsonFlowConversionError() {
        // Given
        Function<EntityWithMetadata<TestEntity>, JsonNode> faultyConverter = entityResponse -> {
            throw new RuntimeException("JSON conversion failed");
        };

        // When
        EntityProcessorCalculationResponse response = serializer.withRequest(request)
                .toEntityWithMetadata(TestEntity.class)
                .toJsonFlow(faultyConverter)
                .complete();

        // Then
        assertNotNull(response);
        assertFalse(response.getSuccess());
    }

    @Test
    @DisplayName("EntityProcessingChain should handle custom converter error")
    void testEntityCustomConverterError() {
        // Given
        Function<EntityWithMetadata<TestEntity>, JsonNode> faultyConverter = entityResponse -> {
            throw new RuntimeException("Custom converter failed");
        };

        // When
        EntityProcessorCalculationResponse response = serializer.withRequest(request)
                .toEntityWithMetadata(TestEntity.class)
                .complete(faultyConverter);

        // Then
        assertNotNull(response);
        assertFalse(response.getSuccess());
    }

    @Test
    @DisplayName("EntityProcessingChain should use custom error handler")
    void testEntityCustomErrorHandler() {
        // Given
        BiFunction<Throwable, EntityWithMetadata<TestEntity>, ErrorInfo> customErrorHandler =
                (error, entityResponse) -> new ErrorInfo("ENTITY_ERROR",
                        "Entity error for ID " + entityResponse.entity().getId() + ": " + error.getMessage());

        Function<ProcessorSerializer.ProcessorEntityResponseExecutionContext<TestEntity>, EntityWithMetadata<TestEntity>> faultyMapper = context -> {
            throw new RuntimeException("Entity processing failed");
        };

        // When
        EntityProcessorCalculationResponse response = serializer.withRequest(request)
                .toEntityWithMetadata(TestEntity.class)
                .withErrorHandler(customErrorHandler)
                .map(faultyMapper)
                .complete();

        // Then
        assertNotNull(response);
        assertFalse(response.getSuccess());
    }

    @Test
    @DisplayName("ProcessingChain should handle payload extraction error")
    void testPayloadExtractionError() {
        // Given - Request with null payload
        EntityProcessorCalculationRequest badRequest = new EntityProcessorCalculationRequest();
        badRequest.setId("bad-request");
        badRequest.setPayload(null);

        // When
        EntityProcessorCalculationResponse response = serializer.withRequest(badRequest)
                .complete();

        // Then
        assertNotNull(response);
        assertFalse(response.getSuccess());
    }

    @Test
    @DisplayName("ProcessingChain should handle entity extraction error")
    void testEntityExtractionError() {
        // Given - Payload that cannot be converted to TestEntity
        ObjectNode badPayload = objectMapper.createObjectNode();
        badPayload.put("invalidField", "invalidValue");

        DataPayload payload = new DataPayload();
        payload.setData(badPayload);
        request.setPayload(payload);

        // When
        EntityProcessorCalculationResponse response = serializer.withRequest(request)
                .toEntityWithMetadata(TestEntity.class)
                .complete();

        // Then
        assertNotNull(response);
        assertFalse(response.getSuccess());
    }

    @Test
    @DisplayName("ProcessingChain should handle null entity after extraction")
    void testNullEntityHandling() {
        // Given - Payload that results in null entity (empty object)
        ObjectNode emptyPayload = objectMapper.createObjectNode();

        DataPayload payload = new DataPayload();
        payload.setData(emptyPayload);
        request.setPayload(payload);

        // When
        EntityProcessorCalculationResponse response = serializer.withRequest(request)
                .toEntityWithMetadata(TestEntity.class)
                .complete();

        // Then
        assertNotNull(response);
        // The response depends on how Jackson handles empty objects
        // It might succeed with a TestEntity with null fields or fail
    }

    @Test
    @DisplayName("ProcessingChain should handle executeFunction")
    void testExecuteFunction() {
        // Given
        Function<ProcessorSerializer.ProcessorExecutionContext, String> customFunction = context ->
                "Function executed with request ID " + context.request().getRequestId();

        // When
        String result = serializer.executeFunction(request, customFunction);

        // Then
        assertEquals("Function executed with request ID req-456", result);
    }

    @Test
    @DisplayName("ProcessingChain should handle executeFunction with error")
    void testExecuteFunctionWithError() {
        // Given
        Function<ProcessorSerializer.ProcessorExecutionContext, String> faultyFunction = context -> {
            throw new RuntimeException("Function execution failed");
        };

        // When & Then
        assertThrows(RuntimeException.class, () ->
                serializer.executeFunction(request, faultyFunction));
    }

    @Test
    @DisplayName("ProcessingChain should handle context access in transformations")
    void testContextAccess() {
        // Given
        Function<ProcessorSerializer.ProcessorExecutionContext, JsonNode> contextMapper = context -> {
            // Verify context provides access to request and payload
            assertNotNull(context.request());
            assertNotNull(context.payload());
            assertEquals(request, context.request());
            assertEquals(testPayload, context.payload());

            ObjectNode result = objectMapper.createObjectNode();
            result.put("requestId", context.request().getId());
            result.put("entityId", context.request().getEntityId().toString());
            result.put("originalName", context.payload().get("name").asText());
            return result;
        };

        // When
        EntityProcessorCalculationResponse response = serializer.withRequest(request)
                .map(contextMapper)
                .complete();

        // Then
        assertNotNull(response);
        assertTrue(response.getSuccess());

        JsonNode resultData = response.getPayload().getData();
        assertEquals("test-request-123", resultData.get("requestId").asText());
        assertEquals(ENTITY_ID.toString(), resultData.get("entityId").asText());
        assertEquals("Fluffy", resultData.get("originalName").asText());
    }

    @Test
    @DisplayName("EntityProcessingChain should handle context access in entity transformations")
    void testEntityContextAccess() {
        // Given
        Function<ProcessorSerializer.ProcessorEntityResponseExecutionContext<TestEntity>, EntityWithMetadata<TestEntity>> contextMapper = context -> {
            // Verify context provides access to request and entity
            assertNotNull(context.request());
            assertNotNull(context.entityResponse().entity());
            assertEquals(request, context.request());
            assertEquals(123L, context.entityResponse().entity().getId());
            assertEquals("Fluffy", context.entityResponse().entity().getName());

            EntityWithMetadata<TestEntity> entityResponse = context.entityResponse();
            TestEntity transformedEntity = new TestEntity(
                    entityResponse.entity().getId(),
                    "Processed by " + context.request().getProcessorName(),
                    "context-verified"
            );
            return new EntityWithMetadata<>(transformedEntity, entityResponse.metadata());
        };

        // When
        EntityProcessorCalculationResponse response = serializer.withRequest(request)
                .toEntityWithMetadata(TestEntity.class)
                .map(contextMapper)
                .complete();

        // Then
        assertNotNull(response);
        assertTrue(response.getSuccess());

        JsonNode resultData = response.getPayload().getData();
        assertEquals(123L, resultData.get("id").asLong());
        assertEquals("Processed by TestProcessor", resultData.get("name").asText());
        assertEquals("context-verified", resultData.get("status").asText());
    }

    @Test
    @DisplayName("ProcessingChain should handle multiple error handlers (last one wins)")
    void testMultipleErrorHandlers() {
        // Given
        BiFunction<Throwable, JsonNode, ErrorInfo> firstHandler =
                (error, data) -> new ErrorInfo("FIRST_ERROR", "First handler");

        BiFunction<Throwable, JsonNode, ErrorInfo> secondHandler =
                (error, data) -> new ErrorInfo("SECOND_ERROR", "Second handler: " + error.getMessage());

        Function<ProcessorSerializer.ProcessorExecutionContext, JsonNode> faultyMapper = context -> {
            throw new RuntimeException("Test error");
        };

        // When
        EntityProcessorCalculationResponse response = serializer.withRequest(request)
                .withErrorHandler(firstHandler)
                .withErrorHandler(secondHandler) // This should override the first one
                .map(faultyMapper)
                .complete();

        // Then
        assertNotNull(response);
        assertFalse(response.getSuccess());
    }

    // ========================================
    // EDGE CASE TESTS FOR ERROR STATE HANDLING
    // ========================================

    @Test
    @DisplayName("toEntity should skip when error already exists")
    void testToEntityWithMetadataSkipsWhenErrorExists() {
        // Given - Create a chain with an initial error
        Function<ProcessorSerializer.ProcessorExecutionContext, JsonNode> faultyMapper = context -> {
            throw new RuntimeException("Initial processing error");
        };

        // When - Try to call toEntity after error
        EntityProcessorCalculationResponse response = serializer.withRequest(request)
                .map(faultyMapper) // This creates an error
                .toEntityWithMetadata(TestEntity.class) // This should be skipped
                .complete();

        // Then
        assertNotNull(response);
        assertFalse(response.getSuccess());
    }

    @Test
    @DisplayName("validate should skip when error already exists")
    void testValidateSkipsWhenErrorExists() {
        // Given - Create an entity chain with an initial error
        Function<ProcessorSerializer.ProcessorEntityResponseExecutionContext<TestEntity>, EntityWithMetadata<TestEntity>> faultyMapper = context -> {
            throw new RuntimeException("Entity processing error");
        };

        Function<EntityWithMetadata<TestEntity>, Boolean> validator = entityResponse -> true; // Should never be called

        // When
        EntityProcessorCalculationResponse response = serializer.withRequest(request)
                .toEntityWithMetadata(TestEntity.class)
                .map(faultyMapper) // This creates an error
                .validate(validator) // This should be skipped
                .complete();

        // Then
        assertNotNull(response);
        assertFalse(response.getSuccess());
    }

    @Test
    @DisplayName("validate should handle null processedEntity")
    void testValidateWithNullProcessedEntity() {
        // Given - Create a serializer that extracts null entity
        JacksonProcessorSerializer nullEntitySerializer = new JacksonProcessorSerializer(objectMapper) {
            @Override
            public <T extends CyodaEntity> EntityWithMetadata<T> extractEntityWithMetadata(EntityProcessorCalculationRequest request, Class<T> clazz) {
                return null; // Return null entity wrapper
            }
        };

        Function<EntityWithMetadata<TestEntity>, Boolean> validator = entityResponse -> true; // Should never be called with null

        // When
        EntityProcessorCalculationResponse response = nullEntitySerializer.withRequest(request)
                .toEntityWithMetadata(TestEntity.class)
                .validate(validator)
                .complete();

        // Then
        assertNotNull(response);
        assertFalse(response.getSuccess());
    }

    @Test
    @DisplayName("validate with custom message should handle null processedEntity")
    void testValidateWithCustomMessageAndNullProcessedEntity() {
        // Given - Create a serializer that extracts null entity
        JacksonProcessorSerializer nullEntitySerializer = new JacksonProcessorSerializer(objectMapper) {
            @Override
            public <T extends CyodaEntity> EntityWithMetadata<T> extractEntityWithMetadata(EntityProcessorCalculationRequest request, Class<T> clazz) {
                return null; // Return null entity wrapper
            }
        };

        Function<EntityWithMetadata<TestEntity>, Boolean> validator = entityResponse -> true;
        String customMessage = "Custom validation message";

        // When
        EntityProcessorCalculationResponse response = nullEntitySerializer.withRequest(request)
                .toEntityWithMetadata(TestEntity.class)
                .validate(validator, customMessage)
                .complete();

        // Then
        assertNotNull(response);
        assertFalse(response.getSuccess());
    }

    @Test
    @DisplayName("toJsonFlow should skip when error already exists")
    void testToJsonFlowSkipsWhenErrorExists() {
        // Given - Create an entity chain with an initial error
        Function<ProcessorSerializer.ProcessorEntityResponseExecutionContext<TestEntity>, EntityWithMetadata<TestEntity>> faultyMapper = context -> {
            throw new RuntimeException("Entity processing error");
        };

        Function<EntityWithMetadata<TestEntity>, JsonNode> converter = entityResponse -> {
            // This should never be called
            ObjectNode result = objectMapper.createObjectNode();
            result.put("shouldNotBeReached", true);
            return result;
        };

        // When
        EntityProcessorCalculationResponse response = serializer.withRequest(request)
                .toEntityWithMetadata(TestEntity.class)
                .map(faultyMapper) // This creates an error
                .toJsonFlow(converter) // This should be skipped
                .complete();

        // Then
        assertNotNull(response);
        assertFalse(response.getSuccess());
    }

    @Test
    @DisplayName("toJsonFlow should skip conversion when processedEntity is null")
    void testToJsonFlowWithNullProcessedEntity() {
        // Given - Create a serializer that extracts null entity
        JacksonProcessorSerializer nullEntitySerializer = new JacksonProcessorSerializer(objectMapper) {
            @Override
            public <T extends CyodaEntity> EntityWithMetadata<T> extractEntityWithMetadata(EntityProcessorCalculationRequest request, Class<T> clazz) {
                return null; // Return null entity wrapper
            }
        };

        Function<EntityWithMetadata<TestEntity>, JsonNode> converter = entityResponse -> {
            // This should never be called with null
            ObjectNode result = objectMapper.createObjectNode();
            result.put("converted", true);
            return result;
        };

        // When
        EntityProcessorCalculationResponse response = nullEntitySerializer.withRequest(request)
                .toEntityWithMetadata(TestEntity.class)
                .toJsonFlow(converter)
                .complete();

        // Then - toJsonFlow should fail when entity wrapper is null
        assertNotNull(response);
        assertFalse(response.getSuccess()); // Failure because null entity wrapper is an error
    }

    @Test
    @DisplayName("complete should handle null processedEntity in EntityProcessingChain")
    void testEntityCompleteWithNullProcessedEntity() {
        // Given - Create a serializer that extracts null entity
        JacksonProcessorSerializer nullEntitySerializer = new JacksonProcessorSerializer(objectMapper) {
            @Override
            public <T extends CyodaEntity> EntityWithMetadata<T> extractEntityWithMetadata(EntityProcessorCalculationRequest request, Class<T> clazz) {
                return null; // Return null entity wrapper
            }
        };

        // When
        EntityProcessorCalculationResponse response = nullEntitySerializer.withRequest(request)
                .toEntityWithMetadata(TestEntity.class)
                .complete();

        // Then
        assertNotNull(response);
        assertFalse(response.getSuccess());
    }

    @Test
    @DisplayName("complete should handle entityToJsonNode conversion exception")
    void testEntityCompleteWithEntityToJsonNodeException() {
        // Given - Create a serializer that throws during entity conversion
        JacksonProcessorSerializer faultySerializer = new JacksonProcessorSerializer(objectMapper) {
            @Override
            public <T extends CyodaEntity> JsonNode entityToJsonNode(T entity) {
                throw new RuntimeException("Entity to JSON conversion failed");
            }
        };

        // When
        EntityProcessorCalculationResponse response = faultySerializer.withRequest(request)
                .toEntityWithMetadata(TestEntity.class)
                .complete();

        // Then
        assertNotNull(response);
        assertFalse(response.getSuccess());
    }

    // ========================================
    // EDGE CASES FOR complete(Function<T, JsonNode> converter)
    // ========================================

    @Test
    @DisplayName("complete with converter should skip when error already exists")
    void testCompleteWithConverterSkipsWhenErrorExists() {
        // Given - Create an entity chain with an initial error
        Function<ProcessorSerializer.ProcessorEntityResponseExecutionContext<TestEntity>, EntityWithMetadata<TestEntity>> faultyMapper = context -> {
            throw new RuntimeException("Entity processing error");
        };

        Function<EntityWithMetadata<TestEntity>, JsonNode> converter = entityResponse -> {
            // This should never be called
            ObjectNode result = objectMapper.createObjectNode();
            result.put("shouldNotBeReached", true);
            return result;
        };

        // When
        EntityProcessorCalculationResponse response = serializer.withRequest(request)
                .toEntityWithMetadata(TestEntity.class)
                .map(faultyMapper) // This creates an error
                .complete(converter); // This should be skipped

        // Then
        assertNotNull(response);
        assertFalse(response.getSuccess());
    }

    @Test
    @DisplayName("complete with converter should handle null processedEntity")
    void testCompleteWithConverterAndNullProcessedEntity() {
        // Given - Create a serializer that extracts null entity
        JacksonProcessorSerializer nullEntitySerializer = new JacksonProcessorSerializer(objectMapper) {
            @Override
            public <T extends CyodaEntity> EntityWithMetadata<T> extractEntityWithMetadata(EntityProcessorCalculationRequest request, Class<T> clazz) {
                return null; // Return null entity wrapper
            }
        };

        Function<EntityWithMetadata<TestEntity>, JsonNode> converter = entityResponse -> {
            // This should never be called with null
            ObjectNode result = objectMapper.createObjectNode();
            result.put("converted", true);
            return result;
        };

        // When
        EntityProcessorCalculationResponse response = nullEntitySerializer.withRequest(request)
                .toEntityWithMetadata(TestEntity.class)
                .complete(converter);

        // Then
        assertNotNull(response);
        assertFalse(response.getSuccess());
    }

    @Test
    @DisplayName("complete with converter should handle converter exception")
    void testCompleteWithConverterException() {
        // Given
        Function<EntityWithMetadata<TestEntity>, JsonNode> faultyConverter = entityResponse -> {
            throw new RuntimeException("Converter failed");
        };

        // When
        EntityProcessorCalculationResponse response = serializer.withRequest(request)
                .toEntityWithMetadata(TestEntity.class)
                .complete(faultyConverter);

        // Then
        assertNotNull(response);
        assertFalse(response.getSuccess());
    }

    @Test
    @DisplayName("complete with converter should handle converter exception with custom error handler")
    void testCompleteWithConverterExceptionAndCustomErrorHandler() {
        // Given
        Function<EntityWithMetadata<TestEntity>, JsonNode> faultyConverter = entityResponse -> {
            throw new IllegalArgumentException("Invalid entity for conversion");
        };

        BiFunction<Throwable, EntityWithMetadata<TestEntity>, ErrorInfo> customErrorHandler =
                (error, entityResponse) -> new ErrorInfo("CUSTOM_CONVERSION_ERROR",
                        "Failed to convert entity " + entityResponse.entity().getId() + ": " + error.getMessage());

        // When
        EntityProcessorCalculationResponse response = serializer.withRequest(request)
                .toEntityWithMetadata(TestEntity.class)
                .withErrorHandler(customErrorHandler)
                .complete(faultyConverter);

        // Then
        assertNotNull(response);
        assertFalse(response.getSuccess());
    }

    @Test
    @DisplayName("complete with converter should handle null converter")
    void testCompleteWithNullConverter() {
        // Given
        Function<EntityWithMetadata<TestEntity>, JsonNode> nullConverter = null;

        // When
        EntityProcessorCalculationResponse response = serializer.withRequest(request)
                .toEntityWithMetadata(TestEntity.class)
                .complete(nullConverter);

        // Then
        assertNotNull(response);
        assertFalse(response.getSuccess());
    }

    // ========================================
    // ADDITIONAL ERROR PROPAGATION TESTS
    // ========================================

    @Test
    @DisplayName("extractEntity should properly extract metadata from payload")
    void testExtractEntityWithMetadata() {
        // Given - Create request with metadata in payload
        EntityProcessorCalculationRequest requestWithMeta = new EntityProcessorCalculationRequest();
        requestWithMeta.setId("test-request-123");
        UUID entityId = SimpleSystemClock.INSTANCE.uniqueTimeUUIDinMicros();
        requestWithMeta.setEntityId(entityId);

        // Create test payload with both data and metadata
        ObjectNode testData = objectMapper.createObjectNode();
        testData.put("id", 123L);
        testData.put("name", "Fluffy");
        testData.put("status", "available");

        ObjectNode testMeta = objectMapper.createObjectNode();
        testMeta.put("id", entityId.toString());
        testMeta.put("state", "ACTIVE");
        testMeta.put("creationDate", "2023-01-01T00:00:00Z");

        DataPayload payload = new DataPayload();
        payload.setData(testData);
        payload.setMeta(testMeta);
        requestWithMeta.setPayload(payload);

        // When
        EntityWithMetadata<TestEntity> entityWithMetadata = serializer.extractEntityWithMetadata(requestWithMeta, TestEntity.class);

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
        assertEquals(entityId, entityWithMetadata.metadata().getId());
        assertEquals("ACTIVE", entityWithMetadata.metadata().getState());
        assertNotNull(entityWithMetadata.metadata().getCreationDate());
    }

    @Test
    @DisplayName("extractEntity should handle payload without metadata")
    void testExtractEntityWithoutMetadata() {
        // Given - Create request without metadata (current test setup)
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

        // Metadata should be empty/null since no meta was provided
        assertNull(entityWithMetadata.metadata().getId());
        assertNull(entityWithMetadata.metadata().getState());
    }

    @Test
    @DisplayName("Error should propagate through multiple entity operations")
    void testErrorPropagationThroughMultipleEntityOperations() {
        // Given - Create an initial error in entity extraction
        JacksonProcessorSerializer faultySerializer = new JacksonProcessorSerializer(objectMapper) {
            @Override
            public <T extends CyodaEntity> EntityWithMetadata<T> extractEntityWithMetadata(EntityProcessorCalculationRequest request, Class<T> clazz) {
                throw new RuntimeException("Entity extraction failed");
            }
        };

        Function<ProcessorSerializer.ProcessorEntityResponseExecutionContext<TestEntity>, EntityWithMetadata<TestEntity>> mapper1 = context -> {
            // Should never be called
            EntityWithMetadata<TestEntity> entityResponse = context.entityResponse();
            TestEntity transformedEntity = new TestEntity(999L, "mapped1", "error");
            return new EntityWithMetadata<>(transformedEntity, entityResponse.metadata());
        };

        Function<EntityWithMetadata<TestEntity>, Boolean> validator = entityResponse -> {
            // Should never be called
            return true;
        };

        Function<ProcessorSerializer.ProcessorEntityResponseExecutionContext<TestEntity>, EntityWithMetadata<TestEntity>> mapper2 = context -> {
            // Should never be called
            EntityWithMetadata<TestEntity> entityResponse = context.entityResponse();
            TestEntity transformedEntity = new TestEntity(999L, "mapped2", "error");
            return new EntityWithMetadata<>(transformedEntity, entityResponse.metadata());
        };

        Function<EntityWithMetadata<TestEntity>, JsonNode> converter = entityResponse -> {
            // Should never be called
            return objectMapper.createObjectNode();
        };

        // When
        EntityProcessorCalculationResponse response = faultySerializer.withRequest(request)
                .toEntityWithMetadata(TestEntity.class) // Error occurs here
                .map(mapper1) // Should be skipped
                .validate(validator) // Should be skipped
                .map(mapper2) // Should be skipped
                .toJsonFlow(converter) // Should be skipped
                .complete();

        // Then
        assertNotNull(response);
        assertFalse(response.getSuccess());
    }

    @Test
    @DisplayName("Error should propagate through mixed JSON and entity operations")
    void testErrorPropagationThroughMixedOperations() {
        // Given - Create an initial error in JSON processing
        Function<ProcessorSerializer.ProcessorExecutionContext, JsonNode> faultyJsonMapper = context -> {
            throw new RuntimeException("JSON processing failed");
        };

        Function<ProcessorSerializer.ProcessorEntityResponseExecutionContext<TestEntity>, EntityWithMetadata<TestEntity>> entityMapper = context -> {
            // Should never be called
            EntityWithMetadata<TestEntity> entityResponse = context.entityResponse();
            TestEntity transformedEntity = new TestEntity(999L, "mapped", "error");
            return new EntityWithMetadata<>(transformedEntity, entityResponse.metadata());
        };

        // When
        EntityProcessorCalculationResponse response = serializer.withRequest(request)
                .map(faultyJsonMapper) // Error occurs here
                .toEntityWithMetadata(TestEntity.class) // Should be skipped
                .map(entityMapper) // Should be skipped
                .complete();

        // Then
        assertNotNull(response);
        assertFalse(response.getSuccess());
    }

    @Test
    @DisplayName("withErrorHandler should work correctly when called after error occurs")
    void testWithErrorHandlerAfterError() {
        // Given - Create an initial error
        Function<ProcessorSerializer.ProcessorEntityResponseExecutionContext<TestEntity>, EntityWithMetadata<TestEntity>> faultyMapper = context -> {
            throw new RuntimeException("Processing failed");
        };

        BiFunction<Throwable, EntityWithMetadata<TestEntity>, ErrorInfo> customErrorHandler =
                (error, entityResponse) -> new ErrorInfo("LATE_ERROR_HANDLER", "Late handler: " + error.getMessage());

        // When
        EntityProcessorCalculationResponse response = serializer.withRequest(request)
                .toEntityWithMetadata(TestEntity.class)
                .map(faultyMapper) // Error occurs here
                .withErrorHandler(customErrorHandler) // Set error handler after error
                .complete();

        // Then
        assertNotNull(response);
        assertFalse(response.getSuccess());
    }

    // ========================================
    // COVERAGE TESTS FOR SPECIFIC LINES
    // ========================================

    @Test
    @DisplayName("EntityProcessingChain complete should use custom error handler when entityToJsonNode fails")
    void testEntityCompleteWithEntityToJsonNodeExceptionAndCustomErrorHandler() {
        // Given - Create a serializer that throws during entityToJsonNode conversion
        JacksonProcessorSerializer faultySerializer = new JacksonProcessorSerializer(objectMapper) {
            @Override
            public <T extends CyodaEntity> JsonNode entityToJsonNode(T entity) {
                throw new RuntimeException("Entity to JSON conversion failed");
            }
        };

        BiFunction<Throwable, EntityWithMetadata<TestEntity>, ErrorInfo> customErrorHandler =
                (error, entityResponse) -> new ErrorInfo("CUSTOM_ENTITY_CONVERSION_ERROR",
                        "Custom handler: Failed to convert entity " + entityResponse.entity().getId() + " - " + error.getMessage());

        // When
        EntityProcessorCalculationResponse response = faultySerializer.withRequest(request)
                .toEntityWithMetadata(TestEntity.class)
                .withErrorHandler(customErrorHandler)
                .complete(); // This should trigger line 359

        // Then
        assertNotNull(response);
        assertFalse(response.getSuccess());
    }

    @Test
    @DisplayName("EntityProcessingChain complete with converter should use custom error handler when error exists")
    void testCompleteWithConverterAndExistingErrorWithCustomErrorHandler() {
        // Given - Create an entity chain with an initial error and custom error handler
        Function<ProcessorSerializer.ProcessorEntityResponseExecutionContext<TestEntity>, EntityWithMetadata<TestEntity>> faultyMapper = context -> {
            throw new RuntimeException("Initial entity processing error");
        };

        BiFunction<Throwable, EntityWithMetadata<TestEntity>, ErrorInfo> customErrorHandler =
                (error, entityResponse) -> new ErrorInfo("CUSTOM_EXISTING_ERROR",
                        "Custom handler for existing error: " + error.getMessage());

        Function<EntityWithMetadata<TestEntity>, JsonNode> converter = entityResponse -> {
            // This should never be called due to existing error
            ObjectNode result = objectMapper.createObjectNode();
            result.put("shouldNotBeReached", true);
            return result;
        };

        // When
        EntityProcessorCalculationResponse response = serializer.withRequest(request)
                .toEntityWithMetadata(TestEntity.class)
                .map(faultyMapper) // This creates an error
                .withErrorHandler(customErrorHandler)
                .complete(converter); // This should trigger line 375

        // Then
        assertNotNull(response);
        assertFalse(response.getSuccess());
    }
}
