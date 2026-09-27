package com.example.tests;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.cyoda.cloud.api.common.model.WorkflowConfigurationDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.net.URISyntaxException;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test class to verify that workflow configuration JSON files in
 * src/test/resources/example/config/workflow can be successfully
 * marshalled into WorkflowConfiguration objects with a strict ObjectMapper.
 */
@DisplayName("Workflow Configuration Marshalling Tests")
class WorkflowConfigurationMarshallingTest {

    private ObjectMapper objectMapper;
    private File workflowConfigDir;

    @BeforeEach
    void setUp() throws URISyntaxException {
        objectMapper = new ObjectMapper();
        objectMapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, true);

        workflowConfigDir = new File(
                getClass().getClassLoader()
                        .getResource("example/config/workflow")
                        .toURI()
        );
    }

    @Test
    @DisplayName("Should successfully marshall all workflow JSON files with strict ObjectMapper")
    void testMarshallAllWorkflowFiles() {
        // Given
        File[] files = workflowConfigDir.listFiles((dir, name) ->
                name.endsWith(".json") && name.contains("workflow"));

        assertNotNull(files, "Workflow directory should contain files");
        List<File> workflowFiles = Arrays.asList(files);
        assertFalse(workflowFiles.isEmpty(), "Should have at least one workflow file to test");

        // When/Then - Marshall each file with strict ObjectMapper
        for (File workflowFile : workflowFiles) {
            assertDoesNotThrow(
                    () -> objectMapper.readValue(workflowFile, WorkflowConfigurationDto.class),
                    "Failed to marshall " + workflowFile.getName()
            );
        }
    }
}

