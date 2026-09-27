package com.java_template.it;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.java_template.common.config.Config;
import com.java_template.common.config.CyodaJackson;
import com.java_template.common.tool.CyodaInit;
import com.java_template.common.tool.CyodaInitConfig;
import com.java_template.testing.cyoda.*;
import org.cyoda.cloud.api.common.model.WorkflowConfigurationDto;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The only test that touches the example application's models (ExampleEntity, OtherEntity);
 * CyodaInit derives model names from entity classes, so isolation is by ownership, not by name.
 */
@CyodaIntegrationTest
@TestPropertySource(properties = "app.config.entity-base-package=com.example.application")
class ModelAndWorkflowSetupIT {

    @Autowired CyodaInit cyodaInit;
    @Autowired Config config;

    private final ObjectMapper om = CyodaJackson.configure(new ObjectMapper());

    @Test
    void cyodaInitImportsSampleDataLocksTheModelAndImportsTheWorkflowAndCanRecreateIt(@TempDir Path workflowDir) throws Exception {
        ObjectNode workflow = WorkflowTemplating.load("/example/config/workflow/template_workflow.json", config.getGrpcProcessorTag());
        Path file = workflowDir.resolve("ExampleEntity/version_1/ExampleEntity.json");
        Files.createDirectories(file.getParent());
        om.writeValue(file.toFile(), workflow);
        CyodaInitConfig init = CyodaInitConfig.withRecreateModels(true);
        init.setWorkflowDir(workflowDir.toString());

        cyodaInit.initCyoda(init);

        CyodaRest rest = CyodaTestEnvironment.rest(Profile.mockMemory());
        assertExampleEntityV1IsLockedWithTheWorkflow(rest, workflow);

        // A second --recreate-models run hits the model this run created and locked: cyoda-go refuses to
        // delete a LOCKED model (409 MODEL_ALREADY_LOCKED), so CyodaInit must unlock it first.
        cyodaInit.initCyoda(init);

        assertExampleEntityV1IsLockedWithTheWorkflow(rest, workflow);

        // An already-unlocked model (409 MODEL_ALREADY_UNLOCKED on unlock) is recreated too.
        rest.put("model/ExampleEntity/1/unlock", null).requireSuccess();
        cyodaInit.initCyoda(init);

        assertExampleEntityV1IsLockedWithTheWorkflow(rest, workflow);
    }

    private void assertExampleEntityV1IsLockedWithTheWorkflow(CyodaRest rest, ObjectNode workflow) throws Exception {
        JsonNode models = rest.get("model/").requireSuccess().body();
        List<JsonNode> exampleEntityModels = StreamSupport.stream(models.spliterator(), false)
                .filter(m -> "ExampleEntity".equals(m.path("modelName").asText())
                        && m.path("modelVersion").asInt() == 1)
                .toList();
        assertThat(exampleEntityModels).hasSize(1);
        assertThat(exampleEntityModels.getFirst().path("currentState").asText()).isEqualTo("LOCKED");

        JsonNode exported = rest.get("model/ExampleEntity/1/workflow/export").requireSuccess().body();
        // cyoda-go's export omits an empty array (e.g. a terminal state's "transitions") rather than
        // emitting "[]"; normalise both sides the same way before comparing so the round trip is
        // judged on content, not on that omitempty quirk. The equality assertion itself stays strict.
        WorkflowConfigurationDto imported = om.treeToValue(withoutEmptyArrays(workflow), WorkflowConfigurationDto.class);
        WorkflowConfigurationDto roundTripped = om.treeToValue(withoutEmptyArrays(exported.get("workflows").get(0)), WorkflowConfigurationDto.class);
        assertThat(roundTripped).isEqualTo(imported);
    }

    /** Recursively drops any object field whose value is an empty array. */
    private static JsonNode withoutEmptyArrays(JsonNode node) {
        if (node.isObject()) {
            ObjectNode copy = ((ObjectNode) node).deepCopy();
            Iterator<Map.Entry<String, JsonNode>> fields = copy.properties().iterator();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                JsonNode value = field.getValue();
                if (value.isArray() && value.isEmpty()) {
                    fields.remove();
                } else {
                    field.setValue(withoutEmptyArrays(value));
                }
            }
            return copy;
        }
        if (node.isArray()) {
            ArrayNode copy = ((ArrayNode) node).deepCopy();
            for (int i = 0; i < copy.size(); i++) {
                copy.set(i, withoutEmptyArrays(copy.get(i)));
            }
            return copy;
        }
        return node;
    }

    @Test
    void everyExampleWorkflowImports() throws Exception {
        CyodaRest rest = CyodaTestEnvironment.rest(Profile.mockMemory());
        Path dir = Path.of("src/test/resources/example/config/workflow");
        try (Stream<Path> files = Files.list(dir)) {
            for (Path f : files.filter(p -> p.toString().endsWith(".json")).toList()) {
                String model = "wf_it_" + UUID.randomUUID().toString().substring(0, 8);
                rest.post("model/import/JSON/SAMPLE_DATA/" + model + "/1", om.readTree("{\"sampleFieldA\":\"a\"}")).requireSuccess();

                CyodaModelSetup.importWorkflow(rest, model, 1,
                        WorkflowTemplating.applyTag(om.readTree(f.toFile()), config.getGrpcProcessorTag()));
            }
        }
    }
}
