package com.java_template.common.serializer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.java_template.common.config.CyodaJackson;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.java_template.common.serializer.jackson.JacksonCriterionSerializer;
import com.java_template.common.workflow.CyodaEntity;
import com.java_template.common.workflow.OperationSpecification;
import org.cyoda.cloud.api.event.common.DataPayload;
import org.cyoda.cloud.api.event.common.EntityMetadata;
import org.cyoda.cloud.api.event.common.ModelSpec;
import org.cyoda.cloud.api.event.processing.EntityCriteriaCalculationRequest;
import org.cyoda.cloud.api.event.processing.EntityCriteriaCalculationResponse;
import org.cyoda.uuid.SimpleSystemClock;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.function.BiFunction;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ABOUTME: Unit tests for EvaluationChain testing the actual assembly and logic
 * without mocks, using real JacksonCriterionSerializer to test the complete chain behavior.
 */
class EvaluationChainTest {

    public static final @NotNull UUID ENTITY_ID = SimpleSystemClock.INSTANCE.uniqueTimeUUIDinMicros();
    private ObjectMapper objectMapper;
    private JacksonCriterionSerializer serializer;
    private EntityCriteriaCalculationRequest request;
    private ObjectNode testPayload;

    // Test entity for evaluation chain tests
    @SuppressWarnings({"LombokGetterMayBeUsed", "LombokSetterMayBeUsed", "unused"}) // Not here
    static class TestEntity implements CyodaEntity {
        private Long id;
        private String name;
        private String status;
        private String category;

        @SuppressWarnings("unused") // Needed by Jackson
        public TestEntity() {}

        public Long getId() { return id; }
        public void setId(Long id) { this.id = id; }
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public String getStatus() { return status; }
        public void setStatus(String status) { this.status = status; }
        public String getCategory() { return category; }
        public void setCategory(String category) { this.category = category; }

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
        objectMapper = CyodaJackson.configure(new ObjectMapper());
        serializer = new JacksonCriterionSerializer(objectMapper);

        // Create test request with real data
        request = new EntityCriteriaCalculationRequest();
        request.setId("test-criteria-123");
        request.setRequestId("req-456");
        request.setEntityId(ENTITY_ID);
        request.setCriteriaId("criterion-123");
        request.setCriteriaName("TestCriterion");

        // Create test payload
        testPayload = objectMapper.createObjectNode();
        testPayload.put("id", 123L);
        testPayload.put("name", "Fluffy");
        testPayload.put("status", "available");
        testPayload.put("category", "pet");

        DataPayload payload = new DataPayload();
        payload.setData(testPayload);
        request.setPayload(payload);
    }

    @Test
    @DisplayName("EvaluationChain should initialize with extracted payload")
    void testEvaluationChainInitialization() {
        // When
        CriterionSerializer.EvaluationChain chain = serializer.withRequest(request);

        // Then
        assertNotNull(chain);
        assertInstanceOf(CriterionSerializer.EvaluationChainImpl.class, chain);
    }

    @Test
    @DisplayName("EvaluationChain evaluate should handle successful evaluation")
    void testEvaluateSuccess() {
        // Given
        Function<CriterionSerializer.CriterionEvaluationContext, EvaluationOutcome> evaluator = context -> {
            JsonNode payload = context.payload();
            assertEquals(testPayload, payload);
            assertEquals(request, context.request());

            // Check if pet is available
            return payload.get("status").asText().equals("available")
                    ? EvaluationOutcome.success()
                    : EvaluationOutcome.Fail.businessRuleFailure("Pet not available");
        };

        // When
        EntityCriteriaCalculationResponse response = serializer.withRequest(request)
                .evaluate(evaluator)
                .complete();

        // Then
        assertNotNull(response);
        assertTrue(response.getSuccess());
        assertTrue(response.getMatches());
    }

    @Test
    @DisplayName("EvaluationChain evaluate should handle failed evaluation")
    void testEvaluateFailure() {
        // Given
        Function<CriterionSerializer.CriterionEvaluationContext, EvaluationOutcome> evaluator = context -> {
            JsonNode payload = context.payload();

            // Check if pet is a dog (it's not)
            return payload.get("category").asText().equals("dog")
                    ? EvaluationOutcome.success()
                    : EvaluationOutcome.Fail.businessRuleFailure("Not a dog");
        };

        // When
        EntityCriteriaCalculationResponse response = serializer.withRequest(request)
                .evaluate(evaluator)
                .complete();

        // Then
        assertNotNull(response);
        assertTrue(response.getSuccess());
        assertFalse(response.getMatches());
    }

    @Test
    @DisplayName("EvaluationChain should handle evaluation exception")
    void testEvaluateException() {
        // Given
        Function<CriterionSerializer.CriterionEvaluationContext, EvaluationOutcome> faultyEvaluator = context -> {
            throw new RuntimeException("Evaluation failed");
        };

        // When
        EntityCriteriaCalculationResponse response = serializer.withRequest(request)
                .evaluate(faultyEvaluator)
                .complete();

        // Then
        assertNotNull(response);
        assertFalse(response.getSuccess());
        assertFalse(response.getMatches());
    }

    @Test
    @DisplayName("EvaluationChain should use custom error handler")
    void testCustomErrorHandler() {
        // Given
        BiFunction<Throwable, JsonNode, ErrorInfo> customErrorHandler =
                (error, payload) -> new ErrorInfo("CUSTOM_EVAL_ERROR", "Custom: " + error.getMessage());

        Function<CriterionSerializer.CriterionEvaluationContext, EvaluationOutcome> faultyEvaluator = context -> {
            throw new RuntimeException("Evaluation failed");
        };

        // When
        EntityCriteriaCalculationResponse response = serializer.withRequest(request)
                .withErrorHandler(customErrorHandler)
                .evaluate(faultyEvaluator)
                .complete();

        // Then
        assertNotNull(response);
        assertFalse(response.getSuccess());
        assertFalse(response.getMatches());
    }

    @Test
    @DisplayName("EvaluationChain evaluateEntity should extract entity and evaluate")
    void testEvaluateEntity() {
        // Given
        Function<CriterionSerializer.CriterionEntityEvaluationContext<TestEntity>, EvaluationOutcome> entityEvaluator = context -> {
            TestEntity entity = context.entityWithMetadata().entity();
            assertEquals(request, context.request());
            assertEquals(123L, entity.getId());
            assertEquals("Fluffy", entity.getName());

            // Check if entity is valid and available
            return entity.isValid(context.entityWithMetadata().metadata()) && "available".equals(entity.getStatus())
                    ? EvaluationOutcome.success()
                    : EvaluationOutcome.Fail.structuralFailure("Entity validation failed");
        };

        // When
        EntityCriteriaCalculationResponse response = serializer.withRequest(request)
                .evaluateEntity(TestEntity.class, entityEvaluator)
                .complete();

        // Then
        assertNotNull(response);
        assertTrue(response.getSuccess());
        assertTrue(response.getMatches());
    }

    @Test
    @DisplayName("EvaluationChain should handle entity extraction failure")
    void testEvaluateEntityExtractionFailure() {
        // Given - Payload that cannot be converted to TestEntity
        ObjectNode badPayload = objectMapper.createObjectNode();
        badPayload.put("invalidField", "invalidValue");

        DataPayload payload = new DataPayload();
        payload.setData(badPayload);
        request.setPayload(payload);

        Function<CriterionSerializer.CriterionEntityEvaluationContext<TestEntity>, EvaluationOutcome> entityEvaluator = context -> {
            // Should never be called
            return EvaluationOutcome.success();
        };

        // When
        EntityCriteriaCalculationResponse response = serializer.withRequest(request)
                .evaluateEntity(TestEntity.class, entityEvaluator)
                .complete();

        // Then
        assertNotNull(response);
        assertFalse(response.getSuccess());
        assertFalse(response.getMatches());
    }

    @Test
    @DisplayName("EvaluationChain should handle payload extraction error")
    void testPayloadExtractionError() {
        // Given - Request with null payload
        EntityCriteriaCalculationRequest badRequest = new EntityCriteriaCalculationRequest();
        badRequest.setId("bad-request");
        badRequest.setPayload(null);

        // When
        EntityCriteriaCalculationResponse response = serializer.withRequest(badRequest)
                .complete();

        // Then
        assertNotNull(response);
        assertFalse(response.getSuccess());
        assertFalse(response.getMatches());
    }

    @Test
    @DisplayName("EvaluationChain should handle no evaluation performed")
    void testNoEvaluationPerformed() {
        // When - Complete without calling evaluate
        EntityCriteriaCalculationResponse response = serializer.withRequest(request)
                .complete();

        // Then
        assertNotNull(response);
        assertFalse(response.getSuccess());
        assertFalse(response.getMatches());
    }

    @Test
    @DisplayName("EvaluationChain should handle fluent chaining")
    void testFluentChaining() {
        // Given
        BiFunction<Throwable, JsonNode, ErrorInfo> customErrorHandler =
                (error, payload) -> new ErrorInfo("CHAIN_ERROR", "Chained error");

        ReasonAttachmentStrategy customStrategy = ReasonAttachmentStrategy.none();

        Function<CriterionSerializer.CriterionEvaluationContext, EvaluationOutcome> evaluator =
                context -> EvaluationOutcome.success();

        // When
        EntityCriteriaCalculationResponse response = serializer.withRequest(request)
                .withErrorHandler(customErrorHandler)
                .withReasonAttachment(customStrategy)
                .evaluate(evaluator)
                .complete();

        // Then
        assertNotNull(response);
        assertTrue(response.getSuccess());
        assertTrue(response.getMatches());
    }

    @Test
    @DisplayName("EvaluationChain should skip second evaluation if first succeeds")
    void testSkipSecondEvaluation() {
        // Given
        Function<CriterionSerializer.CriterionEvaluationContext, EvaluationOutcome> firstEvaluator =
                context -> EvaluationOutcome.success();

        Function<CriterionSerializer.CriterionEvaluationContext, EvaluationOutcome> secondEvaluator = context -> {
            // This should never be called
            fail("Second evaluator should not be called");
            return EvaluationOutcome.Fail.businessRuleFailure("Should not reach here");
        };

        // When
        EntityCriteriaCalculationResponse response = serializer.withRequest(request)
                .evaluate(firstEvaluator)
                .evaluate(secondEvaluator) // Should be skipped
                .complete();

        // Then
        assertNotNull(response);
        assertTrue(response.getSuccess());
        assertTrue(response.getMatches());
    }

    @Test
    @DisplayName("EvaluationChain should skip evaluation if error already exists")
    void testSkipEvaluationWhenErrorExists() {
        // Given - Request with null payload to create initial error
        EntityCriteriaCalculationRequest badRequest = new EntityCriteriaCalculationRequest();
        badRequest.setId("bad-request");
        badRequest.setPayload(null);

        Function<CriterionSerializer.CriterionEvaluationContext, EvaluationOutcome> evaluator = context -> {
            // This should never be called
            fail("Evaluator should not be called when error exists");
            return EvaluationOutcome.success();
        };

        // When
        EntityCriteriaCalculationResponse response = serializer.withRequest(badRequest)
                .evaluate(evaluator) // Should be skipped due to initial error
                .complete();

        // Then
        assertNotNull(response);
        assertFalse(response.getSuccess());
        assertFalse(response.getMatches());
    }

    @Test
    @DisplayName("EvaluationChain should handle mixed evaluation types")
    void testMixedEvaluationTypes() {
        // Given
        Function<CriterionSerializer.CriterionEvaluationContext, EvaluationOutcome> jsonEvaluator =
                context -> EvaluationOutcome.success();

        Function<CriterionSerializer.CriterionEntityEvaluationContext<TestEntity>, EvaluationOutcome> entityEvaluator = context -> {
            // This should never be called since JSON evaluation already succeeded
            fail("Entity evaluator should not be called after JSON evaluation succeeds");
            return EvaluationOutcome.success();
        };

        // When
        EntityCriteriaCalculationResponse response = serializer.withRequest(request)
                .evaluate(jsonEvaluator)
                .evaluateEntity(TestEntity.class, entityEvaluator) // Should be skipped
                .complete();

        // Then
        assertNotNull(response);
        assertTrue(response.getSuccess());
        assertTrue(response.getMatches());
    }

    @Test
    @DisplayName("EvaluationChain should handle context access in evaluations")
    void testContextAccess() {
        // Given
        Function<CriterionSerializer.CriterionEvaluationContext, EvaluationOutcome> contextEvaluator = context -> {
            // Verify context provides access to request and payload
            assertNotNull(context.request());
            assertNotNull(context.payload());
            assertEquals(request, context.request());
            assertEquals(testPayload, context.payload());

            // Access request metadata
            assertEquals("test-criteria-123", context.request().getId());
            assertEquals(ENTITY_ID, context.request().getEntityId());
            assertEquals("TestCriterion", context.request().getCriteriaName());

            // Access payload data
            assertEquals(123L, context.payload().get("id").asLong());
            assertEquals("Fluffy", context.payload().get("name").asText());

            return EvaluationOutcome.success();
        };

        // When
        EntityCriteriaCalculationResponse response = serializer.withRequest(request)
                .evaluate(contextEvaluator)
                .complete();

        // Then
        assertNotNull(response);
        assertTrue(response.getSuccess());
        assertTrue(response.getMatches());
    }

    @Test
    @DisplayName("EvaluationChain should handle entity context access")
    void testEntityContextAccess() {
        // Given
        Function<CriterionSerializer.CriterionEntityEvaluationContext<TestEntity>, EvaluationOutcome> entityContextEvaluator = context -> {
            // Verify context provides access to request and entity
            assertNotNull(context.request());
            assertNotNull(context.entityWithMetadata());
            assertEquals(request, context.request());

            // Access request metadata
            assertEquals("test-criteria-123", context.request().getId());
            assertEquals("TestCriterion", context.request().getCriteriaName());

            // Access entity data
            TestEntity entity = context.entityWithMetadata().entity();
            assertEquals(123L, entity.getId());
            assertEquals("Fluffy", entity.getName());
            assertEquals("available", entity.getStatus());
            assertEquals("pet", entity.getCategory());

            return EvaluationOutcome.success();
        };

        // When
        EntityCriteriaCalculationResponse response = serializer.withRequest(request)
                .evaluateEntity(TestEntity.class, entityContextEvaluator)
                .complete();

        // Then
        assertNotNull(response);
        assertTrue(response.getSuccess());
        assertTrue(response.getMatches());
    }

    @Test
    @DisplayName("EvaluationChain should handle multiple error handlers")
    void testMultipleErrorHandlers() {
        // Given
        BiFunction<Throwable, JsonNode, ErrorInfo> firstHandler =
                (error, payload) -> new ErrorInfo("FIRST_ERROR", "First handler");

        BiFunction<Throwable, JsonNode, ErrorInfo> secondHandler =
                (error, payload) -> new ErrorInfo("SECOND_ERROR", "Second handler: " + error.getMessage());

        Function<CriterionSerializer.CriterionEvaluationContext, EvaluationOutcome> faultyEvaluator = context -> {
            throw new RuntimeException("Test error");
        };

        // When
        EntityCriteriaCalculationResponse response = serializer.withRequest(request)
                .withErrorHandler(firstHandler)
                .withErrorHandler(secondHandler) // This should override the first one
                .evaluate(faultyEvaluator)
                .complete();

        // Then
        assertNotNull(response);
        assertFalse(response.getSuccess());
        assertFalse(response.getMatches());
    }

    // ========================================
    // EDGE CASE TESTS
    // ========================================

    @Test
    @DisplayName("EvaluationChain should handle null error handler")
    void testNullErrorHandler() {
        // Given
        Function<CriterionSerializer.CriterionEvaluationContext, EvaluationOutcome> faultyEvaluator = context -> {
            throw new RuntimeException("Test error");
        };

        // When
        EntityCriteriaCalculationResponse response = serializer.withRequest(request)
                .withErrorHandler(null)
                .evaluate(faultyEvaluator)
                .complete();

        // Then
        assertNotNull(response);
        assertFalse(response.getSuccess());
        assertFalse(response.getMatches());
    }

    @Test
    @DisplayName("EvaluationChain should handle null reason attachment strategy")
    void testNullReasonAttachmentStrategy() {
        // Given
        Function<CriterionSerializer.CriterionEvaluationContext, EvaluationOutcome> evaluator =
                context -> EvaluationOutcome.Fail.businessRuleFailure("Test failure");

        // When
        EntityCriteriaCalculationResponse response = serializer.withRequest(request)
                .withReasonAttachment(null)
                .evaluate(evaluator)
                .complete();

        // Then
        assertNotNull(response);
        assertTrue(response.getSuccess());
        assertFalse(response.getMatches());
    }

    @Test
    @DisplayName("EvaluationChain should handle evaluation with different failure types")
    void testDifferentFailureTypes() {
        // Test validation failure
        Function<CriterionSerializer.CriterionEvaluationContext, EvaluationOutcome> validationEvaluator =
                context -> EvaluationOutcome.Fail.structuralFailure("Validation failed");

        EntityCriteriaCalculationResponse response1 = serializer.withRequest(request)
                .evaluate(validationEvaluator)
                .complete();

        assertNotNull(response1);
        assertTrue(response1.getSuccess());
        assertFalse(response1.getMatches());

        // Test business rule failure
        Function<CriterionSerializer.CriterionEvaluationContext, EvaluationOutcome> businessRuleEvaluator =
                context -> EvaluationOutcome.Fail.businessRuleFailure("Business rule failed");

        EntityCriteriaCalculationResponse response2 = serializer.withRequest(request)
                .evaluate(businessRuleEvaluator)
                .complete();

        assertNotNull(response2);
        assertTrue(response2.getSuccess());
        assertFalse(response2.getMatches());

        // Test data quality failure
        Function<CriterionSerializer.CriterionEvaluationContext, EvaluationOutcome> dataQualityEvaluator =
                context -> EvaluationOutcome.Fail.dataQualityFailure("Data quality failed");

        EntityCriteriaCalculationResponse response3 = serializer.withRequest(request)
                .evaluate(dataQualityEvaluator)
                .complete();

        assertNotNull(response3);
        assertTrue(response3.getSuccess());
        assertFalse(response3.getMatches());

        // Test structural failure
        Function<CriterionSerializer.CriterionEvaluationContext, EvaluationOutcome> structuralEvaluator =
                context -> EvaluationOutcome.Fail.structuralFailure("Structural failure");

        EntityCriteriaCalculationResponse response4 = serializer.withRequest(request)
                .evaluate(structuralEvaluator)
                .complete();

        assertNotNull(response4);
        assertTrue(response4.getSuccess());
        assertFalse(response4.getMatches());
    }

    @Test
    @DisplayName("EvaluationChain should handle reason attachment strategies")
    void testReasonAttachmentStrategies() {
        Function<CriterionSerializer.CriterionEvaluationContext, EvaluationOutcome> evaluator =
                context -> EvaluationOutcome.Fail.businessRuleFailure("Business rule failed");

        // Test with toReason strategy (default)
        EntityCriteriaCalculationResponse response1 = serializer.withRequest(request)
                .withReasonAttachment(ReasonAttachmentStrategy.toReason())
                .evaluate(evaluator)
                .complete();

        assertNotNull(response1);
        assertTrue(response1.getSuccess());
        assertFalse(response1.getMatches());
        assertNotNull(response1.getReason());
        assertTrue(response1.getReason().contains("Business rule failed"));

        // Test with toWarnings strategy
        EntityCriteriaCalculationResponse response2 = serializer.withRequest(request)
                .withReasonAttachment(ReasonAttachmentStrategy.toWarnings())
                .evaluate(evaluator)
                .complete();

        assertNotNull(response2);
        assertTrue(response2.getSuccess());
        assertFalse(response2.getMatches());

        // Test with none strategy
        EntityCriteriaCalculationResponse response3 = serializer.withRequest(request)
                .withReasonAttachment(ReasonAttachmentStrategy.none())
                .evaluate(evaluator)
                .complete();

        assertNotNull(response3);
        assertTrue(response3.getSuccess());
        assertFalse(response3.getMatches());
    }

    @Test
    @DisplayName("EvaluationChain should handle entity evaluation exception")
    void testEntityEvaluationException() {
        // Given
        Function<CriterionSerializer.CriterionEntityEvaluationContext<TestEntity>, EvaluationOutcome> faultyEntityEvaluator = context -> {
            throw new RuntimeException("Entity evaluation failed");
        };

        // When
        EntityCriteriaCalculationResponse response = serializer.withRequest(request)
                .evaluateEntity(TestEntity.class, faultyEntityEvaluator)
                .complete();

        // Then
        assertNotNull(response);
        assertFalse(response.getSuccess());
        assertFalse(response.getMatches());
    }

    @Test
    @DisplayName("EvaluationChain should handle error propagation through chain")
    void testErrorPropagationThroughChain() {
        // Given - Create initial error with payload extraction
        EntityCriteriaCalculationRequest badRequest = new EntityCriteriaCalculationRequest();
        badRequest.setId("bad-request");
        badRequest.setPayload(null);

        Function<CriterionSerializer.CriterionEvaluationContext, EvaluationOutcome> evaluator1 = context -> {
            fail("First evaluator should not be called");
            return EvaluationOutcome.success();
        };

        Function<CriterionSerializer.CriterionEntityEvaluationContext<TestEntity>, EvaluationOutcome> evaluator2 = context -> {
            fail("Second evaluator should not be called");
            return EvaluationOutcome.success();
        };

        // When
        EntityCriteriaCalculationResponse response = serializer.withRequest(badRequest)
                .evaluate(evaluator1) // Should be skipped
                .evaluateEntity(TestEntity.class, evaluator2) // Should be skipped
                .withErrorHandler((error, payload) -> new ErrorInfo("LATE_ERROR", "Late error"))
                .complete();

        // Then
        assertNotNull(response);
        assertFalse(response.getSuccess());
        assertFalse(response.getMatches());
    }

    // ========================================
    // COVERAGE TESTS FOR SPECIFIC LINES
    // ========================================

    @Test
    @DisplayName("EvaluationChain should handle Fail outcome and create evaluation reason")
    void testFailOutcomeWithEvaluationReason() {
        // Given - This test specifically covers line 162: else if (outcome instanceof EvaluationOutcome.Fail fail)
        Function<CriterionSerializer.CriterionEvaluationContext, EvaluationOutcome> failEvaluator = context -> {
            // Return a specific Fail outcome to trigger line 162
            return EvaluationOutcome.Fail.businessRuleFailure("Business rule violation detected");
        };

        // When
        EntityCriteriaCalculationResponse response = serializer.withRequest(request)
                .evaluate(failEvaluator) // This should trigger line 162
                .complete();

        // Then
        assertNotNull(response);
        assertTrue(response.getSuccess()); // Success because evaluation completed
        assertFalse(response.getMatches()); // No match because evaluation failed
    }

    @Test
    @DisplayName("EvaluationChain should use custom error handler when no evaluation performed")
    void testCustomErrorHandlerWhenNoEvaluationPerformed() {
        // Given - This test specifically covers line 202: ErrorInfo errorInfo = errorHandler.apply(...)
        BiFunction<Throwable, JsonNode, ErrorInfo> customErrorHandler =
                (error, payload) -> {
                    // Verify this is called with the expected error
                    assertInstanceOf(IllegalStateException.class, error);
                    assertEquals("No evaluation was performed", error.getMessage());
                    return new ErrorInfo("NO_EVALUATION_CUSTOM", "Custom: " + error.getMessage());
                };

        // When - Complete without calling any evaluate methods
        EntityCriteriaCalculationResponse response = serializer.withRequest(request)
                .withErrorHandler(customErrorHandler) // Set custom error handler
                .complete(); // This should trigger line 202

        // Then
        assertNotNull(response);
        assertFalse(response.getSuccess());
        assertFalse(response.getMatches());
    }

    @Test
    @DisplayName("EvaluationChain should handle entity Fail outcome with evaluation reason")
    void testEntityFailOutcomeWithEvaluationReason() {
        // Given - Test entity evaluation that returns Fail outcome
        Function<CriterionSerializer.CriterionEntityEvaluationContext<TestEntity>, EvaluationOutcome> entityFailEvaluator = context -> {
            TestEntity entity = context.entityWithMetadata().entity();
            // Return a specific Fail outcome for entity evaluation
            return EvaluationOutcome.Fail.dataQualityFailure("Entity data quality issue: " + entity.getName());
        };

        // When
        EntityCriteriaCalculationResponse response = serializer.withRequest(request)
                .evaluateEntity(TestEntity.class, entityFailEvaluator) // This should also trigger line 162
                .complete();

        // Then
        assertNotNull(response);
        assertTrue(response.getSuccess()); // Success because evaluation completed
        assertFalse(response.getMatches()); // No match because evaluation failed
    }

    @Test
    @DisplayName("EvaluationChain should handle structural failure outcome")
    void testStructuralFailureOutcome() {
        // Given - Test structural failure specifically
        Function<CriterionSerializer.CriterionEvaluationContext, EvaluationOutcome> structuralFailEvaluator =
                context -> EvaluationOutcome.Fail.structuralFailure("Data structure is invalid");

        // When
        EntityCriteriaCalculationResponse response = serializer.withRequest(request)
                .evaluate(structuralFailEvaluator)
                .complete();

        // Then
        assertNotNull(response);
        assertTrue(response.getSuccess());
        assertFalse(response.getMatches());
    }

    @Test
    @DisplayName("EvaluationChain should handle data quality failure outcome")
    void testDataQualityFailureOutcome() {
        // Given - Test data quality failure specifically
        Function<CriterionSerializer.CriterionEvaluationContext, EvaluationOutcome> dataQualityFailEvaluator =
                context -> EvaluationOutcome.Fail.dataQualityFailure("Data quality standards not met");

        // When
        EntityCriteriaCalculationResponse response = serializer.withRequest(request)
                .evaluate(dataQualityFailEvaluator)
                .complete();

        // Then
        assertNotNull(response);
        assertTrue(response.getSuccess());
        assertFalse(response.getMatches());
    }
}
