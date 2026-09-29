package com.java_template.common.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ABOUTME: Pins the check that replaces the removed WorkflowComponentValidationStandaloneTest
 * in the CI "workflow-validation" build type (.github/workflows/build.yml).
 */
class WorkflowImplementationValidatorTest {

    private final WorkflowImplementationValidator validator =
            new WorkflowImplementationValidator(new ObjectMapper());

    @Test
    void aWorkflowReferencingAProcessorWithNoJavaClassFailsValidation(@TempDir Path tempDir) throws IOException {
        Path workflowFile = tempDir.resolve("MissingProcessor.json");
        Files.writeString(workflowFile, """
                {
                  "states": {
                    "initial": {
                      "transitions": [
                        {
                          "processors": [
                            { "name": "ThisProcessorClassDoesNotExistAnywhere" }
                          ]
                        }
                      ]
                    }
                  }
                }
                """);

        assertThat(validator.validateSpecificWorkflowFile(workflowFile)).isFalse();
    }

    @Test
    void aWorkflowWithNoProcessorsOrCriteriaPassesValidation(@TempDir Path tempDir) throws IOException {
        Path workflowFile = tempDir.resolve("Empty.json");
        Files.writeString(workflowFile, """
                {
                  "states": {
                    "initial": {
                      "transitions": []
                    }
                  }
                }
                """);

        assertThat(validator.validateSpecificWorkflowFile(workflowFile)).isTrue();
    }

    /**
     * ABOUTME: validateWorkflowImplementations() (the full-scan entry point CI's
     * validateWorkflowImplementations Gradle task calls) against configurable directories rather than
     * the fixed src/main/... project layout, which this template ships with no workflow directory at
     * all (T2: the CI build type used to always fail because of exactly that).
     */
    @Test
    void noWorkflowDirectoryMeansNothingToValidateAndPasses(
            @TempDir Path workflowDir, @TempDir Path processorDir, @TempDir Path criterionDir) {
        Path missingWorkflowDir = workflowDir.resolve("does-not-exist");
        WorkflowImplementationValidator scanner =
                new WorkflowImplementationValidator(new ObjectMapper(), missingWorkflowDir, processorDir, criterionDir);

        assertThat(scanner.validateWorkflowImplementations()).isTrue();
    }

    @Test
    void aWorkflowReferencingAnExistingProcessorClassPasses(
            @TempDir Path workflowDir, @TempDir Path processorDir, @TempDir Path criterionDir) throws IOException {
        writeWorkflow(workflowDir, "Thing", "ThingProcessor");
        Files.createFile(processorDir.resolve("ThingProcessor.java"));
        WorkflowImplementationValidator scanner =
                new WorkflowImplementationValidator(new ObjectMapper(), workflowDir, processorDir, criterionDir);

        assertThat(scanner.validateWorkflowImplementations()).isTrue();
    }

    @Test
    void aWorkflowReferencingAMissingProcessorClassFailsTheFullScan(
            @TempDir Path workflowDir, @TempDir Path processorDir, @TempDir Path criterionDir) throws IOException {
        writeWorkflow(workflowDir, "Thing", "ThingProcessor");
        // processorDir stays empty: ThingProcessor.java does not exist.
        WorkflowImplementationValidator scanner =
                new WorkflowImplementationValidator(new ObjectMapper(), workflowDir, processorDir, criterionDir);

        assertThat(scanner.validateWorkflowImplementations()).isFalse();
    }

    /** Writes {@code <workflowDir>/<entityName>/version_1/<entityName>.json} referencing one processor. */
    private static void writeWorkflow(Path workflowDir, String entityName, String processorName) throws IOException {
        Path file = workflowDir.resolve(entityName).resolve("version_1").resolve(entityName + ".json");
        Files.createDirectories(file.getParent());
        Files.writeString(file, """
                {
                  "states": {
                    "initial": {
                      "transitions": [
                        {
                          "processors": [
                            { "name": "%s" }
                          ]
                        }
                      ]
                    }
                  }
                }
                """.formatted(processorName));
    }
}
