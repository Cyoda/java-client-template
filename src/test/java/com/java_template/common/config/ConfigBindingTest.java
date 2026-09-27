package com.java_template.common.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

import java.util.List;

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

    @Test
    void localProfileTargetsADefaultLocalCyoda() throws Exception {
        List<PropertySource<?>> sources = new YamlPropertySourceLoader()
                .load("local", new ClassPathResource("application-local.yml"));
        PropertySource<?> local = sources.getFirst();

        assertThat(local.getProperty("server.port")).isEqualTo(8081);
        assertThat(local.getProperty("app.config.cyoda-api-url")).isEqualTo("http://localhost:8080/api");
        assertThat(local.getProperty("app.config.grpc-address")).isEqualTo("localhost");
        assertThat(local.getProperty("app.config.grpc-server-port")).isEqualTo(9090);
        assertThat(local.getProperty("app.config.grpc-tls")).isEqualTo(false);
        assertThat(local.getProperty("app.config.auth-mode")).isEqualTo("none");
    }
}
