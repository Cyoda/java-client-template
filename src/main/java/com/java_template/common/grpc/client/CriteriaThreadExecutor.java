package com.java_template.common.grpc.client;

import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * ABOUTME: Executor for criteria calculation events using a dedicated thread pool.
 * Criteria events are medium-weight operations that evaluate workflow conditions
 * and are generally faster than processor events.
 */
public class CriteriaThreadExecutor implements CalculationExecutionStrategy {
    private static final Logger log = LoggerFactory.getLogger(CriteriaThreadExecutor.class);

    private final ExecutorService executorService;
    private final boolean useVirtualThreads;
    private final int threadPoolSize;

    public CriteriaThreadExecutor(boolean useVirtualThreads, int threadPoolSize) {
        this.useVirtualThreads = useVirtualThreads;
        this.threadPoolSize = threadPoolSize;
        if (useVirtualThreads) {
            // One virtual thread per task: a callout blocked on a nested cascade holds no scarce thread (spec §4.5).
            this.executorService = Executors.newThreadPerTaskExecutor(
                    Thread.ofVirtual().name("criteria-calculation-", 0).factory());
            log.info("Initialized CriteriaThreadExecutor with one virtual thread per task (pool size ignored)");
        } else {
            this.executorService = Executors.newFixedThreadPool(
                threadPoolSize,
                Thread.ofPlatform().name("criteria-calculation-", 0).factory()
            );
            log.info("Initialized CriteriaThreadExecutor with {} platform threads", threadPoolSize);
        }
    }

    @Override
    public void run(final Runnable task) {
        executorService.submit(task);
    }

    @PreDestroy
    public void shutdown() {
        log.info("Shutting down CriteriaThreadExecutor...");
        executorService.shutdown();
        try {
            if (!executorService.awaitTermination(5, TimeUnit.SECONDS)) {
                log.warn("CriteriaThreadExecutor did not terminate in time, forcing shutdown");
                executorService.shutdownNow();
            }
        } catch (InterruptedException e) {
            log.error("Interrupted while shutting down CriteriaThreadExecutor", e);
            executorService.shutdownNow();
            Thread.currentThread().interrupt();
        }
        log.info("CriteriaThreadExecutor shutdown complete");
    }
}

