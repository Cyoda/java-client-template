package com.java_template.common.grpc.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.java_template.common.call.CalloutScope;
import com.java_template.common.call.CyodaCallContext;
import com.java_template.common.call.CyodaCallContexts;
import com.java_template.common.config.Config;
import com.java_template.common.config.CyodaObjectMapper;
import com.java_template.common.grpc.client.event_handling.ProcessorEventStrategy;
import com.java_template.common.workflow.CyodaContextFactory;
import com.java_template.common.workflow.CyodaProcessor;
import com.java_template.common.workflow.OperationFactory;
import io.cloudevents.v1.proto.CloudEvent;
import org.cyoda.cloud.api.event.processing.EntityProcessorCalculationResponse;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * ABOUTME: Verifies AbstractEventStrategy.handleEvent opens a CalloutScope carrying the callout's
 * tx-token around every processor invocation, and that the scope is gone (closed and ended) once the
 * callout has been answered.
 */
class CalloutScopeLifecycleTest {

    @Test
    void theScopeHoldsTheTokenDuringTheCalloutAndIsGoneAfterwards() throws Exception {
        CyodaObjectMapper mappers = CyodaObjectMapper.standalone();
        ObjectMapper om = mappers.protocol();
        AtomicReference<String> seenToken = new AtomicReference<>();
        AtomicReference<Object> seenAuth = new AtomicReference<>("unset");
        AtomicReference<String> contextToken = new AtomicReference<>();
        CyodaProcessor processor = mock(CyodaProcessor.class);
        when(processor.process(any())).thenAnswer(inv -> {
            seenToken.set(CalloutScope.current().map(CalloutScope::txToken).orElse(null));
            seenAuth.set(SecurityContextHolder.getContext().getAuthentication());
            contextToken.set(((com.java_template.common.workflow.CyodaEventContext<?>) inv.getArgument(0)).txToken());
            return new EntityProcessorCalculationResponse();
        });
        OperationFactory factory = mock(OperationFactory.class);
        when(factory.getProcessorForModel(any())).thenReturn(processor);
        ProcessorEventStrategy strategy = new ProcessorEventStrategy(factory, mappers, new CyodaContextFactory(mappers));
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken("leftover", "x", List.of()));

        try {
            ObjectNode request = om.createObjectNode();
            request.put("id", "evt-1").put("requestId", "r-1").put("entityId", UUID.randomUUID().toString())
                    .put("processorId", "p").put("processorName", "P");
            request.putObject("transition").put("name", "t");
            request.putObject("workflow").put("name", "w");
            ObjectNode payload = request.putObject("payload");
            payload.put("type", "ENTITY");
            payload.putObject("meta").putObject("modelKey").put("name", "m").put("version", 1);
            payload.putObject("data");
            CloudEvent ce = CloudEvent.newBuilder().setId("ce").setSource("s").setSpecVersion("1.0")
                    .setType("EntityProcessorCalculationRequest").setTextData(om.writeValueAsString(request))
                    .putAttributes("cyodatxtoken", CloudEvent.CloudEventAttributeValue.newBuilder().setCeString("tx-abc").build())
                    .build();

            strategy.handleEvent(ce);

            assertThat(seenToken.get()).isEqualTo("tx-abc");
            assertThat(contextToken.get()).isEqualTo("tx-abc");
            assertThat(seenAuth.get()).isNull();
            assertThat(CalloutScope.current()).isEmpty();
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    /**
     * Under COMMIT_BEFORE_DISPATCH with startNewTxOnDispatch=false, the callout carries no
     * tx-token (CalloutScope's own Javadoc). Its calls must still go out unjoined as M2M, not
     * with some other credential, and the user's SecurityContext must still be cleared for the
     * callout's duration (T23, T12b).
     */
    @Test
    void tokenlessDispatchGoesOutUnjoinedAsM2mAndClearsTheSecurityContext() throws Exception {
        CyodaObjectMapper mappers = CyodaObjectMapper.standalone();
        ObjectMapper om = mappers.protocol();
        CyodaCallContexts callContexts = new CyodaCallContexts(new Config());
        AtomicReference<CyodaCallContext> seenContext = new AtomicReference<>();
        AtomicReference<Object> seenAuth = new AtomicReference<>("unset");
        CyodaProcessor processor = mock(CyodaProcessor.class);
        when(processor.process(any())).thenAnswer(inv -> {
            seenContext.set(callContexts.current());
            seenAuth.set(SecurityContextHolder.getContext().getAuthentication());
            return new EntityProcessorCalculationResponse();
        });
        OperationFactory factory = mock(OperationFactory.class);
        when(factory.getProcessorForModel(any())).thenReturn(processor);
        ProcessorEventStrategy strategy = new ProcessorEventStrategy(factory, mappers, new CyodaContextFactory(mappers));
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken("leftover", "x", List.of()));

        try {
            ObjectNode request = om.createObjectNode();
            request.put("id", "evt-2").put("requestId", "r-2").put("entityId", UUID.randomUUID().toString())
                    .put("processorId", "p").put("processorName", "P");
            request.putObject("transition").put("name", "t");
            request.putObject("workflow").put("name", "w");
            ObjectNode payload = request.putObject("payload");
            payload.put("type", "ENTITY");
            payload.putObject("meta").putObject("modelKey").put("name", "m").put("version", 1);
            payload.putObject("data");
            // No "cyodatxtoken" attribute at all: the tokenless-dispatch case.
            CloudEvent ce = CloudEvent.newBuilder().setId("ce-2").setSource("s").setSpecVersion("1.0")
                    .setType("EntityProcessorCalculationRequest").setTextData(om.writeValueAsString(request))
                    .build();

            strategy.handleEvent(ce);

            assertThat(seenContext.get()).isEqualTo(CyodaCallContext.m2m());
            assertThat(seenContext.get().isJoined()).isFalse();
            assertThat(seenAuth.get()).isNull();
        } finally {
            SecurityContextHolder.clearContext();
        }
    }
}
