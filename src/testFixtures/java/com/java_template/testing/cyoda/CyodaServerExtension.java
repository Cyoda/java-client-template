package com.java_template.testing.cyoda;

import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.LifecycleMethodExecutionExceptionHandler;
import org.junit.jupiter.api.extension.TestExecutionExceptionHandler;
import org.junit.platform.commons.support.AnnotationSupport;

import java.util.Arrays;
import java.util.Optional;

/**
 * Starts the profile's cyoda server before the test class and stops every server when the
 * test engine finishes. Spring's cached test contexts close later (JVM shutdown), so their
 * member streams may log one connection loss after the server is gone; that is expected.
 * <p>
 * When a test or one of its lifecycle methods fails, the last {@value #LOG_TAIL_LINES} lines of that
 * profile's server log are attached to the failure as a suppressed exception (spec §6.1).
 */
public class CyodaServerExtension implements BeforeAllCallback, TestExecutionExceptionHandler,
        LifecycleMethodExecutionExceptionHandler {

    static final int LOG_TAIL_LINES = 50;

    /** The tail of the named profile's server log, if that server was started in this JVM. */
    @FunctionalInterface
    interface LogTails {
        Optional<String> tail(String profileName, int lines);
    }

    private final LogTails logTails;

    public CyodaServerExtension() {
        this(CyodaTestEnvironment::logTailIfStarted);
    }

    CyodaServerExtension(LogTails logTails) {
        this.logTails = logTails;
    }

    @Override
    public void beforeAll(ExtensionContext context) {
        CyodaTestEnvironment.server(CyodaProfiles.byName(profileOf(context)));
        context.getRoot().getStore(ExtensionContext.Namespace.GLOBAL)
                .getOrComputeIfAbsent("cyoda-servers", k -> new Closer(), Closer.class);
    }

    @Override
    public void handleTestExecutionException(ExtensionContext context, Throwable throwable) throws Throwable {
        throw withServerLog(context, throwable);
    }

    @Override
    public void handleBeforeAllMethodExecutionException(ExtensionContext context, Throwable throwable) throws Throwable {
        throw withServerLog(context, throwable);
    }

    @Override
    public void handleBeforeEachMethodExecutionException(ExtensionContext context, Throwable throwable) throws Throwable {
        throw withServerLog(context, throwable);
    }

    @Override
    public void handleAfterEachMethodExecutionException(ExtensionContext context, Throwable throwable) throws Throwable {
        throw withServerLog(context, throwable);
    }

    @Override
    public void handleAfterAllMethodExecutionException(ExtensionContext context, Throwable throwable) throws Throwable {
        throw withServerLog(context, throwable);
    }

    private Throwable withServerLog(ExtensionContext context, Throwable throwable) {
        boolean attached = Arrays.stream(throwable.getSuppressed()).anyMatch(CyodaServerLog.class::isInstance);
        if (!attached) {
            String profile = profileOf(context);
            logTails.tail(profile, LOG_TAIL_LINES).ifPresent(tail -> throwable.addSuppressed(new CyodaServerLog(
                    "last " + LOG_TAIL_LINES + " lines of the cyoda '" + profile + "' server log:\n" + tail)));
        }
        return throwable;
    }

    private static String profileOf(ExtensionContext context) {
        return context.getTestClass()
                .flatMap(testClass -> AnnotationSupport.findAnnotation(testClass, CyodaIntegrationTest.class))
                .map(CyodaIntegrationTest::profile)
                .orElse(CyodaProfiles.DEFAULT);
    }

    /** Carries the server log tail on a failure; it has no stack trace of its own. */
    static final class CyodaServerLog extends RuntimeException {
        CyodaServerLog(String message) {
            super(message, null, false, false);
        }
    }

    static final class Closer implements ExtensionContext.Store.CloseableResource, AutoCloseable {
        @Override
        public void close() {
            CyodaTestEnvironment.closeAll();
        }
    }
}
