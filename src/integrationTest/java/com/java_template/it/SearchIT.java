package com.java_template.it;

import com.fasterxml.jackson.databind.node.IntNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.TextNode;
import com.java_template.common.config.Config;
import com.java_template.common.dto.EntityWithMetadata;
import com.java_template.common.dto.PageResult;
import com.java_template.common.grpc.client.monitoring.ConnectionStateTracker;
import com.java_template.common.repository.SearchAndRetrievalParams;
import com.java_template.common.service.EntityService;
import com.java_template.it.support.ItThing;
import com.java_template.testing.cyoda.*;
import org.cyoda.cloud.api.common.model.*;
import org.cyoda.cloud.api.event.common.ModelSpec;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

@CyodaIntegrationTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SearchIT {

    @Autowired EntityService entityService;
    @Autowired Config config;
    @Autowired ConnectionStateTracker tracker;

    private ModelSpec spec;
    private String model;

    @BeforeAll
    void seed() {
        CyodaAwait.memberReady(tracker, Duration.ofSeconds(20));
        model = "search_it_" + UUID.randomUUID().toString().substring(0, 8);
        spec = new ModelSpec().withName(model).withVersion(1);
        CyodaModelSetup.createModel(CyodaTestEnvironment.rest(Profile.mockMemory()), model, 1, ItThing.sampleData(),
                WorkflowTemplating.load("/it-workflows/thing-workflow.json", config.getGrpcProcessorTag()));
        List<ItThing> things = new ArrayList<>();
        IntStream.rangeClosed(1, 5).forEach(i -> {
            ItThing t = ItThing.of(model, "n" + i, i);
            t.setTags(List.of(i % 2 == 0 ? "even" : "odd", "x"));
            things.add(t);
        });
        entityService.save(things);
    }

    private SearchAndRetrievalParams direct() {
        return SearchAndRetrievalParams.builder().inMemory(true).pageSize(1000).build();
    }

    private List<Integer> amounts(PageResult<EntityWithMetadata<ItThing>> page) {
        return page.data().stream().map(e -> e.entity().getAmount()).sorted().toList();
    }

    private static GroupConditionDto and(GroupConditionDtoAllOfConditions... conditions) {
        return new GroupConditionDto().operator(GroupConditionDto.OperatorEnum.AND).conditions(List.of(conditions));
    }

    @Test
    void simpleCondition() {
        var gt3 = new SimpleConditionDto().jsonPath("$.amount")
                .operatorType(SimpleConditionDto.OperatorTypeEnum.GREATER_THAN).value(IntNode.valueOf(3));

        assertThat(amounts(entityService.search(spec, and(gt3), ItThing.class, direct()))).containsExactly(4, 5);
    }

    @Test
    void nestedGroupWithOr() {
        var one = new SimpleConditionDto().jsonPath("$.amount")
                .operatorType(SimpleConditionDto.OperatorTypeEnum.EQUALS).value(IntNode.valueOf(1));
        var five = new SimpleConditionDto().jsonPath("$.amount")
                .operatorType(SimpleConditionDto.OperatorTypeEnum.EQUALS).value(IntNode.valueOf(5));
        var or = new GroupConditionDto().operator(GroupConditionDto.OperatorEnum.OR).conditions(List.of(one, five));

        assertThat(amounts(entityService.search(spec, and(or), ItThing.class, direct()))).containsExactly(1, 5);
    }

    @Test
    void lifecycleCondition() {
        var isNew = new LifecycleConditionDto().field("state")
                .operatorType(LifecycleConditionDto.OperatorTypeEnum.EQUALS).value(TextNode.valueOf("new"));

        assertThat(entityService.search(spec, and(isNew), ItThing.class, direct()).data()).hasSize(5);
    }

    @Test
    void arrayConditionWithPositionalValues() {
        var evenFirst = new ArrayConditionDto().jsonPath("$.tags[*]")
                .values(List.of(TextNode.valueOf("even"), JsonNodeFactory.instance.nullNode()));

        assertThat(amounts(entityService.search(spec, and(evenFirst), ItThing.class, direct()))).containsExactly(2, 4);
    }

    @Test
    void pagedSnapshotSearchAcrossThreePages() {
        String paged = model + "_paged";
        CyodaModelSetup.createModel(CyodaTestEnvironment.rest(Profile.mockMemory()), paged, 1, ItThing.sampleData(),
                WorkflowTemplating.load("/it-workflows/thing-workflow.json", config.getGrpcProcessorTag()));
        entityService.save(IntStream.range(0, 250).mapToObj(i -> ItThing.of(paged, "p" + i, i)).toList());
        ModelSpec pagedSpec = new ModelSpec().withName(paged).withVersion(1);

        PageResult<EntityWithMetadata<ItThing>> first = entityService.search(pagedSpec, and(), ItThing.class,
                SearchAndRetrievalParams.builder().pageSize(100).pageNumber(0).build());
        PageResult<EntityWithMetadata<ItThing>> second = entityService.search(pagedSpec, and(), ItThing.class,
                SearchAndRetrievalParams.builder().pageSize(100).pageNumber(1).searchId(first.searchId()).build());
        PageResult<EntityWithMetadata<ItThing>> third = entityService.search(pagedSpec, and(), ItThing.class,
                SearchAndRetrievalParams.builder().pageSize(100).pageNumber(2).searchId(first.searchId()).build());

        assertThat(first.totalElements()).isEqualTo(250);
        assertThat(first.data()).hasSize(100);
        assertThat(second.data()).hasSize(100);
        assertThat(third.data()).hasSize(50);
    }

    @Test
    void pointInTimeSeesThePastValue() throws Exception {
        // Owns its own model (mirrors the class's own seed() setup) so this test's writes never
        // touch the shared `model`/`spec` that the other tests assert exact contents against.
        String pitModel = model + "_pit";
        ModelSpec pitSpec = new ModelSpec().withName(pitModel).withVersion(1);
        CyodaModelSetup.createModel(CyodaTestEnvironment.rest(Profile.mockMemory()), pitModel, 1, ItThing.sampleData(),
                WorkflowTemplating.load("/it-workflows/thing-workflow.json", config.getGrpcProcessorTag()));

        EntityWithMetadata<ItThing> created = entityService.create(ItThing.of(pitModel, "pit", 7));
        Thread.sleep(50);
        Date before = new Date();
        Thread.sleep(50);
        ItThing changed = created.entity().in(pitModel);
        changed.setName("pit-changed");
        entityService.update(created.getId(), changed, null);

        EntityWithMetadata<ItThing> past = entityService.getById(created.getId(), pitSpec, ItThing.class, before);

        assertThat(past.entity().getName()).isEqualTo("pit");
    }
}
