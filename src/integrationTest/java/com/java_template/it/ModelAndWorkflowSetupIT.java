package com.java_template.it;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.util.UUID;
import java.util.stream.Stream;

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
    void cyodaInitImportsSampleDataLocksTheModelAndImportsTheWorkflow(@TempDir Path workflowDir) throws Exception {
        ObjectNode workflow = WorkflowTemplating.load("/example/config/workflow/template_workflow.json", config.getGrpcProcessorTag());
        Path file = workflowDir.resolve("ExampleEntity/version_1/ExampleEntity.json");
        Files.createDirectories(file.getParent());
        om.writeValue(file.toFile(), workflow);
        CyodaInitConfig init = CyodaInitConfig.withRecreateModels(true);
        init.setWorkflowDir(workflowDir.toString());

        cyodaInit.initCyoda(init);

        CyodaRest rest = CyodaTestEnvironment.rest(Profile.mockMemory());
        JsonNode models = rest.get("model/").requireSuccess().body();
        assertThat(models.toString()).contains("ExampleEntity").contains("LOCKED");

        JsonNode exported = rest.get("model/ExampleEntity/1/workflow/export").requireSuccess().body();
        WorkflowConfigurationDto imported = om.treeToValue(workflow, WorkflowConfigurationDto.class);
        WorkflowConfigurationDto roundTripped = om.treeToValue(exported.get("workflows").get(0), WorkflowConfigurationDto.class);
        assertThat(roundTripped).isEqualTo(imported);
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
