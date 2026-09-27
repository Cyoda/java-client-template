package com.java_template.common.util;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/**
 * ABOUTME: Joins a future at a public service boundary and surfaces its failure as the typed exception the
 * call raised (spec §4.4), e.g. CyodaCalloutEndedException or CyodaRetryableException, rather than the
 * CompletionException that {@link CompletableFuture#join()} wraps it in.
 */
public final class Futures {

    private Futures() {
    }

    /**
     * {@link CompletableFuture#join()}, with any CompletionException unwrapped: a RuntimeException cause is
     * rethrown as-is, an Error cause as-is, and a checked cause wrapped in a RuntimeException.
     */
    public static <T> T joinUnwrapped(CompletableFuture<T> future) {
        try {
            return future.join();
        } catch (CompletionException e) {
            throw unwrap(e);
        }
    }

    static RuntimeException unwrap(CompletionException e) {
        Throwable cause = e;
        while (cause instanceof CompletionException && cause.getCause() != null) {
            cause = cause.getCause();
        }
        if (cause instanceof RuntimeException runtime) {
            return runtime;
        }
        if (cause instanceof Error error) {
            throw error;
        }
        return new RuntimeException(cause.getMessage(), cause);
    }
}
