package com.tm.core.web;

import com.tm.core.application.TaskService;
import com.tm.core.domain.Task;
import com.tm.core.domain.enums.Priority;
import com.tm.core.domain.enums.TaskState;
import com.tm.core.web.api.TasksApiDelegate;
import com.tm.core.web.model.CreateTaskRequest;
import com.tm.core.web.model.MatrixCell;
import com.tm.core.web.model.PagedTaskResponse;
import com.tm.core.web.model.PatchTaskRequest;
import com.tm.core.web.model.TaskMatrixResponse;
import com.tm.core.web.model.UpdateTaskRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Implements TasksApiDelegate — the thin adapter between generated REST controllers and TaskService.
 * Extracts JWT claims (userId, tenantId) from the SecurityContext; never reads them from request body.
 * Maps between generated model types and domain types.
 * See CODING_PATTERNS.md §1 (delegate pattern) and §14 (BOLA defence).
 */
@Service
@RequiredArgsConstructor
public class TasksApiDelegateImpl implements TasksApiDelegate {

    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 100;

    private final TaskService taskService;

    // -------------------------------------------------------------------------
    // TasksApiDelegate implementation
    // -------------------------------------------------------------------------

    @Override
    public ResponseEntity<com.tm.core.web.model.Task> createTask(CreateTaskRequest req) {
        Jwt jwt = currentJwt();
        Task task = taskService.createTask(req, userId(jwt), tenantId(jwt));
        return ResponseEntity.status(201).body(mapToModel(task));
    }

    @Override
    public ResponseEntity<com.tm.core.web.model.Task> getTask(UUID id) {
        Jwt jwt = currentJwt();
        Task task = taskService.getTask(id, tenantId(jwt), userId(jwt));
        return ResponseEntity.ok(mapToModel(task));
    }

    @Override
    public ResponseEntity<PagedTaskResponse> listTasks(
            String cursor, Integer limit,
            com.tm.core.web.model.TaskState state,
            com.tm.core.web.model.Priority importance,
            com.tm.core.web.model.Priority urgency) {

        Jwt jwt = currentJwt();
        int pageSize = resolveLimit(limit);
        int pageIndex = decodeCursor(cursor);

        TaskState domainState = state != null ? TaskState.valueOf(state.name()) : null;
        Priority domainImportance = importance != null ? Priority.valueOf(importance.name()) : null;
        Priority domainUrgency = urgency != null ? Priority.valueOf(urgency.name()) : null;

        Page<Task> page = taskService.listTasks(
                tenantId(jwt), userId(jwt),
                domainState, domainImportance, domainUrgency,
                PageRequest.of(pageIndex, pageSize));

        PagedTaskResponse response = new PagedTaskResponse();
        response.setData(page.getContent().stream().map(this::mapToModel).toList());
        response.setTotalCount((int) page.getTotalElements());
        response.setNextCursor(page.hasNext() ? encodeCursor(pageIndex + 1) : null);
        return ResponseEntity.ok(response);
    }

    @Override
    public ResponseEntity<com.tm.core.web.model.Task> updateTask(UUID id, UpdateTaskRequest req) {
        Jwt jwt = currentJwt();
        Task task = taskService.updateTask(id, req, tenantId(jwt), userId(jwt));
        return ResponseEntity.ok(mapToModel(task));
    }

    @Override
    public ResponseEntity<com.tm.core.web.model.Task> patchTask(UUID id, PatchTaskRequest req) {
        Jwt jwt = currentJwt();
        Task task = taskService.patchTask(id, req, tenantId(jwt), userId(jwt));
        return ResponseEntity.ok(mapToModel(task));
    }

    @Override
    public ResponseEntity<Void> deleteTask(UUID id) {
        Jwt jwt = currentJwt();
        taskService.deleteTask(id, tenantId(jwt), userId(jwt));
        return ResponseEntity.noContent().build();
    }

    @Override
    public ResponseEntity<TaskMatrixResponse> getTaskMatrix() {
        Jwt jwt = currentJwt();
        List<Task> tasks = taskService.getMatrix(tenantId(jwt), userId(jwt));
        return ResponseEntity.ok(buildMatrix(tasks));
    }

    // -------------------------------------------------------------------------
    // Mapping helpers
    // -------------------------------------------------------------------------

    private com.tm.core.web.model.Task mapToModel(Task task) {
        com.tm.core.web.model.Task dto = new com.tm.core.web.model.Task();
        dto.setId(task.getId());
        dto.setTitle(task.getTitle());
        dto.setDescription(task.getDescription());
        dto.setState(com.tm.core.web.model.TaskState.valueOf(task.getState().name()));
        dto.setImportance(com.tm.core.web.model.Priority.valueOf(task.getImportance().name()));
        dto.setUrgency(com.tm.core.web.model.Priority.valueOf(task.getUrgency().name()));
        dto.setDueDate(task.getDueDate());
        dto.setVersion(task.getVersion());
        dto.setCreatedAt(task.getCreatedAt() != null
                ? OffsetDateTime.ofInstant(task.getCreatedAt(), ZoneOffset.UTC) : null);
        dto.setUpdatedAt(task.getUpdatedAt() != null
                ? OffsetDateTime.ofInstant(task.getUpdatedAt(), ZoneOffset.UTC) : null);
        return dto;
    }

    private TaskMatrixResponse buildMatrix(List<Task> tasks) {
        // Group tasks by importance_urgency key into 9 cells.
        Map<String, List<com.tm.core.web.model.Task>> cellMap = new HashMap<>();
        for (Task task : tasks) {
            String key = task.getImportance().name() + "_" + task.getUrgency().name();
            cellMap.computeIfAbsent(key, k -> new ArrayList<>()).add(mapToModel(task));
        }

        List<MatrixCell> cells = new ArrayList<>(9);
        for (Priority importance : Priority.values()) {
            for (Priority urgency : Priority.values()) {
                String key = importance.name() + "_" + urgency.name();
                MatrixCell cell = new MatrixCell();
                cell.setImportance(com.tm.core.web.model.Priority.valueOf(importance.name()));
                cell.setUrgency(com.tm.core.web.model.Priority.valueOf(urgency.name()));
                cell.setTasks(cellMap.getOrDefault(key, Collections.emptyList()));
                cells.add(cell);
            }
        }

        TaskMatrixResponse response = new TaskMatrixResponse();
        response.setCells(cells);
        return response;
    }

    // -------------------------------------------------------------------------
    // Cursor pagination (Base64-encoded page index)
    // -------------------------------------------------------------------------

    private int decodeCursor(String cursor) {
        if (cursor == null) return 0;
        try {
            return Integer.parseInt(new String(java.util.Base64.getUrlDecoder().decode(cursor)));
        } catch (Exception e) {
            return 0;   // invalid cursor → restart from page 0
        }
    }

    private String encodeCursor(int pageIndex) {
        return java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString(String.valueOf(pageIndex).getBytes());
    }

    private int resolveLimit(Integer limit) {
        if (limit == null) return DEFAULT_PAGE_SIZE;
        return Math.min(Math.max(limit, 1), MAX_PAGE_SIZE);
    }

    // -------------------------------------------------------------------------
    // JWT helpers
    // -------------------------------------------------------------------------

    private Jwt currentJwt() {
        return (Jwt) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
    }

    private UUID userId(Jwt jwt) {
        return UUID.fromString(jwt.getSubject());
    }

    private UUID tenantId(Jwt jwt) {
        return UUID.fromString(jwt.getClaimAsString("tenantId"));
    }
}
