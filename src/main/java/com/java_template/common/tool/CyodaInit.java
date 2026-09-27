package com.java_template.common.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import com.java_template.common.call.CyodaCallContext;
import com.java_template.common.call.CyodaCallContexts;
import com.java_template.common.config.Config;
import com.java_template.common.config.CyodaObjectMapper;
import com.java_template.common.exception.CyodaHttpException;
import com.java_template.common.util.HttpUtils;
import com.java_template.common.workflow.CyodaEntity;
import org.cyoda.cloud.api.event.common.ModelSpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.type.filter.AssignableTypeFilter;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Stream;


/**
 * ABOUTME: Initialization tool for setting up Cyoda platform configuration
 * including workflow definitions, entity models, and system bootstrapping.
 *<p>
 * This tool dynamically discovers entities and uses their getModelKey() method
 * to get the correct entity name and version instead of parsing file paths.
 *<p>
 * By default, entity classes are discovered by scanning the classpath and workflow
 * JSON files are loaded from classpath resources under {@code /workflow/}. Both can
 * be overridden with absolute filesystem paths via {@link CyodaInitConfig}.
 */
@Component
public class CyodaInit {
    private static final Logger logger = LoggerFactory.getLogger(CyodaInit.class);
    private static final String CLASSPATH_WORKFLOW_PATTERN = "classpath:/workflow/**/*.json";
    public static final int THREAD_POOL_SIZE = 20;

    private final HttpUtils httpUtils;
    private final CyodaCallContexts callContexts;
    private final ObjectMapper objectMapper;
    private final Config config;

    public CyodaInit(HttpUtils httpUtils, CyodaCallContexts callContexts, CyodaObjectMapper wireMapper, Config config) {
        this.httpUtils = httpUtils;
        this.callContexts = callContexts;
        this.objectMapper = wireMapper.mapper();
        this.config = config;
    }

    public void initCyoda(CyodaInitConfig config) {
        logger.info("🔄 Starting workflow import into Cyoda...");
        if (config.recreateModels()) {
            logger.info("⚠️  Recreate models flag is enabled - existing models will be deleted and recreated");
        }

        CyodaCallContext ctx = callContexts.current();
        initEntitiesSchemaFromEntities(ctx, config);
        logger.info("✅ Workflow import process completed.");
    }


    /**
     * Initialize entities schema from discovered entities using their getModelKey() method.
     */
    private void initEntitiesSchemaFromEntities(CyodaCallContext ctx, CyodaInitConfig config) {
        logger.info("🔍 Discovering entities dynamically...");

        List<ModelSpec> modelSpecs = config.entitySourceDir() != null
                ? discoverEntitiesFromSourceDir(Paths.get(config.entitySourceDir()))
                : discoverEntitiesFromClasspath();

        logger.info("🔍 Discovered {} entities: {}", modelSpecs.size(),
                modelSpecs.stream().map(spec -> spec.getName() + ":" + spec.getVersion()).toList());

        ExecutorService executor = Executors.newFixedThreadPool(THREAD_POOL_SIZE);
        try {
            List<CompletableFuture<Void>> futures = new ArrayList<>();

            for (ModelSpec modelSpec : modelSpecs) {
                Optional<String> workflowJson = loadWorkflowJson(config, modelSpec.getName(), modelSpec.getVersion());
                if (workflowJson.isPresent()) {
                    logger.info("✅ Found workflow for entity: {} (version: {})", modelSpec.getName(), modelSpec.getVersion());
                    final String json = workflowJson.get();
                    CompletableFuture<Void> future = CompletableFuture.runAsync(() ->
                                    importWorkflowForEntity(json, modelSpec.getName(), modelSpec.getVersion(), ctx, config),
                            executor
                    );
                    futures.add(future);
                } else {
                    logger.warn("⚠️ No workflow file found for entity: {} (version: {})", modelSpec.getName(), modelSpec.getVersion());
                }
            }

            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
        } finally {
            executor.shutdown();
        }
    }


    /**
     * Discover entities by scanning the classpath for {@link CyodaEntity} implementations.
     * This is the default mode and works in both IDE and packaged JAR environments.
     */
    private List<ModelSpec> discoverEntitiesFromClasspath() {
        List<ModelSpec> modelSpecs = new ArrayList<>();
        ClassPathScanningCandidateComponentProvider scanner =
                new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AssignableTypeFilter(CyodaEntity.class));

        for (BeanDefinition bd : scanner.findCandidateComponents(config.getEntityBasePackage())) {
            ModelSpec spec = loadModelSpecFromClassName(bd.getBeanClassName());
            if (spec != null) {
                modelSpecs.add(spec);
                logger.debug("✅ Discovered entity from classpath: {} (version: {})", spec.getName(), spec.getVersion());
            }
        }

        return modelSpecs;
    }

    /**
     * Discover entities by scanning {@code .java} source files in the given directory.
     * Used when {@code --entity-source-dir} is specified in {@link CyodaInitConfig}.
     */
    private List<ModelSpec> discoverEntitiesFromSourceDir(Path entityDir) {
        List<ModelSpec> modelSpecs = new ArrayList<>();

        if (!Files.exists(entityDir)) {
            logger.warn("📁 Entity source directory '{}' does not exist", entityDir);
            return modelSpecs;
        }

        try (Stream<Path> javaFiles = Files.walk(entityDir)) {
            List<Path> entityFiles = javaFiles
                    .filter(path -> path.toString().endsWith(".java"))
                    .filter(path -> !path.getFileName().toString().startsWith("Test"))
                    .toList();

            for (Path javaFile : entityFiles) {
                String relativePath = entityDir.relativize(javaFile).toString();
                String className = relativePath.replace(File.separator, ".").replace(".java", "");
                ModelSpec spec = loadModelSpecFromClassName(config.getEntityBasePackage() + "." + className);
                if (spec != null) {
                    modelSpecs.add(spec);
                    logger.debug("✅ Discovered entity from source dir: {} (version: {})", spec.getName(), spec.getVersion());
                }
            }
        } catch (IOException e) {
            logger.error("❌ Error scanning entity source directory: {}", e.getMessage(), e);
        }

        return modelSpecs;
    }

    /**
     * Load a {@link ModelSpec} from a fully-qualified class name.
     * Returns {@code null} for non-entity classes or on any error.
     */
    private ModelSpec loadModelSpecFromClassName(String fullClassName) {
        try {
            Class<?> clazz = Class.forName(fullClassName);
            if (!CyodaEntity.class.isAssignableFrom(clazz)) {
                return null;
            }
            CyodaEntity entity = (CyodaEntity) clazz.getDeclaredConstructor().newInstance();
            return entity.getModelKey().modelKey();
        } catch (Exception e) {
            logger.debug("Could not load entity class {}: {}", fullClassName, e.getMessage());
            return null;
        }
    }

    /**
     * Load workflow JSON for the given entity and version.
     * Uses classpath resources by default; uses the filesystem directory from
     * {@link CyodaInitConfig#workflowDir()} when that option is set.
     */
    private Optional<String> loadWorkflowJson(CyodaInitConfig config, String entityName, Integer version) {
        if (config.workflowDir() != null) {
            return loadWorkflowJsonFromDir(Paths.get(config.workflowDir()), entityName, version);
        }
        return loadWorkflowJsonFromClasspath(entityName, version);
    }

    /**
     * Load workflow JSON from classpath resources matching {@code /workflow/**}/{@code *.json}.
     */
    private Optional<String> loadWorkflowJsonFromClasspath(String entityName, Integer version) {
        try {
            PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
            Resource[] resources = resolver.getResources(CLASSPATH_WORKFLOW_PATTERN);
            String entityNameLower = entityName.toLowerCase();

            for (Resource resource : resources) {
                String fileName = resource.getFilename();
                if (fileName == null) continue;

                String fileNameLower = fileName.toLowerCase();
                String fileNameWithoutExtension = fileNameLower.endsWith(".json")
                        ? fileNameLower.substring(0, fileNameLower.length() - 5)
                        : fileNameLower;

                String pathStr = resource.getURL().toString().toLowerCase();
                if (fileNameWithoutExtension.equals(entityNameLower) &&
                        (pathStr.contains("/version_" + version + "/") ||
                         pathStr.contains("/v" + version + "/"))) {
                    try (var inputStream = resource.getInputStream()) {
                        return Optional.of(new String(inputStream.readAllBytes(), StandardCharsets.UTF_8));
                    }
                }
            }
        } catch (IOException e) {
            logger.error("❌ Error searching classpath for workflow file: {}", e.getMessage(), e);
        }
        return Optional.empty();
    }

    /**
     * Load workflow JSON from a filesystem directory.
     * Used when {@code --workflow-dir} is specified in {@link CyodaInitConfig}.
     */
    private Optional<String> loadWorkflowJsonFromDir(Path workflowDir, String entityName, Integer version) {
        Path file = findWorkflowFile(workflowDir, entityName, version);
        if (file == null) return Optional.empty();
        try {
            return Optional.of(Files.readString(file));
        } catch (IOException e) {
            logger.error("❌ Error reading workflow file {}: {}", file, e.getMessage(), e);
            return Optional.empty();
        }
    }

    /**
     * Find a matching workflow {@code .json} file in a filesystem directory by entity name and version.
     */
    @SuppressWarnings("SameParameterValue")
    private Path findWorkflowFile(Path workflowDir, String entityName, Integer version) {
        if (!Files.exists(workflowDir)) {
            return null;
        }

        try (Stream<Path> workflowFilesStream = Files.walk(workflowDir)) {
            return workflowFilesStream
                    .filter(path -> path.toString().toLowerCase().endsWith(".json"))
                    .filter(path -> {
                        String pathStr = path.toString().toLowerCase();
                        String fileName = path.getFileName().toString().toLowerCase();
                        String entityNameLower = entityName.toLowerCase();

                        String fileNameWithoutExtension = fileName.endsWith(".json")
                                ? fileName.substring(0, fileName.length() - 5)
                                : fileName;

                        return fileNameWithoutExtension.equals(entityNameLower) &&
                                (pathStr.contains("version_" + version) || pathStr.contains("v" + version));
                    })
                    .findFirst()
                    .orElse(null);
        } catch (IOException e) {
            logger.error("❌ Error searching for workflow file: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Import workflow for a specific entity.
     * Supports workflow files containing either a single workflow object or an array of workflows.
     */
    private void importWorkflowForEntity(String dtoContent, String entityName, Integer version, CyodaCallContext ctx, CyodaInitConfig initConfig) {
        logger.info("📄 Processing workflow for entity: {}, version: {}", entityName, version);

        // The entity model must exist before a workflow can be imported for it; cyoda-go's
        // workflow/import endpoint verifies model existence and returns 404 MODEL_NOT_FOUND otherwise.
        checkAndCreateEntityModel(ctx, entityName, version, initConfig);

        JsonNode dtoJson;
        try {
            dtoJson = objectMapper.readTree(dtoContent);
        } catch (IOException e) {
            throw new RuntimeException("Failed to parse workflow JSON for " + entityName, e);
        }

        // Wrap the workflow content in the required format: {"workflows": [...]}
        // If the file contains an array, use it directly; otherwise wrap single workflow in array
        ObjectNode wrappedContent = objectMapper.createObjectNode();
        ArrayNode workflowsArray;
        if (dtoJson.isArray()) {
            workflowsArray = (ArrayNode) dtoJson;
            logger.debug("📄 Workflow contains {} workflow(s)", workflowsArray.size());
        } else {
            workflowsArray = objectMapper.createArrayNode();
            workflowsArray.add(dtoJson);
        }
        wrappedContent.set("workflows", workflowsArray);

        // Other alternatives are "MERGE" and "ACTIVATE"
        // MERGE will just add these workflows which may not be what you want, because you might have several workflows active for the same model
        // ACTIVATE will activate the imported ones and deactivate the others for the same model
        // Since we want to initialize, we'll just REPLACE, meaning for the models imported, only this one workflow will exist.
        wrappedContent.set("importMode", new TextNode("REPLACE"));

        String wrappedContentJson = wrappedContent.toString();

        // Use the endpoint format: model/{entity_name}/{version}/workflow/import
        String importPath = String.format("model/%s/%s/workflow/import", entityName, version);
        logger.debug("🔗 Using import endpoint: {}", importPath);

        JsonNode response = httpUtils.sendPostRequest(ctx, config.getCyodaApiUrl(), importPath, wrappedContentJson).join();

        int statusCode = response.get("status").asInt();
        if (statusCode >= 200 && statusCode < 300) {
            logger.info("✅ Successfully imported workflow for entity: {} (version: {})", entityName, version);
        } else {
            String body = response.path("json").toString();
            String errorMsg = String.format("Failed to import workflow for entity %s (version %s). Status code: %d, body: %s",
                    entityName, version, statusCode, body);
            logger.error("❌ {}", errorMsg);
            throw new RuntimeException(errorMsg);
        }
    }

    /**
     * Checks if entity model exists and creates it if needed
     */
    private void checkAndCreateEntityModel(CyodaCallContext ctx, String entityName, Integer version, CyodaInitConfig config) {
        String exportPath = String.format("model/export/SIMPLE_VIEW/%s/%s", entityName, version);
        logger.debug("🔍 Checking if entity model exists: {}", exportPath);

        // Only the existence check itself is wrapped: a failure from deleteEntityModel or
        // createEntityModel below must propagate as-is, not be relabelled "Failed to check
        // entity model" (that used to hide, for example, a 409 MODEL_HAS_ENTITIES from delete).
        boolean exists = entityModelExists(ctx, entityName, version, exportPath);

        if (exists) {
            if (config.recreateModels()) {
                logger.info("🗑️  Entity model exists for: {} (version: {}), deleting due to --recreate-models flag", entityName, version);
                deleteEntityModel(ctx, entityName, version);
                logger.info("📝 Creating entity model for: {} (version: {})", entityName, version);
                createEntityModel(ctx, entityName, version);
            } else {
                logger.info("✅ Entity model already exists for: {} (version: {})", entityName, version);
            }
        } else {
            logger.info("📝 Entity model not found, creating for: {} (version: {})", entityName, version);
            createEntityModel(ctx, entityName, version);
        }
    }

    /** True when the entity model export succeeds; false on a 404. Any other failure is thrown, not relabelled. */
    private boolean entityModelExists(CyodaCallContext ctx, String entityName, Integer version, String exportPath) {
        JsonNode response;
        try {
            response = httpUtils.sendGetRequest(ctx, this.config.getCyodaApiUrl(), exportPath).join();
        } catch (Exception ex) {
            if (isNotFound(ex)) {
                return false;
            }
            throw new RuntimeException("Failed to check entity model for " + entityName, ex);
        }

        int statusCode = response.get("status").asInt();
        if (statusCode >= 200 && statusCode < 300) {
            return true;
        } else if (statusCode == 404) {
            return false;
        } else {
            String body = response.path("json").toString();
            String errorMsg = String.format("Failed to check entity model for %s (version %s). Status code: %d, body: %s",
                    entityName, version, statusCode, body);
            logger.error("❌ {}", errorMsg);
            throw new RuntimeException(errorMsg);
        }
    }

    /** True when the cause chain carries a 404 CyodaHttpException (the model/resource does not exist). */
    private static boolean isNotFound(Throwable ex) {
        for (Throwable t = ex; t != null; t = t.getCause()) {
            if (t instanceof CyodaHttpException che && che.status() == 404) {
                return true;
            }
        }
        return false;
    }

    /**
     * Creates entity model using sample data, then sets change level to STRUCTURAL and locks the model
     */
    private void createEntityModel(CyodaCallContext ctx, String entityName, Integer version) {
        String importPath = String.format("model/import/JSON/SAMPLE_DATA/%s/%s", entityName, version);
        logger.debug("🔗 Creating entity model at: {}", importPath);

        // Load example JSON files from classpath
        List<String> exampleJsonFiles = loadExampleJsonFiles(entityName);

        if (exampleJsonFiles.isEmpty()) {
            logger.debug("No example JSON files found for entity: {}, using empty object", entityName);
            sendEntityModelRequest(ctx, importPath, "{}", entityName, version);
        } else {
            logger.info("Found {} example JSON file(s) for entity: {}", exampleJsonFiles.size(), entityName);
            for (int i = 0; i < exampleJsonFiles.size(); i++) {
                String jsonContent = exampleJsonFiles.get(i);
                logger.debug("Sending example {} of {} for entity: {}", i + 1, exampleJsonFiles.size(), entityName);
                sendEntityModelRequest(ctx, importPath, jsonContent, entityName, version);
            }
        }

        setChangeLevel(ctx, entityName, version);
        lockModel(ctx, entityName, version);
    }

    /**
     * Load example JSON files from classpath for the given entity
     */
    private List<String> loadExampleJsonFiles(String entityName) {
        List<String> jsonContents = new ArrayList<>();
        String examplesPath = String.format("/entity-schemas/examples/%s", entityName);

        try {
            // Get the resource as a URL to check if directory exists
            var resource = getClass().getResource(examplesPath);
            if (resource == null) {
                logger.debug("No examples directory found at classpath: {}", examplesPath);
                return jsonContents;
            }

            // Read directory contents from classpath
            var uri = resource.toURI();
            Path examplesDir;

            if (uri.getScheme().equals("jar")) {
                // Running from JAR - need to use FileSystem
                try (var fs = java.nio.file.FileSystems.newFileSystem(uri, java.util.Collections.emptyMap())) {
                    examplesDir = fs.getPath(examplesPath);
                    jsonContents = readJsonFilesFromDirectory(examplesDir);
                }
            } else {
                // Running from IDE/filesystem
                examplesDir = Paths.get(uri);
                jsonContents = readJsonFilesFromDirectory(examplesDir);
            }

        } catch (Exception e) {
            logger.debug("Could not load example files for entity {}: {}", entityName, e.getMessage());
        }

        return jsonContents;
    }

    /**
     * Read all JSON files from a directory
     */
    private List<String> readJsonFilesFromDirectory(Path directory) throws IOException {
        List<String> jsonContents = new ArrayList<>();

        if (!Files.exists(directory) || !Files.isDirectory(directory)) {
            return jsonContents;
        }

        try (Stream<Path> files = Files.list(directory)) {
            List<Path> jsonFiles = files
                    .filter(path -> path.toString().endsWith(".json"))
                    .sorted()
                    .toList();

            for (Path jsonFile : jsonFiles) {
                String content = Files.readString(jsonFile);
                jsonContents.add(content);
                logger.debug("Loaded example file: {}", jsonFile.getFileName());
            }
        }

        return jsonContents;
    }

    /**
     * Send a single entity model creation request
     */
    private void sendEntityModelRequest(CyodaCallContext ctx, String importPath, String requestBody, String entityName, Integer version) {
        JsonNode response = httpUtils.sendPostRequest(ctx, config.getCyodaApiUrl(), importPath, requestBody).join();
        int statusCode = response.get("status").asInt();

        if (statusCode >= 200 && statusCode < 300) {
            logger.debug("✅ Successfully sent entity model request for: {} (version: {})", entityName, version);
        } else {
            String body = response.path("json").toString();
            String errorMsg = String.format("Failed to create entity model for %s (version %s). Status code: %d, body: %s",
                    entityName, version, statusCode, body);
            logger.error("❌ {}", errorMsg);
            throw new RuntimeException(errorMsg);
        }
    }

    /**
     * Sets the change level to STRUCTURAL for the entity model
     */
    private void setChangeLevel(CyodaCallContext ctx, String entityName, Integer version) {
        String changeLevel = "STRUCTURAL";
        String changeLevelPath = String.format("model/%s/%s/changeLevel/%s", entityName, version, changeLevel);
        logger.debug("🔗 Setting change level to {} for entity: {} (version: {})", changeLevel, entityName, version);

        JsonNode response = httpUtils.sendPostRequest(ctx, config.getCyodaApiUrl(), changeLevelPath, null).join();
        int statusCode = response.get("status").asInt();

        if (statusCode >= 200 && statusCode < 300) {
            logger.info("✅ Successfully set change level to {} for entity: {} (version: {})", changeLevel, entityName, version);
        } else {
            String body = response.path("json").toString();
            String errorMsg = String.format("Failed to set change level for %s (version %s). Status code: %d, body: %s",
                    entityName, version, statusCode, body);
            logger.error("❌ {}", errorMsg);
            throw new RuntimeException(errorMsg);
        }
    }

    /**
     * Locks the entity model
     */
    private void lockModel(CyodaCallContext ctx, String entityName, Integer version) {
        String lockPath = String.format("model/%s/%s/lock", entityName, version);
        logger.debug("🔗 Locking entity model for: {} (version: {})", entityName, version);

        JsonNode response = httpUtils.sendPutRequest(ctx, config.getCyodaApiUrl(), lockPath, null).join();
        int statusCode = response.get("status").asInt();

        if (statusCode >= 200 && statusCode < 300) {
            logger.info("✅ Successfully locked entity model for: {} (version: {})", entityName, version);
        } else {
            String body = response.path("json").toString();
            String errorMsg = String.format("Failed to lock entity model for %s (version %s). Status code: %d, body: %s",
                    entityName, version, statusCode, body);
            logger.error("❌ {}", errorMsg);
            throw new RuntimeException(errorMsg);
        }
    }

    /**
     * Unlocks the entity model. A model that is already unlocked (409 MODEL_ALREADY_UNLOCKED) is fine.
     */
    private void unlockModel(CyodaCallContext ctx, String entityName, Integer version) {
        String unlockPath = String.format("model/%s/%s/unlock", entityName, version);
        logger.debug("🔗 Unlocking entity model for: {} (version: {})", entityName, version);

        try {
            JsonNode response = httpUtils.sendPutRequest(ctx, config.getCyodaApiUrl(), unlockPath, null).join();
            int statusCode = response.get("status").asInt();
            if (statusCode < 200 || statusCode >= 300) {
                String errorMsg = String.format("Failed to unlock entity model for %s (version %s). Status code: %d, body: %s",
                        entityName, version, statusCode, response.path("json").toString());
                logger.error("❌ {}", errorMsg);
                throw new RuntimeException(errorMsg);
            }
            logger.info("🔓 Unlocked entity model for: {} (version: {})", entityName, version);
        } catch (CompletionException ex) {
            if (isAlreadyUnlocked(ex)) {
                logger.info("ℹ️  Entity model is already unlocked for: {} (version: {})", entityName, version);
                return;
            }
            throw ex;
        }
    }

    private static boolean isAlreadyUnlocked(Throwable ex) {
        for (Throwable t = ex; t != null; t = t.getCause()) {
            if (t instanceof CyodaHttpException che
                    && che.status() == 409
                    && "MODEL_ALREADY_UNLOCKED".equals(che.getErrorCode())) {
                return true;
            }
        }
        return false;
    }

    /**
     * Deletes the entity model if it exists, unlocking it first
     */
    private void deleteEntityModel(CyodaCallContext ctx, String entityName, Integer version) {
        // First check if the model exists
        String exportPath = String.format("model/export/SIMPLE_VIEW/%s/%s", entityName, version);
        logger.debug("🔍 Checking if entity model exists before deletion: {}", exportPath);

        try {
            JsonNode checkResponse = httpUtils.sendGetRequest(ctx, config.getCyodaApiUrl(), exportPath).join();
            int checkStatusCode = checkResponse.get("status").asInt();

            if (checkStatusCode == 404) {
                logger.info("ℹ️  Entity model does not exist for: {} (version: {}), skipping deletion", entityName, version);
                return;
            } else if (checkStatusCode < 200 || checkStatusCode >= 300) {
                String body = checkResponse.path("json").toString();
                logger.warn("⚠️  Could not verify entity model existence for {} (version {}). Status code: {}, body: {}",
                        entityName, version, checkStatusCode, body);
                // Continue with deletion attempt anyway
            }
        } catch (Exception ex) {
            if (isNotFound(ex)) {
                logger.info("ℹ️  Entity model does not exist for: {} (version: {}), skipping deletion", entityName, version);
                return;
            }
            logger.warn("⚠️  Could not verify entity model existence for {} (version {}): {}",
                    entityName, version, ex.getMessage());
            // Continue with deletion attempt anyway
        }

        // cyoda-go refuses to delete a LOCKED model (409 MODEL_ALREADY_LOCKED), and createEntityModel
        // locks every model it creates, so unlock first. cyoda-go also refuses to unlock a model
        // that still has entities (409 MODEL_HAS_ENTITIES): the model is never unlocked in that
        // case, so there is nothing to re-lock, but the failure must not be relabelled.
        try {
            unlockModel(ctx, entityName, version);
        } catch (Exception ex) {
            if (isModelHasEntities(ex)) {
                throw new IllegalStateException(String.format(
                        "Cannot recreate %s v%s: it still has entities (MODEL_HAS_ENTITIES). "
                                + "Delete its entities first, or run without --recreate-models",
                        entityName, version));
            }
            throw ex instanceof RuntimeException re ? re
                    : new RuntimeException("Failed to unlock entity model for " + entityName, ex);
        }

        // Model exists and is unlocked, proceed with deletion
        String deletePath = String.format("model/%s/%s", entityName, version);
        logger.debug("🔗 Deleting entity model for: {} (version: {})", entityName, version);

        JsonNode response;
        try {
            response = httpUtils.sendDeleteRequest(ctx, config.getCyodaApiUrl(), deletePath).join();
        } catch (Exception ex) {
            if (isModelHasEntities(ex)) {
                // Defensive: cyoda-go's own check for this actually runs on unlock (caught above),
                // so unlockModel succeeding means no entities existed at that point. If one is
                // refused here anyway (e.g. an entity created concurrently), the model is now
                // unlocked, so re-lock it best-effort rather than leave it open to schema changes.
                try {
                    lockModel(ctx, entityName, version);
                } catch (Exception relockEx) {
                    logger.warn("⚠️  Could not re-lock entity model for {} (version {}) after MODEL_HAS_ENTITIES: {}",
                            entityName, version, relockEx.getMessage());
                }
                throw new IllegalStateException(String.format(
                        "Cannot recreate %s v%s: it still has entities (MODEL_HAS_ENTITIES). "
                                + "Delete its entities first, or run without --recreate-models",
                        entityName, version));
            }
            throw ex instanceof RuntimeException re ? re
                    : new RuntimeException("Failed to delete entity model for " + entityName, ex);
        }
        int statusCode = response.get("status").asInt();

        if (statusCode >= 200 && statusCode < 300) {
            logger.info("✅ Successfully deleted entity model for: {} (version: {})", entityName, version);
        } else if (statusCode == 404) {
            logger.info("ℹ️  Entity model was already deleted for: {} (version: {})", entityName, version);
        } else {
            String body = response.path("json").toString();
            String errorMsg = String.format("Failed to delete entity model for %s (version %s). Status code: %d, body: %s",
                    entityName, version, statusCode, body);
            logger.error("❌ {}", errorMsg);
            throw new RuntimeException(errorMsg);
        }
    }

    /** True when the cause chain carries a 409 CyodaHttpException with code MODEL_HAS_ENTITIES. */
    private static boolean isModelHasEntities(Throwable ex) {
        for (Throwable t = ex; t != null; t = t.getCause()) {
            if (t instanceof CyodaHttpException che
                    && che.status() == 409
                    && "MODEL_HAS_ENTITIES".equals(che.getErrorCode())) {
                return true;
            }
        }
        return false;
    }
}
