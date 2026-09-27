package com.java_template.common.service;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.java_template.common.auth.CyodaTokenSource;
import com.java_template.common.call.CyodaCallContexts;
import com.java_template.common.config.Config;
import com.java_template.common.config.CyodaObjectMapper;
import com.java_template.common.exception.CyodaHttpException;
import com.java_template.common.exception.WorkflowExportException;
import com.java_template.common.util.HttpUtils;
import com.java_template.common.util.JsonUtils;
import com.sun.net.httpserver.HttpServer;
import org.cyoda.cloud.api.event.common.ModelSpec;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** WorkflowServiceImpl against a stub cyoda answering every request with a 400 problem detail. */
class WorkflowServiceImplTest {

    private static final String PROBLEM = """
            {"status":400,"detail":"MODEL_NOT_FOUND: model m/1 not found","properties":{"errorCode":"MODEL_NOT_FOUND"}}""";

    private HttpServer server;
    private WorkflowServiceImpl workflowService;

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api", ex -> {
            byte[] body = PROBLEM.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/problem+json");
            ex.sendResponseHeaders(400, body.length);
            ex.getResponseBody().write(body);
            ex.close();
        });
        server.start();
        Config config = new Config();
        config.setCyodaApiUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/api");
        CyodaTokenSource tokens = mock(CyodaTokenSource.class);
        when(tokens.bearerToken()).thenReturn(Optional.of("m2m"));
        CyodaObjectMapper wireMapper = CyodaObjectMapper.standalone();
        HttpUtils http = new HttpUtils(new JsonUtils(wireMapper), wireMapper, config, tokens);
        workflowService = new WorkflowServiceImpl(http, new CyodaCallContexts(config), wireMapper, config);
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    void aFailedImportSurfacesTheTypedExceptionDirectly() {
        ModelSpec spec = new ModelSpec().withName("m").withVersion(1);

        assertThatThrownBy(() -> workflowService.importWorkflows(spec, JsonNodeFactory.instance.arrayNode(), "REPLACE"))
                .isInstanceOfSatisfying(CyodaHttpException.class, e -> {
                    assertThat(e.status()).isEqualTo(400);
                    assertThat(e.getErrorCode()).isEqualTo("MODEL_NOT_FOUND");
                });
    }

    @Test
    void aFailedExportKeepsItsWorkflowExportExceptionContractWithTheTypedCause() {
        assertThatThrownBy(() -> workflowService.exportWorkflows("m", 1))
                .isInstanceOf(WorkflowExportException.class)
                .hasCauseInstanceOf(CyodaHttpException.class);
    }
}
