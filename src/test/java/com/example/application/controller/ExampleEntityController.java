package com.example.application.controller;

import com.example.application.entity.example_entity.version_1.ExampleEntity;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.java_template.common.dto.EntityWithMetadata;
import com.java_template.common.dto.PageResult;
import com.java_template.common.repository.SearchAndRetrievalParams;
import com.java_template.common.service.EntityService;
import com.java_template.common.util.CyodaExceptionUtil;
import jakarta.validation.Valid;
import lombok.Getter;
import lombok.Setter;
import org.cyoda.cloud.api.common.model.*;
import org.cyoda.cloud.api.event.common.EntityChangeMeta;
import org.cyoda.cloud.api.event.common.ModelSpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.net.URI;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * Golden Example Controller - Template for creating new controllers
 * <p>
 * This is a generified example controller that demonstrates:
 * - Proper REST controller implementation with Spring Boot
 * - EntityService integration patterns
 * - CRUD operations with EntityWithMetadata
 * - Performance-optimized EntityService methods
 * - Point-in-time query support for temporal data access
 * - Pagination support for large datasets
 * - Business identifier duplicate checking
 * - Workflow transition support
 * - Error handling with ProblemDetail (RFC 7807)
 * - Logging best practices
 * - Location header for created resources
 * <p>
 * SEARCH PATTERN GUIDANCE:
 * - In-memory search (inMemory=true): Use for small, bounded result sets that fit in memory
 * - Paginated search (inMemory=false): Use for large or unknown result set sizes with UI pagination
 * - Streaming (searchAsStream/streamAll): Use for processing large datasets without loading into memory
 * <p>
 * To create a new controller:
 * 1. Copy this file to your controller package
 * 2. Rename class from ExampleEntityController to YourEntityController
 * 3. Update entity type from ExampleEntity to your entity
 * 4. Update @RequestMapping path from "/ui/example" to your path
 * 5. Update ModelSpec entity name from "ExampleEntity" to your entity name
 * 6. Update business ID field name in findByBusinessId calls
 * 7. Add custom workflow transition endpoints as needed
 * 8. Add custom search/filter parameters as needed
 * 9. Update request/response DTOs for your specific use cases
 */
@RestController
@RequestMapping("/ui/example")
@CrossOrigin(origins = "*")
public class ExampleEntityController {

    private static final Logger logger = LoggerFactory.getLogger(ExampleEntityController.class);
    private final EntityService entityService;
    private final ObjectMapper objectMapper;

    public ExampleEntityController(EntityService entityService, ObjectMapper objectMapper) {
        this.entityService = entityService;
        this.objectMapper = objectMapper;
    }

    /**
     * Create a new entity
     * POST /ui/example
     * <p>
     * IMPORTANT: This endpoint checks for duplicate business identifiers before creating.
     * Since Cyoda does not enforce uniqueness of business identifiers, this must be
     * done at the application level. Returns 409 Conflict if entity already exists.
     * <p>
     * Returns 201 Created with Location header pointing to the new resource.
     */
    @PostMapping
    public ResponseEntity<EntityWithMetadata<ExampleEntity>> createEntity(@Valid @RequestBody ExampleEntity entity) {
        try {
            // Check for duplicate business identifier
            ModelSpec modelSpec = new ModelSpec().withName(ExampleEntity.ENTITY_NAME).withVersion(ExampleEntity.ENTITY_VERSION);
            EntityWithMetadata<ExampleEntity> existing = entityService.findByBusinessIdOrNull(
                    modelSpec, entity.getExampleId(), "exampleId", ExampleEntity.class);

            if (existing != null) {
                logger.warn("ExampleEntity with business ID {} already exists", entity.getExampleId());
                ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(
                    HttpStatus.CONFLICT,
                    String.format("ExampleEntity already exists with ID: %s", entity.getExampleId())
                );
                return ResponseEntity.of(problemDetail).build();
            }

            EntityWithMetadata<ExampleEntity> response = entityService.create(entity);
            logger.info("ExampleEntity created with ID: {}", response.metadata().getId());

            // Build Location header for the created resource
            URI location = ServletUriComponentsBuilder
                .fromCurrentRequest()
                .path("/{id}")
                .buildAndExpand(response.metadata().getId())
                .toUri();

            return ResponseEntity.created(location).body(response);
        } catch (Exception e) {
            ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_REQUEST,
                String.format("Failed to create entity: %s", e.getMessage())
            );
            return ResponseEntity.of(problemDetail).build();
        }
    }

    /**
     * Get entity by technical UUID (FASTEST method)
     * GET /ui/example/{id}?pointInTime=2025-10-03T10:15:30Z
     * <p>
     * Supports point-in-time queries to retrieve historical entity state.
     * Point-in-time parameter is optional - omit for current state.
     */
    @GetMapping("/{id}")
    public ResponseEntity<EntityWithMetadata<ExampleEntity>> getEntityById(
            @PathVariable UUID id,
            @RequestParam(required = false) OffsetDateTime pointInTime) {
        try {
            ModelSpec modelSpec = new ModelSpec().withName(ExampleEntity.ENTITY_NAME).withVersion(ExampleEntity.ENTITY_VERSION);
            EntityWithMetadata<ExampleEntity> response = entityService.getById(id, modelSpec, ExampleEntity.class, pointInTime);
            if (response == null) {
                return ResponseEntity.notFound().build();
            }
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_REQUEST,
                String.format("Failed to retrieve entity with ID '%s': %s", id, e.getMessage())
            );
            return ResponseEntity.of(problemDetail).build();
        }
    }

    /**
     * Get entity by business identifier (MEDIUM SPEED)
     * GET /ui/example/business/{exampleId}?pointInTime=2025-10-03T10:15:30Z
     * <p>
     * Supports point-in-time queries to retrieve historical entity state.
     * Point-in-time parameter is optional - omit for current state.
     */
    @GetMapping("/business/{exampleId}")
    public ResponseEntity<EntityWithMetadata<ExampleEntity>> getEntityByBusinessId(
            @PathVariable String exampleId,
            @RequestParam(required = false) OffsetDateTime pointInTime) {
        try {
            ModelSpec modelSpec = new ModelSpec().withName(ExampleEntity.ENTITY_NAME).withVersion(ExampleEntity.ENTITY_VERSION);
            EntityWithMetadata<ExampleEntity> response = entityService.findByBusinessId(
                    modelSpec, exampleId, "exampleId", ExampleEntity.class, pointInTime);

            if (response == null) {
                return ResponseEntity.notFound().build();
            }
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_REQUEST,
                String.format("Failed to retrieve entity with business ID '%s': %s", exampleId, e.getMessage())
            );
            return ResponseEntity.of(problemDetail).build();
        }
    }

    /**
     * Get entity change history metadata
     * GET /ui/example/{id}/changes?pointInTime=2025-10-03T10:15:30Z
     */
    @GetMapping("/{id}/changes")
    public ResponseEntity<List<EntityChangeMeta>> getEntityChangesMetadata(
            @PathVariable UUID id,
            @RequestParam(required = false) OffsetDateTime pointInTime) {
        try {
            List<EntityChangeMeta> changes =
                    entityService.getEntityChangesMetadata(id, pointInTime);
            return ResponseEntity.ok(changes);
        } catch (Exception e) {
            // Check if it's a NOT_FOUND error (entity doesn't exist)
            if (CyodaExceptionUtil.isNotFound(e)) {
                return ResponseEntity.notFound().build();
            }
            ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_REQUEST,
                String.format("Failed to retrieve change history for entity with ID '%s': %s", id, e.getMessage())
            );
            return ResponseEntity.of(problemDetail).build();
        }
    }

    /**
     * Update entity with optional workflow transition
     * PUT /ui/example/{id}?transition=TRANSITION_NAME
     * <p>
     * The transition parameter is optional. If provided, it triggers a workflow
     * transition after updating the entity data. The transition name must match
     * a transition defined in the entity's workflow configuration.
     */
    @PutMapping("/{id}")
    public ResponseEntity<EntityWithMetadata<ExampleEntity>> updateEntity(
            @PathVariable UUID id,
            @Valid @RequestBody ExampleEntity entity,
            @RequestParam(required = false) String transition) {
        try {
            EntityWithMetadata<ExampleEntity> response = entityService.update(id, entity, transition);
            logger.info("ExampleEntity updated with ID: {}", id);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_REQUEST,
                String.format("Failed to update entity with ID '%s': %s", id, e.getMessage())
            );
            return ResponseEntity.of(problemDetail).build();
        }
    }

    /**
     * EXAMPLE 1: LOW VOLUME - In-memory search for small, bounded result sets
     * GET /ui/example/by-category?category=PREMIUM&pointInTime=2025-10-03T10:15:30Z
     *<p>
     * WHEN TO USE:
     * - You expect a small number of results (e.g., < 1000 entities)
     * - The result set is bounded by nature (e.g., filtering by a specific category)
     * - You need all results at once for client-side processing
     *<p>
     * PATTERN: search() with inMemory=true
     * - Returns all matching results in a single call
     * - No pagination support (all results loaded into memory)
     * - Fast for small result sets
     * - DO NOT USE for unbounded queries or large result sets
     */
    @GetMapping("/by-category")
    public ResponseEntity<List<EntityWithMetadata<ExampleEntity>>> searchByCategory(
            @RequestParam String category,
            @RequestParam(required = false) OffsetDateTime pointInTime) {
        try {
            ModelSpec modelSpec = new ModelSpec().withName(ExampleEntity.ENTITY_NAME).withVersion(ExampleEntity.ENTITY_VERSION);

            SimpleConditionDto categoryCondition = new SimpleConditionDto()
                    .jsonPath("$.category")
                    .operatorType(SimpleConditionDto.OperatorTypeEnum.EQUALS)
                    .value(objectMapper.valueToTree(category));

            GroupConditionDto condition = new GroupConditionDto()
                    .operator(GroupConditionDto.OperatorEnum.AND)
                    .conditions(List.of(categoryCondition));

            // Use in-memory search for small, bounded result sets
            // inMemory=true loads all results into memory - only use for small result sets
            PageResult<EntityWithMetadata<ExampleEntity>> result = entityService.search(
                    modelSpec,
                    condition,
                    ExampleEntity.class,
                    SearchAndRetrievalParams.builder()
                            .pageSize(1000)
                            .pageNumber(0)
                            .pointInTime(pointInTime)
                            .inMemory(true)
                            .build());

            logger.info("Found {} entities in category '{}'", result.data().size(), category);
            return ResponseEntity.ok(result.data());
        } catch (Exception e) {
            ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_REQUEST,
                String.format("Failed to search entities by category '%s': %s", category, e.getMessage())
            );
            return ResponseEntity.of(problemDetail).build();
        }
    }

    /**
     * EXAMPLE 2: HIGH VOLUME PAGEABLE - Paginated search with searchId for efficient multi-page navigation
     * GET /ui/example/search?name=John&minAmount=100&page=0&size=50&searchId=uuid&pointInTime=2025-10-03T10:15:30Z
     *<p>
     * WHEN TO USE:
     * - Result set size is unknown or potentially large
     * - You need to support pagination in a UI
     * - You want efficient multi-page navigation without re-running the query
     *<p>
     * PATTERN: search() with inMemory=false and searchId support
     * - Returns PageResult with searchId for subsequent page requests
     * - Use searchId from previous response to get next page from cached snapshot
     * - Efficient for large result sets
     * - Supports point-in-time consistency across pages
     */
    @GetMapping("/search")
    public ResponseEntity<PageResult<EntityWithMetadata<ExampleEntity>>> searchWithPagination(
            @RequestParam(required = false) String name,
            @RequestParam(required = false) Double minAmount,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size,
            @RequestParam(required = false) UUID searchId,
            @RequestParam(required = false) OffsetDateTime pointInTime) {
        try {
            ModelSpec modelSpec = new ModelSpec().withName(ExampleEntity.ENTITY_NAME).withVersion(ExampleEntity.ENTITY_VERSION);

            List<GroupConditionDtoAllOfConditions> conditions = new ArrayList<>();

            if (name != null && !name.trim().isEmpty()) {
                conditions.add(new SimpleConditionDto()
                        .jsonPath("$.name")
                        .operatorType(SimpleConditionDto.OperatorTypeEnum.CONTAINS)
                        .value(objectMapper.valueToTree(name)));
            }

            if (minAmount != null) {
                conditions.add(new SimpleConditionDto()
                        .jsonPath("$.amount")
                        .operatorType(SimpleConditionDto.OperatorTypeEnum.GREATER_OR_EQUAL)
                        .value(objectMapper.valueToTree(minAmount)));
            }

            SearchAndRetrievalParams paginationParams =
                    SearchAndRetrievalParams.builder()
                            .pageSize(size)
                            .pageNumber(page)
                            .pointInTime(pointInTime)
                            .searchId(searchId)
                            .build();

            PageResult<EntityWithMetadata<ExampleEntity>> pageResult;
            if (conditions.isEmpty()) {
                // No filters: use findAll with searchId support
                pageResult = entityService.findAll(
                        modelSpec, ExampleEntity.class, paginationParams);
            } else {
                // With filters: use paginated search with searchId support
                GroupConditionDto condition = new GroupConditionDto()
                        .operator(GroupConditionDto.OperatorEnum.AND)
                        .conditions(conditions);

                // inMemory=false enables pagination with searchId support
                pageResult = entityService.search(
                        modelSpec, condition, ExampleEntity.class, paginationParams);
            }

            logger.info("Search returned page {} of {} (total: {} entities, searchId: {})",
                    pageResult.pageNumber(), pageResult.totalPages(), pageResult.totalElements(), pageResult.searchId());

            // Return PageResult directly - client can use searchId for next page
            return ResponseEntity.ok(pageResult);
        } catch (Exception e) {
            ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_REQUEST,
                String.format("Failed to search entities: %s", e.getMessage())
            );
            return ResponseEntity.of(problemDetail).build();
        }
    }

    /**
     * EXAMPLE 3: HIGH VOLUME STREAMING - Stream results for memory-efficient processing
     * POST /ui/example/export
     * <p>
     * WHEN TO USE:
     * - You need to process large result sets without loading everything into memory
     * - You're exporting data, generating reports, or performing batch operations
     * - Result set size is unknown or very large (e.g., millions of entities)
     * - You don't need random access to results, just sequential processing
     *<p>
     * PATTERN: searchAsStream() for memory-efficient processing
     * - Automatically handles pagination internally
     * - Processes entities one at a time or in small batches
     * - No memory pressure from large result sets
     * - Must close stream after use (use try-with-resources)
     */
    @PostMapping("/export")
    public ResponseEntity<String> exportEntities(
            @RequestBody(required = false) SearchRequest searchRequest) {
        try {
            ModelSpec modelSpec = new ModelSpec().withName(ExampleEntity.ENTITY_NAME).withVersion(ExampleEntity.ENTITY_VERSION);
            OffsetDateTime pointInTime = searchRequest != null ? searchRequest.getPointInTime() : null;

            // Build search condition if criteria provided
            GroupConditionDto condition = null;
            if (searchRequest != null) {
                List<GroupConditionDtoAllOfConditions> conditions = new ArrayList<>();

                if (searchRequest.getName() != null && !searchRequest.getName().trim().isEmpty()) {
                    conditions.add(new SimpleConditionDto()
                            .jsonPath("$.name")
                            .operatorType(SimpleConditionDto.OperatorTypeEnum.CONTAINS)
                            .value(objectMapper.valueToTree(searchRequest.getName())));
                }

                if (searchRequest.getMinAmount() != null) {
                    conditions.add(new SimpleConditionDto()
                            .jsonPath("$.amount")
                            .operatorType(SimpleConditionDto.OperatorTypeEnum.GREATER_OR_EQUAL)
                            .value(objectMapper.valueToTree(searchRequest.getMinAmount())));
                }

                if (!conditions.isEmpty()) {
                    condition = new GroupConditionDto()
                            .operator(GroupConditionDto.OperatorEnum.AND)
                            .conditions(conditions);
                }
            }

            // Use streaming for memory-efficient processing
            // The stream MUST be closed after use - use try-with-resources
            long count;
            if (condition == null) {
                // No filters: stream all entities
                try (Stream<EntityWithMetadata<ExampleEntity>> stream =
                        entityService.streamAll(modelSpec, ExampleEntity.class,
                                SearchAndRetrievalParams.builder()
                                        .pageSize(100)
                                        .pointInTime(pointInTime)
                                        .build())) {

                    // Process each entity as it's retrieved (no memory pressure)
                    count = stream.count();
                }
            } else {
                // With filters: stream matching entities
                try (Stream<EntityWithMetadata<ExampleEntity>> stream =
                        entityService.searchAsStream(modelSpec, condition, ExampleEntity.class,
                                SearchAndRetrievalParams.builder()
                                        .pageSize(100)
                                        .inMemory(false)
                                        .pointInTime(pointInTime)
                                        .build())) {

                    // Process each entity as it's retrieved (no memory pressure)
                    count = stream.count();
                }
            }

            logger.info("Exported {} entities", count);
            return ResponseEntity.ok(String.format("Exported %d entities", count));
        } catch (Exception e) {
            ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_REQUEST,
                String.format("Failed to export entities: %s", e.getMessage())
            );
            return ResponseEntity.of(problemDetail).build();
        }
    }

    /**
     * Example workflow transition endpoint
     * POST /ui/example/{id}/approve
     * <p>
     * Example of a custom endpoint that triggers a workflow transition.
     * This pattern is useful for business actions that should be exposed
     * as explicit REST operations rather than generic PUT with transition parameter.
     */
    @PostMapping("/{id}/approve")
    public ResponseEntity<EntityWithMetadata<ExampleEntity>> approveEntity(@PathVariable UUID id) {
        try {
            ModelSpec modelSpec = new ModelSpec().withName(ExampleEntity.ENTITY_NAME).withVersion(ExampleEntity.ENTITY_VERSION);
            EntityWithMetadata<ExampleEntity> current = entityService.getById(id, modelSpec, ExampleEntity.class);

            EntityWithMetadata<ExampleEntity> response = entityService.update(id, current.entity(), "approve");
            logger.info("ExampleEntity approved with ID: {}", id);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_REQUEST,
                String.format("Failed to approve entity with ID '%s': %s", id, e.getMessage())
            );
            return ResponseEntity.of(problemDetail).build();
        }
    }

    /**
     * Delete entity by technical UUID
     * DELETE /ui/example/{id}
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteEntity(@PathVariable UUID id) {
        try {
            entityService.deleteById(id);
            logger.info("ExampleEntity deleted with ID: {}", id);
            return ResponseEntity.noContent().build();
        } catch (Exception e) {
            ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_REQUEST,
                String.format("Failed to delete entity with ID '%s': %s", id, e.getMessage())
            );
            return ResponseEntity.of(problemDetail).build();
        }
    }

    /**
     * Delete entity by business identifier
     * DELETE /ui/example/business/{exampleId}
     */
    @DeleteMapping("/business/{exampleId}")
    public ResponseEntity<Void> deleteEntityByBusinessId(@PathVariable String exampleId) {
        try {
            ModelSpec modelSpec = new ModelSpec().withName(ExampleEntity.ENTITY_NAME).withVersion(ExampleEntity.ENTITY_VERSION);
            boolean deleted = entityService.deleteByBusinessId(modelSpec, exampleId, "exampleId", ExampleEntity.class);

            if (!deleted) {
                return ResponseEntity.notFound().build();
            }

            logger.info("ExampleEntity deleted with business ID: {}", exampleId);
            return ResponseEntity.noContent().build();
        } catch (Exception e) {
            ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_REQUEST,
                String.format("Failed to delete entity with business ID '%s': %s", exampleId, e.getMessage())
            );
            return ResponseEntity.of(problemDetail).build();
        }
    }

    /**
     * Delete all entities (DANGEROUS - use with caution)
     * DELETE /ui/example
     * <p>
     * WARNING: This deletes ALL entities of this type. Use with extreme caution.
     * Consider requiring additional authorization or confirmation for this endpoint.
     */
    @DeleteMapping
    public ResponseEntity<String> deleteAllEntities() {
        try {
            ModelSpec modelSpec = new ModelSpec().withName(ExampleEntity.ENTITY_NAME).withVersion(ExampleEntity.ENTITY_VERSION);
            Integer deletedCount = entityService.deleteAll(modelSpec);
            logger.warn("Deleted all ExampleEntities - count: {}", deletedCount);
            return ResponseEntity.ok().body(String.format("Deleted %d entities", deletedCount));
        } catch (Exception e) {
            ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_REQUEST,
                String.format("Failed to delete all entities: %s", e.getMessage())
            );
            return ResponseEntity.of(problemDetail).build();
        }
    }

    // ========================================
    // Request DTOs for specific operations
    // ========================================

    /**
     * DTO for advanced search requests
     */
    @Getter
    @Setter
    public static class SearchRequest {
        private String name;
        private Double minAmount;
        private Double maxAmount;
        private OffsetDateTime pointInTime;
    }
}
