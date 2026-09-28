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
}
