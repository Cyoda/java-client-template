package com.java_template.common.grpc.client.event_handling;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.java_template.common.config.CyodaObjectMapper;
import com.java_template.common.exception.CyodaOperationException;
import io.cloudevents.v1.proto.CloudEvent;
import org.cyoda.cloud.api.event.search.EntityResponse;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for CloudEventParser verifying successful parsing returns
 * the typed response directly, and parse failures throw CyodaOperationException.
 */
class CloudEventParserTest {

    private final CloudEventParser parser = new CloudEventParser(CyodaObjectMapper.standalone());

    @Test
    void validJsonReturnsTypedResponse() {
        String json = "{\"id\":\"test-id\",\"success\":true}";
        CloudEvent event = CloudEvent.newBuilder()
                .setTextData(json)
                .build();

        EntityResponse result = parser.parseCloudEvent(event, EntityResponse.class);

        assertNotNull(result);
        assertEquals("test-id", result.getId());
    }

    @Test
    void invalidJsonThrowsCyodaOperationException() {
        CloudEvent event = CloudEvent.newBuilder()
                .setTextData("not valid json {{{")
                .build();

        CyodaOperationException ex = assertThrows(
                CyodaOperationException.class,
                () -> parser.parseCloudEvent(event, EntityResponse.class)
        );

        assertEquals("PARSE_ERROR", ex.getErrorCode());
        assertFalse(ex.isRetryable());
    }
}
