package com.java_template.testing.cyoda;

import org.springframework.boot.test.util.TestPropertyValues;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.test.context.ContextConfigurationAttributes;
import org.springframework.test.context.ContextCustomizer;
import org.springframework.test.context.ContextCustomizerFactory;
import org.springframework.test.context.MergedContextConfiguration;
import org.springframework.test.context.TestContextAnnotationUtils;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Points the Spring context at the profile's cyoda server (registered in META-INF/spring.factories). */
public class CyodaContextCustomizerFactory implements ContextCustomizerFactory {

    @Override
    public ContextCustomizer createContextCustomizer(Class<?> testClass, List<ContextConfigurationAttributes> attrs) {
        CyodaIntegrationTest ann = TestContextAnnotationUtils.findMergedAnnotation(testClass, CyodaIntegrationTest.class);
        return ann == null ? null : new Customizer(ann.profile());
    }

    static final class Customizer implements ContextCustomizer {
        private final String profile;

        Customizer(String profile) {
            this.profile = profile;
        }

        @Override
        public void customizeContext(ConfigurableApplicationContext context, MergedContextConfiguration merged) {
            CyodaServer server = CyodaTestEnvironment.server(CyodaProfiles.byName(profile));
            String tag = "it-" + UUID.randomUUID().toString().substring(0, 8);
            TestPropertyValues.of(
                    "app.config.cyoda-api-url=" + server.apiUrl(),
                    "app.config.grpc-address=" + server.grpcHost(),
                    "app.config.grpc-server-port=" + server.grpcPort(),
                    "app.config.grpc-tls=false",
                    "app.config.auth-mode=none",
                    "app.config.grpc-processor-tag=" + tag,
                    "server.port=0"
            ).applyTo(context);
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Customizer c && c.profile.equals(profile);
        }

        @Override
        public int hashCode() {
            return Objects.hash(profile);
        }
    }
}
