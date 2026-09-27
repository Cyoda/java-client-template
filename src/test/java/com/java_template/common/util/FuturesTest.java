package com.java_template.common.util;

import com.java_template.common.exception.CyodaCalloutEndedException;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FuturesTest {

    @Test
    void aValueIsReturned() {
        assertThat(Futures.joinUnwrapped(CompletableFuture.completedFuture("v"))).isEqualTo("v");
    }

    @Test
    void aRuntimeCauseIsRethrownAsIs() {
        CyodaCalloutEndedException ended = new CyodaCalloutEndedException("CALLOUT_SUPERSEDED", "superseded");

        assertThatThrownBy(() -> Futures.joinUnwrapped(CompletableFuture.failedFuture(ended))).isSameAs(ended);
    }

    @Test
    void aRuntimeCauseThrownInsideADependentStageIsRethrownAsIs() {
        IllegalStateException boom = new IllegalStateException("boom");
        CompletableFuture<Object> stage = CompletableFuture.completedFuture("x").thenApply(x -> {
            throw boom;
        });

        assertThatThrownBy(() -> Futures.joinUnwrapped(stage)).isSameAs(boom);
    }

    @Test
    void nestedCompletionExceptionsAreUnwrapped() {
        IllegalArgumentException inner = new IllegalArgumentException("inner");

        assertThatThrownBy(() -> Futures.joinUnwrapped(
                CompletableFuture.failedFuture(new CompletionException(new CompletionException(inner))))).isSameAs(inner);
    }

    @Test
    void anErrorIsRethrownAsIs() {
        AssertionError error = new AssertionError("fatal");

        assertThatThrownBy(() -> Futures.joinUnwrapped(CompletableFuture.failedFuture(error))).isSameAs(error);
    }

    @Test
    void aCheckedCauseIsWrappedInARuntimeException() {
        IOException io = new IOException("io");

        assertThatThrownBy(() -> Futures.joinUnwrapped(CompletableFuture.failedFuture(io)))
                .isExactlyInstanceOf(RuntimeException.class)
                .hasMessage("io")
                .hasCause(io);
    }
}
