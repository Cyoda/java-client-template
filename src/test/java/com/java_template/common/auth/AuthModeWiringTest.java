package com.java_template.common.auth;

import com.java_template.common.config.Config;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import static org.assertj.core.api.Assertions.assertThat;

class AuthModeWiringTest {

    @Configuration
    @EnableConfigurationProperties(Config.class)
    @Import({Authentication.class, NoCyodaAuthentication.class})
    static class Wiring {
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner().withUserConfiguration(Wiring.class);

    @Test
    void noneModeCreatesNoClientRegistrationAndSendsNoToken() {
        runner.withPropertyValues("app.config.auth-mode=none").run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertThat(ctx).doesNotHaveBean(Authentication.class);
            assertThat(ctx.getBean(CyodaTokenSource.class).bearerToken()).isEmpty();
        });
    }

    @Test
    void clientCredentialsModeFailsFastOnMissingCredentials() {
        runner.withPropertyValues("app.config.cyoda-api-url=http://localhost:1/api", "app.config.cyoda-client-id=")
                .run(ctx -> assertThat(ctx).hasFailed()
                        .getFailure().rootCause()
                        .hasMessageContaining("app.config.cyoda-client-id")
                        .hasMessageContaining("app.config.cyoda-client-secret"));
    }

    @Test
    void clientCredentialsModeWithCredentialsProvidesTheM2mSource() {
        runner.withPropertyValues("app.config.cyoda-api-url=http://localhost:1/api",
                        "app.config.cyoda-client-id=id", "app.config.cyoda-client-secret=secret")
                .run(ctx -> assertThat(ctx.getBean(CyodaTokenSource.class)).isInstanceOf(Authentication.class));
    }
}
