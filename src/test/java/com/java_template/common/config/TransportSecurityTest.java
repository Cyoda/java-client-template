package com.java_template.common.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ABOUTME: With auth-mode=client-credentials the client secret and the M2M token must not cross the network in
 * plaintext: startup fails on an http token URI or grpc-tls=false, unless the host is loopback or
 * app.config.allow-insecure-transport=true. auth-mode=none logs one warning.
 */
@ExtendWith(OutputCaptureExtension.class)
class TransportSecurityTest {

    @Configuration
    @EnableConfigurationProperties(Config.class)
    static class Bind {
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner().withUserConfiguration(Bind.class);

    @Test
    void anHttpTokenUriToARemoteHostFailsStartup() {
        runner.withPropertyValues("app.config.cyoda-api-url=http://cyoda.example.com/api",
                        "app.config.grpc-address=cyoda.example.com")
                .run(ctx -> assertThat(ctx).hasFailed().getFailure().rootCause()
                        .hasMessageContaining("app.config.cyoda-api-url")
                        .hasMessageContaining("http://cyoda.example.com/api/oauth/token")
                        .hasMessageContaining("app.config.allow-insecure-transport"));
    }

    @Test
    void plaintextGrpcToARemoteHostFailsStartup() {
        runner.withPropertyValues("app.config.cyoda-api-url=https://cyoda.example.com/api",
                        "app.config.grpc-address=grpc-cyoda.example.com", "app.config.grpc-tls=false")
                .run(ctx -> assertThat(ctx).hasFailed().getFailure().rootCause()
                        .hasMessageContaining("app.config.grpc-tls")
                        .hasMessageContaining("grpc-cyoda.example.com")
                        .hasMessageContaining("app.config.allow-insecure-transport"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"localhost", "127.0.0.1", "127.10.20.30", "[::1]"})
    void plaintextToALoopbackHostIsAllowed(String host) {
        String grpcHost = host.startsWith("[") ? host.substring(1, host.length() - 1) : host;
        runner.withPropertyValues("app.config.cyoda-api-url=http://" + host + ":8080/api",
                        "app.config.grpc-address=" + grpcHost, "app.config.grpc-tls=false")
                .run(ctx -> assertThat(ctx).hasNotFailed());
    }

    @Test
    void aLookalikeOfLoopbackIsNotLoopback() {
        runner.withPropertyValues("app.config.cyoda-api-url=http://127.0.0.1.example.com/api",
                        "app.config.grpc-address=localhost.example.com", "app.config.grpc-tls=false")
                .run(ctx -> assertThat(ctx).hasFailed());
    }

    @Test
    void allowInsecureTransportPermitsPlaintext() {
        runner.withPropertyValues("app.config.cyoda-api-url=http://cyoda.internal/api",
                        "app.config.grpc-address=cyoda.internal", "app.config.grpc-tls=false",
                        "app.config.allow-insecure-transport=true")
                .run(ctx -> assertThat(ctx).hasNotFailed());
    }

    @Test
    void httpsAndTlsToARemoteHostStart() {
        runner.withPropertyValues("app.config.cyoda-api-url=https://cyoda.example.com/api",
                        "app.config.grpc-address=grpc-cyoda.example.com")
                .run(ctx -> assertThat(ctx).hasNotFailed());
    }

    @Test
    void authModeNoneStartsOnPlaintextAndWarnsOnce(CapturedOutput output) {
        runner.withPropertyValues("app.config.auth-mode=none", "app.config.cyoda-api-url=http://cyoda.example.com/api",
                        "app.config.grpc-address=cyoda.example.com", "app.config.grpc-tls=false")
                .run(ctx -> assertThat(ctx).hasNotFailed());

        assertThat(output.getOut().split("app.config.auth-mode=none", -1)).hasSize(2);
        assertThat(output.getOut()).contains("WARN");
    }

    @Test
    void clientCredentialsDoesNotWarnAboutAuthModeNone(CapturedOutput output) {
        runner.withPropertyValues("app.config.cyoda-api-url=https://cyoda.example.com/api",
                        "app.config.grpc-address=grpc-cyoda.example.com")
                .run(ctx -> assertThat(ctx).hasNotFailed());

        assertThat(output.getOut()).doesNotContain("app.config.auth-mode=none");
    }
}
