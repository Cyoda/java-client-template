package com.java_template.common.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.core.io.ClassPathResource;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ConfigBindingTest {

    @Configuration
    @EnableConfigurationProperties(Config.class)
    static class Bind {
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner().withUserConfiguration(Bind.class);

    @Test
    void defaultsAreCloudShaped() {
        runner.run(ctx -> {
            Config c = ctx.getBean(Config.class);
            assertThat(c.isGrpcTls()).isTrue();
            assertThat(c.getAuthMode()).isEqualTo(Config.AuthMode.CLIENT_CREDENTIALS);
            assertThat(c.getExecutionMode()).isEqualTo("virtual");
            assertThat(c.getGrpcCallDeadlineMs()).isEqualTo(120_000L);
            assertThat(c.getKeepAliveWarningThreshold()).isEqualTo(20_000L);
        });
    }

    @Test
    void relaxedBindingOfAuthModeNone() {
        runner.withPropertyValues("app.config.auth-mode=none", "app.config.grpc-tls=false")
                .run(ctx -> {
                    Config c = ctx.getBean(Config.class);
                    assertThat(c.getAuthMode()).isEqualTo(Config.AuthMode.NONE);
                    assertThat(c.isGrpcTls()).isFalse();
                });
    }

    /** The README tells Cloud users to pass credentials as these environment variables. */
    @Test
    void cloudCredentialsBindFromTheEnvironmentVariablesTheReadmeDocuments() {
        runner.withInitializer(ctx -> ctx.getEnvironment().getPropertySources().addFirst(
                        new SystemEnvironmentPropertySource(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, Map.of(
                                "APP_CONFIG_CYODA_HOST", "tenant.eu.cyoda.net",
                                "APP_CONFIG_CYODA_CLIENT_ID", "the-id",
                                "APP_CONFIG_CYODA_CLIENT_SECRET", "the-secret"))))
                .run(ctx -> {
                    Config c = ctx.getBean(Config.class);
                    assertThat(c.getCyodaHost()).isEqualTo("tenant.eu.cyoda.net");
                    assertThat(c.getCyodaClientId()).isEqualTo("the-id");
                    assertThat(c.getCyodaClientSecret()).isEqualTo("the-secret");
                });
    }

    @Test
    void cyodaLocalProfileTargetsADefaultLocalCyoda() throws Exception {
        List<PropertySource<?>> sources = new YamlPropertySourceLoader()
                .load("cyoda-local", new ClassPathResource("application-cyoda-local.yml"));
        PropertySource<?> local = sources.getFirst();

        assertThat(local.getProperty("server.port")).isEqualTo(8081);
        assertThat(local.getProperty("server.address")).isEqualTo("127.0.0.1");
        assertThat(local.getProperty("app.config.cyoda-api-url")).isEqualTo("http://localhost:8080/api");
        assertThat(local.getProperty("app.config.grpc-address")).isEqualTo("localhost");
        assertThat(local.getProperty("app.config.grpc-server-port")).isEqualTo(9090);
        assertThat(local.getProperty("app.config.grpc-tls")).isEqualTo(false);
        assertThat(local.getProperty("app.config.auth-mode")).isEqualTo("none");
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"0", "-1"})
    void aNonPositiveCallDeadlineIsRejectedAtStartup(String deadline) {
        runner.withPropertyValues("app.config.grpc-call-deadline-ms=" + deadline)
                .run(ctx -> assertThat(ctx).hasFailed().getFailure().rootCause()
                        .hasMessageContaining("app.config.grpc-call-deadline-ms")
                        .hasMessageContaining("must be greater than 0"));
    }
}
