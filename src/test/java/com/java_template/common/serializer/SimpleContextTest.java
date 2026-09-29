package com.java_template.common.serializer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.java_template.common.config.CyodaJackson;
import com.java_template.common.serializer.jackson.JacksonCriterionSerializer;
import com.java_template.common.serializer.jackson.JacksonProcessorSerializer;
import org.cyoda.cloud.api.event.common.DataPayload;
import org.cyoda.cloud.api.event.processing.EntityCriteriaCalculationRequest;
import org.cyoda.cloud.api.event.processing.EntityCriteriaCalculationResponse;
import org.cyoda.cloud.api.event.processing.EntityProcessorCalculationRequest;
import org.cyoda.cloud.api.event.processing.EntityProcessorCalculationResponse;
import org.cyoda.uuid.SimpleSystemClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ABOUTME: Simple test to verify the new context-aware methods work correctly.
 * Tests basic functionality without complex entity extraction.
 */
class SimpleContextTest {

    private CriterionSerializer criterionSerializer;
    private ProcessorSerializer processorSerializer;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = CyodaJackson.configure(new ObjectMapper());
        criterionSerializer = new JacksonCriterionSerializer(objectMapper);
        processorSerializer = new JacksonProcessorSerializer(objectMapper);
    }

    @Test
    void testCriterionEvaluateWithContext() {
        // Create test request with metadata
        EntityCriteriaCalculationRequest request = new EntityCriteriaCalculationRequest();
        request.setId("test-id");
        UUID entityId1 = SimpleSystemClock.INSTANCE.uniqueTimeUUIDinMicros();
        request.setEntityId(entityId1);
        request.setTransactionId("txn-456");
        request.setCriteriaId("criteria-789");
        request.setCriteriaName("TestCriteria");
        request.setTarget(EntityCriteriaCalculationRequest.Target.WORKFLOW);

        // Create test payload
        DataPayload payload = new DataPayload();
        JsonNode testData = objectMapper.createObjectNode()
                .put("testField", "testValue")
                .put("numericField", 42);
        payload.setData(testData);
        request.setPayload(payload);

        // Test evaluate - can access both request metadata and payload
        EntityCriteriaCalculationResponse response = criterionSerializer.withRequest(request)
                .evaluate(context -> {
                    System.out.println("Context evaluation called!");

                    // Access request metadata
                    UUID entityId = context.request().getEntityId();
                    String transactionId = context.request().getTransactionId();

                    // Access payload data
                    JsonNode data = context.payload();
                    String testField = data.get("testField").asText();
                    int numericField = data.get("numericField").asInt();

                    System.out.println("Entity ID: " + entityId);
                    System.out.println("Transaction ID: " + transactionId);
                    System.out.println("Test field: " + testField);
                    System.out.println("Numeric field: " + numericField);

                    // Validation logic using both metadata and payload
                    if (entityId1.equals(entityId) && "testValue".equals(testField) && numericField > 40) {
                        System.out.println("Returning success");
                        return EvaluationOutcome.success();
                    } else {
                        System.out.println("Returning failure");
                        return EvaluationOutcome.Fail.of("Validation failed for entity: " + entityId);
                    }
                })
                .complete();

        // Debug the response
        System.out.println("Response success: " + response.getSuccess());
        System.out.println("Response matches: " + response.getMatches());
        System.out.println("Response error: " + response.getError());
        System.out.println("Response warnings: " + response.getWarnings());

        assertTrue(response.getMatches());
    }

    @Test
    void testProcessorMapWithContext() {
        // Create test request
        EntityProcessorCalculationRequest request = new EntityProcessorCalculationRequest();
        request.setId("test-id");
        UUID entityId = SimpleSystemClock.INSTANCE.uniqueTimeUUIDinMicros();
        request.setEntityId(entityId);
        request.setTransactionId("txn-123");
        request.setProcessorId("processor-456");
        request.setProcessorName("TestProcessor");

        // Create test payload
        DataPayload payload = new DataPayload();
        JsonNode testData = objectMapper.createObjectNode()
                .put("originalField", "originalValue");
        payload.setData(testData);
        request.setPayload(payload);

        // Test map - can access both request metadata and payload
        EntityProcessorCalculationResponse response = processorSerializer.withRequest(request)
                .map(context -> {
                    System.out.println("Processor context evaluation called!");

                    // Access request metadata
                    UUID entityId1 = context.request().getEntityId();
                    String transactionId = context.request().getTransactionId();

                    System.out.println("Entity ID: " + entityId1);
                    System.out.println("Transaction ID: " + transactionId);

                    // Access and modify payload data
                    JsonNode data = context.payload();
                    return objectMapper.createObjectNode()
                            .put("originalField", data.get("originalField").asText())
                            .put("entityId", entityId1.toString())
                            .put("transactionId", transactionId)
                            .put("processedAt", System.currentTimeMillis());
                })
                .complete();

        // Debug the response
        System.out.println("Processor response success: " + response.getSuccess());
        System.out.println("Processor response error: " + response.getError());
        System.out.println("Processor response warnings: " + response.getWarnings());

        assertTrue(response.getSuccess());
        JsonNode resultData = response.getPayload().getData();
        assertEquals("originalValue", resultData.get("originalField").asText());
        assertEquals(entityId.toString(), resultData.get("entityId").asText());
        assertEquals("txn-123", resultData.get("transactionId").asText());
        assertTrue(resultData.has("processedAt"));
    }
}
