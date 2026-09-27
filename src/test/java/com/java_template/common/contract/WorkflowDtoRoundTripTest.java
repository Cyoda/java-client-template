package com.java_template.common.contract;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.java_template.common.config.CyodaJackson;
import org.cyoda.cloud.api.common.model.ArrayConditionDto;
import org.cyoda.cloud.api.common.model.FunctionConditionDto;
import org.cyoda.cloud.api.common.model.GroupConditionDto;
import org.cyoda.cloud.api.common.model.WorkflowConfigurationDto;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.Comparator;

import static org.assertj.core.api.Assertions.assertThat;

class WorkflowDtoRoundTripTest {

    private final ObjectMapper om = CyodaJackson.configure(new ObjectMapper())
            .setSerializationInclusion(JsonInclude.Include.NON_NULL);

    /** Numbers compare by value (IntNode 10 == LongNode 10); everything else by equals. */
    private static final Comparator<JsonNode> NUMERIC_AWARE = (a, b) -> {
        if (a.isNumber() && b.isNumber()) {
            return a.decimalValue().compareTo(b.decimalValue());
        }
        return a.equals(b) ? 0 : 1;
    };

    @Test
    void everyConditionTypeAndProcessorRoundTrips() throws Exception {
        JsonNode input;
        try (InputStream in = getClass().getResourceAsStream("/contract/workflow-roundtrip.json")) {
            input = om.readTree(in);
        }

        WorkflowConfigurationDto dto = om.treeToValue(input, WorkflowConfigurationDto.class);
        JsonNode reserialized = om.readTree(om.writeValueAsString(dto));

        assertThat(dto.getCriterion()).isInstanceOf(GroupConditionDto.class);
        assertThat(((GroupConditionDto) dto.getCriterion()).getConditions())
                .anySatisfy(c -> assertThat(c).isInstanceOf(ArrayConditionDto.class));
        assertThat(dto.getStates().get("new").getTransitions().getFirst().getCriterion())
                .isInstanceOf(FunctionConditionDto.class);
        assertThat(reserialized.equals(NUMERIC_AWARE, input))
                .as("round trip of\n%s\nproduced\n%s", input.toPrettyString(), reserialized.toPrettyString())
                .isTrue();
    }
}
