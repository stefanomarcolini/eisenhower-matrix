package com.tm.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tm.core.domain.Role;
import com.tm.core.domain.TaskHistory;
import com.tm.core.domain.enums.TaskState;
import com.tm.core.infrastructure.RoleRepository;
import com.tm.core.infrastructure.TaskHistoryRepository;
import com.tm.core.infrastructure.TaskRepository;
import com.tm.core.infrastructure.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.http.MediaType;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.containers.PostgreSQLContainer;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Integration tests for Session 6: task CRUD, state machine, soft-delete,
 * optimistic locking, BOLA defence, and admin role guard.
 *
 * Required by IMPLEMENTATION_ROADMAP.md Session 6 test spec:
 * - All task state transitions (including illegal → 422)
 * - DELETE → 204, task absent from subsequent GET (soft-delete)
 * - task_history row written on every state change
 * - Cross-user access → 404 (BOLA: same-tenant, different userId)
 * - Admin role guard → 403 for STANDARD users
 *
 * No @Transactional on this class — MockMvc requests run in their own committed
 * transactions. See CODING_PATTERNS.md §9.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@ActiveProfiles("test")
@Sql(scripts = "/db/test-seed.sql", executionPhase = Sql.ExecutionPhase.BEFORE_TEST_CLASS)
class TaskIntegrationTest {

    // Container started in a static block so it is ready before Spring initialises its
    // ApplicationContext. @DynamicPropertySource then provides the JDBC URL to the context.
    // Using @Container + @Testcontainers here would cause a JUnit 5 extension ordering race
    // (SpringExtension.beforeAll runs first and tries to obtain the mapped port before
    // TestcontainersExtension has started the container). See CODING_PATTERNS.md §9.
    static final PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>(System.getProperty("postgresql.test.image", "postgres:17-alpine"));

    static {
        postgres.start();
    }

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    MockMvc mockMvc;
    @Autowired WebApplicationContext webApplicationContext;
    @Autowired FilterChainProxy springSecurityFilterChain;
    private final ObjectMapper objectMapper = new ObjectMapper();
    @Autowired UserRepository userRepository;
    @Autowired TaskRepository taskRepository;
    @Autowired TaskHistoryRepository taskHistoryRepository;
    @Autowired RoleRepository roleRepository;

    @MockitoBean
    JavaMailSender mailSender;

    private static final String TENANT_ID = "00000000-0000-0000-0000-000000000001";
    private static final String TEST_EMAIL   = "taskuser@example.com";
    private static final String TEST_EMAIL_2 = "taskuser2@example.com";
    private static final String TEST_PASSWORD = "Str0ng!Pass#1";

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
                .addFilters(springSecurityFilterChain)
                .build();
        taskHistoryRepository.deleteAll();
        taskRepository.deleteAll();
        userRepository.deleteAll();
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /** Registers a user and returns the issued JWT. */
    private String registerAndGetToken(String email) throws Exception {
        MvcResult result = mockMvc.perform(post("/internal/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "tenantId", TENANT_ID,
                                "email", email,
                                "password", TEST_PASSWORD))))
                .andExpect(status().isOk())
                .andReturn();
        Map<?, ?> body = objectMapper.readValue(result.getResponse().getContentAsString(), Map.class);
        return (String) body.get("token");
    }

    /** Creates a task and returns the full response body. */
    private Map<?, ?> createTask(String token, String title) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/tasks")
                        .header("Authorization", "Bearer " + token)
                        .header("X-Tenant-ID", TENANT_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "title", title,
                                "importance", "HIGH",
                                "urgency", "MEDIUM"))))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readValue(result.getResponse().getContentAsString(), Map.class);
    }

    /** Issues a PATCH to transition a task and asserts the expected status. */
    private void patchState(String token, String taskId, String newState,
                            int version, int expectedStatus) throws Exception {
        mockMvc.perform(patch("/api/v1/tasks/" + taskId)
                        .header("Authorization", "Bearer " + token)
                        .header("X-Tenant-ID", TENANT_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "state", newState,
                                "version", version))))
                .andExpect(status().is(expectedStatus));
    }

    // -------------------------------------------------------------------------
    // Create
    // -------------------------------------------------------------------------

    @Test
    void createTask_returns201WithPlannedState() throws Exception {
        String token = registerAndGetToken(TEST_EMAIL);

        mockMvc.perform(post("/api/v1/tasks")
                        .header("Authorization", "Bearer " + token)
                        .header("X-Tenant-ID", TENANT_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "title", "My Task",
                                "description", "Some description",
                                "importance", "HIGH",
                                "urgency", "LOW"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.state").value("PLANNED"))
                .andExpect(jsonPath("$.importance").value("HIGH"))
                .andExpect(jsonPath("$.urgency").value("LOW"))
                .andExpect(jsonPath("$.version").value(0))
                .andExpect(jsonPath("$.id").isNotEmpty());
    }

    @Test
    void createTask_writesCreationHistoryRow() throws Exception {
        String token = registerAndGetToken(TEST_EMAIL);
        createTask(token, "History Task");

        // task_history row written for creation (fromState = null)
        assertThat(taskHistoryRepository.count()).isEqualTo(1);
    }

    // -------------------------------------------------------------------------
    // Read
    // -------------------------------------------------------------------------

    @Test
    void getTask_existingTask_returns200() throws Exception {
        String token = registerAndGetToken(TEST_EMAIL);
        Map<?, ?> created = createTask(token, "Readable Task");
        String id = (String) created.get("id");

        mockMvc.perform(get("/api/v1/tasks/" + id)
                        .header("Authorization", "Bearer " + token)
                        .header("X-Tenant-ID", TENANT_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.title").value("Readable Task"));
    }

    @Test
    void listTasks_returnsPaginatedResults() throws Exception {
        String token = registerAndGetToken(TEST_EMAIL);
        createTask(token, "Task 1");
        createTask(token, "Task 2");

        mockMvc.perform(get("/api/v1/tasks")
                        .header("Authorization", "Bearer " + token)
                        .header("X-Tenant-ID", TENANT_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalCount").value(2))
                .andExpect(jsonPath("$.data").isArray());
    }

    // -------------------------------------------------------------------------
    // Full replace (PUT)
    // -------------------------------------------------------------------------

    @Test
    void updateTask_validVersion_returns200() throws Exception {
        String token = registerAndGetToken(TEST_EMAIL);
        Map<?, ?> created = createTask(token, "Original Title");
        String id = (String) created.get("id");

        mockMvc.perform(put("/api/v1/tasks/" + id)
                        .header("Authorization", "Bearer " + token)
                        .header("X-Tenant-ID", TENANT_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "title", "Updated Title",
                                "importance", "LOW",
                                "urgency", "HIGH",
                                "version", 0))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("Updated Title"))
                .andExpect(jsonPath("$.importance").value("LOW"))
                .andExpect(jsonPath("$.urgency").value("HIGH"));
    }

    @Test
    void updateTask_staleVersion_returns409() throws Exception {
        String token = registerAndGetToken(TEST_EMAIL);
        Map<?, ?> created = createTask(token, "Version Task");
        String id = (String) created.get("id");

        // Advance version to 1 via a successful update.
        mockMvc.perform(put("/api/v1/tasks/" + id)
                        .header("Authorization", "Bearer " + token)
                        .header("X-Tenant-ID", TENANT_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "title", "First Update",
                                "importance", "HIGH",
                                "urgency", "MEDIUM",
                                "version", 0))))
                .andExpect(status().isOk());

        // Re-submit with the old version — expect 409.
        mockMvc.perform(put("/api/v1/tasks/" + id)
                        .header("Authorization", "Bearer " + token)
                        .header("X-Tenant-ID", TENANT_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "title", "Stale Update",
                                "importance", "HIGH",
                                "urgency", "MEDIUM",
                                "version", 0))))
                .andExpect(status().isConflict());
    }

    // -------------------------------------------------------------------------
    // State transitions — legal
    // -------------------------------------------------------------------------

    @Test
    void patchState_plannedToInProgress_returns200() throws Exception {
        String token = registerAndGetToken(TEST_EMAIL);
        Map<?, ?> created = createTask(token, "Transition Task");
        String id = (String) created.get("id");

        patchState(token, id, "IN_PROGRESS", 0, 200);

        mockMvc.perform(get("/api/v1/tasks/" + id)
                        .header("Authorization", "Bearer " + token)
                        .header("X-Tenant-ID", TENANT_ID))
                .andExpect(jsonPath("$.state").value("IN_PROGRESS"));
    }

    @Test
    void patchState_inProgressToCompleted_returns200() throws Exception {
        String token = registerAndGetToken(TEST_EMAIL);
        Map<?, ?> created = createTask(token, "Complete Task");
        String id = (String) created.get("id");

        patchState(token, id, "IN_PROGRESS", 0, 200);
        patchState(token, id, "COMPLETED", 1, 200);

        mockMvc.perform(get("/api/v1/tasks/" + id)
                        .header("Authorization", "Bearer " + token)
                        .header("X-Tenant-ID", TENANT_ID))
                .andExpect(jsonPath("$.state").value("COMPLETED"));
    }

    @Test
    void patchState_overdueToInProgress_returns200() throws Exception {
        String token = registerAndGetToken(TEST_EMAIL);
        Map<?, ?> created = createTask(token, "Overdue Task");
        String id = (String) created.get("id");

        // Simulate the nightly scheduler: directly set state to OVERDUE.
        // JPA @Version increments to 1 on save.
        taskRepository.findById(UUID.fromString(id)).ifPresent(task -> {
            task.setState(TaskState.OVERDUE);
            taskRepository.save(task);
        });

        // OVERDUE → IN_PROGRESS is a legal user-driven transition (PROJECT_OVERVIEW.md §3).
        patchState(token, id, "IN_PROGRESS", 1, 200);

        mockMvc.perform(get("/api/v1/tasks/" + id)
                        .header("Authorization", "Bearer " + token)
                        .header("X-Tenant-ID", TENANT_ID))
                .andExpect(jsonPath("$.state").value("IN_PROGRESS"));
    }

    @Test
    void patchState_legalTransition_writesTaskHistoryRow() throws Exception {
        String token = registerAndGetToken(TEST_EMAIL);
        Map<?, ?> created = createTask(token, "Audit Task");
        String id = (String) created.get("id");

        long historyBefore = taskHistoryRepository.count(); // 1 for creation
        patchState(token, id, "IN_PROGRESS", 0, 200);

        assertThat(taskHistoryRepository.count()).isEqualTo(historyBefore + 1);
    }

    // -------------------------------------------------------------------------
    // State transitions — illegal (→ 422)
    // -------------------------------------------------------------------------

    @Test
    void patchState_plannedToCompleted_returns422() throws Exception {
        String token = registerAndGetToken(TEST_EMAIL);
        Map<?, ?> created = createTask(token, "Skip Task");
        String id = (String) created.get("id");

        // PLANNED → COMPLETED skips IN_PROGRESS — illegal.
        patchState(token, id, "COMPLETED", 0, 422);
    }

    @Test
    void patchState_completedToAnyState_returns422() throws Exception {
        String token = registerAndGetToken(TEST_EMAIL);
        Map<?, ?> created = createTask(token, "Terminal Task");
        String id = (String) created.get("id");

        patchState(token, id, "IN_PROGRESS", 0, 200);
        patchState(token, id, "COMPLETED", 1, 200);

        // COMPLETED is terminal — any transition out is illegal.
        patchState(token, id, "PLANNED", 2, 422);
        patchState(token, id, "IN_PROGRESS", 2, 422);
    }

    @Test
    void patchState_plannedToOverdue_returns422() throws Exception {
        String token = registerAndGetToken(TEST_EMAIL);
        Map<?, ?> created = createTask(token, "No Scheduler Task");
        String id = (String) created.get("id");

        // Users must not set OVERDUE directly — only the scheduler does.
        patchState(token, id, "OVERDUE", 0, 422);
    }

    @Test
    void patchState_missingVersionWhenStatePresent_returns400() throws Exception {
        String token = registerAndGetToken(TEST_EMAIL);
        Map<?, ?> created = createTask(token, "No Version Task");
        String id = (String) created.get("id");

        mockMvc.perform(patch("/api/v1/tasks/" + id)
                        .header("Authorization", "Bearer " + token)
                        .header("X-Tenant-ID", TENANT_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "state", "IN_PROGRESS"))))
                .andExpect(status().isBadRequest());
    }

    // -------------------------------------------------------------------------
    // Optimistic locking (PATCH state)
    // -------------------------------------------------------------------------

    @Test
    void patchState_staleVersion_returns409() throws Exception {
        String token = registerAndGetToken(TEST_EMAIL);
        Map<?, ?> created = createTask(token, "Stale Task");
        String id = (String) created.get("id");

        // Advance version to 1.
        patchState(token, id, "IN_PROGRESS", 0, 200);

        // Re-submit with the old version (0) — the DB now has version 1.
        patchState(token, id, "COMPLETED", 0, 409);
    }

    // -------------------------------------------------------------------------
    // Soft-delete
    // -------------------------------------------------------------------------

    @Test
    void deleteTask_returns204AndTaskBecomesInvisibleToGet() throws Exception {
        String token = registerAndGetToken(TEST_EMAIL);
        Map<?, ?> created = createTask(token, "Doomed Task");
        String id = (String) created.get("id");

        mockMvc.perform(delete("/api/v1/tasks/" + id)
                        .header("Authorization", "Bearer " + token)
                        .header("X-Tenant-ID", TENANT_ID))
                .andExpect(status().isNoContent());

        // Soft-deleted task must return 404 on GET.
        mockMvc.perform(get("/api/v1/tasks/" + id)
                        .header("Authorization", "Bearer " + token)
                        .header("X-Tenant-ID", TENANT_ID))
                .andExpect(status().isNotFound());

        // @SQLRestriction("deleted_at IS NULL") hides the row from findAll().
        assertThat(taskRepository.findAll()).isEmpty();
    }

    @Test
    void deleteTask_absentFromListEndpoint() throws Exception {
        String token = registerAndGetToken(TEST_EMAIL);
        createTask(token, "Task A");
        Map<?, ?> toDelete = createTask(token, "Task B — to be deleted");
        String deletedId = (String) toDelete.get("id");

        mockMvc.perform(delete("/api/v1/tasks/" + deletedId)
                        .header("Authorization", "Bearer " + token)
                        .header("X-Tenant-ID", TENANT_ID))
                .andExpect(status().isNoContent());

        // List returns only 1 (non-deleted) task.
        mockMvc.perform(get("/api/v1/tasks")
                        .header("Authorization", "Bearer " + token)
                        .header("X-Tenant-ID", TENANT_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalCount").value(1));
    }

    // -------------------------------------------------------------------------
    // BOLA — cross-user access (same tenant, different userId)
    // -------------------------------------------------------------------------

    @Test
    void getTask_differentUser_returns404() throws Exception {
        String token1 = registerAndGetToken(TEST_EMAIL);
        String token2 = registerAndGetToken(TEST_EMAIL_2);

        // User1 creates a task.
        Map<?, ?> created = createTask(token1, "Private Task");
        String id = (String) created.get("id");

        // User2 (different userId, same tenant) must receive 404.
        mockMvc.perform(get("/api/v1/tasks/" + id)
                        .header("Authorization", "Bearer " + token2)
                        .header("X-Tenant-ID", TENANT_ID))
                .andExpect(status().isNotFound());
    }

    @Test
    void deleteTask_differentUser_returns404() throws Exception {
        String token1 = registerAndGetToken(TEST_EMAIL);
        String token2 = registerAndGetToken(TEST_EMAIL_2);

        Map<?, ?> created = createTask(token1, "Protected Task");
        String id = (String) created.get("id");

        // User2 cannot delete User1's task.
        mockMvc.perform(delete("/api/v1/tasks/" + id)
                        .header("Authorization", "Bearer " + token2)
                        .header("X-Tenant-ID", TENANT_ID))
                .andExpect(status().isNotFound());
    }

    // -------------------------------------------------------------------------
    // Admin role guard (STANDARD user → 403)
    // -------------------------------------------------------------------------

    @Test
    void adminStats_standardUser_returns403() throws Exception {
        // Registered users get the STANDARD role by default.
        String standardToken = registerAndGetToken(TEST_EMAIL);

        mockMvc.perform(get("/api/v1/admin/stats")
                        .header("Authorization", "Bearer " + standardToken)
                        .header("X-Tenant-ID", TENANT_ID))
                .andExpect(status().isForbidden());
    }

    @Test
    void adminListUsers_standardUser_returns403() throws Exception {
        String standardToken = registerAndGetToken(TEST_EMAIL);

        mockMvc.perform(get("/api/v1/admin/users")
                        .header("Authorization", "Bearer " + standardToken)
                        .header("X-Tenant-ID", TENANT_ID))
                .andExpect(status().isForbidden());
    }

    // -------------------------------------------------------------------------
    // Matrix
    // -------------------------------------------------------------------------

    @Test
    void getTaskMatrix_returnsNineCells() throws Exception {
        String token = registerAndGetToken(TEST_EMAIL);
        createTask(token, "Matrix Task");

        mockMvc.perform(get("/api/v1/tasks/matrix")
                        .header("Authorization", "Bearer " + token)
                        .header("X-Tenant-ID", TENANT_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cells").isArray())
                .andExpect(jsonPath("$.cells.length()").value(9));
    }

    @Test
    void getTaskMatrix_taskAppearsInCorrectCell() throws Exception {
        String token = registerAndGetToken(TEST_EMAIL);

        // Create a task with HIGH importance and LOW urgency
        mockMvc.perform(post("/api/v1/tasks")
                        .header("Authorization", "Bearer " + token)
                        .header("X-Tenant-ID", TENANT_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "title", "Cell Test Task",
                                "importance", "HIGH",
                                "urgency", "LOW"))))
                .andExpect(status().isCreated());

        MvcResult matrixResult = mockMvc.perform(get("/api/v1/tasks/matrix")
                        .header("Authorization", "Bearer " + token)
                        .header("X-Tenant-ID", TENANT_ID))
                .andExpect(status().isOk())
                .andReturn();

        var cells = objectMapper.readTree(matrixResult.getResponse().getContentAsString()).get("cells");
        boolean found = false;
        for (var cell : cells) {
            if ("HIGH".equals(cell.get("importance").asText())
                    && "LOW".equals(cell.get("urgency").asText())) {
                assertThat(cell.get("tasks").size()).isEqualTo(1);
                assertThat(cell.get("tasks").get(0).get("title").asText()).isEqualTo("Cell Test Task");
                found = true;
            }
        }
        assertThat(found).isTrue();
    }

    @Test
    void getTaskMatrix_deletedTaskAbsent() throws Exception {
        String token = registerAndGetToken(TEST_EMAIL);
        Map<?, ?> created = createTask(token, "To Be Deleted");
        String id = (String) created.get("id");

        mockMvc.perform(delete("/api/v1/tasks/" + id)
                        .header("Authorization", "Bearer " + token)
                        .header("X-Tenant-ID", TENANT_ID))
                .andExpect(status().isNoContent());

        MvcResult matrixResult = mockMvc.perform(get("/api/v1/tasks/matrix")
                        .header("Authorization", "Bearer " + token)
                        .header("X-Tenant-ID", TENANT_ID))
                .andExpect(status().isOk())
                .andReturn();

        var cells = objectMapper.readTree(matrixResult.getResponse().getContentAsString()).get("cells");
        int totalTasks = 0;
        for (var cell : cells) {
            totalTasks += cell.get("tasks").size();
        }
        assertThat(totalTasks).isEqualTo(0);
    }

    @Test
    void getTaskMatrix_isUserScoped() throws Exception {
        String token1 = registerAndGetToken(TEST_EMAIL);
        String token2 = registerAndGetToken(TEST_EMAIL_2);
        createTask(token1, "User1 Private Task");

        MvcResult matrixResult = mockMvc.perform(get("/api/v1/tasks/matrix")
                        .header("Authorization", "Bearer " + token2)
                        .header("X-Tenant-ID", TENANT_ID))
                .andExpect(status().isOk())
                .andReturn();

        var cells = objectMapper.readTree(matrixResult.getResponse().getContentAsString()).get("cells");
        int totalTasks = 0;
        for (var cell : cells) {
            totalTasks += cell.get("tasks").size();
        }
        assertThat(totalTasks).isEqualTo(0);
    }

    // -------------------------------------------------------------------------
    // Task history — content verification
    // -------------------------------------------------------------------------

    @Test
    void createTask_historyRowHasNullFromStateAndPlannedToState() throws Exception {
        String token = registerAndGetToken(TEST_EMAIL);
        Map<?, ?> created = createTask(token, "History Content Task");
        String id = (String) created.get("id");

        List<TaskHistory> history =
                taskHistoryRepository.findByTaskIdOrderByChangedAtDesc(UUID.fromString(id));
        assertThat(history).hasSize(1);
        assertThat(history.get(0).getFromState()).isNull();
        assertThat(history.get(0).getToState()).isEqualTo("PLANNED");
    }

    @Test
    void patchState_historyRowHasCorrectFromAndToState() throws Exception {
        String token = registerAndGetToken(TEST_EMAIL);
        Map<?, ?> created = createTask(token, "State History Task");
        String id = (String) created.get("id");

        patchState(token, id, "IN_PROGRESS", 0, 200);

        List<TaskHistory> history =
                taskHistoryRepository.findByTaskIdOrderByChangedAtDesc(UUID.fromString(id));
        assertThat(history).hasSize(2);
        // Filter by toState to avoid ordering fragility when rows share the same millisecond timestamp
        TaskHistory transition = history.stream()
                .filter(h -> "IN_PROGRESS".equals(h.getToState()))
                .findFirst().orElseThrow();
        assertThat(transition.getFromState()).isEqualTo("PLANNED");
        assertThat(transition.getToState()).isEqualTo("IN_PROGRESS");
    }

    // -------------------------------------------------------------------------
    // Admin — happy path (ADMIN role)
    // -------------------------------------------------------------------------

    @Test
    void adminStats_adminUser_returns200() throws Exception {
        String adminToken = registerAsAdmin(TEST_EMAIL);

        mockMvc.perform(get("/api/v1/admin/stats")
                        .header("Authorization", "Bearer " + adminToken)
                        .header("X-Tenant-ID", TENANT_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalUsers").isNumber())
                .andExpect(jsonPath("$.totalTasks").isNumber());
    }

    @Test
    void adminListUsers_adminUser_returns200() throws Exception {
        String adminToken = registerAsAdmin(TEST_EMAIL);

        mockMvc.perform(get("/api/v1/admin/users")
                        .header("Authorization", "Bearer " + adminToken)
                        .header("X-Tenant-ID", TENANT_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.totalCount").isNumber());
    }

    // -------------------------------------------------------------------------
    // Admin — role management and tenant creation
    // -------------------------------------------------------------------------

    @Test
    void adminUpdateUserRole_promotesStandardUserToAdmin_returns200() throws Exception {
        String adminToken = registerAsAdmin(TEST_EMAIL);

        // Register a second user to promote
        MvcResult registerResult = mockMvc.perform(post("/internal/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "tenantId", TENANT_ID,
                                "email", TEST_EMAIL_2,
                                "password", TEST_PASSWORD))))
                .andExpect(status().isOk())
                .andReturn();
        Map<?, ?> registerBody = objectMapper.readValue(
                registerResult.getResponse().getContentAsString(), Map.class);
        String targetUserId = (String) registerBody.get("userId");

        mockMvc.perform(patch("/api/v1/admin/users/" + targetUserId + "/role")
                        .header("Authorization", "Bearer " + adminToken)
                        .header("X-Tenant-ID", TENANT_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("role", "ADMIN"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("ADMIN"))
                .andExpect(jsonPath("$.id").value(targetUserId));
    }

    @Test
    void adminCreateTenant_adminUser_returns201WithName() throws Exception {
        String adminToken = registerAsAdmin(TEST_EMAIL);
        String tenantName = "test-tenant-" + System.currentTimeMillis();

        mockMvc.perform(post("/api/v1/admin/tenants")
                        .header("Authorization", "Bearer " + adminToken)
                        .header("X-Tenant-ID", TENANT_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("name", tenantName))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isString())
                .andExpect(jsonPath("$.name").value(tenantName));
    }

    @Test
    void adminExportTasks_returns501NotImplemented() throws Exception {
        // Export is intentionally not implemented in v1 (requires Apache POI / iText).
        // API contract documents it as returning 501.
        String adminToken = registerAsAdmin(TEST_EMAIL);

        mockMvc.perform(get("/api/v1/admin/reports/tasks")
                        .header("Authorization", "Bearer " + adminToken)
                        .header("X-Tenant-ID", TENANT_ID)
                        .param("format", "xlsx"))
                .andExpect(status().isNotImplemented());
    }

    // -------------------------------------------------------------------------
    // Helpers (extended)
    // -------------------------------------------------------------------------

    /** Registers a user, promotes to ADMIN role, refreshes JWT, returns ADMIN-scoped JWT. */
    private String registerAsAdmin(String email) throws Exception {
        MvcResult registerResult = mockMvc.perform(post("/internal/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "tenantId", TENANT_ID,
                                "email", email,
                                "password", TEST_PASSWORD))))
                .andExpect(status().isOk())
                .andReturn();
        Map<?, ?> registerBody = objectMapper.readValue(
                registerResult.getResponse().getContentAsString(), Map.class);
        String userId = (String) registerBody.get("userId");

        // Promote to ADMIN by updating the role entity directly (no admin endpoint yet exists)
        Role adminRole = roleRepository.findByName("ADMIN").orElseThrow();
        userRepository.findById(UUID.fromString(userId)).ifPresent(user -> {
            user.setRole(adminRole);
            userRepository.save(user);
        });

        // Refresh JWT so it contains role=ADMIN
        MvcResult refreshResult = mockMvc.perform(post("/internal/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("userId", userId))))
                .andExpect(status().isOk())
                .andReturn();
        Map<?, ?> refreshBody = objectMapper.readValue(
                refreshResult.getResponse().getContentAsString(), Map.class);
        return (String) refreshBody.get("token");
    }
}
