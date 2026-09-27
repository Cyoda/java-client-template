package com.java_template.common.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.java_template.common.dto.PageResult;
import com.java_template.common.repository.SearchAndRetrievalParams;
import com.java_template.common.service.EntityService;
import com.java_template.common.workflow.CyodaEntity;
import com.java_template.common.workflow.OperationSpecification;
import org.cyoda.cloud.api.event.common.ModelSpec;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** ABOUTME: EntityCrudOperations hands the REST point in time to EntityService without truncating it. */
class EntityCrudOperationsPointInTimeTest {

    private static final OffsetDateTime NANO_PIT = OffsetDateTime.parse("2026-09-27T10:11:12.123456789Z");

    static class Thing implements CyodaEntity {
        @Override
        public OperationSpecification getModelKey() {
            return new OperationSpecification.Entity(new ModelSpec().withName("thing").withVersion(1), "thing");
        }
    }

    private final EntityService entityService = mock(EntityService.class);
    private final EntityCrudOperations<Thing> ops = new EntityCrudOperations<>(
            entityService, new ObjectMapper(), LoggerFactory.getLogger(getClass()),
            "thing", 1, Thing.class, "thingId");

    @Test
    void getByIdPassesThePointInTimeUnchanged() {
        UUID id = UUID.randomUUID();

        ops.getById(id, NANO_PIT);

        verify(entityService).getById(eq(id), any(ModelSpec.class), eq(Thing.class), same(NANO_PIT));
    }

    @Test
    void getByBusinessIdPassesThePointInTimeUnchanged() {
        ops.getByBusinessId("T-1", NANO_PIT);

        verify(entityService).findByBusinessId(any(ModelSpec.class), eq("T-1"), eq("thingId"), eq(Thing.class), same(NANO_PIT));
    }

    @Test
    void getChangesMetadataPassesThePointInTimeUnchanged() {
        UUID id = UUID.randomUUID();

        ops.getChangesMetadata(id, NANO_PIT);

        verify(entityService).getEntityChangesMetadata(eq(id), same(NANO_PIT));
    }

    @Test
    void listAndSearchPassThePointInTimeUnchanged() {
        when(entityService.findAll(any(), eq(Thing.class), any(SearchAndRetrievalParams.class)))
                .thenReturn(PageResult.of(null, List.of(), 0, 10, 0L));
        when(entityService.search(any(), any(), eq(Thing.class), any(SearchAndRetrievalParams.class)))
                .thenReturn(PageResult.of(null, List.of(), 0, 10, 0L));

        ops.list(10, 0, null, List.of(), null, NANO_PIT);
        ops.search(10, 0, null, "name", "x", NANO_PIT);

        verify(entityService).findAll(any(), eq(Thing.class), argThat(p -> NANO_PIT.equals(p.pointInTime())));
        verify(entityService).search(any(), any(), eq(Thing.class), argThat(p -> NANO_PIT.equals(p.pointInTime())));
    }
}
