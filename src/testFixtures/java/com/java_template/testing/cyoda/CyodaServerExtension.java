package com.java_template.testing.cyoda;

import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.platform.commons.support.AnnotationSupport;

/**
 * Starts the profile's cyoda server before the test class and stops every server when the
 * test engine finishes. Spring's cached test contexts close later (JVM shutdown), so their
 * member streams may log one connection loss after the server is gone; that is expected.
 */
public class CyodaServerExtension implements BeforeAllCallback {

    @Override
    public void beforeAll(ExtensionContext context) {
        String profile = AnnotationSupport.findAnnotation(context.getRequiredTestClass(), CyodaIntegrationTest.class)
                .map(CyodaIntegrationTest::profile)
                .orElse(CyodaProfiles.DEFAULT);
        CyodaTestEnvironment.server(CyodaProfiles.byName(profile));
        context.getRoot().getStore(ExtensionContext.Namespace.GLOBAL)
                .getOrComputeIfAbsent("cyoda-servers", k -> new Closer(), Closer.class);
    }

    static final class Closer implements ExtensionContext.Store.CloseableResource, AutoCloseable {
        @Override
        public void close() {
            CyodaTestEnvironment.closeAll();
        }
    }
}
