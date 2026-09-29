package com.java_template.testing.cyoda;

import com.java_template.common.grpc.client.monitoring.ConnectionStateTracker;
import com.java_template.common.grpc.client.monitoring.ObserverState;

import java.time.Duration;

public final class CyodaAwait {

    private CyodaAwait() {
    }

    /** Waits for the greet (ObserverState.READY) so the first transition cannot hit NO_COMPUTE_MEMBER_FOR_TAG. */
    public static void memberReady(ConnectionStateTracker tracker, Duration limit) {
        long deadline = System.nanoTime() + limit.toNanos();
        while (System.nanoTime() < deadline) {
            if (tracker.getLastObserverState() == ObserverState.READY) {
                return;
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        }
        throw new AssertionError("compute member not READY within " + limit + " (state: " + tracker.getLastObserverState() + ")");
    }
}
