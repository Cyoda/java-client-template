package com.java_template.common.grpc.client.connection;

import com.java_template.common.exception.CyodaRetryableException;
import io.grpc.ConnectivityState;
import io.grpc.ManagedChannel;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * ABOUTME: Bounded wait for the gRPC channel to Cyoda to be READY, taken before an unjoined multi-reply call
 * (spec §4.5). Such a call has no deadline, because its duration grows with the result size, and its stub waits
 * for readiness ({@code withWaitForReady}); without this bound it would wait for an unreachable Cyoda forever.
 * <p>
 * The wait blocks the calling thread on a latch released by {@link ManagedChannel#notifyWhenStateChanged}. The
 * repository calls it on its virtual-thread executor, so a waiting call holds no platform thread.
 */
public class ChannelReadiness {

    /** The error code of the {@link CyodaRetryableException} thrown when Cyoda stays unreachable. */
    public static final String UNAVAILABLE = "UNAVAILABLE";

    private final ManagedChannel channel;

    public ChannelReadiness(ManagedChannel channel) {
        this.channel = channel;
    }

    /**
     * Returns as soon as the channel is READY, asking it to connect if it is idle. Waits at most {@code boundMs}.
     *
     * @throws CyodaRetryableException code {@link #UNAVAILABLE}, when the channel is not READY within
     *                                 {@code boundMs} or is shut down
     */
    public void awaitReady(long boundMs) {
        ConnectivityState state = channel.getState(true);
        if (state == ConnectivityState.READY) {
            return;
        }
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(boundMs);
        while (state != ConnectivityState.READY) {
            if (state == ConnectivityState.SHUTDOWN) {
                throw new CyodaRetryableException(UNAVAILABLE, "Cyoda is unreachable: the gRPC channel to it is shut down");
            }
            long remaining = deadline - System.nanoTime();
            CountDownLatch changed = new CountDownLatch(1);
            channel.notifyWhenStateChanged(state, changed::countDown);
            try {
                if (remaining <= 0 || !changed.await(remaining, TimeUnit.NANOSECONDS)) {
                    throw unreachable(boundMs, state);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrupted while waiting for the connection to Cyoda", e);
            }
            state = channel.getState(true);
        }
    }

    private static CyodaRetryableException unreachable(long boundMs, ConnectivityState state) {
        return new CyodaRetryableException(UNAVAILABLE, "Cyoda was unreachable for " + boundMs
                + " ms: the gRPC channel to it did not become READY (last state " + state + ")");
    }
}
