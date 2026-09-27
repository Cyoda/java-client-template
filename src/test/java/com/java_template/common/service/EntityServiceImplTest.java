package com.java_template.common.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.java_template.common.dto.EntityWithMetadata;
import com.java_template.common.dto.PageResult;
import com.java_template.common.repository.CrudRepository;
import com.java_template.common.repository.SearchAndRetrievalParams;
import com.java_template.common.workflow.CyodaEntity;
import com.java_template.common.workflow.OperationSpecification;
import lombok.Getter;
import lombok.Setter;
import org.cyoda.cloud.api.common.model.GroupConditionDto;
import org.cyoda.cloud.api.common.model.SimpleConditionDto;
import org.cyoda.cloud.api.event.common.DataPayload;
import org.cyoda.cloud.api.event.common.EntityChangeMeta;
import org.cyoda.cloud.api.event.common.EntityMetadata;
import org.cyoda.cloud.api.event.common.ModelSpec;
import org.cyoda.cloud.api.event.entity.EntityDeleteAllResponse;
import org.cyoda.cloud.api.event.entity.EntityDeleteResponse;
import org.cyoda.cloud.api.event.entity.EntityTransactionInfo;
import org.cyoda.cloud.api.event.entity.EntityTransactionResponse;
import org.cyoda.uuid.SimpleSystemClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for EntityServiceImpl focusing on repository interactions,
 * error handling, and business logic validation.
 */
@ExtendWith(MockitoExtension.class)
class EntityServiceImplTest {

    // Test constants
    private static final String MODEL_NAME = "test-entity";
    private static final int MODEL_VERSION = 1;
    private static final String BUSINESS_ID_FIELD = "name";
    private static final String TRANSITION_ACTIVATE = "ACTIVATE";

    @Mock
    private CrudRepository repository;

    private ObjectMapper objectMapper;
    private EntityServiceImpl entityService;
    private UUID testEntityId;
    private UUID testEntityId2;
    private TestEntity testEntity;
    private TestEntity testEntity2;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        entityService = new EntityServiceImpl(repository, objectMapper);
        testEntityId = SimpleSystemClock.INSTANCE.uniqueTimeUUIDinMicros();
        testEntityId2 = SimpleSystemClock.INSTANCE.uniqueTimeUUIDinMicros();
        testEntity = new TestEntity(123L, "Test Entity", "ACTIVE");
        testEntity2 = new TestEntity(456L, "Test Entity 2", "INACTIVE");
    }

    // ========================================
    // TEST DATA BUILDERS
    // ========================================

    private DataPayload createTestDataPayload(TestEntity entity, UUID entityId, String state) {
        DataPayload payload = new DataPayload();
        payload.setData(objectMapper.valueToTree(entity));

        // Create EntityMetadata using the actual Cyoda class
        EntityMetadata metadata = new EntityMetadata();
        metadata.setId(entityId);
        metadata.setState(state);
        metadata.setCreationDate(new Date());
        payload.setMeta(objectMapper.valueToTree(metadata));

        return payload;
    }

    private DataPayload createTestDataPayload(TestEntity entity, UUID entityId) {
        return createTestDataPayload(entity, entityId, entity.getStatus());
    }

    private EntityTransactionResponse createTransactionResponse(UUID entityId) {
        EntityTransactionResponse response = new EntityTransactionResponse();
        EntityTransactionInfo transactionInfo = new EntityTransactionInfo();
        transactionInfo.setEntityIds(List.of(entityId));
        transactionInfo.setTransactionId(SimpleSystemClock.INSTANCE.uniqueTimeUUIDinMicros());
        response.setTransactionInfo(transactionInfo);
        return response;
    }

    private EntityDeleteResponse createDeleteResponse(UUID entityId) {
        EntityDeleteResponse response = new EntityDeleteResponse();
        response.setEntityId(entityId);
        return response;
    }

    private EntityDeleteAllResponse createDeleteAllResponse(int numDeleted) {
        EntityDeleteAllResponse response = new EntityDeleteAllResponse();
        response.setNumDeleted(numDeleted);
        return response;
    }

    private GroupConditionDto createActiveStatusCondition() {
        ObjectMapper objectMapper = new ObjectMapper();
        SimpleConditionDto simpleCondition = new SimpleConditionDto()
                .jsonPath("$.status")
                .operatorType(SimpleConditionDto.OperatorTypeEnum.EQUALS)
                .value(objectMapper.valueToTree("ACTIVE"));

        return new GroupConditionDto()
                .operator(GroupConditionDto.OperatorEnum.AND)
                .conditions(List.of(simpleCondition));
    }

    private ModelSpec createTestModelSpec() {
        return new ModelSpec().withName(MODEL_NAME).withVersion(MODEL_VERSION);
    }

    private void assertEntityMatches(TestEntity actual, TestEntity expected) {
        assertEquals(expected.getId(), actual.getId());
        assertEquals(expected.getName(), actual.getName());
        assertEquals(expected.getStatus(), actual.getStatus());
    }

    private void assertMetadata(EntityWithMetadata<?> result, UUID expectedId, String expectedState) {
        assertEquals(expectedId, result.metadata().getId());
        assertEquals(expectedState, result.metadata().getState());
    }

    private void assertRepositoryFailure(Runnable operation, String expectedCauseMessage) {
        RuntimeException exception = assertThrows(RuntimeException.class, operation::run);
        // EntityService doesn't wrap exceptions, so the repository exception bubbles up directly
        assertTrue(exception.getMessage().contains(expectedCauseMessage),
                "Expected message to contain '" + expectedCauseMessage + "' but was: " + exception.getMessage());
    }

    // Test entity class
    @Setter
    @Getter
    static class TestEntity implements CyodaEntity {
        private Long id;
        private String name;
        private String status;

        @SuppressWarnings("unused") // Used by Jackson
        public TestEntity() {}

        public TestEntity(Long id, String name, String status) {
            this.id = id;
            this.name = name;
            this.status = status;
        }

        @Override
        public OperationSpecification getModelKey() {
            ModelSpec modelSpec = new ModelSpec();
            modelSpec.setName(MODEL_NAME);
            modelSpec.setVersion(MODEL_VERSION);
            return new OperationSpecification.Entity(modelSpec, MODEL_NAME);
        }

        @Override
        public boolean isValid(EntityMetadata metadata) {
            return id != null && name != null && !name.trim().isEmpty();
        }
    }

    // ========================================
    // REPOSITORY FAILURE TESTS
    // ========================================

    @Test
    @DisplayName("getById should handle repository failure gracefully")
    void testGetByIdRepositoryFailure() {
        when(repository.findById(eq(testEntityId), isNull()))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException("Repository error")));

        assertRepositoryFailure(() -> entityService.getById(testEntityId, createTestModelSpec(), TestEntity.class), "Repository error");
        verify(repository).findById(eq(testEntityId), isNull());
    }

    @Test
    @DisplayName("deleteById should handle repository failure gracefully")
    void testDeleteByIdRepositoryFailure() {
        when(repository.deleteById(testEntityId))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException("Delete failed")));

        assertRepositoryFailure(() -> entityService.deleteById(testEntityId), "Delete failed");
        verify(repository).deleteById(testEntityId);
    }

    @Test
    @DisplayName("save should handle repository failure gracefully")
    void testCreateRepositoryFailure() {
        when(repository.save(eq(createTestModelSpec()), any()))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException("Save failed")));

        assertRepositoryFailure(() -> entityService.create(testEntity), "Save failed");
        verify(repository).save(eq(createTestModelSpec()), any());
    }

    @Test
    @DisplayName("update should handle repository failure gracefully")
    void testUpdateRepositoryFailure() {
        when(repository.update(eq(testEntityId), any(), isNull()))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException("Update failed")));

        assertRepositoryFailure(() -> entityService.update(testEntityId, testEntity, null), "Update failed");
        verify(repository).update(eq(testEntityId), any(), isNull());
    }

    // ========================================
    // SUCCESSFUL REPOSITORY INTERACTIONS
    // ========================================

    @Test
    @DisplayName("getById should return EntityWithMetadata when successful")
    void testGetByIdSuccess() {
        DataPayload dataPayload = createTestDataPayload(testEntity, testEntityId);
        when(repository.findById(eq(testEntityId), isNull()))
                .thenReturn(CompletableFuture.completedFuture(dataPayload));

        EntityWithMetadata<TestEntity> result = entityService.getById(testEntityId, createTestModelSpec(), TestEntity.class);

        assertNotNull(result);
        assertNotNull(result.entity());
        assertNotNull(result.metadata());
        assertEntityMatches(result.entity(), testEntity);
        assertMetadata(result, testEntityId, testEntity.getStatus());
        verify(repository).findById(eq(testEntityId), isNull());
    }

    @Test
    @DisplayName("save should return EntityWithMetadata when successful")
    void testCreateSuccess() {
        UUID savedEntityId = SimpleSystemClock.INSTANCE.uniqueTimeUUIDinMicros();
        EntityTransactionResponse transactionResponse = createTransactionResponse(savedEntityId);
        when(repository.save(eq(createTestModelSpec()), any()))
                .thenReturn(CompletableFuture.completedFuture(transactionResponse));
        when(repository.findById(eq(savedEntityId), any()))
                .thenReturn(CompletableFuture.completedFuture(createTestDataPayload(testEntity, savedEntityId)));
        when(repository.getEntityChangesMetadata(eq(savedEntityId), isNull()))
                .thenReturn(CompletableFuture.completedFuture(List.of(
                        new EntityChangeMeta()
                                .withTransactionId(transactionResponse.getTransactionInfo().getTransactionId())
                                .withTimeOfChange(new Date())
                )));

        EntityWithMetadata<TestEntity> result = entityService.create(testEntity);

        assertNotNull(result);
        assertNotNull(result.entity());
        assertNotNull(result.metadata());
        assertEntityMatches(result.entity(), testEntity);
        assertEquals(savedEntityId, result.metadata().getId());
        verify(repository).save(eq(createTestModelSpec()), any());
    }

    @Test
    @DisplayName("deleteById should return entity ID when successful")
    void testDeleteByIdSuccess() {
        EntityDeleteResponse deleteResponse = createDeleteResponse(testEntityId);
        when(repository.deleteById(testEntityId))
                .thenReturn(CompletableFuture.completedFuture(deleteResponse));

        UUID result = entityService.deleteById(testEntityId);

        assertEquals(testEntityId, result);
        verify(repository).deleteById(testEntityId);
    }

    @Test
    @DisplayName("deleteAll should sum deletion counts correctly")
    void testDeleteAllSuccess() {
        List<EntityDeleteAllResponse> responses = List.of(
                createDeleteAllResponse(5),
                createDeleteAllResponse(3)
        );
        when(repository.deleteAll(createTestModelSpec()))
                .thenReturn(CompletableFuture.completedFuture(responses));

        Integer result = entityService.deleteAll(createTestModelSpec());

        assertEquals(8, result);
        verify(repository).deleteAll(createTestModelSpec());
    }

    @Test
    @DisplayName("deleteAll with model parameters should work correctly")
    void testDeleteAllWithModelParameters() {
        String customModel = "custom-model";
        int customVersion = 2;
        ModelSpec customModelSpec = new ModelSpec().withName(customModel).withVersion(customVersion);
        List<EntityDeleteAllResponse> responses = List.of(createDeleteAllResponse(10));
        when(repository.deleteAll(eq(customModelSpec)))
                .thenReturn(CompletableFuture.completedFuture(responses));

        Integer result = entityService.deleteAll(customModelSpec);

        assertEquals(10, result);
        verify(repository).deleteAll(eq(customModelSpec));
    }

    // ========================================
    // BUSINESS LOGIC TESTS
    // ========================================

    @Test
    @DisplayName("update should pass null transition directly to repository")
    void testUpdateWithNullTransition() {
        EntityTransactionResponse response = createTransactionResponse(testEntityId);
        when(repository.update(eq(testEntityId), any(), isNull()))
                .thenReturn(CompletableFuture.completedFuture(response));
        when(repository.findById(eq(testEntityId), any()))
                .thenReturn(CompletableFuture.completedFuture(createTestDataPayload(testEntity, testEntityId)));
        when(repository.getEntityChangesMetadata(eq(testEntityId), isNull()))
                .thenReturn(CompletableFuture.completedFuture(List.of(
                        new EntityChangeMeta()
                                .withTransactionId(response.getTransactionInfo().getTransactionId())
                                .withTimeOfChange(new Date())
                )));

        EntityWithMetadata<TestEntity> result = entityService.update(testEntityId, testEntity, null);

        assertNotNull(result);
        assertNotNull(result.entity());
        assertNotNull(result.metadata());
        assertEntityMatches(result.entity(), testEntity);
        assertEquals(testEntityId, result.metadata().getId());
        verify(repository).update(eq(testEntityId), any(), isNull());
    }

    @Test
    @DisplayName("update should use provided transition when not null")
    void testUpdateWithCustomTransition() {
        EntityTransactionResponse response = createTransactionResponse(testEntityId);
        when(repository.update(eq(testEntityId), any(), eq(TRANSITION_ACTIVATE)))
                .thenReturn(CompletableFuture.completedFuture(response));
        when(repository.findById(eq(testEntityId), any()))
                .thenReturn(CompletableFuture.completedFuture(createTestDataPayload(testEntity, testEntityId)));
        when(repository.getEntityChangesMetadata(eq(testEntityId), isNull()))
                .thenReturn(CompletableFuture.completedFuture(List.of(
                        new EntityChangeMeta()
                                .withTransactionId(response.getTransactionInfo().getTransactionId())
                                .withTimeOfChange(new Date())
                )));

        EntityWithMetadata<TestEntity> result = entityService.update(testEntityId, testEntity, TRANSITION_ACTIVATE);

        assertNotNull(result);
        assertNotNull(result.entity());
        assertNotNull(result.metadata());
        assertEntityMatches(result.entity(), testEntity);
        assertEquals(testEntityId, result.metadata().getId());
        verify(repository).update(eq(testEntityId), any(), eq(TRANSITION_ACTIVATE));
    }

    // ========================================
    // EDGE CASES AND VALIDATION
    // ========================================

    @Test
    @DisplayName("saveAll should return empty list when no entities provided")
    void testCreateAllEmptyCollection() {
        Collection<TestEntity> emptyEntities = List.of();

        var result = entityService.save(emptyEntities);

        assertNotNull(result);
        assertTrue(result.isEmpty());
        verify(repository, never()).saveAll(any(ModelSpec.class), any());
    }

    @Test
    @DisplayName("updateAll should return empty list when no entities provided")
    void testUpdateAllEmptyCollection() {
        Collection<TestEntity> emptyEntities = List.of();

        var result = entityService.updateAll(emptyEntities, TRANSITION_ACTIVATE);

        assertNotNull(result);
        assertTrue(result.isEmpty());
        verify(repository, never()).updateAll(any(), anyString());
    }

    @Test
    @DisplayName("updateAll should pass null transition directly to repository")
    void testUpdateAllWithNullTransition() {
        Collection<TestEntity> entities = List.of(testEntity);
        List<EntityTransactionResponse> responses = List.of(createTransactionResponse(testEntityId));
        when(repository.updateAll(any(), isNull(),any(), any()))
                .thenReturn(CompletableFuture.completedFuture(responses));

        when(repository.findById(eq(testEntityId), any()))
                .thenReturn(CompletableFuture.completedFuture(createTestDataPayload(testEntity, testEntityId)));
        when(repository.getEntityChangesMetadata(eq(testEntityId), isNull()))
                .thenReturn(CompletableFuture.completedFuture(List.of(
                        new EntityChangeMeta()
                                .withTransactionId(responses.getFirst().getTransactionInfo().getTransactionId())
                                .withTimeOfChange(new Date())
                )));

        List<EntityWithMetadata<TestEntity>> result = entityService.updateAll(entities, null);

        assertNotNull(result);
        assertEquals(1, result.size());
        EntityWithMetadata<TestEntity> entityWithMetadata = result.getFirst();
        assertNotNull(entityWithMetadata.entity());
        assertNotNull(entityWithMetadata.metadata());
        assertEntityMatches(entityWithMetadata.entity(), testEntity);
        assertEquals(testEntityId, entityWithMetadata.metadata().getId());
        verify(repository).updateAll(any(), isNull(), any(), any());
    }

    // ========================================
    // MISSING COVERAGE TESTS
    // ========================================

    @Test
    @DisplayName("findByBusinessId should call repository.findAllByCriteria with correct search condition")
    void testFindByBusinessIdRepositoryCall() {
        TestEntity entityWithBusinessId = new TestEntity(123L, "TEST-123", "ACTIVE");
        List<DataPayload> payloads = List.of(createTestDataPayload(entityWithBusinessId, testEntityId));
        when(repository.findAllByCriteria(eq(createTestModelSpec()), any(GroupConditionDto.class), any()))
                .thenReturn(CompletableFuture.completedFuture(PageResult.of(SimpleSystemClock.INSTANCE.uniqueTimeUUIDinMicros(), payloads, 0, 1, payloads.size())));

        EntityWithMetadata<TestEntity> result = entityService.findByBusinessId(createTestModelSpec(), "TEST-123", BUSINESS_ID_FIELD, TestEntity.class);

        assertNotNull(result);
        assertNotNull(result.entity());
        assertNotNull(result.metadata());
        assertEntityMatches(result.entity(), entityWithBusinessId);
        assertMetadata(result, testEntityId, entityWithBusinessId.getStatus());
        verify(repository).findAllByCriteria(eq(createTestModelSpec()), any(GroupConditionDto.class), any());
    }

    @Test
    @DisplayName("findByBusinessId should handle repository failure")
    void testFindByBusinessIdRepositoryFailure() {
        when(repository.findAllByCriteria(eq(createTestModelSpec()), any(GroupConditionDto.class), any()))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException("Search failed")));

        assertRepositoryFailure(() -> entityService.findByBusinessId(createTestModelSpec(), "TEST-123", BUSINESS_ID_FIELD, TestEntity.class),
                "Search failed");
        verify(repository).findAllByCriteria(eq(createTestModelSpec()), any(GroupConditionDto.class), any());
    }

    @Test
    @DisplayName("findAll should call repository.findAll with correct model parameters")
    void testFindAllRepositoryCall() {
        List<DataPayload> payloads = List.of(createTestDataPayload(testEntity, testEntityId));
        when(repository.findAll(eq(createTestModelSpec()), any()))
                .thenReturn(CompletableFuture.completedFuture(PageResult.of(SimpleSystemClock.INSTANCE.uniqueTimeUUIDinMicros(), payloads, 0, 100, payloads.size())));

        PageResult<EntityWithMetadata<TestEntity>> result = entityService.findAll(createTestModelSpec(), TestEntity.class);

        assertNotNull(result);
        assertEquals(1, result.data().size());
        EntityWithMetadata<TestEntity> entityWithMetadata = result.data().getFirst();
        assertEntityMatches(entityWithMetadata.entity(), testEntity);
        assertMetadata(entityWithMetadata, testEntityId, testEntity.getStatus());
        verify(repository).findAll(eq(createTestModelSpec()), any());
    }

    @Test
    @DisplayName("findAll should handle repository failure")
    void testFindAllRepositoryFailure() {
        when(repository.findAll(eq(createTestModelSpec()), any()))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException("Find all failed")));

        assertRepositoryFailure(() -> entityService.findAll(createTestModelSpec(), TestEntity.class), "Find all failed");
        verify(repository).findAll(eq(createTestModelSpec()), any());
    }

    @Test
    @DisplayName("search should call repository.findAllByCriteria with search condition")
    void testSearchRepositoryCall() {
        GroupConditionDto condition = createActiveStatusCondition();
        List<DataPayload> payloads = List.of(createTestDataPayload(testEntity, testEntityId));
        when(repository.findAllByCriteria(eq(createTestModelSpec()), any(GroupConditionDto.class), any()))
                .thenReturn(CompletableFuture.completedFuture(PageResult.of(SimpleSystemClock.INSTANCE.uniqueTimeUUIDinMicros(), payloads, 0, 100, payloads.size())));

        PageResult<EntityWithMetadata<TestEntity>> result = entityService.search(createTestModelSpec(), condition, TestEntity.class);

        assertNotNull(result);
        assertEquals(1, result.data().size());
        EntityWithMetadata<TestEntity> entityWithMetadata = result.data().getFirst();
        assertEntityMatches(entityWithMetadata.entity(), testEntity);
        assertMetadata(entityWithMetadata, testEntityId, testEntity.getStatus());
        verify(repository).findAllByCriteria(eq(createTestModelSpec()), any(GroupConditionDto.class), any());
    }

    @Test
    @DisplayName("search should handle repository failure")
    void testSearchRepositoryFailure() {
        GroupConditionDto condition = createActiveStatusCondition();
        when(repository.findAllByCriteria(eq(createTestModelSpec()), any(GroupConditionDto.class), any()))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException("Search failed")));

        assertRepositoryFailure(() -> entityService.search(createTestModelSpec(), condition, TestEntity.class), "Search failed");
        verify(repository).findAllByCriteria(eq(createTestModelSpec()), any(GroupConditionDto.class), any());
    }

    // ========================================
    // ADDITIONAL COVERAGE TESTS
    // ========================================


    @Test
    @DisplayName("deleteAll should handle repository failure")
    void testDeleteAllRepositoryFailure() {
        when(repository.deleteAll(createTestModelSpec()))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException("Delete all failed")));

        assertRepositoryFailure(() -> entityService.deleteAll(createTestModelSpec()), "Delete all failed");
        verify(repository).deleteAll(createTestModelSpec());
    }

    @Test
    @DisplayName("deleteAll with model parameters should handle repository failure")
    void testDeleteAllWithModelParametersFailure() {
        String customModel = "custom-model";
        int customVersion = 2;
        ModelSpec customModelSpec = new ModelSpec().withName(customModel).withVersion(customVersion);
        when(repository.deleteAll(customModelSpec))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException("Delete failed")));

        assertRepositoryFailure(() -> entityService.deleteAll(customModelSpec), "Delete failed");
        verify(repository).deleteAll(customModelSpec);
    }

    @Test
    @DisplayName("getItems should handle repository failure")
    void testGetItemsRepositoryFailure() {
        when(repository.findAll(eq(createTestModelSpec()), any()))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException("Find all failed")));

        assertRepositoryFailure(() -> entityService.findAll(createTestModelSpec(), TestEntity.class),
                "Find all failed");
        verify(repository).findAll(eq(createTestModelSpec()), any());
    }

    @Test
    @DisplayName("updateByBusinessId should handle repository failure during find")
    void testUpdateByBusinessIdFindFailure() {
        testEntity.setName("TEST-123");
        when(repository.findAllByCriteria(eq(createTestModelSpec()), any(GroupConditionDto.class), any()))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException("Find failed")));

        assertRepositoryFailure(() -> entityService.updateByBusinessId(testEntity, BUSINESS_ID_FIELD, TRANSITION_ACTIVATE),
                "Find failed");
        verify(repository).findAllByCriteria(eq(createTestModelSpec()), any(GroupConditionDto.class), any());
        verify(repository, never()).update(any(UUID.class), any(), anyString());
    }

    @Test
    @DisplayName("updateByBusinessId should handle entity not found")
    void testUpdateByBusinessIdEntityNotFound() {
        testEntity.setName("NONEXISTENT");
        when(repository.findAllByCriteria(eq(createTestModelSpec()), any(GroupConditionDto.class), any()))
                .thenReturn(CompletableFuture.completedFuture(PageResult.of(SimpleSystemClock.INSTANCE.uniqueTimeUUIDinMicros(), List.of(), 0, 1, 0L)));

        RuntimeException exception = assertThrows(RuntimeException.class,
                () -> entityService.updateByBusinessId(testEntity, BUSINESS_ID_FIELD, TRANSITION_ACTIVATE));
        assertTrue(exception.getMessage().contains("Entity not found with business ID"));
        verify(repository).findAllByCriteria(eq(createTestModelSpec()), any(GroupConditionDto.class), any());
        verify(repository, never()).update(any(UUID.class), any(), anyString());
    }

    @Test
    @DisplayName("updateByBusinessId should successfully update entity when found")
    void testUpdateByBusinessIdSuccess() {
        testEntity.setName("TEST-123");
        UUID existingEntityTechnicalId = SimpleSystemClock.INSTANCE.uniqueTimeUUIDinMicros();

        TestEntity foundEntity = new TestEntity(123L, "TEST-123", "ACTIVE");
        DataPayload foundPayload = createTestDataPayload(foundEntity, existingEntityTechnicalId);
        when(repository.findAllByCriteria(eq(createTestModelSpec()), any(GroupConditionDto.class), any()))
                .thenReturn(CompletableFuture.completedFuture(PageResult.of(SimpleSystemClock.INSTANCE.uniqueTimeUUIDinMicros(), List.of(foundPayload), 0, 1, 1L)));

        EntityTransactionResponse updateResponse = createTransactionResponse(existingEntityTechnicalId);
        when(repository.update(eq(existingEntityTechnicalId), any(), eq(TRANSITION_ACTIVATE)))
                .thenReturn(CompletableFuture.completedFuture(updateResponse));

        when(repository.findById(eq(existingEntityTechnicalId), any()))
                .thenReturn(CompletableFuture.completedFuture(foundPayload));
        when(repository.getEntityChangesMetadata(eq(existingEntityTechnicalId), isNull()))
                .thenReturn(CompletableFuture.completedFuture(List.of(
                        new EntityChangeMeta()
                                .withTransactionId(updateResponse.getTransactionInfo().getTransactionId())
                                .withTimeOfChange(new Date())
                )));

        EntityWithMetadata<TestEntity> result = entityService.updateByBusinessId(testEntity, BUSINESS_ID_FIELD, TRANSITION_ACTIVATE);

        assertNotNull(result);
        assertNotNull(result.entity());
        assertNotNull(result.metadata());
        assertEntityMatches(result.entity(), testEntity);
        assertEquals(existingEntityTechnicalId, result.metadata().getId());
        verify(repository).findAllByCriteria(eq(createTestModelSpec()), any(GroupConditionDto.class), any());
        verify(repository).update(eq(existingEntityTechnicalId), any(), eq(TRANSITION_ACTIVATE));
    }

    @Test
    @DisplayName("deleteByBusinessId should handle repository failure during find")
    void testDeleteByBusinessIdFindFailure() {
        when(repository.findAllByCriteria(eq(createTestModelSpec()), any(GroupConditionDto.class), any()))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException("Find failed")));

        assertRepositoryFailure(() -> entityService.deleteByBusinessId(createTestModelSpec(), "TEST-123", BUSINESS_ID_FIELD, TestEntity.class),
                "Find failed");
        verify(repository).findAllByCriteria(eq(createTestModelSpec()), any(GroupConditionDto.class), any());
        verify(repository, never()).deleteById(any(UUID.class));
    }

    @Test
    @DisplayName("deleteByBusinessId should return false when entity not found")
    void testDeleteByBusinessIdEntityNotFound() {
        when(repository.findAllByCriteria(eq(createTestModelSpec()), any(GroupConditionDto.class), any()))
                .thenReturn(CompletableFuture.completedFuture(PageResult.of(SimpleSystemClock.INSTANCE.uniqueTimeUUIDinMicros(), List.of(), 0, 1, 0L)));

        boolean result = entityService.deleteByBusinessId(createTestModelSpec(), "NONEXISTENT", BUSINESS_ID_FIELD, TestEntity.class);

        assertFalse(result);
        verify(repository).findAllByCriteria(eq(createTestModelSpec()), any(GroupConditionDto.class), any());
        verify(repository, never()).deleteById(any(UUID.class));
    }

    @Test
    @DisplayName("deleteByBusinessId should successfully delete entity when found")
    void testDeleteByBusinessIdSuccess() {
        UUID entityTechnicalId = SimpleSystemClock.INSTANCE.uniqueTimeUUIDinMicros();
        TestEntity foundEntity = new TestEntity(123L, "TEST-123", "ACTIVE");
        DataPayload foundPayload = createTestDataPayload(foundEntity, entityTechnicalId);
        when(repository.findAllByCriteria(eq(createTestModelSpec()), any(GroupConditionDto.class), any()))
                .thenReturn(CompletableFuture.completedFuture(PageResult.of(SimpleSystemClock.INSTANCE.uniqueTimeUUIDinMicros(), List.of(foundPayload), 0, 1, 1L)));

        EntityDeleteResponse deleteResponse = createDeleteResponse(entityTechnicalId);
        when(repository.deleteById(entityTechnicalId))
                .thenReturn(CompletableFuture.completedFuture(deleteResponse));

        boolean result = entityService.deleteByBusinessId(createTestModelSpec(), "TEST-123", BUSINESS_ID_FIELD, TestEntity.class);

        assertTrue(result);
        verify(repository).findAllByCriteria(eq(createTestModelSpec()), any(GroupConditionDto.class), any());
        verify(repository).deleteById(entityTechnicalId);
    }

    @Test
    @DisplayName("saveAll should call repository.saveAll with non-empty collection")
    void testCreateAllWithEntitiesRepositoryCall() {
        Collection<TestEntity> entities = List.of(testEntity, testEntity2);
        EntityTransactionInfo transactionInfo = new EntityTransactionInfo();
        transactionInfo.setEntityIds(List.of(testEntityId, testEntityId2));
        transactionInfo.setTransactionId(SimpleSystemClock.INSTANCE.uniqueTimeUUIDinMicros());
        EntityTransactionResponse transactionResponse = new EntityTransactionResponse();
        transactionResponse.setTransactionInfo(transactionInfo);

        when(repository.saveAll(eq(createTestModelSpec()), eq(entities), any(), any()))
                .thenReturn(CompletableFuture.completedFuture(List.of(transactionResponse)));

        when(repository.findById(eq(testEntityId), any()))
                .thenReturn(CompletableFuture.completedFuture(createTestDataPayload(testEntity, testEntityId)));
        when(repository.getEntityChangesMetadata(eq(testEntityId), isNull()))
                .thenReturn(CompletableFuture.completedFuture(List.of(
                        new EntityChangeMeta()
                                .withTransactionId(transactionResponse.getTransactionInfo().getTransactionId())
                                .withTimeOfChange(new Date())
                )));

        when(repository.findById(eq(testEntityId2), any()))
                .thenReturn(CompletableFuture.completedFuture(createTestDataPayload(testEntity2, testEntityId2)));
        when(repository.getEntityChangesMetadata(eq(testEntityId2), isNull()))
                .thenReturn(CompletableFuture.completedFuture(List.of(
                        new EntityChangeMeta()
                                .withTransactionId(transactionResponse.getTransactionInfo().getTransactionId())
                                .withTimeOfChange(new Date())
                )));

        List<EntityWithMetadata<TestEntity>> result = entityService.save(entities);

        assertNotNull(result);
        assertEquals(2, result.size());
        // Verify that entities are properly mapped
        assertEntityMatches(result.getFirst().entity(), testEntity);
        assertEntityMatches(result.get(1).entity(), testEntity2);
        assertEquals(testEntityId, result.getFirst().metadata().getId());
        assertEquals(testEntityId2, result.get(1).metadata().getId());
        verify(repository).saveAll(eq(createTestModelSpec()), eq(entities), any(), any());
    }

    @Test
    @DisplayName("saveAll should handle repository failure with non-empty collection")
    void testCreateAllWithEntitiesRepositoryFailure() {
        Collection<TestEntity> entities = List.of(testEntity, testEntity2);
        when(repository.saveAll(eq(createTestModelSpec()), eq(entities), any(), any()))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException("Save all failed")));

        assertRepositoryFailure(() -> entityService.save(entities), "Save all failed");
        verify(repository).saveAll(eq(createTestModelSpec()), eq(entities), any(), any());
    }

    // ========================================
    // STREAM TESTS
    // ========================================

    @Test
    @DisplayName("streamAll should stream all entities across multiple pages")
    void testStreamAllMultiplePages() {
        // Create test data for 3 pages
        TestEntity entity1 = new TestEntity(1L, "Entity 1", "ACTIVE");
        TestEntity entity2 = new TestEntity(2L, "Entity 2", "ACTIVE");
        TestEntity entity3 = new TestEntity(3L, "Entity 3", "ACTIVE");
        TestEntity entity4 = new TestEntity(4L, "Entity 4", "ACTIVE");
        TestEntity entity5 = new TestEntity(5L, "Entity 5", "ACTIVE");

        UUID id1 = SimpleSystemClock.INSTANCE.uniqueTimeUUIDinMicros();
        UUID id2 = SimpleSystemClock.INSTANCE.uniqueTimeUUIDinMicros();
        UUID id3 = SimpleSystemClock.INSTANCE.uniqueTimeUUIDinMicros();
        UUID id4 = SimpleSystemClock.INSTANCE.uniqueTimeUUIDinMicros();
        UUID id5 = SimpleSystemClock.INSTANCE.uniqueTimeUUIDinMicros();

        UUID searchId = SimpleSystemClock.INSTANCE.uniqueTimeUUIDinMicros();

        // Mock page 0 (2 items)
        List<DataPayload> page1Payloads = List.of(
                createTestDataPayload(entity1, id1),
                createTestDataPayload(entity2, id2)
        );
        when(repository.findAll(eq(createTestModelSpec()), eq(SearchAndRetrievalParams.builder().pageSize(2).pageNumber(0).searchId(null).build())))
                .thenReturn(CompletableFuture.completedFuture(
                        PageResult.of(searchId, page1Payloads, 0, 2, 5L)
                ));

        // Mock page 1 (2 items)
        List<DataPayload> page2Payloads = List.of(
                createTestDataPayload(entity3, id3),
                createTestDataPayload(entity4, id4)
        );
        when(repository.findAll(eq(createTestModelSpec()), eq(SearchAndRetrievalParams.builder().pageSize(2).pageNumber(1).searchId(searchId).build())))
                .thenReturn(CompletableFuture.completedFuture(
                        PageResult.of(searchId, page2Payloads, 1, 2, 5L)
                ));

        // Mock page 2 (1 item)
        List<DataPayload> page3Payloads = List.of(
                createTestDataPayload(entity5, id5)
        );
        when(repository.findAll(eq(createTestModelSpec()), eq(SearchAndRetrievalParams.builder().pageSize(2).pageNumber(2).searchId(searchId).build())))
                .thenReturn(CompletableFuture.completedFuture(
                        PageResult.of(searchId, page3Payloads, 2, 2, 5L)
                ));

        // Execute stream
        List<EntityWithMetadata<TestEntity>> result = entityService.streamAll(
                createTestModelSpec(),
                TestEntity.class,
                SearchAndRetrievalParams.builder().pageSize(2).build()
        ).toList();

        // Verify results
        assertNotNull(result);
        assertEquals(5, result.size());
        assertEntityMatches(result.getFirst().entity(), entity1);
        assertEntityMatches(result.get(1).entity(), entity2);
        assertEntityMatches(result.get(2).entity(), entity3);
        assertEntityMatches(result.get(3).entity(), entity4);
        assertEntityMatches(result.get(4).entity(), entity5);

        // Verify repository calls
        verify(repository).findAll(eq(createTestModelSpec()), eq(SearchAndRetrievalParams.builder().pageSize(2).pageNumber(0).searchId(null).build()));
        verify(repository).findAll(eq(createTestModelSpec()), eq(SearchAndRetrievalParams.builder().pageSize(2).pageNumber(1).searchId(searchId).build()));
        verify(repository).findAll(eq(createTestModelSpec()), eq(SearchAndRetrievalParams.builder().pageSize(2).pageNumber(2).searchId(searchId).build()));
    }

    @Test
    @DisplayName("streamAll should handle empty result set")
    void testStreamAllEmptyResults() {
        when(repository.findAll(eq(createTestModelSpec()), any()))
                .thenReturn(CompletableFuture.completedFuture(
                        PageResult.of(SimpleSystemClock.INSTANCE.uniqueTimeUUIDinMicros(), List.of(), 0, 100, 0L)
                ));

        List<EntityWithMetadata<TestEntity>> result = entityService.streamAll(
                createTestModelSpec(),
                TestEntity.class,
                SearchAndRetrievalParams.builder().pageSize(100).build()
        ).toList();

        assertNotNull(result);
        assertTrue(result.isEmpty());
        verify(repository).findAll(eq(createTestModelSpec()), any());
    }

    @Test
    @DisplayName("streamAll should handle single page result")
    void testStreamAllSinglePage() {
        TestEntity entity1 = new TestEntity(1L, "Entity 1", "ACTIVE");
        TestEntity entity2 = new TestEntity(2L, "Entity 2", "ACTIVE");
        UUID id1 = SimpleSystemClock.INSTANCE.uniqueTimeUUIDinMicros();
        UUID id2 = SimpleSystemClock.INSTANCE.uniqueTimeUUIDinMicros();

        List<DataPayload> payloads = List.of(
                createTestDataPayload(entity1, id1),
                createTestDataPayload(entity2, id2)
        );

        // totalElements = 2, pageSize = 100, so totalPages = 1 (no more pages)
        when(repository.findAll(eq(createTestModelSpec()), any()))
                .thenReturn(CompletableFuture.completedFuture(
                        PageResult.of(SimpleSystemClock.INSTANCE.uniqueTimeUUIDinMicros(), payloads, 0, 100, 2L)
                ));

        List<EntityWithMetadata<TestEntity>> result = entityService.streamAll(
                createTestModelSpec(),
                TestEntity.class,
                SearchAndRetrievalParams.builder().pageSize(100).build()
        ).toList();

        assertNotNull(result);
        assertEquals(2, result.size());
        assertEntityMatches(result.getFirst().entity(), entity1);
        assertEntityMatches(result.get(1).entity(), entity2);
        verify(repository).findAll(eq(createTestModelSpec()), any());
    }

    @Test
    @DisplayName("streamAll should support filtering with stream operations")
    void testStreamAllWithFiltering() {
        TestEntity activeEntity = new TestEntity(1L, "Active Entity", "ACTIVE");
        TestEntity inactiveEntity = new TestEntity(2L, "Inactive Entity", "INACTIVE");
        UUID id1 = SimpleSystemClock.INSTANCE.uniqueTimeUUIDinMicros();
        UUID id2 = SimpleSystemClock.INSTANCE.uniqueTimeUUIDinMicros();

        List<DataPayload> payloads = List.of(
                createTestDataPayload(activeEntity, id1),
                createTestDataPayload(inactiveEntity, id2)
        );

        when(repository.findAll(eq(createTestModelSpec()), any()))
                .thenReturn(CompletableFuture.completedFuture(
                        PageResult.of(SimpleSystemClock.INSTANCE.uniqueTimeUUIDinMicros(), payloads, 0, 100, 2L)
                ));

        List<EntityWithMetadata<TestEntity>> result = entityService.streamAll(
                        createTestModelSpec(),
                        TestEntity.class,
                        SearchAndRetrievalParams.builder().pageSize(100).build()
                )
                .filter(e -> "ACTIVE".equals(e.entity().getStatus()))
                .toList();

        assertNotNull(result);
        assertEquals(1, result.size());
        assertEntityMatches(result.getFirst().entity(), activeEntity);
    }

    @Test
    @DisplayName("streamAll should support mapping with stream operations")
    void testStreamAllWithMapping() {
        TestEntity entity1 = new TestEntity(1L, "Entity 1", "ACTIVE");
        TestEntity entity2 = new TestEntity(2L, "Entity 2", "ACTIVE");
        UUID id1 = SimpleSystemClock.INSTANCE.uniqueTimeUUIDinMicros();
        UUID id2 = SimpleSystemClock.INSTANCE.uniqueTimeUUIDinMicros();

        List<DataPayload> payloads = List.of(
                createTestDataPayload(entity1, id1),
                createTestDataPayload(entity2, id2)
        );

        // totalElements = 2, pageSize = 100, so totalPages = 1 (no more pages)
        when(repository.findAll(eq(createTestModelSpec()), any()))
                .thenReturn(CompletableFuture.completedFuture(
                        PageResult.of(SimpleSystemClock.INSTANCE.uniqueTimeUUIDinMicros(), payloads, 0, 100, 2L)
                ));

        List<String> names = entityService.streamAll(
                        createTestModelSpec(),
                        TestEntity.class,
                        SearchAndRetrievalParams.builder().pageSize(100).build()
                )
                .map(e -> e.entity().getName())
                .toList();

        assertNotNull(names);
        assertEquals(2, names.size());
        assertEquals("Entity 1", names.getFirst());
        assertEquals("Entity 2", names.get(1));
    }

    @Test
    @DisplayName("streamAll should handle repository failure on first page")
    void testStreamAllRepositoryFailureFirstPage() {
        when(repository.findAll(eq(createTestModelSpec()), any()))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException("Find all failed")));

        assertRepositoryFailure(
                () -> entityService.streamAll(createTestModelSpec(), TestEntity.class, SearchAndRetrievalParams.builder().pageSize(100).build()).toList(),
                "Find all failed"
        );
        verify(repository).findAll(eq(createTestModelSpec()), any());
    }

    @Test
    @DisplayName("streamAll should handle repository failure on subsequent page")
    void testStreamAllRepositoryFailureSubsequentPage() {
        TestEntity entity1 = new TestEntity(1L, "Entity 1", "ACTIVE");
        UUID id1 = SimpleSystemClock.INSTANCE.uniqueTimeUUIDinMicros();
        UUID searchId = SimpleSystemClock.INSTANCE.uniqueTimeUUIDinMicros();

        List<DataPayload> page1Payloads = List.of(createTestDataPayload(entity1, id1));
        when(repository.findAll(eq(createTestModelSpec()), eq(SearchAndRetrievalParams.builder().pageSize(1).pageNumber(0).build())))
                .thenReturn(CompletableFuture.completedFuture(
                        PageResult.of(searchId, page1Payloads, 0, 1, 2L)
                ));

        when(repository.findAll(eq(createTestModelSpec()), eq(SearchAndRetrievalParams.builder().pageSize(1).pageNumber(1).searchId(searchId).build())))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException("Page 2 failed")));

        assertRepositoryFailure(
                () -> entityService.streamAll(createTestModelSpec(), TestEntity.class, SearchAndRetrievalParams.builder().pageSize(1).build()).toList(),
                "Page 2 failed"
        );
        verify(repository).findAll(eq(createTestModelSpec()), eq(SearchAndRetrievalParams.builder().pageSize(1).pageNumber(0).build()));
        verify(repository).findAll(eq(createTestModelSpec()), eq(SearchAndRetrievalParams.builder().pageSize(1).pageNumber(1).searchId(searchId).build()));
    }

    @Test
    @DisplayName("streamAll should respect pointInTime parameter")
    void testStreamAllWithPointInTime() {
        Date pointInTime = new Date();
        TestEntity entity1 = new TestEntity(1L, "Entity 1", "ACTIVE");
        UUID id1 = SimpleSystemClock.INSTANCE.uniqueTimeUUIDinMicros();

        List<DataPayload> payloads = List.of(createTestDataPayload(entity1, id1));
        when(repository.findAll(eq(createTestModelSpec()), any()))
                .thenReturn(CompletableFuture.completedFuture(
                        PageResult.of(SimpleSystemClock.INSTANCE.uniqueTimeUUIDinMicros(), payloads, 0, 100, 1L)
                ));

        List<EntityWithMetadata<TestEntity>> result = entityService.streamAll(
                createTestModelSpec(),
                TestEntity.class,
                SearchAndRetrievalParams.builder().pageSize(100).pointInTime(pointInTime).build()
        ).toList();

        assertNotNull(result);
        assertEquals(1, result.size());
        assertEntityMatches(result.getFirst().entity(), entity1);
        verify(repository).findAll(eq(createTestModelSpec()), eq(SearchAndRetrievalParams.builder().pageSize(100).pageNumber(0).pointInTime(pointInTime).build()));
    }

    @Test
    @DisplayName("streamAll should support count operation")
    void testStreamAllCount() {
        TestEntity entity1 = new TestEntity(1L, "Entity 1", "ACTIVE");
        TestEntity entity2 = new TestEntity(2L, "Entity 2", "ACTIVE");
        UUID id1 = SimpleSystemClock.INSTANCE.uniqueTimeUUIDinMicros();
        UUID id2 = SimpleSystemClock.INSTANCE.uniqueTimeUUIDinMicros();

        List<DataPayload> payloads = List.of(
                createTestDataPayload(entity1, id1),
                createTestDataPayload(entity2, id2)
        );

        // totalElements = 2, pageSize = 100, so totalPages = 1 (no more pages)
        when(repository.findAll(eq(createTestModelSpec()), any()))
                .thenReturn(CompletableFuture.completedFuture(
                        PageResult.of(SimpleSystemClock.INSTANCE.uniqueTimeUUIDinMicros(), payloads, 0, 100, 2L)
                ));

        long count = entityService.streamAll(
                createTestModelSpec(),
                TestEntity.class,
                SearchAndRetrievalParams.builder().pageSize(100).build()
        ).count();

        assertEquals(2, count);
        verify(repository).findAll(eq(createTestModelSpec()), any());
    }

    @Test
    @DisplayName("streamAll should provide accurate size estimation from the start")
    void testStreamAllSizeEstimation() {
        TestEntity entity1 = new TestEntity(1L, "Entity 1", "ACTIVE");
        TestEntity entity2 = new TestEntity(2L, "Entity 2", "ACTIVE");
        TestEntity entity3 = new TestEntity(3L, "Entity 3", "ACTIVE");
        UUID id1 = SimpleSystemClock.INSTANCE.uniqueTimeUUIDinMicros();
        UUID id2 = SimpleSystemClock.INSTANCE.uniqueTimeUUIDinMicros();
        UUID id3 = SimpleSystemClock.INSTANCE.uniqueTimeUUIDinMicros();
        UUID searchId = SimpleSystemClock.INSTANCE.uniqueTimeUUIDinMicros();

        // First page (page 0): 2 items, totalElements = 3
        List<DataPayload> page1Payloads = List.of(
                createTestDataPayload(entity1, id1),
                createTestDataPayload(entity2, id2)
        );

        // Second page (page 1): 1 item
        List<DataPayload> page2Payloads = List.of(
                createTestDataPayload(entity3, id3)
        );

        // totalElements = 3, pageSize = 2, so totalPages = 2
        when(repository.findAll(eq(createTestModelSpec()), eq(SearchAndRetrievalParams.builder().pageSize(2).build())))
                .thenReturn(CompletableFuture.completedFuture(
                        PageResult.of(searchId, page1Payloads, 0, 2, 3L)
                ));

        when(repository.findAll(eq(createTestModelSpec()), eq(SearchAndRetrievalParams.builder().pageSize(2).pageNumber(1).searchId(searchId).build())))
                .thenReturn(CompletableFuture.completedFuture(
                        PageResult.of(searchId, page2Payloads, 1, 2, 3L)
                ));

        Stream<EntityWithMetadata<TestEntity>> stream = entityService.streamAll(
                createTestModelSpec(),
                TestEntity.class,
                SearchAndRetrievalParams.builder().pageSize(2).build()
        );

        // Get the spliterator to check size estimation
        Spliterator<EntityWithMetadata<TestEntity>> spliterator = stream.spliterator();

        // Size should be known immediately (3 total elements) since first page is fetched upfront
        long initialSize = spliterator.estimateSize();
        assertEquals(3, initialSize, "Initial size should be 3 (total elements)");

        // Verify SIZED characteristic is present
        assertTrue((spliterator.characteristics() & Spliterator.SIZED) != 0,
                "Spliterator should have SIZED characteristic");

        // Consume first element
        spliterator.tryAdvance(entity -> {});

        // After first element, size should be: 3 total - 1 processed = 2 remaining
        long sizeAfterFirst = spliterator.estimateSize();
        assertEquals(2, sizeAfterFirst, "Size after first element should be 2 remaining");

        // Consume second element
        spliterator.tryAdvance(entity -> {});

        // Size should now be 1 remaining
        long sizeAfterSecond = spliterator.estimateSize();
        assertEquals(1, sizeAfterSecond, "Size after second element should be 1 remaining");

        // Consume third element
        spliterator.tryAdvance(entity -> {});

        // Size should now be 0 remaining
        long sizeAfterThird = spliterator.estimateSize();
        assertEquals(0, sizeAfterThird, "Size after third element should be 0 remaining");

        verify(repository).findAll(eq(createTestModelSpec()), eq(SearchAndRetrievalParams.builder().pageSize(2).pageNumber(0).build()));
        verify(repository).findAll(eq(createTestModelSpec()), eq(SearchAndRetrievalParams.builder().pageSize(2).pageNumber(1).searchId(searchId).build()));
    }

    // ========================================
    // ENTITY STATISTICS TESTS
    // ========================================

    @Test
    @DisplayName("getEntityStatsByState should return stats map when successful")
    void testGetEntityStatsByStateSuccess() {
        Map<String, Long> expectedStats = Map.of(
                "DRAFT", 5L,
                "VALIDATED", 10L,
                "ARCHIVED", 2L
        );
        when(repository.getEntityStatsByState(eq(createTestModelSpec()), isNull()))
                .thenReturn(CompletableFuture.completedFuture(expectedStats));

        Map<String, Long> result = entityService.getEntityStatsByState(createTestModelSpec());

        assertNotNull(result);
        assertEquals(3, result.size());
        assertEquals(5L, result.get("DRAFT"));
        assertEquals(10L, result.get("VALIDATED"));
        assertEquals(2L, result.get("ARCHIVED"));
        verify(repository).getEntityStatsByState(eq(createTestModelSpec()), isNull());
    }

    @Test
    @DisplayName("getEntityStatsByState with pointInTime should return stats map when successful")
    void testGetEntityStatsByStateWithPointInTimeSuccess() {
        Date pointInTime = new Date();
        Map<String, Long> expectedStats = Map.of(
                "DRAFT", 3L,
                "VALIDATED", 7L
        );
        when(repository.getEntityStatsByState(eq(createTestModelSpec()), eq(pointInTime)))
                .thenReturn(CompletableFuture.completedFuture(expectedStats));

        Map<String, Long> result = entityService.getEntityStatsByState(createTestModelSpec(), pointInTime);

        assertNotNull(result);
        assertEquals(2, result.size());
        assertEquals(3L, result.get("DRAFT"));
        assertEquals(7L, result.get("VALIDATED"));
        verify(repository).getEntityStatsByState(eq(createTestModelSpec()), eq(pointInTime));
    }

    @Test
    @DisplayName("getEntityStatsByState with specific states should return filtered stats")
    void testGetEntityStatsByStateWithSpecificStates() {
        List<String> states = List.of("DRAFT", "VALIDATED");
        Date pointInTime = new Date();
        Map<String, Long> expectedStats = Map.of(
                "DRAFT", 5L,
                "VALIDATED", 10L
        );
        when(repository.getEntityStatsByState(eq(createTestModelSpec()), eq(states), eq(pointInTime)))
                .thenReturn(CompletableFuture.completedFuture(expectedStats));

        Map<String, Long> result = entityService.getEntityStatsByState(createTestModelSpec(), states, pointInTime);

        assertNotNull(result);
        assertEquals(2, result.size());
        assertEquals(5L, result.get("DRAFT"));
        assertEquals(10L, result.get("VALIDATED"));
        assertNull(result.get("ARCHIVED"));
        verify(repository).getEntityStatsByState(eq(createTestModelSpec()), eq(states), eq(pointInTime));
    }

    @Test
    @DisplayName("getEntityStatsByState should return empty map when no entities exist")
    void testGetEntityStatsByStateEmpty() {
        Map<String, Long> emptyStats = Collections.emptyMap();
        when(repository.getEntityStatsByState(eq(createTestModelSpec()), isNull()))
                .thenReturn(CompletableFuture.completedFuture(emptyStats));

        Map<String, Long> result = entityService.getEntityStatsByState(createTestModelSpec());

        assertNotNull(result);
        assertTrue(result.isEmpty());
        verify(repository).getEntityStatsByState(eq(createTestModelSpec()), isNull());
    }
}
