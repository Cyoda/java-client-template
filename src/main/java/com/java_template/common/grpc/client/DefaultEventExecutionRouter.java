package com.java_template.common.grpc.client;

import org.cyoda.cloud.api.event.common.CloudEventType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * ABOUTME: Default implementation of EventExecutionRouter that routes events to
 * appropriate thread pools based on CloudEventType:
 * - ENTITY_PROCESSOR_CALCULATION_REQUEST → processor pool (heavy, long-running)
 * - ENTITY_CRITERIA_CALCULATION_REQUEST → criteria pool (medium weight)
 * - All other events → control pool (lightweight, must be fast)
 */
public class DefaultEventExecutionRouter implements EventExecutionRouter, AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(DefaultEventExecutionRouter.class);

    private final CalculationExecutionStrategy processorExecutor;
    private final CalculationExecutionStrategy criteriaExecutor;
    private final CalculationExecutionStrategy controlExecutor;

    public DefaultEventExecutionRouter(
            CalculationExecutionStrategy processorExecutor,
            CalculationExecutionStrategy criteriaExecutor,
            CalculationExecutionStrategy controlExecutor
    ) {
        this.processorExecutor = processorExecutor;
        this.criteriaExecutor = criteriaExecutor;
        this.controlExecutor = controlExecutor;
        log.info("Initialized DefaultEventExecutionRouter with separate thread pools for processor, criteria, and control events");
    }

    @Override
    public void routeAndExecute(CloudEventType eventType, Runnable task) {
        switch (eventType) {
            case ENTITY_PROCESSOR_CALCULATION_REQUEST:
                log.debug("Routing {} to processor thread pool", eventType);
                processorExecutor.run(task);
                break;

            case ENTITY_CRITERIA_CALCULATION_REQUEST:
                log.debug("Routing {} to criteria thread pool", eventType);
                criteriaExecutor.run(task);
                break;

            default:
                // All other events (keep-alive, ACK, greet, etc.) go to control pool
                log.debug("Routing {} to control thread pool", eventType);
                controlExecutor.run(task);
                break;
        }
    }

    /**
     * Shuts down all three executors so Spring context shutdown cannot leave background
     * threads running; called automatically by Spring's inferred destroy method on context close.
     */
    @Override
    public void close() {
        log.info("Closing DefaultEventExecutionRouter, shutting down all executors");
        for (CalculationExecutionStrategy e : new CalculationExecutionStrategy[]{processorExecutor, criteriaExecutor, controlExecutor}) {
            if (e instanceof ProcessorThreadExecutor p) p.shutdown();
            if (e instanceof CriteriaThreadExecutor c) c.shutdown();
            if (e instanceof ControlThreadExecutor k) k.shutdown();
        }
    }
}

