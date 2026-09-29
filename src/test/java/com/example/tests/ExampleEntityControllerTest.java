package com.example.tests;

import com.example.application.controller.ExampleEntityController;
import com.example.application.entity.example_entity.version_1.ExampleEntity;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.java_template.common.dto.EntityWithMetadata;
import com.java_template.common.dto.PageResult;
import com.java_template.common.repository.SearchAndRetrievalParams;
import com.java_template.common.service.EntityService;
import org.cyoda.cloud.api.common.model.GroupConditionDto;
import org.cyoda.cloud.api.event.common.EntityChangeMeta;
import org.cyoda.cloud.api.event.common.EntityMetadata;
import org.cyoda.cloud.api.event.common.ModelSpec;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Unit tests for ExampleEntityController
 * Tests REST endpoints, validation, and error handling
 */
@DisplayName("ExampleEntityController Tests")
class ExampleEntityControllerTest {

    private ObjectMapper objectMapper;
    private MockMvc mockMvc;

    @Mock
    private EntityService entityService;
    private AutoCloseable autoCloseable;

    @BeforeEach
    void setUp() {
        autoCloseable = MockitoAnnotations.openMocks(this);
        objectMapper = new ObjectMapper();
        objectMapper.findAndRegisterModules();
        ExampleEntityController controller = new ExampleEntityController(entityService, objectMapper);
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @AfterEach
    void tearDown() throws Exception {
        autoCloseable.close();
    }

    // ========================================
    // CREATE TESTS
    // ========================================

    @Test
    @DisplayName("Should create entity successfully when no duplicate exists")
    void testCreateEntitySuccess() throws Exception {
        ExampleEntity entity = createTestEntity("TEST-001");
        UUID createdId = UUID.randomUUID();
        EntityWithMetadata<ExampleEntity> created = createEntityWithMetadata(entity, createdId);

        when(entityService.findByBusinessIdOrNull(any(ModelSpec.class), eq("TEST-001"), eq("exampleId"), eq(ExampleEntity.class)))
                .thenReturn(null);
        when(entityService.create(any(ExampleEntity.class))).thenReturn(created);

        mockMvc.perform(post("/ui/example")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(entity)))
                .andExpect(status().isCreated())
                .andExpect(header().exists("Location"))
                .andExpect(header().string("Location", containsString(createdId.toString())));

        verify(entityService).findByBusinessIdOrNull(any(ModelSpec.class), eq("TEST-001"), eq("exampleId"), eq(ExampleEntity.class));
        verify(entityService).create(any(ExampleEntity.class));
    }

    @Test
    @DisplayName("Should return conflict when duplicate business ID exists")
    void testCreateEntityDuplicate() throws Exception {
        ExampleEntity entity = createTestEntity("TEST-001");
        EntityWithMetadata<ExampleEntity> existing = createEntityWithMetadata(entity, UUID.randomUUID());

        when(entityService.findByBusinessIdOrNull(any(), eq("TEST-001"), eq("exampleId"), eq(ExampleEntity.class)))
                .thenReturn(existing);

        mockMvc.perform(post("/ui/example")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(entity)))
                .andExpect(status().isConflict());

        verify(entityService).findByBusinessIdOrNull(any(), eq("TEST-001"), eq("exampleId"), eq(ExampleEntity.class));
        verify(entityService, never()).create(any());
    }

    @Test
    @DisplayName("Should return bad request when create fails")
    void testCreateEntityFailure() throws Exception {
        ExampleEntity entity = createTestEntity("TEST-001");

        when(entityService.findByBusinessIdOrNull(any(), eq("TEST-001"), eq("exampleId"), eq(ExampleEntity.class)))
                .thenReturn(null);
        when(entityService.create(any())).thenThrow(new RuntimeException("Database error"));

        mockMvc.perform(post("/ui/example")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(entity)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("A failure's internal message never reaches the response body")
    void testFailureBodyIsGeneric() throws Exception {
        String internal = "connect to http://cyoda.internal:8080/api failed: secret-detail";
        when(entityService.getById(any(), any(), eq(ExampleEntity.class), nullable(OffsetDateTime.class)))
                .thenThrow(new RuntimeException(internal));
        when(entityService.getById(any(), any(), eq(ExampleEntity.class)))
                .thenThrow(new RuntimeException(internal));
        when(entityService.create(any())).thenThrow(new RuntimeException(internal));
        when(entityService.deleteById(any())).thenThrow(new RuntimeException(internal));

        UUID id = UUID.randomUUID();
        for (var request : List.of(
                get("/ui/example/" + id),
                post("/ui/example").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(createTestEntity("TEST-002"))),
                delete("/ui/example/" + id))) {
            String body = mockMvc.perform(request)
                    .andExpect(status().is4xxClientError())
                    .andExpect(jsonPath("$.correlationId").exists())
                    .andReturn().getResponse().getContentAsString();
            org.assertj.core.api.Assertions.assertThat(body)
                    .doesNotContain("cyoda.internal").doesNotContain("secret-detail");
        }
    }

    // ========================================
    // GET BY ID TESTS
    // ========================================

    @Test
    @DisplayName("Should get entity by technical ID successfully")
    void testGetEntityByIdSuccess() throws Exception {
        UUID entityId = UUID.randomUUID();
        ExampleEntity entity = createTestEntity("TEST-001");
        EntityWithMetadata<ExampleEntity> entityWithMetadata = createEntityWithMetadata(entity, entityId);

        when(entityService.getById(eq(entityId), any(ModelSpec.class), eq(ExampleEntity.class), isNull(OffsetDateTime.class)))
                .thenReturn(entityWithMetadata);

        mockMvc.perform(get("/ui/example/{id}", entityId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entity.exampleId").value("TEST-001"));

        verify(entityService).getById(eq(entityId), any(ModelSpec.class), eq(ExampleEntity.class), isNull(OffsetDateTime.class));
    }

    @Test
    @DisplayName("Should return not found when entity does not exist")
    void testGetEntityByIdNotFound() throws Exception {
        UUID entityId = UUID.randomUUID();

        when(entityService.getById(eq(entityId), any(ModelSpec.class), eq(ExampleEntity.class), isNull(OffsetDateTime.class)))
                .thenReturn(null);

        mockMvc.perform(get("/ui/example/{id}", entityId))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("Should get entity by ID with point in time")
    void testGetEntityByIdWithPointInTime() throws Exception {
        UUID entityId = UUID.randomUUID();
        ExampleEntity entity = createTestEntity("TEST-001");
        EntityWithMetadata<ExampleEntity> entityWithMetadata = createEntityWithMetadata(entity, entityId);
        OffsetDateTime pointInTime = OffsetDateTime.now().minusDays(1);

        when(entityService.getById(eq(entityId), any(ModelSpec.class), eq(ExampleEntity.class), nullable(OffsetDateTime.class)))
                .thenReturn(entityWithMetadata);

        mockMvc.perform(get("/ui/example/{id}", entityId)
                .param("pointInTime", pointInTime.toString()))
                .andExpect(status().isOk());

        ArgumentCaptor<OffsetDateTime> pitCaptor = ArgumentCaptor.forClass(OffsetDateTime.class);
        verify(entityService).getById(eq(entityId), any(ModelSpec.class), eq(ExampleEntity.class), pitCaptor.capture());
        assertNotNull(pitCaptor.getValue());
        // Passed through without truncation to milliseconds.
        assertEquals(pointInTime.toInstant(), pitCaptor.getValue().toInstant());
    }

    // ========================================
    // GET BY BUSINESS ID TESTS
    // ========================================

    @Test
    @DisplayName("Should get entity by business ID successfully")
    void testGetEntityByBusinessIdSuccess() throws Exception {
        ExampleEntity entity = createTestEntity("TEST-001");
        EntityWithMetadata<ExampleEntity> entityWithMetadata = createEntityWithMetadata(entity, UUID.randomUUID());

        when(entityService.findByBusinessId(any(ModelSpec.class), eq("TEST-001"), eq("exampleId"),
                eq(ExampleEntity.class), isNull(OffsetDateTime.class)))
                .thenReturn(entityWithMetadata);

        mockMvc.perform(get("/ui/example/business/{exampleId}", "TEST-001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entity.exampleId").value("TEST-001"));
    }

    @Test
    @DisplayName("Should return not found when business ID does not exist")
    void testGetEntityByBusinessIdNotFound() throws Exception {
        when(entityService.findByBusinessId(any(ModelSpec.class), eq("NONEXISTENT"), eq("exampleId"),
                eq(ExampleEntity.class), isNull(OffsetDateTime.class)))
                .thenReturn(null);

        mockMvc.perform(get("/ui/example/business/{exampleId}", "NONEXISTENT"))
                .andExpect(status().isNotFound());
    }

    // ========================================
    // UPDATE TESTS
    // ========================================

    @Test
    @DisplayName("Should update entity successfully")
    void testUpdateEntitySuccess() throws Exception {
        UUID entityId = UUID.randomUUID();
        ExampleEntity entity = createTestEntity("TEST-001");
        EntityWithMetadata<ExampleEntity> updated = createEntityWithMetadata(entity, entityId);

        when(entityService.update(eq(entityId), any(ExampleEntity.class), isNull()))
                .thenReturn(updated);

        mockMvc.perform(put("/ui/example/{id}", entityId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(entity)))
                .andExpect(status().isOk());

        verify(entityService).update(eq(entityId), any(ExampleEntity.class), isNull());
    }

    @Test
    @DisplayName("Should update entity with workflow transition")
    void testUpdateEntityWithTransition() throws Exception {
        UUID entityId = UUID.randomUUID();
        ExampleEntity entity = createTestEntity("TEST-001");
        EntityWithMetadata<ExampleEntity> updated = createEntityWithMetadata(entity, entityId);

        when(entityService.update(eq(entityId), any(ExampleEntity.class), eq("approve")))
                .thenReturn(updated);

        mockMvc.perform(put("/ui/example/{id}", entityId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(entity))
                .param("transition", "approve"))
                .andExpect(status().isOk());

        verify(entityService).update(eq(entityId), any(ExampleEntity.class), eq("approve"));
    }

    // ========================================
    // DELETE TESTS
    // ========================================

    @Test
    @DisplayName("Should delete entity by ID successfully")
    void testDeleteEntitySuccess() throws Exception {
        UUID entityId = UUID.randomUUID();

        when(entityService.deleteById(entityId)).thenReturn(entityId);

        mockMvc.perform(delete("/ui/example/{id}", entityId))
                .andExpect(status().isNoContent());

        verify(entityService).deleteById(entityId);
    }

    @Test
    @DisplayName("Should delete entity by business ID successfully")
    void testDeleteEntityByBusinessIdSuccess() throws Exception {
        when(entityService.deleteByBusinessId(any(ModelSpec.class), eq("TEST-001"),
                eq("exampleId"), eq(ExampleEntity.class)))
                .thenReturn(true);

        mockMvc.perform(delete("/ui/example/business/{exampleId}", "TEST-001"))
                .andExpect(status().isNoContent());

        verify(entityService).deleteByBusinessId(any(ModelSpec.class), eq("TEST-001"),
                eq("exampleId"), eq(ExampleEntity.class));
    }

    @Test
    @DisplayName("Should return not found when deleting non-existent business ID")
    void testDeleteEntityByBusinessIdNotFound() throws Exception {
        when(entityService.deleteByBusinessId(any(ModelSpec.class), eq("NONEXISTENT"),
                eq("exampleId"), eq(ExampleEntity.class)))
                .thenReturn(false);

        mockMvc.perform(delete("/ui/example/business/{exampleId}", "NONEXISTENT"))
                .andExpect(status().isNotFound());
    }

    // ========================================
    // LIST ENTITIES TESTS
    // ========================================

    @Test
    @DisplayName("Should search entities by category (low volume in-memory)")
    void testSearchByCategory() throws Exception {
        List<EntityWithMetadata<ExampleEntity>> entities = new ArrayList<>();
        entities.add(createEntityWithMetadata(createTestEntity("TEST-001"), UUID.randomUUID()));
        entities.add(createEntityWithMetadata(createTestEntity("TEST-002"), UUID.randomUUID()));

        PageResult<EntityWithMetadata<ExampleEntity>> pageResult = PageResult.of(
                UUID.randomUUID(), entities, 0, 1000, 2);

        when(entityService.search(any(ModelSpec.class), any(GroupConditionDto.class),
                eq(ExampleEntity.class), any(SearchAndRetrievalParams.class)))
                .thenReturn(pageResult);

        mockMvc.perform(get("/ui/example/by-category")
                .param("category", "PREMIUM"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)));

        verify(entityService).search(any(ModelSpec.class), any(GroupConditionDto.class),
                eq(ExampleEntity.class), any(SearchAndRetrievalParams.class));
    }



    // ========================================
    // SEARCH TESTS
    // ========================================

    @Test
    @DisplayName("Should search with pagination (high volume pageable)")
    void testSearchWithPagination() throws Exception {
        List<EntityWithMetadata<ExampleEntity>> entities = new ArrayList<>();
        entities.add(createEntityWithMetadata(createTestEntity("TEST-001"), UUID.randomUUID()));

        UUID searchId = UUID.randomUUID();
        PageResult<EntityWithMetadata<ExampleEntity>> pageResult = PageResult.of(
                searchId, entities, 0, 50, 1);

        when(entityService.search(any(ModelSpec.class), any(GroupConditionDto.class),
                eq(ExampleEntity.class), any(SearchAndRetrievalParams.class)))
                .thenReturn(pageResult);

        mockMvc.perform(get("/ui/example/search")
                .param("name", "test")
                .param("page", "0")
                .param("size", "50"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.searchId").value(searchId.toString()))
                .andExpect(jsonPath("$.data", hasSize(1)));

        verify(entityService).search(any(ModelSpec.class), any(GroupConditionDto.class),
                eq(ExampleEntity.class), any(SearchAndRetrievalParams.class));
    }

    // ========================================
    // WORKFLOW TRANSITION TESTS
    // ========================================

    @Test
    @DisplayName("Should approve entity")
    void testApproveEntity() throws Exception {
        UUID entityId = UUID.randomUUID();
        ExampleEntity entity = createTestEntity("TEST-001");
        EntityWithMetadata<ExampleEntity> current = createEntityWithMetadata(entity, entityId);
        EntityWithMetadata<ExampleEntity> updated = createEntityWithMetadata(entity, entityId);

        when(entityService.getById(eq(entityId), any(ModelSpec.class), eq(ExampleEntity.class)))
                .thenReturn(current);
        when(entityService.update(eq(entityId), any(ExampleEntity.class), eq("approve")))
                .thenReturn(updated);

        mockMvc.perform(post("/ui/example/{id}/approve", entityId))
                .andExpect(status().isOk());

        verify(entityService).getById(eq(entityId), any(ModelSpec.class), eq(ExampleEntity.class));
        verify(entityService).update(eq(entityId), any(ExampleEntity.class), eq("approve"));
    }

    // ========================================
    // CHANGE METADATA TESTS
    // ========================================

    @Test
    @DisplayName("Should get entity change metadata")
    void testGetEntityChangeMetadata() throws Exception {
        UUID entityId = UUID.randomUUID();
        List<EntityChangeMeta> changeMetadata = new ArrayList<>();
        EntityChangeMeta meta = new EntityChangeMeta();
        changeMetadata.add(meta);

        when(entityService.getEntityChangesMetadata(eq(entityId), isNull(OffsetDateTime.class)))
                .thenReturn(changeMetadata);

        mockMvc.perform(get("/ui/example/{id}/changes", entityId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)));

        verify(entityService).getEntityChangesMetadata(eq(entityId), isNull(OffsetDateTime.class));
    }

    @Test
    @DisplayName("Should return OK with null when entity has no change metadata")
    void testGetEntityChangeMetadataNotFound() throws Exception {
        UUID entityId = UUID.randomUUID();

        when(entityService.getEntityChangesMetadata(eq(entityId), isNull(OffsetDateTime.class)))
                .thenReturn(null);

        mockMvc.perform(get("/ui/example/{id}/changes", entityId))
                .andExpect(status().isOk());

        verify(entityService).getEntityChangesMetadata(eq(entityId), isNull(OffsetDateTime.class));
    }

    // ========================================
    // Helper Methods
    // ========================================

    private ExampleEntity createTestEntity(String exampleId) {
        ExampleEntity entity = new ExampleEntity();
        entity.setExampleId(exampleId);
        entity.setName("Test Entity");
        entity.setAmount(100.0);
        entity.setQuantity(5);
        return entity;
    }

    private EntityWithMetadata<ExampleEntity> createEntityWithMetadata(ExampleEntity entity, UUID id) {
        EntityMetadata metadata = new EntityMetadata();
        metadata.setId(id);
        metadata.setState("DRAFT");
        return new EntityWithMetadata<>(entity, metadata);
    }
}

