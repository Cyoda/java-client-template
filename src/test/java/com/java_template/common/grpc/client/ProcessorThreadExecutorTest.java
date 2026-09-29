package com.java_template.common.grpc.client;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class ProcessorThreadExecutorTest {

    @Test
    void virtualModeIsNotBoundedByThePoolSize() throws Exception {
        ProcessorThreadExecutor executor = new ProcessorThreadExecutor(true, 20);
        int tasks = 100;
        CountDownLatch allStarted = new CountDownLatch(tasks);
        CountDownLatch release = new CountDownLatch(1);
        for (int i = 0; i < tasks; i++) {
            executor.run(() -> {
                allStarted.countDown();
                try {
                    release.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }

        boolean started = allStarted.await(5, TimeUnit.SECONDS);
        release.countDown();
        executor.shutdown();

        assertThat(started).as("100 blocked tasks all started, so nested cascades cannot starve").isTrue();
    }
}
