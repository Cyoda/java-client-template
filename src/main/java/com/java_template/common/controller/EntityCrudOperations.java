package com.java_template.common.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.java_template.common.dto.EntityWithMetadata;
import com.java_template.common.dto.PageResult;
import com.java_template.common.repository.SearchAndRetrievalParams;
import com.java_template.common.service.EntityService;
import com.java_template.common.util.CyodaExceptionUtil;
import com.java_template.common.workflow.CyodaEntity;
import org.cyoda.cloud.api.common.model.*;
import org.cyoda.cloud.api.event.common.EntityChangeMeta;
import org.cyoda.cloud.api.event.common.ModelSpec;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.net.URI;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.function.Consumer;

/**
 * ABOUTME: Reusable component providing standard CRUD operations for entity controllers.
 * Uses modern Java 21 features including records, sealed types, and pattern matching.
 * Delegates all standard entity operations to avoid code duplication across controllers.
 *<p>
 * <p>Usage in controllers:
 * <pre>{@code
 * @RestController
 * @RequestMapping("/ui/parties")
 * public class PartyController {
 *     private final EntityCrudOperations<Party> crudOps;
 *<p>
 *     public PartyController(EntityService entityService, ObjectMapper objectMapper) {
 *         this.crudOps = new EntityCrudOperations<>(
 *             entityService,
 *             objectMapper,
 *             LoggerFactory.getLogger(PartyController.class),
 *             Party.ENTITY_NAME,
 *             Party.ENTITY_VERSION,
 *             Party.class,
 *             "partyId"
 *         );
 *     }
 *<p>
 *     @PostMapping
 *     public ResponseEntity<EntityWithMetadata<Party>> create(@RequestBody Party party) {
 *         return crudOps.create(party);
 *     }
 * }
 * }</pre>
 *<p>
 * @param <T> The entity type that implements CyodaEntity
 */
@SuppressWarnings("unused")
public class EntityCrudOperations<T extends CyodaEntity> {

    private final EntityService entityService;
    private final ObjectMapper objectMapper;
    private final Logger logger;
    private final String entityName;
    private final Integer entityVersion;
    private final Class<T> entityClass;
    private final String businessIdField;

    /**
     * Creates a new EntityCrudOperations instance for a specific entity type.
     *
     * @param entityService   The entity service for Cyoda operations
     * @param objectMapper    Jackson ObjectMapper for JSON operations
     * @param logger          Logger instance from the controller
     * @param entityName      Entity name constant (e.g., Party.ENTITY_NAME)
     * @param entityVersion   Entity version constant (e.g., Party.ENTITY_VERSION)
     * @param entityClass     Entity class reference (e.g., Party.class)
     * @param businessIdField The JSON path field name for business ID (e.g., "partyId")
     */
    public EntityCrudOperations(
            EntityService entityService,
            ObjectMapper objectMapper,
            Logger logger,
            String entityName,
            Integer entityVersion,
            Class<T> entityClass,
            String businessIdField) {
        this.entityService = entityService;
        this.objectMapper = objectMapper;
        this.logger = logger;
        this.entityName = entityName;
        this.entityVersion = entityVersion;
        this.entityClass = entityClass;
        this.businessIdField = businessIdField;
    }

    /**
     * Creates a ModelSpec for this entity type.
     */
    private ModelSpec modelSpec() {
        return new ModelSpec().withName(entityName).withVersion(entityVersion);
    }

    /**
     * Creates a new entity with duplicate business ID check.
     *
     * @param entity              The entity to create
     * @param businessIdExtractor Function to extract business ID from entity
     * @param preCreateHook       Optional hook to run before creation (e.g., set defaults)
     * @return ResponseEntity with created entity or error
     */
    public ResponseEntity<EntityWithMetadata<T>> create(
            T entity,
            java.util.function.Function<T, String> businessIdExtractor,
            Consumer<T> preCreateHook) {
        try {
            String businessId = businessIdExtractor.apply(entity);

            // Check for duplicate business identifier
            EntityWithMetadata<T> existing = entityService.findByBusinessIdOrNull(
                    modelSpec(), businessId, businessIdField, entityClass);

            if (existing != null) {
                logger.warn("{} with business ID {} already exists", entityName, businessId);
                ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(
                        HttpStatus.CONFLICT,
                        String.format("%s already exists with ID: %s", entityName, businessId)
                );
                return ResponseEntity.of(problemDetail).build();
            }

            // Run pre-create hook if provided
            if (preCreateHook != null) {
                preCreateHook.accept(entity);
            }

            EntityWithMetadata<T> response = entityService.create(entity);
            logger.info("{} created with ID: {}", entityName, response.metadata().getId());

            URI location = ServletUriComponentsBuilder
                    .fromCurrentRequest()
                    .path("/{id}")
                    .buildAndExpand(response.metadata().getId())
                    .toUri();

            return ResponseEntity.created(location).body(response);
        } catch (Exception e) {
            ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(
                    HttpStatus.BAD_REQUEST,
                    String.format("Failed to create %s: %s", entityName, e.getMessage())
            );
            return ResponseEntity.of(problemDetail).build();
        }
    }

    /**
     * Creates a new entity with duplicate business ID check (no pre-create hook).
     */
    public ResponseEntity<EntityWithMetadata<T>> create(
            T entity,
            java.util.function.Function<T, String> businessIdExtractor) {
        return create(entity, businessIdExtractor, null);
    }

    /**
     * Creates a new entity without duplicate check (for entities with auto-generated IDs).
     */
    public ResponseEntity<EntityWithMetadata<T>> createWithoutDuplicateCheck(
            T entity,
            Consumer<T> preCreateHook) {
        try {
            if (preCreateHook != null) {
                preCreateHook.accept(entity);
            }

            EntityWithMetadata<T> response = entityService.create(entity);
            logger.info("{} created with ID: {}", entityName, response.metadata().getId());

            URI location = ServletUriComponentsBuilder
                    .fromCurrentRequest()
                    .path("/{id}")
                    .buildAndExpand(response.metadata().getId())
                    .toUri();

            return ResponseEntity.created(location).body(response);
        } catch (Exception e) {
            ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(
                    HttpStatus.BAD_REQUEST,
                    String.format("Failed to create %s: %s", entityName, e.getMessage())
            );
            return ResponseEntity.of(problemDetail).build();
        }
    }

    /**
     * Creates a new entity with composite business key duplicate check.
     * Uses multiple field name/extractor pairs to form a unique business identifier.
     *
     * @param entity              The entity to create
     * @param businessIdExtractors Map of field names to functions that extract composite key fields
     * @param preCreateHook       Optional hook to run before creation (e.g., set defaults)
     * @return ResponseEntity with created entity or error
     */
    public ResponseEntity<EntityWithMetadata<T>> createWithCompositeKey(
            T entity,
            Map<String, java.util.function.Function<T, Object>> businessIdExtractors,
            Consumer<T> preCreateHook) {
        try {
            // Check for duplicate using composite key
            EntityWithMetadata<T> existing = entityService.findByCompositeKeyOrNull(
                    modelSpec(), entity, businessIdExtractors, entityClass);

            if (existing != null) {
                // Build a readable description of the composite key for the error message
                List<String> keyPairs = new ArrayList<>();
                for (Map.Entry<String, java.util.function.Function<T, Object>> entry : businessIdExtractors.entrySet()) {
                    Object value = entry.getValue().apply(entity);
                    keyPairs.add(entry.getKey() + "=" + value);
                }
                String compositeKeyDesc = String.join(", ", keyPairs);

                logger.warn("{} with composite key ({}) already exists", entityName, compositeKeyDesc);
                ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(
                        HttpStatus.CONFLICT,
                        String.format("%s already exists with composite key: %s", entityName, compositeKeyDesc)
                );
                return ResponseEntity.of(problemDetail).build();
            }

            // Run pre-create hook if provided
            if (preCreateHook != null) {
                preCreateHook.accept(entity);
            }

            EntityWithMetadata<T> response = entityService.create(entity);
            logger.info("{} created with ID: {}", entityName, response.metadata().getId());

            URI location = ServletUriComponentsBuilder
                    .fromCurrentRequest()
                    .path("/{id}")
                    .buildAndExpand(response.metadata().getId())
                    .toUri();

            return ResponseEntity.created(location).body(response);
        } catch (Exception e) {
            ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(
                    HttpStatus.BAD_REQUEST,
                    String.format("Failed to create %s: %s", entityName, e.getMessage())
            );
            return ResponseEntity.of(problemDetail).build();
        }
    }

    /**
     * Creates a new entity with composite business key duplicate check (no pre-create hook).
     */
    public ResponseEntity<EntityWithMetadata<T>> createWithCompositeKey(
            T entity,
            Map<String, java.util.function.Function<T, Object>> businessIdExtractors) {
        return createWithCompositeKey(entity, businessIdExtractors, null);
    }

    /**
     * Gets entity by technical UUID.
     */
    public ResponseEntity<EntityWithMetadata<T>> getById(UUID id, OffsetDateTime pointInTime) {
        try {
            EntityWithMetadata<T> response = entityService.getById(
                    id, modelSpec(), entityClass, pointInTime);
            if (response == null) {
                return ResponseEntity.notFound().build();
            }
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(
                    HttpStatus.BAD_REQUEST,
                    String.format("Failed to retrieve %s with ID '%s': %s", entityName, id, e.getMessage())
            );
            return ResponseEntity.of(problemDetail).build();
        }
    }

    /**
     * Gets entity by business identifier.
     */
    public ResponseEntity<EntityWithMetadata<T>> getByBusinessId(
            String businessId,
            OffsetDateTime pointInTime) {
        try {
            EntityWithMetadata<T> response = entityService.findByBusinessId(
                    modelSpec(), businessId, businessIdField, entityClass, pointInTime);

            if (response == null) {
                return ResponseEntity.notFound().build();
            }
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(
                    HttpStatus.BAD_REQUEST,
                    String.format("Failed to retrieve %s with business ID '%s': %s",
                            entityName, businessId, e.getMessage())
            );
            return ResponseEntity.of(problemDetail).build();
        }
    }

    /**
     * Gets entity change history metadata.
     */
    public ResponseEntity<List<EntityChangeMeta>> getChangesMetadata(
            UUID id,
            OffsetDateTime pointInTime) {
        try {
            List<EntityChangeMeta> changes = entityService.getEntityChangesMetadata(
                    id, pointInTime);
            return ResponseEntity.ok(changes);
        } catch (Exception e) {
            if (CyodaExceptionUtil.isNotFound(e)) {
                return ResponseEntity.notFound().build();
            }
            ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(
                    HttpStatus.BAD_REQUEST,
                    String.format("Failed to retrieve change history for %s with ID '%s': %s",
                            entityName, id, e.getMessage())
            );
            return ResponseEntity.of(problemDetail).build();
        }
    }

    /**
     * Updates entity with optional workflow transition.
     */
    public ResponseEntity<EntityWithMetadata<T>> update(
            UUID id,
            T entity,
            String transition) {
        try {
            EntityWithMetadata<T> response = entityService.update(id, entity, transition);
            logger.info("{} updated with ID: {}", entityName, id);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(
                    HttpStatus.BAD_REQUEST,
                    String.format("Failed to update %s with ID '%s': %s", entityName, id, e.getMessage())
            );
            return ResponseEntity.of(problemDetail).build();
        }
    }

    /**
     * Lists all entities with pagination and optional filtering.
     * Supports filtering by entity fields and metadata state.
     *
     * @param pageSize      Tht page size
     * @param pageNumber    The page number (1-based)
     * @param searchId      The search ID from previous response (null for new search)
     * @param filters       List of field filters to apply
     * @param stateFilter   Optional state filter (from metadata)
     * @param pointInTime   Optional point-in-time query
     * @return Paginated list of entities
     */
    public ResponseEntity<PageResult<EntityWithMetadata<T>>> list(
            int pageSize,
            int pageNumber,
            @Nullable UUID searchId,
            List<FieldFilter> filters,
            String stateFilter,
            OffsetDateTime pointInTime) {
        try {
            List<GroupConditionDtoAllOfConditions> conditions = new ArrayList<>();

            // Build search conditions from filters
            for (FieldFilter filter : filters) {
                if (filter.value() != null && !filter.value().trim().isEmpty()) {
                    SimpleConditionDto condition = new SimpleConditionDto()
                            .jsonPath("$." + filter.fieldName())
                            .operatorType(filter.operation())
                            .value(objectMapper.valueToTree(filter.value()));
                    conditions.add(condition);
                }
            }

            // Add state filter as LifecycleCondition if provided
            if (stateFilter != null && !stateFilter.trim().isEmpty()) {
                LifecycleConditionDto stateCondition = new LifecycleConditionDto()
                        .field("state")
                        .operatorType(LifecycleConditionDto.OperatorTypeEnum.EQUALS)
                        .value(objectMapper.valueToTree(stateFilter));
                conditions.add(stateCondition);
            }

            if (conditions.isEmpty()) {
                // Use paginated findAll when no filters
                return ResponseEntity.ok(entityService.findAll(
                        modelSpec(), entityClass, SearchAndRetrievalParams.builder()
                                .pageSize(pageSize)
                                .pageNumber(pageNumber)
                                .pointInTime(pointInTime)
                                .inMemory(false)
                                .searchId(searchId)
                                .build()
                ));
            } else {
                // For filtered results, get all matching results then manually paginate
                GroupConditionDto groupCondition = new GroupConditionDto()
                        .operator(GroupConditionDto.OperatorEnum.AND)
                        .conditions(conditions);
                PageResult<EntityWithMetadata<T>> pageResult = entityService.search(
                        modelSpec(), groupCondition, entityClass, SearchAndRetrievalParams.builder()
                                .pageSize(pageSize)
                                .pageNumber(pageNumber)
                                .pointInTime(pointInTime)
                                .inMemory(false)
                                .searchId(searchId)
                                .build()
                );

                return ResponseEntity.ok(pageResult);
            }
        } catch (Exception e) {
            ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(
                    HttpStatus.BAD_REQUEST,
                    String.format("Failed to list %ss: %s", entityName, e.getMessage())
            );
            return ResponseEntity.of(problemDetail).build();
        }
    }

    /**
     * Searches entities by a single field with CONTAINS operation.
     */
    public ResponseEntity<PageResult<EntityWithMetadata<T>>> search(
            int pageSize,
            int pageNumber,
            @Nullable UUID searchId,
            String fieldName,
            String searchValue,
            OffsetDateTime pointInTime) {
        try {
            SimpleConditionDto condition = new SimpleConditionDto()
                    .jsonPath("$." + fieldName)
                    .operatorType(SimpleConditionDto.OperatorTypeEnum.CONTAINS)
                    .value(objectMapper.valueToTree(searchValue));

            GroupConditionDto groupCondition = new GroupConditionDto()
                    .operator(GroupConditionDto.OperatorEnum.AND)
                    .conditions(List.of(condition));

            PageResult<EntityWithMetadata<T>> pageResult = entityService.search(
                    modelSpec(), groupCondition, entityClass, SearchAndRetrievalParams.builder()
                            .pageSize(pageSize)
                            .pageNumber(pageNumber)
                            .pointInTime(pointInTime)
                            .inMemory(false)
                            .searchId(searchId)
                            .build()
            );
            return ResponseEntity.ok(pageResult);
        } catch (Exception e) {
            ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(
                    HttpStatus.BAD_REQUEST,
                    String.format("Failed to search %ss by %s '%s': %s",
                            entityName, fieldName, searchValue, e.getMessage())
            );
            return ResponseEntity.of(problemDetail).build();
        }
    }

    /**
     * Executes a workflow transition on an entity.
     */
    public ResponseEntity<EntityWithMetadata<T>> executeTransition(
            UUID id,
            String transitionName) {
        try {
            EntityWithMetadata<T> current = entityService.getById(id, modelSpec(), entityClass);
            EntityWithMetadata<T> response = entityService.update(id, current.entity(), transitionName);
            logger.info("{} transitioned with ID: {} using transition: {}", entityName, id, transitionName);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(
                    HttpStatus.BAD_REQUEST,
                    String.format("Failed to execute transition '%s' on %s with ID '%s': %s",
                            transitionName, entityName, id, e.getMessage())
            );
            return ResponseEntity.of(problemDetail).build();
        }
    }

    /**
     * Deletes entity by technical UUID.
     */
    public ResponseEntity<Void> deleteById(UUID id) {
        try {
            entityService.deleteById(id);
            logger.info("{} deleted with ID: {}", entityName, id);
            return ResponseEntity.noContent().build();
        } catch (Exception e) {
            ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(
                    HttpStatus.BAD_REQUEST,
                    String.format("Failed to delete %s with ID '%s': %s", entityName, id, e.getMessage())
            );
            return ResponseEntity.of(problemDetail).build();
        }
    }

    /**
     * Deletes entity by business identifier.
     */
    public ResponseEntity<Void> deleteByBusinessId(String businessId) {
        try {
            boolean deleted = entityService.deleteByBusinessId(
                    modelSpec(), businessId, businessIdField, entityClass);

            if (!deleted) {
                return ResponseEntity.notFound().build();
            }

            logger.info("{} deleted with business ID: {}", entityName, businessId);
            return ResponseEntity.noContent().build();
        } catch (Exception e) {
            ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(
                    HttpStatus.BAD_REQUEST,
                    String.format("Failed to delete %s with business ID '%s': %s",
                            entityName, businessId, e.getMessage())
            );
            return ResponseEntity.of(problemDetail).build();
        }
    }

    /**
     * Deletes all entities (DANGEROUS - use with caution).
     */
    public ResponseEntity<String> deleteAll() {
        try {
            Integer deletedCount = entityService.deleteAll(modelSpec());
            logger.warn("Deleted all {}s - count: {}", entityName, deletedCount);
            return ResponseEntity.ok().body(String.format("Deleted %d %ss", deletedCount, entityName));
        } catch (Exception e) {
            ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(
                    HttpStatus.BAD_REQUEST,
                    String.format("Failed to delete all %ss: %s", entityName, e.getMessage())
            );
            return ResponseEntity.of(problemDetail).build();
        }
    }

    /**
     * Creates multiple entities in batch.
     *
     * @param entities Collection of entities to create
     * @return ResponseEntity with list of created entities or error
     */
    public ResponseEntity<List<EntityWithMetadata<T>>> createAll(List<T> entities) {
        return createAll(entities, null, null);
    }

    /**
     * Creates multiple entities in batch with transaction control parameters.
     *
     * @param entities Collection of entities to create
     * @param transactionWindow Maximum number of entities per transaction (null for default)
     * @param transactionTimeoutMs Transaction timeout in milliseconds (null for default)
     * @return ResponseEntity with list of created entities or error
     */
    public ResponseEntity<List<EntityWithMetadata<T>>> createAll(
            List<T> entities,
            Integer transactionWindow,
            Long transactionTimeoutMs) {
        try {
            List<EntityWithMetadata<T>> responses = entityService.save(entities, transactionWindow, transactionTimeoutMs);
            logger.info("Created {} {}s", responses.size(), entityName);
            return ResponseEntity.status(HttpStatus.CREATED).body(responses);
        } catch (Exception e) {
            ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(
                    HttpStatus.BAD_REQUEST,
                    String.format("Failed to create %ss: %s", entityName, e.getMessage())
            );
            return ResponseEntity.of(problemDetail).build();
        }
    }

    /**
     * Updates multiple entities in batch.
     *
     * @param entities Collection of entities to update (must have id field)
     * @param transition Optional workflow transition name (null to stay in same state)
     * @return ResponseEntity with list of updated entities or error
     */
    public ResponseEntity<List<EntityWithMetadata<T>>> updateAll(
            List<T> entities,
            String transition) {
        return updateAll(entities, transition, null, null);
    }

    /**
     * Updates multiple entities in batch with transaction control parameters.
     *
     * @param entities Collection of entities to update (must have id field)
     * @param transition Optional workflow transition name (null to stay in same state)
     * @param transactionWindow Maximum number of entities per transaction (null for default)
     * @param transactionTimeoutMs Transaction timeout in milliseconds (null for default)
     * @return ResponseEntity with list of updated entities or error
     */
    public ResponseEntity<List<EntityWithMetadata<T>>> updateAll(
            List<T> entities,
            String transition,
            Integer transactionWindow,
            Long transactionTimeoutMs) {
        try {
            List<EntityWithMetadata<T>> responses = entityService.updateAll(
                    entities, transition, transactionWindow, transactionTimeoutMs);
            logger.info("Updated {} {}s", responses.size(), entityName);
            return ResponseEntity.ok(responses);
        } catch (Exception e) {
            ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(
                    HttpStatus.BAD_REQUEST,
                    String.format("Failed to update %ss: %s", entityName, e.getMessage())
            );
            return ResponseEntity.of(problemDetail).build();
        }
    }

    /**
     * Record for field filter specification.
     *
     * @param fieldName The JSON path field name (without $. prefix)
     * @param operation The comparison operation
     * @param value     The value to compare against
     */
    public record FieldFilter(String fieldName, SimpleConditionDto.OperatorTypeEnum operation, String value) {
        public static FieldFilter equals(String fieldName, String value) {
            return new FieldFilter(fieldName, SimpleConditionDto.OperatorTypeEnum.EQUALS, value);
        }

        public static FieldFilter contains(String fieldName, String value) {
            return new FieldFilter(fieldName, SimpleConditionDto.OperatorTypeEnum.CONTAINS, value);
        }

        public static FieldFilter greaterThan(String fieldName, String value) {
            return new FieldFilter(fieldName, SimpleConditionDto.OperatorTypeEnum.GREATER_THAN, value);
        }

        public static FieldFilter lessThan(String fieldName, String value) {
            return new FieldFilter(fieldName, SimpleConditionDto.OperatorTypeEnum.LESS_THAN, value);
        }
    }
}
