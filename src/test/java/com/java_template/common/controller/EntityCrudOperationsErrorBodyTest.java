package com.java_template.common.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.java_template.common.service.EntityService;
import com.java_template.common.workflow.CyodaEntity;
import com.java_template.common.workflow.OperationSpecification;
import org.cyoda.cloud.api.event.common.ModelSpec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.LoggerFactory;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * ABOUTME: A failed EntityCrudOperations call answers with a generic ProblemDetail and a correlation id; the
 * exception's message (which can carry internal hosts, URLs or Cyoda error text) is only logged, with that id.
 */
@ExtendWith(OutputCaptureExtension.class)
class EntityCrudOperationsErrorBodyTest {

    private static final String INTERNAL = "connect to http://cyoda.internal:8080/api failed: secret-detail";

    static class Thing implements CyodaEntity {
        @Override
        public OperationSpecification getModelKey() {
            return new OperationSpecification.Entity(new ModelSpec().withName("thing").withVersion(1), "thing");
        }
    }

    private final EntityService entityService = mock(EntityService.class);
    private final EntityCrudOperations<Thing> ops = new EntityCrudOperations<>(
            entityService, new ObjectMapper(), LoggerFactory.getLogger(getClass()),
            "thing", 1, Thing.class, "thingId");

    @Test
    void createFailureHidesTheCauseAndCarriesACorrelationId(CapturedOutput output) {
        when(entityService.findByBusinessIdOrNull(any(), anyString(), anyString(), eq(Thing.class))).thenReturn(null);
        when(entityService.create(any())).thenThrow(new IllegalStateException(INTERNAL));

        assertGeneric(() -> ops.create(new Thing(), t -> "T-1"), output);
    }

    @Test
    void aRefusedLoggedInCallAnswers500(CapturedOutput output) {
        when(entityService.deleteById(any())).thenThrow(new java.util.concurrent.CompletionException(
                new com.java_template.common.exception.CyodaCredentialException(INTERNAL)));

        ResponseEntity<Void> response = ops.deleteById(UUID.randomUUID());

        assertThat(response.getStatusCode().value()).isEqualTo(500);
        assertGeneric(() -> response, output);
    }

    @Test
    void everyOperationHidesTheCause(CapturedOutput output) {
        UUID id = UUID.randomUUID();
        RuntimeException boom = new RuntimeException(INTERNAL);
        when(entityService.getById(any(), any(), any(), org.mockito.ArgumentMatchers.nullable(java.time.OffsetDateTime.class))).thenThrow(boom);
        when(entityService.findByBusinessId(any(), any(), any(), any(), org.mockito.ArgumentMatchers.nullable(java.time.OffsetDateTime.class))).thenThrow(boom);
        when(entityService.getEntityChangesMetadata(any(), org.mockito.ArgumentMatchers.nullable(java.time.OffsetDateTime.class))).thenThrow(boom);
        when(entityService.update(any(), any(), any())).thenThrow(boom);
        when(entityService.deleteById(any())).thenThrow(boom);
        when(entityService.deleteByBusinessId(any(), any(), any(), any())).thenThrow(boom);
        when(entityService.deleteAll(any(ModelSpec.class))).thenThrow(boom);
        when(entityService.save(any(), any(), any())).thenThrow(boom);
        when(entityService.updateAll(any(), any(), any(), any())).thenThrow(boom);
        when(entityService.search(any(), any(), any(), any())).thenThrow(boom);
        when(entityService.findAll(any(), any(), any())).thenThrow(boom);

        assertGeneric(() -> ops.getById(id, null), output);
        assertGeneric(() -> ops.getByBusinessId("T-1", null), output);
        assertGeneric(() -> ops.getChangesMetadata(id, null), output);
        assertGeneric(() -> ops.update(id, new Thing(), null), output);
        assertGeneric(() -> ops.deleteById(id), output);
        assertGeneric(() -> ops.deleteByBusinessId("T-1"), output);
        assertGeneric(() -> ops.deleteAll(), output);
        assertGeneric(() -> ops.createAll(List.of(new Thing())), output);
        assertGeneric(() -> ops.updateAll(List.of(new Thing()), null), output);
        assertGeneric(() -> ops.search(10, 0, null, "name", "x", null), output);
        assertGeneric(() -> ops.list(10, 0, null, List.of(), null, null), output);
        assertGeneric(() -> ops.executeTransition(id, "go"), output);
    }

    private static void assertGeneric(Supplier<? extends ResponseEntity<?>> call, CapturedOutput output) {
        ResponseEntity<?> response = call.get();

        assertThat(response.getStatusCode().is4xxClientError() || response.getStatusCode().is5xxServerError()).isTrue();
        assertThat(response.getBody()).isInstanceOf(ProblemDetail.class);
        ProblemDetail problem = (ProblemDetail) response.getBody();
        assertThat(String.valueOf(problem.getDetail()))
                .doesNotContain("cyoda.internal").doesNotContain("secret-detail").doesNotContain("http");
        assertThat(problem.getProperties()).containsKey("correlationId");
        String correlationId = String.valueOf(problem.getProperties().get("correlationId"));
        assertThat(UUID.fromString(correlationId)).isNotNull();
        assertThat(problem.getDetail()).contains(correlationId);
        assertThat(String.valueOf(problem.getProperties())).doesNotContain("secret-detail");
        // The server log keeps the cause, under the same id.
        assertThat(output.getOut()).contains(correlationId).contains("secret-detail");
    }
}
