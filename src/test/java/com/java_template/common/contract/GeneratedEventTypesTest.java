package com.java_template.common.contract;

import com.fasterxml.jackson.databind.JsonNode;
import org.cyoda.cloud.api.event.common.BaseEvent;
import org.cyoda.cloud.api.event.entity.EntityPatchRequest;
import org.cyoda.cloud.api.event.processing.EntityCriteriaCalculationResponse;
import org.cyoda.cloud.api.event.processing.EntityFunctionCalculationResponse;
import org.cyoda.cloud.api.event.search.EntitySearchRequest;
import org.cyoda.cloud.api.event.search.EntitySnapshotSearchRequest;
import org.cyoda.cloud.api.event.search.OrderBy;
import org.junit.jupiter.api.Test;

import java.lang.reflect.ParameterizedType;

import static org.assertj.core.api.Assertions.assertThat;

class GeneratedEventTypesTest {

    @Test
    void responsesStillExtendBaseEvent() {
        assertThat(BaseEvent.class).isAssignableFrom(EntityCriteriaCalculationResponse.class);
        assertThat(BaseEvent.class).isAssignableFrom(EntityPatchRequest.class);
    }

    @Test
    void functionResultIsAJsonNode() throws Exception {
        assertThat(EntityFunctionCalculationResponse.class.getMethod("getResult").getReturnType()).isEqualTo(JsonNode.class);
    }

    @Test
    void orderByIsOneSharedClass() throws Exception {
        for (Class<?> c : new Class<?>[]{EntitySearchRequest.class, EntitySnapshotSearchRequest.class}) {
            ParameterizedType t = (ParameterizedType) c.getMethod("getOrderBy").getGenericReturnType();
            assertThat(t.getActualTypeArguments()[0]).isEqualTo(OrderBy.class);
        }
    }
}
