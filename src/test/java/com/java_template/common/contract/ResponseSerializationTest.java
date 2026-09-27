package com.java_template.common.contract;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.cyoda.cloud.api.event.common.Error;
import org.cyoda.cloud.api.event.processing.EntityCriteriaCalculationResponse;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ResponseSerializationTest {

    private final ObjectMapper om = new ObjectMapper();

    @Test
    void aSuccessfulAnswerOmitsErrorInsteadOfSendingNull() throws Exception {
        EntityCriteriaCalculationResponse r = new EntityCriteriaCalculationResponse();
        r.setId("id-1");
        r.setRequestId("r-1");
        r.setEntityId(UUID.randomUUID());
        r.setSuccess(true);
        r.setMatches(true);

        JsonNode json = om.readTree(om.writeValueAsString(r));

        assertThat(json.has("error")).isFalse();
        assertThat(json.get("success").asBoolean()).isTrue();
    }

    @Test
    void aFailureSendsSuccessFalseExplicitly() throws Exception {
        EntityCriteriaCalculationResponse r = new EntityCriteriaCalculationResponse();
        r.setId("id-1");
        r.setRequestId("r-1");
        r.setEntityId(UUID.randomUUID());
        r.setSuccess(false);
        r.setMatches(false);
        Error e = new Error();
        e.setCode("GENERAL_ERROR");
        e.setMessage("boom");
        r.setError(e);

        JsonNode json = om.readTree(om.writeValueAsString(r));

        assertThat(json.get("success").isBoolean()).isTrue();
        assertThat(json.get("success").asBoolean()).isFalse();
        assertThat(json.get("matches").asBoolean()).isFalse();
    }
}
