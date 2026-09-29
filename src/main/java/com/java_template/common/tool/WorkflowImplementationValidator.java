package com.java_template.common.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.stream.Stream;

/**
 * ABOUTME: Validation tool that ensures all processors/criteria referenced in workflow JSON files
 * exist as actual Java classes in the application processor and criterion directories.
 *<p>
 * This tool:
 * 1. Scans all workflow JSON files in src/main/resources/workflow/entity/version_1/Entity.json
 * 2. Extracts all processor and criteria names from the workflows
 * 3. Checks if corresponding Java classes exist in:
 *    - src/main/java/com/java_template/application/processor/
 *    - src/main/java/com/java_template/application/criterion/
 * 4. Reports missing implementations with detailed output
 */
public class WorkflowImplementationValidator {
    private static final Logger logger = LoggerFactory.getLogger(WorkflowImplementationValidator.class);

    private final ObjectMapper objectMapper;
    private final Path workflowDir;
    private final Path processorDir;
    private final Path criterionDir;

    public WorkflowImplementationValidator(ObjectMapper objectMapper) {
        this(objectMapper,
                Paths.get(System.getProperty("user.dir")).resolve("src/main/resources/workflow"),
                Paths.get(System.getProperty("user.dir")).resolve("src/main/java/com/java_template/application/processor"),
                Paths.get(System.getProperty("user.dir")).resolve("src/main/java/com/java_template/application/criterion"));
    }

    /** Visible for testing: validates against arbitrary directories instead of the fixed project layout. */
    WorkflowImplementationValidator(ObjectMapper objectMapper, Path workflowDir, Path processorDir, Path criterionDir) {
        this.objectMapper = objectMapper;
        this.workflowDir = workflowDir;
        this.processorDir = processorDir;
        this.criterionDir = criterionDir;
    }

    public static void main(String[] args) {
        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        context.registerBean(ObjectMapper.class, () -> new ObjectMapper());
        context.refresh();

        ObjectMapper objectMapper = context.getBean(ObjectMapper.class);
        WorkflowImplementationValidator validator = new WorkflowImplementationValidator(objectMapper);

        boolean isValid;
        if (args.length >= 1) {
            // Validate specific workflow file: args[0] = workflow file path
            isValid = validator.validateSpecificWorkflowFile(Paths.get(args[0]));
        } else {
            // Validate all discovered workflow files
            isValid = validator.validateWorkflowImplementations();
        }

        context.close();
        System.exit(isValid ? 0 : 1);
    }

    /**
     * Main validation method that orchestrates the entire validation process
     */
    public boolean validateWorkflowImplementations() {
        logger.info("🔍 Validating workflow implementations...");
        logger.info("============================================================");

        List<Path> workflowFiles = findWorkflowFiles();
        if (workflowFiles.isEmpty()) {
            // Not every app ships workflow JSON under src/main/resources/workflow (the template itself
            // does not); with nothing to check, there is nothing to fail on.
            logger.warn("⚠️  No workflow files found under {}; nothing to validate.", workflowDir);
            return true;
        }

        Set<String> existingProcessors = findJavaClasses(processorDir);
        Set<String> existingCriteria = findJavaClasses(criterionDir);

        logger.info("📁 Found {} processor classes", existingProcessors.size());
        logger.info("📁 Found {} criterion classes", existingCriteria.size());
        logger.info("");

        boolean allValid = true;
        int totalProcessorsChecked = 0;
        int totalCriteriaChecked = 0;

        for (Path workflowFile : workflowFiles) {
            String entityName = workflowFile.getFileName().toString().replace(".json", "");
            logger.info("📋 Checking workflow: {}", entityName);
            logger.info("   File: {}", workflowFile);

            ValidationResult result = extractProcessorsAndCriteria(workflowFile);
            if (result == null) {
                allValid = false;
                continue;
            }

            // Validate processors
            Set<String> missingProcessors = new HashSet<>(result.processors);
            missingProcessors.removeAll(existingProcessors);

            if (!missingProcessors.isEmpty()) {
                logger.error("   ❌ Missing processors: {}", String.join(", ", missingProcessors));
                allValid = false;
            } else {
                logger.info("   ✅ All {} processors found", result.processors.size());
            }

            // Validate criteria
            Set<String> missingCriteria = new HashSet<>(result.criteria);
            missingCriteria.removeAll(existingCriteria);

            if (!missingCriteria.isEmpty()) {
                logger.error("   ❌ Missing criteria: {}", String.join(", ", missingCriteria));
                allValid = false;
            } else {
                logger.info("   ✅ All {} criteria found", result.criteria.size());
            }

            if (!result.processors.isEmpty() || !result.criteria.isEmpty()) {
                logger.info("   📊 Processors: {}", result.processors.isEmpty() ? "None" : String.join(", ", result.processors));
                logger.info("   📊 Criteria: {}", result.criteria.isEmpty() ? "None" : String.join(", ", result.criteria));
            }

            totalProcessorsChecked += result.processors.size();
            totalCriteriaChecked += result.criteria.size();
            logger.info("");
        }

        // Summary
        logger.info("============================================================");
        logger.info("📊 VALIDATION SUMMARY");
        logger.info("============================================================");
        logger.info("Workflow files checked: {}", workflowFiles.size());
        logger.info("Total processors referenced: {}", totalProcessorsChecked);
        logger.info("Total criteria referenced: {}", totalCriteriaChecked);
        logger.info("Available processor classes: {}", existingProcessors.size());
        logger.info("Available criterion classes: {}", existingCriteria.size());

        if (allValid) {
            logger.info("✅ ALL WORKFLOW IMPLEMENTATIONS VALIDATED SUCCESSFULLY!");
        } else {
            logger.error("❌ VALIDATION FAILED - Missing implementations found!");
            logger.info("");
            logger.info("💡 To fix missing implementations:");
            logger.info("   1. Create the missing processor/criterion Java classes");
            logger.info("   2. Ensure they implement CyodaProcessor/CyodaCriterion interfaces");
            logger.info("   3. Add @Component annotation for Spring registration");
        }

        return allValid;
    }

    /**
     * Validate a specific workflow file
     */
    public boolean validateSpecificWorkflowFile(Path workflowFile) {
        logger.info("🔍 Validating specific workflow file...");
        logger.info("============================================================");
        logger.info("Workflow file: {}", workflowFile);

        if (!Files.exists(workflowFile)) {
            logger.error("❌ Workflow file not found: {}", workflowFile);
            return false;
        }

        Set<String> existingProcessors = findJavaClasses(processorDir);
        Set<String> existingCriteria = findJavaClasses(criterionDir);

        logger.info("📁 Found {} processor classes", existingProcessors.size());
        logger.info("📁 Found {} criterion classes", existingCriteria.size());
        logger.info("");

        String entityName = workflowFile.getFileName().toString().replace(".json", "");
        logger.info("📋 Checking workflow: {}", entityName);

        ValidationResult result = extractProcessorsAndCriteria(workflowFile);
        if (result == null) {
            return false;
        }

        boolean allValid = true;

        // Validate processors
        Set<String> missingProcessors = new HashSet<>(result.processors);
        missingProcessors.removeAll(existingProcessors);

        if (!missingProcessors.isEmpty()) {
            logger.error("❌ Missing processors: {}", String.join(", ", missingProcessors));
            allValid = false;
        } else {
            logger.info("✅ All {} processors found", result.processors.size());
        }

        // Validate criteria
        Set<String> missingCriteria = new HashSet<>(result.criteria);
        missingCriteria.removeAll(existingCriteria);

        if (!missingCriteria.isEmpty()) {
            logger.error("❌ Missing criteria: {}", String.join(", ", missingCriteria));
            allValid = false;
        } else {
            logger.info("✅ All {} criteria found", result.criteria.size());
        }

        if (!result.processors.isEmpty() || !result.criteria.isEmpty()) {
            logger.info("📊 Processors: {}", result.processors.isEmpty() ? "None" : String.join(", ", result.processors));
            logger.info("📊 Criteria: {}", result.criteria.isEmpty() ? "None" : String.join(", ", result.criteria));
        }

        logger.info("");
        logger.info("============================================================");
        if (allValid) {
            logger.info("✅ VALIDATION PASSED for {}", entityName);
        } else {
            logger.error("❌ VALIDATION FAILED for {}", entityName);
            logger.info("");
            logger.info("💡 To fix missing implementations:");
            logger.info("   1. Create the missing processor/criterion Java classes");
            logger.info("   2. Ensure they implement CyodaProcessor/CyodaCriterion interfaces");
            logger.info("   3. Add @Component annotation for Spring registration");
        }

        return allValid;
    }

    /**
     * Find all workflow JSON files
     */
    private List<Path> findWorkflowFiles() {
        List<Path> workflowFiles = new ArrayList<>();

        if (!Files.exists(workflowDir)) {
            logger.debug("No workflow directory at {}", workflowDir);
            return workflowFiles;
        }

        try (Stream<Path> entityDirs = Files.list(workflowDir)) {
            entityDirs.filter(Files::isDirectory)
                    .forEach(entityDir -> {
                        try (Stream<Path> versionDirs = Files.list(entityDir)) {
                            versionDirs.filter(Files::isDirectory)
                                    .filter(dir -> dir.getFileName().toString().startsWith("version_"))
                                    .forEach(versionDir -> {
                                        try (Stream<Path> jsonFiles = Files.list(versionDir)) {
                                            jsonFiles.filter(file -> file.toString().endsWith(".json"))
                                                    .forEach(workflowFiles::add);
                                        } catch (IOException e) {
                                            logger.warn("Error reading version directory {}: {}", versionDir, e.getMessage());
                                        }
                                    });
                        } catch (IOException e) {
                            logger.warn("Error reading entity directory {}: {}", entityDir, e.getMessage());
                        }
                    });
        } catch (IOException e) {
            logger.error("Error reading workflow directory: {}", e.getMessage());
        }

        return workflowFiles;
    }

    /**
     * Find all Java class names in a directory
     */
    private Set<String> findJavaClasses(Path directory) {
        Set<String> classes = new HashSet<>();

        if (!Files.exists(directory)) {
            return classes;
        }

        try (Stream<Path> files = Files.list(directory)) {
            files.filter(file -> file.toString().endsWith(".java"))
                    .forEach(file -> {
                        String className = file.getFileName().toString().replace(".java", "");
                        classes.add(className);
                    });
        } catch (IOException e) {
            logger.warn("Error reading directory {}: {}", directory, e.getMessage());
        }

        return classes;
    }

    /**
     * Extract processor and criteria names from a workflow JSON file
     */
    private ValidationResult extractProcessorsAndCriteria(Path workflowFile) {
        try {
            JsonNode workflow = objectMapper.readTree(workflowFile.toFile());
            Set<String> processors = new HashSet<>();
            Set<String> criteria = new HashSet<>();

            JsonNode states = workflow.get("states");
            if (states != null && states.isObject()) {
                states.properties().iterator().forEachRemaining(stateEntry -> {
                    JsonNode state = stateEntry.getValue();
                    JsonNode transitions = state.get("transitions");
                    if (transitions != null && transitions.isArray()) {
                        transitions.forEach(transition -> {
                            // Extract processors
                            JsonNode transitionProcessors = transition.get("processors");
                            if (transitionProcessors != null && transitionProcessors.isArray()) {
                                transitionProcessors.forEach(processor -> {
                                    JsonNode name = processor.get("name");
                                    if (name != null) {
                                        processors.add(name.asText());
                                    }
                                });
                            }

                            // Extract criteria
                            JsonNode criterion = transition.get("criterion");
                            if (criterion != null) {
                                extractCriteriaRecursively(criterion, criteria);
                            }
                        });
                    }
                });
            }

            return new ValidationResult(processors, criteria);

        } catch (IOException e) {
            logger.error("❌ Error reading {}: {}", workflowFile, e.getMessage());
            return null;
        }
    }

    /**
     * Recursively extract criteria names from nested criterion structures
     */
    private void extractCriteriaRecursively(JsonNode criterion, Set<String> criteria) {
        if (criterion == null || !criterion.isObject()) {
            return;
        }

        // Check if this is a function criterion
        if ("function".equals(criterion.path("type").asText())) {
            JsonNode function = criterion.get("function");
            if (function != null) {
                JsonNode name = function.get("name");
                if (name != null) {
                    criteria.add(name.asText());
                }
            }
        }

        // Handle nested criteria in various structures
        for (String key : Arrays.asList("and", "or", "not")) {
            JsonNode nested = criterion.get(key);
            if (nested != null) {
                if (nested.isArray()) {
                    nested.forEach(item -> extractCriteriaRecursively(item, criteria));
                } else if (nested.isObject()) {
                    extractCriteriaRecursively(nested, criteria);
                }
            }
        }

        // Handle 'conditions' array (used in group criteria)
        JsonNode conditions = criterion.get("conditions");
        if (conditions != null && conditions.isArray()) {
            conditions.forEach(condition -> extractCriteriaRecursively(condition, criteria));
        }
    }

    /**
     * Result holder for validation data
     */
    private static class ValidationResult {
        final Set<String> processors;
        final Set<String> criteria;

        ValidationResult(Set<String> processors, Set<String> criteria) {
            this.processors = processors;
            this.criteria = criteria;
        }
    }
}
