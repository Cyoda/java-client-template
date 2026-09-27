package com.java_template.it;

import com.java_template.common.config.Config;
import com.java_template.common.dto.EntityWithMetadata;
import com.java_template.common.grpc.client.monitoring.ConnectionStateTracker;
import com.java_template.common.repository.SearchAndRetrievalParams;
import com.java_template.common.service.EntityService;
import com.java_template.it.support.ItCascadeProcessor;
import com.java_template.it.support.ItThing;
import com.java_template.testing.cyoda.*;
import org.cyoda.cloud.api.common.model.GroupConditionDto;
import org.cyoda.cloud.api.event.common.ModelSpec;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/** Regression for spec §4.5: nested + concurrent cascades must not exhaust a thread pool. */
@CyodaIntegrationTest
class CascadeConcurrencyIT {

    private static final int CHAINS = 16;
    private static final int DEPTH = 3;

    @Autowired EntityService entityService;
    @Autowired Config config;
    @Autowired ConnectionStateTracker tracker;
    @Autowired ItCascadeProcessor cascade;

    @Test
    void sixteenConcurrentThreeLevelCascadesAllComplete() throws Exception {
        CyodaAwait.memberReady(tracker, Duration.ofSeconds(20));
        String model = "conc_it_" + UUID.randomUUID().toString().substring(0, 8);
        CyodaModelSetup.createModel(CyodaTestEnvironment.rest(Profile.mockMemory()), model, 1, ItThing.sampleData(),
                WorkflowTemplating.load("/it-workflows/thing-workflow.json", config.getGrpcProcessorTag()));
        List<EntityWithMetadata<ItThing>> parents = entityService.save(
                IntStream.range(0, CHAINS).mapToObj(i -> ItThing.of(model, "c" + i, DEPTH)).toList());

        // Why the latch: each leaf (amount 0) waits until all CHAINS leaves have arrived, so at that moment every
        // level of every chain holds a callout thread: CHAINS × (DEPTH + 1) = 64 held at once. A bounded pool of 20
        // (the pre-§4.5 executor) cannot run the missing leaves, so the barrier times out and the test fails for
        // certain rather than only when the scheduling happens to overlap. One virtual thread per task passes.
        cascade.leafBarriers.put(model, new CountDownLatch(CHAINS));
        try (ExecutorService callers = Executors.newVirtualThreadPerTaskExecutor()) {
            List<CompletableFuture<EntityWithMetadata<ItThing>>> runs = parents.stream()
                    .map(p -> CompletableFuture.supplyAsync(
                            () -> entityService.update(p.getId(), p.entity().in(model), "cascade"), callers))
                    .toList();

            CompletableFuture.allOf(runs.toArray(CompletableFuture[]::new)).get(60, TimeUnit.SECONDS);

            assertThat(runs).allSatisfy(r -> assertThat(r.join().getState()).isEqualTo("cascaded"));
        } finally {
            cascade.leafBarriers.remove(model);
        }

        // Every level committed: CHAINS roots, each with DEPTH nested descendants (amount 3 -> 2 -> 1 -> 0).
        List<EntityWithMetadata<ItThing>> all = entityService.search(new ModelSpec().withName(model).withVersion(1),
                new GroupConditionDto().operator(GroupConditionDto.OperatorEnum.AND).conditions(List.of()),
                ItThing.class, SearchAndRetrievalParams.builder().inMemory(true).pageSize(1000).build()).data();
        assertThat(all).hasSize(CHAINS * (DEPTH + 1)).allSatisfy(e -> assertThat(e.getState()).isEqualTo("cascaded"));
    }
}
