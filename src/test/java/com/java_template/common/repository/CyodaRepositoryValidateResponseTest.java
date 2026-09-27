package com.java_template.common.repository;

import com.java_template.common.exception.CyodaOperationException;
import org.cyoda.cloud.api.event.common.Error;
import org.cyoda.cloud.api.event.entity.EntityTransactionResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import com.java_template.common.config.CyodaObjectMapper;
import com.java_template.common.config.Config;
import com.java_template.common.grpc.client.event_handling.CloudEventBuilder;
import com.java_template.common.grpc.client.event_handling.CloudEventParser;
import org.cyoda.cloud.api.grpc.CloudEventsServiceGrpc;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for CyodaRepository.validateResponse verifying that failed responses
 * throw CyodaOperationException and warnings are logged without throwing.
 */
@ExtendWith(MockitoExtension.class)
class CyodaRepositoryValidateResponseTest {

    @Spy CyodaObjectMapper wireMapper = CyodaObjectMapper.standalone();
    @Mock CloudEventsServiceGrpc.CloudEventsServiceBlockingStub stub;
    @Mock CloudEventBuilder cloudEventBuilder;
    @Mock CloudEventParser cloudEventParser;
    @Mock Config config;

    @InjectMocks CyodaRepository repository;

    @Test
    void successfulResponsePassesThrough() {
        var response = new EntityTransactionResponse();
        response.setSuccess(true);

        EntityTransactionResponse result = repository.validateResponse(response, false);

        assertSame(response, result);
    }

    @Test
    void nullSuccessTreatedAsOk() {
        var response = new EntityTransactionResponse();
        response.setSuccess(null);

        EntityTransactionResponse result = repository.validateResponse(response, false);

        assertSame(response, result);
    }

    @Test
    void failedResponseThrowsWithErrorDetails() {
        var error = new Error();
        error.setCode("SERVER_ERROR");
        error.setMessage("disk full");
        error.setRetryable(true);

        var response = new EntityTransactionResponse();
        response.setSuccess(false);
        response.setError(error);

        CyodaOperationException ex = assertThrows(
                CyodaOperationException.class,
                () -> repository.validateResponse(response, false)
        );

        assertEquals("SERVER_ERROR", ex.getErrorCode());
        assertEquals("disk full", ex.getErrorMessage());
        assertTrue(ex.isRetryable());
    }

    @Test
    void failedResponseWithNoErrorObjectUsesDefaults() {
        var response = new EntityTransactionResponse();
        response.setSuccess(false);
        response.setError(null);

        CyodaOperationException ex = assertThrows(
                CyodaOperationException.class,
                () -> repository.validateResponse(response, false)
        );

        assertEquals("UNKNOWN", ex.getErrorCode());
        assertFalse(ex.isRetryable());
    }

    @Test
    void warningsDoNotCauseException() {
        var response = new EntityTransactionResponse();
        response.setSuccess(true);
        response.setWarnings(List.of("deprecation notice", "slow query"));

        EntityTransactionResponse result = repository.validateResponse(response, false);

        assertSame(response, result);
    }

    @Test
    void warningsLoggedEvenOnFailedResponse() {
        var error = new Error();
        error.setCode("FAIL");
        error.setMessage("bad");
        error.setRetryable(false);

        var response = new EntityTransactionResponse();
        response.setSuccess(false);
        response.setError(error);
        response.setWarnings(List.of("also this warning"));

        CyodaOperationException ex = assertThrows(
                CyodaOperationException.class,
                () -> repository.validateResponse(response, false)
        );

        assertEquals("FAIL", ex.getErrorCode());
    }
}
