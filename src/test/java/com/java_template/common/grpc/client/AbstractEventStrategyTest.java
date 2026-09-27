package com.java_template.common.grpc.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.java_template.common.config.CyodaObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.java_template.common.grpc.client.event_handling.AbstractEventStrategy;
import com.java_template.common.grpc.client.event_handling.ProcessorEventStrategy;
import com.java_template.common.workflow.CyodaContextFactory;
import com.java_template.common.workflow.OperationFactory;
import io.cloudevents.v1.proto.CloudEvent;
import org.cyoda.cloud.api.event.processing.EntityProcessorCalculationResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * ABOUTME: Tests for AbstractEventStrategy, focusing on the recoverRequestIdFromCloudEvent method
 * which handles string-based parsing of potentially corrupted JSON to extract request IDs.
 */
@ExtendWith(MockitoExtension.class)
class AbstractEventStrategyTest {


    @Mock
    private CloudEvent cloudEvent;

    @Test
    void testRecoverRequestIdFromCloudEvent_ValidJsonWithQuotes() {
        // Given
        String jsonData = "{\"requestId\": \"12345678-1234-1234-1234-123456789abc\", \"entityId\": \"entity123\"}";
        when(cloudEvent.getTextData()).thenReturn(jsonData);

        // When
        AbstractEventStrategy.RequestIdRecoveryResult result = AbstractEventStrategy.recoverRequestIdFromCloudEvent(cloudEvent);

        // Then
        assertTrue(result.requestId().isPresent());
        assertEquals("12345678-1234-1234-1234-123456789abc", result.requestId().get());
        assertNull(result.error());
    }

    @Test
    void testRecoverRequestIdFromCloudEvent_ValidJsonNoSpaces() {
        // Given
        String jsonData = "{\"requestId\":\"test-request-id-456\",\"entityId\":\"entity123\"}";
        when(cloudEvent.getTextData()).thenReturn(jsonData);

        // When
        AbstractEventStrategy.RequestIdRecoveryResult result = AbstractEventStrategy.recoverRequestIdFromCloudEvent(cloudEvent);

        // Then
        assertTrue(result.requestId().isPresent());
        assertEquals("test-request-id-456", result.requestId().get());
        assertNull(result.error());
    }

    @Test
    void testRecoverRequestIdFromCloudEvent_SingleQuotes() {
        // Given
        String jsonData = "{'requestId': 'single-quote-id-789', 'entityId': 'entity123'}";
        when(cloudEvent.getTextData()).thenReturn(jsonData);

        // When
        AbstractEventStrategy.RequestIdRecoveryResult result = AbstractEventStrategy.recoverRequestIdFromCloudEvent(cloudEvent);

        // Then
        assertTrue(result.requestId().isPresent());
        assertEquals("single-quote-id-789", result.requestId().get());
        assertNull(result.error());
    }

    @Test
    void testRecoverRequestIdFromCloudEvent_MixedQuotes() {
        // Given
        String jsonData = "{\"requestId\": 'mixed-quote-id-101', \"entityId\": \"entity123\"}";
        when(cloudEvent.getTextData()).thenReturn(jsonData);

        // When
        AbstractEventStrategy.RequestIdRecoveryResult result = AbstractEventStrategy.recoverRequestIdFromCloudEvent(cloudEvent);

        // Then
        assertTrue(result.requestId().isPresent());
        assertEquals("mixed-quote-id-101", result.requestId().get());
        assertNull(result.error());
    }

    @Test
    void testRecoverRequestIdFromCloudEvent_MultilineJson() {
        // Given
        String jsonData = "{\n  \"requestId\": \"multiline-id-202\",\n  \"entityId\": \"entity123\"\n}";
        when(cloudEvent.getTextData()).thenReturn(jsonData);

        // When
        AbstractEventStrategy.RequestIdRecoveryResult result = AbstractEventStrategy.recoverRequestIdFromCloudEvent(cloudEvent);

        // Then
        assertTrue(result.requestId().isPresent());
        assertEquals("multiline-id-202", result.requestId().get());
        assertNull(result.error());
    }

    @Test
    void testRecoverRequestIdFromCloudEvent_UuidFallbackPattern() {
        // Given - corrupted JSON where quotes are missing but UUID is intact
        String jsonData = "{requestId: 12345678-1234-1234-1234-123456789abc, entityId: entity123}";
        when(cloudEvent.getTextData()).thenReturn(jsonData);

        // When
        AbstractEventStrategy.RequestIdRecoveryResult result = AbstractEventStrategy.recoverRequestIdFromCloudEvent(cloudEvent);

        // Then
        assertTrue(result.requestId().isPresent());
        assertEquals("12345678-1234-1234-1234-123456789abc", result.requestId().get());
        assertNull(result.error());
    }

    @Test
    void testRecoverRequestIdFromCloudEvent_GeneralPatternFallback() {
        // Given - corrupted JSON with non-UUID requestId
        String jsonData = "{requestId: simple-request-id-303, entityId: entity123}";
        when(cloudEvent.getTextData()).thenReturn(jsonData);

        // When
        AbstractEventStrategy.RequestIdRecoveryResult result = AbstractEventStrategy.recoverRequestIdFromCloudEvent(cloudEvent);

        // Then
        assertTrue(result.requestId().isPresent());
        assertEquals("simple-request-id-303", result.requestId().get());
        assertNull(result.error());
    }

    @Test
    void testRecoverRequestIdFromCloudEvent_CaseInsensitive() {
        // Given
        String jsonData = "{\"REQUESTID\": \"case-insensitive-id-404\", \"entityId\": \"entity123\"}";
        when(cloudEvent.getTextData()).thenReturn(jsonData);

        // When
        AbstractEventStrategy.RequestIdRecoveryResult result = AbstractEventStrategy.recoverRequestIdFromCloudEvent(cloudEvent);

        // Then
        assertTrue(result.requestId().isPresent());
        assertEquals("case-insensitive-id-404", result.requestId().get());
        assertNull(result.error());
    }

    @Test
    void testRecoverRequestIdFromCloudEvent_NullCloudEvent() {
        // When
        AbstractEventStrategy.RequestIdRecoveryResult result = AbstractEventStrategy.recoverRequestIdFromCloudEvent(null);

        // Then
        assertFalse(result.requestId().isPresent());
        assertEquals("CloudEvent is null, cannot recover requestId", result.error());
    }

    @Test
    void testRecoverRequestIdFromCloudEvent_EmptyTextData() {
        // Given
        when(cloudEvent.getTextData()).thenReturn("");

        // When
        AbstractEventStrategy.RequestIdRecoveryResult result = AbstractEventStrategy.recoverRequestIdFromCloudEvent(cloudEvent);

        // Then
        assertFalse(result.requestId().isPresent());
        assertEquals("CloudEvent text data is empty, cannot recover requestId", result.error());
    }

    @Test
    void testRecoverRequestIdFromCloudEvent_NoRequestIdField() {
        // Given
        String jsonData = "{\"entityId\": \"entity123\", \"processorName\": \"test-processor\"}";
        when(cloudEvent.getTextData()).thenReturn(jsonData);

        // When
        AbstractEventStrategy.RequestIdRecoveryResult result = AbstractEventStrategy.recoverRequestIdFromCloudEvent(cloudEvent);

        // Then
        assertFalse(result.requestId().isPresent());
        assertEquals("Could not recover requestId from CloudEvent text data. No matching patterns found.", result.error());
    }

    @Test
    void testRecoverRequestIdFromCloudEvent_CorruptedJsonWithPartialRequestId() {
        // Given - heavily corrupted JSON but requestId is still extractable
        String jsonData = "{\"req corrupted but requestId: \"recoverable-id-505\" still here}";
        when(cloudEvent.getTextData()).thenReturn(jsonData);

        // When
        AbstractEventStrategy.RequestIdRecoveryResult result = AbstractEventStrategy.recoverRequestIdFromCloudEvent(cloudEvent);

        // Then
        assertTrue(result.requestId().isPresent());
        assertEquals("recoverable-id-505", result.requestId().get());
        assertNull(result.error());
    }

    @Test
    void testRecoverRequestIdFromCloudEvent_RequestIdWithSpecialCharacters() {
        // Given
        String jsonData = "{\"requestId\": \"req-id_with.special@chars#606\", \"entityId\": \"entity123\"}";
        when(cloudEvent.getTextData()).thenReturn(jsonData);

        // When
        AbstractEventStrategy.RequestIdRecoveryResult result = AbstractEventStrategy.recoverRequestIdFromCloudEvent(cloudEvent);

        // Then
        assertTrue(result.requestId().isPresent());
        assertEquals("req-id_with.special@chars#606", result.requestId().get());
        assertNull(result.error());
    }

    @Test
    void anErrorResponseCarriesAFreshIdTheRequestIdAndTheEntityId() throws Exception {
        CyodaObjectMapper wireMapper = CyodaObjectMapper.standalone();
        ObjectMapper om = wireMapper.mapper();
        OperationFactory factory = mock(OperationFactory.class);
        lenient().when(factory.getProcessorForModel(any())).thenThrow(new IllegalStateException("boom"));
        ProcessorEventStrategy strategy = new ProcessorEventStrategy(factory, wireMapper, new CyodaContextFactory(wireMapper));

        String entityId = UUID.randomUUID().toString();
        ObjectNode request = om.createObjectNode();
        request.put("id", "evt-1");
        request.put("requestId", "r-1");
        request.put("entityId", entityId);
        request.put("processorId", "p-1");
        request.put("processorName", "SomeProcessor");
        ObjectNode payload = request.putObject("payload");
        payload.put("type", "ENTITY");
        payload.putObject("meta").putObject("modelKey").put("name", "m").put("version", 1);
        payload.putObject("data");
        io.cloudevents.v1.proto.CloudEvent ce = io.cloudevents.v1.proto.CloudEvent.newBuilder()
                .setId("ce-1").setSource("test").setSpecVersion("1.0")
                .setType("EntityProcessorCalculationRequest")
                .setTextData(om.writeValueAsString(request)).build();

        EntityProcessorCalculationResponse response = strategy.handleEvent(ce);

        assertEquals(Boolean.FALSE, response.getSuccess());
        assertNotNull(response.getId());
        assertNotEquals("evt-1", response.getId());
        assertEquals("r-1", response.getRequestId());
        assertEquals(entityId, response.getEntityId().toString());
    }

    @Test
    void entityIdIsRecoveredFromCorruptedJson() {
        when(cloudEvent.getTextData()).thenReturn(
                "{\"requestId\":\"r-1\",\"entityId\":\"8824c480-c166-11ee-bf9f-ae468cd3ed16\", broken");

        assertEquals(java.util.Optional.of("8824c480-c166-11ee-bf9f-ae468cd3ed16"),
                AbstractEventStrategy.recoverEntityIdFromCloudEvent(cloudEvent));
    }

    @Test
    void aFailedCalloutIsLoggedWithoutItsTxTokenOrTheCallersIdentity() throws Exception {
        CyodaObjectMapper wireMapper = CyodaObjectMapper.standalone();
        ObjectMapper om = wireMapper.mapper();
        OperationFactory factory = mock(OperationFactory.class);
        lenient().when(factory.getProcessorForModel(any())).thenThrow(new IllegalStateException("boom"));
        ProcessorEventStrategy strategy = new ProcessorEventStrategy(factory, wireMapper, new CyodaContextFactory(wireMapper));

        ObjectNode request = om.createObjectNode();
        request.put("id", "evt-1");
        request.put("requestId", "r-1");
        request.put("entityId", UUID.randomUUID().toString());
        request.put("processorId", "p-1");
        request.put("processorName", "SomeProcessor");
        ObjectNode payload = request.putObject("payload");
        payload.put("type", "ENTITY");
        payload.putObject("meta").putObject("modelKey").put("name", "m").put("version", 1);
        payload.putObject("data");
        io.cloudevents.v1.proto.CloudEvent ce = io.cloudevents.v1.proto.CloudEvent.newBuilder()
                .setId("ce-1").setSource("test").setSpecVersion("1.0")
                .setType("EntityProcessorCalculationRequest")
                .putAttributes("cyodatxtoken", ceString("secret-tx-token"))
                .putAttributes("authtype", ceString("user"))
                .putAttributes("authid", ceString("secret-user-id"))
                .putAttributes("authclaims", ceString("ROLE_SECRET"))
                .setTextData(om.writeValueAsString(request)).build();

        ch.qos.logback.classic.Logger log =
                (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(AbstractEventStrategy.class);
        ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> appender =
                new ch.qos.logback.core.read.ListAppender<>();
        appender.start();
        log.addAppender(appender);
        try {
            strategy.handleEvent(ce);
        } finally {
            log.detachAppender(appender);
        }

        assertFalse(appender.list.isEmpty());
        for (ch.qos.logback.classic.spi.ILoggingEvent event : appender.list) {
            String line = event.getFormattedMessage();
            assertFalse(line.contains("secret-tx-token"), line);
            assertFalse(line.contains("secret-user-id"), line);
            assertFalse(line.contains("ROLE_SECRET"), line);
        }
        assertTrue(appender.list.stream().anyMatch(e -> e.getFormattedMessage().contains("ce-1")
                && e.getFormattedMessage().contains("r-1")));
    }

    private static io.cloudevents.v1.proto.CloudEvent.CloudEventAttributeValue ceString(String value) {
        return io.cloudevents.v1.proto.CloudEvent.CloudEventAttributeValue.newBuilder().setCeString(value).build();
    }
}
