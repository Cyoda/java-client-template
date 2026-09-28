package com.java_template.common.grpc.client.connection;

import io.grpc.ConnectivityState;
import io.grpc.ManagedChannel;
import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ABOUTME: A wait that begins in TRANSIENT_FAILURE resets gRPC's reconnect backoff (spec §4.5), but a burst of
 * calls during an outage must not call resetConnectBackoff on every single one of them: that would defeat gRPC's
 * own backoff just as effectively as never resetting it, hammering a struggling Cyoda with reconnect attempts.
 * It is rate-limited to at most once per 500 ms.
 */
class ChannelReadinessTest {

    private ManagedChannel mockChannelThatBecomesReady() {
        ManagedChannel channel = mock(ManagedChannel.class);
        when(channel.getState(true)).thenReturn(
                ConnectivityState.TRANSIENT_FAILURE, ConnectivityState.READY,
                ConnectivityState.TRANSIENT_FAILURE, ConnectivityState.READY);
        // Simulates an instant state-change notification, so the wait loop re-reads getState() right away.
        doAnswer(inv -> {
            Runnable onChange = inv.getArgument(1);
            onChange.run();
            return null;
        }).when(channel).notifyWhenStateChanged(eq(ConnectivityState.TRANSIENT_FAILURE), any());
        return channel;
    }

    @Test
    void aSingleWaitStartingInTransientFailureResetsTheBackoffOnce() {
        ManagedChannel channel = mockChannelThatBecomesReady();
        ChannelReadiness readiness = new ChannelReadiness(channel);

        readiness.awaitReady(1_000);

        verify(channel, times(1)).resetConnectBackoff();
    }

    @Test
    void aBurstOfWaitsWithinTheWindowResetsTheBackoffOnlyOnce() {
        ManagedChannel channel = mockChannelThatBecomesReady();
        ChannelReadiness readiness = new ChannelReadiness(channel);

        readiness.awaitReady(1_000);
        readiness.awaitReady(1_000);

        verify(channel, times(1)).resetConnectBackoff();
    }

    @Test
    void waitsMoreThanTheWindowApartEachResetTheBackoff() throws InterruptedException {
        ManagedChannel channel = mockChannelThatBecomesReady();
        ChannelReadiness readiness = new ChannelReadiness(channel);

        readiness.awaitReady(1_000);
        TimeUnit.MILLISECONDS.sleep(600);
        readiness.awaitReady(1_000);

        verify(channel, times(2)).resetConnectBackoff();
    }
}
