package com.java_template.testing.cyoda;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WorkflowTemplatingTest {

    private final ObjectMapper om = new ObjectMapper();

    @Test
    void replacesEveryProcessorAndFunctionTag() throws Exception {
        ObjectNode wf = (ObjectNode) om.readTree("""
                {"name":"w","states":{"a":{"transitions":[
                  {"name":"t","next":"b",
                   "processors":[{"name":"P","config":{"calculationNodesTags":"${tag}"}}],
                   "criterion":{"type":"function","function":{"name":"C","config":{"calculationNodesTags":"old"}}}}]}}}""");

        ObjectNode out = WorkflowTemplating.applyTag(wf, "it-1234");

        assertThat(out.at("/states/a/transitions/0/processors/0/config/calculationNodesTags").asText()).isEqualTo("it-1234");
        assertThat(out.at("/states/a/transitions/0/criterion/function/config/calculationNodesTags").asText()).isEqualTo("it-1234");
        assertThat(wf.at("/states/a/transitions/0/processors/0/config/calculationNodesTags").asText()).isEqualTo("${tag}");
    }

    @Test
    void refusesAMissingOrEmptyTag() throws Exception {
        ObjectNode missing = (ObjectNode) om.readTree("""
                {"name":"w","states":{"a":{"transitions":[{"name":"t","next":"b","processors":[{"name":"P"}]}]}}}""");
        ObjectNode empty = (ObjectNode) om.readTree("""
                {"name":"w","states":{"a":{"transitions":[{"name":"t","next":"b",
                  "processors":[{"name":"P","config":{"calculationNodesTags":""}}]}]}}}""");

        assertThatThrownBy(() -> WorkflowTemplating.applyTag(missing, "t"))
                .hasMessageContaining("'P'").hasMessageContaining("calculationNodesTags");
        assertThatThrownBy(() -> WorkflowTemplating.applyTag(empty, "t"))
                .hasMessageContaining("matches every member");
    }
}
