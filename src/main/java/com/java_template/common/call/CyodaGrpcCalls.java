package com.java_template.common.call;

import com.java_template.common.auth.CyodaTokenSource;
import com.java_template.common.exception.CyodaErrors;
import com.java_template.common.exception.CyodaRetryableException;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;

import java.util.function.Supplier;

/** ABOUTME: Retry rules for one Cyoda call (spec §4.2, §4.4). */
public final class CyodaGrpcCalls {

    private static final long[] BACKOFF_MS = {50, 100, 200};

    private CyodaGrpcCalls() {
    }

    /**
     * Runs {@code call}, retrying once on gRPC {@code UNAUTHENTICATED} when the context's credential is M2M
     * (after invalidating the cached token), and up to {@code BACKOFF_MS.length} times, with backoff, when it
     * throws a {@link CyodaRetryableException} whose code is in {@link CyodaErrors#JOINED_RETRYABLE}.
     * A {@code FORWARD} credential is never retried on {@code UNAUTHENTICATED}: the failure surfaces to the
     * caller (spec §4.2, "Rejected-token retry").
     */
    public static <T> T call(CyodaCallContext ctx, CyodaTokenSource tokens, Supplier<T> call) {
        for (int attempt = 0; ; attempt++) {
            try {
                return withM2mRetry(ctx, tokens, call);
            } catch (CyodaRetryableException e) {
                if (!CyodaErrors.JOINED_RETRYABLE.contains(e.getErrorCode()) || attempt >= BACKOFF_MS.length) {
                    throw e;
                }
                sleep(BACKOFF_MS[attempt]);
            }
        }
    }

    private static <T> T withM2mRetry(CyodaCallContext ctx, CyodaTokenSource tokens, Supplier<T> call) {
        try {
            return call.get();
        } catch (StatusRuntimeException e) {
            if (e.getStatus().getCode() == Status.Code.UNAUTHENTICATED && ctx.credential() instanceof CyodaCallContext.M2m) {
                tokens.invalidate();
                return call.get();
            }
            throw e;
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
