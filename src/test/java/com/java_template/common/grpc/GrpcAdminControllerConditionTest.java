package com.java_template.common.grpc;

import com.java_template.common.grpc.client.connection.ConnectionManager;
import com.java_template.common.grpc.client.monitoring.GrpcConnectionMonitor;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/** ABOUTME: The unauthenticated gRPC admin endpoints exist only when app.admin.grpc-endpoints.enabled=true. */
class GrpcAdminControllerConditionTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            // Registered as ready singletons, so the mocks' inherited @PostConstruct/@PreDestroy never run.
            .withInitializer(ctx -> {
                ctx.getBeanFactory().registerSingleton("connectionManager", mock(ConnectionManager.class));
                ctx.getBeanFactory().registerSingleton("connectionMonitor", mock(GrpcConnectionMonitor.class));
            })
            .withUserConfiguration(GrpcAdminController.class);

    @Test
    void absentByDefault() {
        runner.run(ctx -> assertThat(ctx).doesNotHaveBean(GrpcAdminController.class));
    }

    @Test
    void absentWhenDisabled() {
        runner.withPropertyValues("app.admin.grpc-endpoints.enabled=false")
                .run(ctx -> assertThat(ctx).doesNotHaveBean(GrpcAdminController.class));
    }

    @Test
    void presentWhenEnabled() {
        runner.withPropertyValues("app.admin.grpc-endpoints.enabled=true")
                .run(ctx -> assertThat(ctx).hasSingleBean(GrpcAdminController.class));
    }
}
