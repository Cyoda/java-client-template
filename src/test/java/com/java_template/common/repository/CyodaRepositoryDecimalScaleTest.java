package com.java_template.common.repository;

import com.java_template.common.auth.CyodaTokenSource;
import com.java_template.common.call.CyodaCallContext;
import com.java_template.common.call.CyodaCallInterceptor;
import com.java_template.common.config.Config;
import com.java_template.common.config.CyodaObjectMapper;
import com.java_template.common.grpc.client.connection.ChannelReadiness;
import com.java_template.common.grpc.client.event_handling.CloudEventParser;
import org.cyoda.cloud.api.event.common.ModelSpec;
import org.cyoda.cloud.api.event.entity.EntityTransactionResponse;
import org.cyoda.cloud.api.grpc.CloudEventsServiceGrpc;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * ABOUTME: An entity's BigDecimal field keeps its exact scale through the real save path: CyodaRepository builds
 * the outgoing tree with entityMapper.valueToTree (spec §4.9), which is CyodaObjectMapper.entities(); the outer
 * envelope is then serialized by protocol(). Jackson 2.19's JsonNodeFeature.STRIP_TRAILING_BIGDECIMAL_ZEROES
 * (default true) would otherwise collapse 10.00 to a DecimalNode of scale -1 in that first step, before
 * protocol() (fixed in an earlier round) ever gets a chance to write it back.
 */
class CyodaRepositoryDecimalScaleTest {

    private final CyodaObjectMapper mappers = CyodaObjectMapper.standalone();
    private RecordingCyodaServer server;
    private CyodaRepository repo;
    private final ModelSpec spec = new ModelSpec().withName("m").withVersion(1);

    @SuppressWarnings("unused")
    static class Widget {
        public String name = "widget-1";
        public BigDecimal amount = new BigDecimal("10.00");
    }

    @BeforeEach
    void setUp() throws Exception {
        server = new RecordingCyodaServer(mappers);
        CyodaTokenSource tokens = mock(CyodaTokenSource.class);
        when(tokens.bearerToken()).thenReturn(Optional.of("m2m-token"));
        var stub = CloudEventsServiceGrpc.newBlockingStub(server.channel).withInterceptors(new CyodaCallInterceptor(tokens));
        Config config = new Config();
        repo = new CyodaRepository(mappers, stub, server.builder, new CloudEventParser(mappers), config, tokens,
                new ChannelReadiness(server.channel));
        server.unary = ce -> new EntityTransactionResponse().withId("r1").withSuccess(true);
    }

    @AfterEach
    void tearDown() {
        repo.shutdownExecutor();
        server.close();
    }

    @Test
    void savingAnEntityWithATrailingZeroScaleSendsItUnchanged() {
        repo.save(CyodaCallContext.m2m(), spec, new Widget()).join();

        assertThat(server.seen).hasSize(1);
        assertThat(server.seen.getFirst().textData()).contains("\"amount\":10.00");
    }
}
