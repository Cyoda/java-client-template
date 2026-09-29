package com.java_template.testing.cyoda;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtensionContext;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** A failing integration test carries the tail of its cyoda server's log (spec §6.1). */
class CyodaServerExtensionFailureLogTest {

    @CyodaIntegrationTest(profile = CyodaProfiles.KEEPALIVE_SHORT)
    static class SomeIT {
    }

    static class NotAnnotatedIT {
    }

    private static ExtensionContext contextFor(Class<?> testClass) {
        ExtensionContext context = mock(ExtensionContext.class);
        when(context.getTestClass()).thenReturn(Optional.of(testClass));
        return context;
    }

    @Test
    void aTestFailureCarriesTheServerLogTailAndIsRethrownUnchanged() {
        Map<String, String> tails = Map.of(CyodaProfiles.KEEPALIVE_SHORT, "line 1\nline 2 ERROR something broke");
        CyodaServerExtension extension = new CyodaServerExtension(
                (profile, lines) -> {
                    assertThat(lines).isEqualTo(50);
                    return Optional.ofNullable(tails.get(profile));
                });
        AssertionError failure = new AssertionError("expected 1 but was 2");

        assertThatThrownBy(() -> extension.handleTestExecutionException(contextFor(SomeIT.class), failure))
                .isSameAs(failure);
        assertThat(failure.getSuppressed()).hasSize(1);
        assertThat(failure.getSuppressed()[0].getMessage())
                .contains("last 50 lines")
                .contains(CyodaProfiles.KEEPALIVE_SHORT)
                .contains("line 2 ERROR something broke");
    }

    @Test
    void aLifecycleFailureCarriesItToo() {
        CyodaServerExtension extension = new CyodaServerExtension((profile, lines) -> Optional.of("tail"));
        IllegalStateException failure = new IllegalStateException("setup failed");

        assertThatThrownBy(() -> extension.handleBeforeEachMethodExecutionException(contextFor(SomeIT.class), failure))
                .isSameAs(failure);
        assertThat(failure.getSuppressed()).hasSize(1);
    }

    @Test
    void noServerStartedMeansNothingIsAttached() {
        CyodaServerExtension extension = new CyodaServerExtension((profile, lines) -> Optional.empty());
        AssertionError failure = new AssertionError("boom");

        assertThatThrownBy(() -> extension.handleTestExecutionException(contextFor(SomeIT.class), failure))
                .isSameAs(failure);
        assertThat(failure.getSuppressed()).isEmpty();
    }

    @Test
    void aClassWithoutTheAnnotationUsesTheDefaultProfile() {
        CyodaServerExtension extension = new CyodaServerExtension(
                (profile, lines) -> Optional.of("profile=" + profile));
        AssertionError failure = new AssertionError("boom");

        assertThatThrownBy(() -> extension.handleTestExecutionException(contextFor(NotAnnotatedIT.class), failure))
                .isSameAs(failure);
        assertThat(failure.getSuppressed()[0].getMessage()).contains("profile=" + CyodaProfiles.DEFAULT);
    }
}
