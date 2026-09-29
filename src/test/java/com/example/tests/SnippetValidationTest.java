package com.example.tests;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.java_template.common.config.CyodaJackson;
import org.cyoda.cloud.api.common.model.ExternalizedProcessorDefinitionDto;
import org.cyoda.cloud.api.common.model.TransitionDefinitionDtoCriterion;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SnippetValidationTest {

    private final ObjectMapper om = CyodaJackson.configure(new ObjectMapper())
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    private JsonNode read(String name) throws Exception {
        try (InputStream in = getClass().getResourceAsStream("/example/config/snippets/" + name)) {
            return om.readTree(in);
        }
    }

    @Test
    void everyCriterionExampleIsAValidCriterion() throws Exception {
        JsonNode snippets = read("criterion_examples.json");
        List<String> checked = new ArrayList<>();
        snippets.fields().forEachRemaining(e -> {
            JsonNode example = e.getValue().get("example");
            if (example != null) {
                try {
                    om.treeToValue(example, TransitionDefinitionDtoCriterion.class);
                } catch (Exception ex) {
                    throw new AssertionError("criterion snippet '" + e.getKey() + "' is invalid: " + ex.getMessage(), ex);
                }
                checked.add(e.getKey());
            }
        });
        assertThat(checked).contains("simple_criterion", "group_criterion", "function_criterion");
    }

    @Test
    void everyProcessorExampleIsAValidProcessor() throws Exception {
        JsonNode snippets = read("processor_examples.json");
        List<JsonNode> processors = new ArrayList<>();
        snippets.get("processor_configuration").fields().forEachRemaining(e -> {
            if (e.getValue().isObject() && e.getValue().has("name")) {
                processors.add(e.getValue());
            }
        });
        snippets.at("/processor_chain_example/processors").forEach(processors::add);

        assertThat(processors).isNotEmpty();
        for (JsonNode p : processors) {
            om.treeToValue(p, ExternalizedProcessorDefinitionDto.class);
        }
    }
}
