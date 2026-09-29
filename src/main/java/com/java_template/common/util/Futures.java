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
            throw withCallerStack(unwrap(e));
        }
    }

    /**
     * Adds a {@link CallerStack}, created here on the joining thread, as a suppressed exception: the unwrapped
     * exception's own trace is the failing (often async) thread's, and would otherwise lose the caller's frames.
     */
    private static RuntimeException withCallerStack(RuntimeException e) {
        e.addSuppressed(new CallerStack());
        return e;
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
            error.addSuppressed(new CallerStack());
            throw error;
        }
        return new RuntimeException(cause.getMessage(), cause);
    }

    /**
     * Marker added as a suppressed exception to what {@link #joinUnwrapped} rethrows: created on the joining
     * thread, its stack trace shows where the caller waited, which the async exception's own trace (from the
     * thread that failed) does not.
     */
    public static final class CallerStack extends RuntimeException {
        CallerStack() {
            super("caller stack");
        }
    }
}
