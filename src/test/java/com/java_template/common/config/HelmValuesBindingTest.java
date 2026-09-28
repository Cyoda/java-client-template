package com.java_template.common.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ABOUTME: The Helm chart's environment variables must bind to app.config.* (Spring Boot relaxed binding),
 * and the client secret must never be a plain value in values.yaml.
 */
class HelmValuesBindingTest {

    @Configuration
    @EnableConfigurationProperties(Config.class)
    static class Bind {
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> envVars() throws Exception {
        Path values = Path.of(System.getProperty("user.dir")).resolve("helm/values.yaml");
        try (InputStream in = Files.newInputStream(values)) {
            Map<String, Object> root = new Yaml().load(in);
            Map<String, Object> container = (Map<String, Object>) root.get("container");
            Map<String, Object> env = (Map<String, Object>) container.get("env");
            return (Map<String, Object>) env.get("vars");
        }
    }

    @Test
    void everyAppConfigVariableNamesARealConfigProperty() throws Exception {
        Set<String> setters = Arrays.stream(Config.class.getMethods()).map(Method::getName)
                .filter(n -> n.startsWith("set")).map(n -> n.substring(3).toLowerCase(Locale.ROOT))
                .collect(Collectors.toSet());

        Set<String> appConfigVars = envVars().keySet().stream().filter(k -> k.startsWith("APP_CONFIG_"))
                .collect(Collectors.toSet());

        assertThat(appConfigVars).isNotEmpty();
        assertThat(envVars().keySet()).allMatch(k -> k.equals("TZ") || k.startsWith("APP_CONFIG_"));
        for (String var : appConfigVars) {
            String property = var.substring("APP_CONFIG_".length()).replace("_", "").toLowerCase(Locale.ROOT);
            assertThat(setters).as(var + " binds to no app.config property").contains(property);
        }
    }

    @Test
    void theClientSecretIsNeverAPlainValue() throws Exception {
        assertThat(envVars().keySet()).noneMatch(k -> k.toUpperCase(Locale.ROOT).contains("SECRET"));
    }

    @Test
    void theChartsVariablesBind() throws Exception {
        Map<String, Object> vars = new HashMap<>();
        envVars().forEach((k, v) -> vars.put(k, String.valueOf(v)));
        vars.put("APP_CONFIG_CYODA_HOST", "tenant.eu.cyoda.net");
        vars.put("APP_CONFIG_GRPC_SERVER_PORT", "8443");
        vars.put("APP_CONFIG_SSL_TRUSTED_HOSTS", "a.example.com");
        vars.put("APP_CONFIG_CYODA_CLIENT_SECRET", "from-secret-key-ref");

        new ApplicationContextRunner().withUserConfiguration(Bind.class)
                .withInitializer(ctx -> ctx.getEnvironment().getPropertySources().addFirst(
                        new SystemEnvironmentPropertySource(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, vars)))
                .run(ctx -> {
                    Config c = ctx.getBean(Config.class);
                    assertThat(c.getCyodaHost()).isEqualTo("tenant.eu.cyoda.net");
                    assertThat(c.getGrpcServerPort()).isEqualTo(8443);
                    assertThat(c.getTrustedHosts()).containsExactly("a.example.com");
                    assertThat(c.getKeepAliveWarningThreshold()).isEqualTo(60_000L);
                    assertThat(c.getGrpcProcessorTag()).isEqualTo("cyoda_application");
                    assertThat(c.getCyodaClientSecret()).isEqualTo("from-secret-key-ref");
                });
    }
}
