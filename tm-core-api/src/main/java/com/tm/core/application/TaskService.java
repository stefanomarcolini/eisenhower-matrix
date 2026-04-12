package com.tm.core.application;

import com.tm.core.domain.IllegalStateTransitionException;
import com.tm.core.domain.Task;
import com.tm.core.domain.enums.Priority;
import com.tm.core.domain.enums.TaskState;
import com.tm.core.infrastructure.TaskRepository;
import com.tm.core.web.model.CreateTaskRequest;
import com.tm.core.web.model.PatchTaskRequest;
import com.tm.core.web.model.UpdateTaskRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Business logic for task CRUD, state machine, and soft-delete.
 * Every state transition writes a TaskHistory row (CODING_PATTERNS.md §20).
 * Soft delete sets deletedAt — never calls taskRepository.delete().
 * userId is always read from the validated JWT claim, never from request body (BOLA defence).
 */
@Service
@RequiredArgsConstructor
public class TaskService {

    private final TaskRepository taskRepository;
    private final TaskHistoryService taskHistoryService;

    // Legal user-driven transitions (PROJECT_OVERVIEW.md §3).
    // OVERDUE is set only by the scheduler — never accepted here as a target.
    private static final Set<String> LEGAL_USER_TRANSITIONS = Set.of(
            "PLANNED→IN_PROGRESS",
            "IN_PROGRESS→COMPLETED",
            "OVERDUE→IN_PROGRESS"
    );

    // -------------------------------------------------------------------------
    // CRUD
    // -------------------------------------------------------------------------

    @Transactional
    public Task createTask(CreateTaskRequest req, UUID userId, UUID tenantId) {
        Task task = new Task();
        task.setTenantId(tenantId);
        task.setUserId(userId);
        task.setTitle(req.getTitle());
        task.setDescription(req.getDescription());
        task.setState(TaskState.PLANNED);   // always PLANNED on creation
        task.setImportance(Priority.valueOf(req.getImportance().name()));
        task.setUrgency(Priority.valueOf(req.getUrgency().name()));
        task.setDueDate(req.getDueDate());
        task = taskRepository.save(task);

        // Record creation in history: no prior state (fromState = null).
        taskHistoryService.record(task, null, TaskState.PLANNED.name(), userId);
        return task;
    }

    @Transactional(readOnly = true)
    public Task getTask(UUID id, UUID tenantId, UUID userId) {
        return taskRepository.findByIdAndTenantIdAndUserId(id, tenantId, userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Task not found"));
    }

    @Transactional(readOnly = true)
    public Page<Task> listTasks(UUID tenantId, UUID userId,
                                TaskState state, Priority importance, Priority urgency,
                                Pageable pageable) {
        Specification<Task> spec = Specification.where(
                (root, query, cb) -> cb.and(
                        cb.equal(root.get("tenantId"), tenantId),
                        cb.equal(root.get("userId"), userId)
                ));
        if (state != null) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("state"), state));
        }
        if (importance != null) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("importance"), importance));
        }
        if (urgency != null) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("urgency"), urgency));
        }
        return taskRepository.findAll(spec, pageable);
    }

    /**
     * Full replacement of all mutable fields. State is NOT changed by PUT — use PATCH.
     * Rejects the update if the client's version doesn't match the DB version (optimistic locking).
     * Note: calling task.setVersion() on a managed entity does NOT trigger Hibernate's WHERE clause
     * check — Hibernate uses the snapshot from when the entity was loaded. A manual comparison
     * is the correct approach when the client must supply the version it last saw.
     */
    @Transactional
    public Task updateTask(UUID id, UpdateTaskRequest req, UUID tenantId, UUID userId) {
        Task task = taskRepository.findByIdAndTenantIdAndUserId(id, tenantId, userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Task not found"));
        if (task.getVersion() != req.getVersion()) {
            throw new ObjectOptimisticLockingFailureException(Task.class, id);
        }
        task.setTitle(req.getTitle());
        task.setDescription(req.getDescription());   // null clears the field (full replacement)
        task.setImportance(Priority.valueOf(req.getImportance().name()));
        task.setUrgency(Priority.valueOf(req.getUrgency().name()));
        task.setDueDate(req.getDueDate());             // null clears the field
        return taskRepository.save(task);
    }

    /**
     * Partial update. When state is included the transition is validated and TaskHistory is written.
     * version is required when state is present (spec: optimistic locking on state changes).
     * null description = "don't change" (can't distinguish from absent in standard JSON deserialization).
     */
    @Transactional
    public Task patchTask(UUID id, PatchTaskRequest req, UUID tenantId, UUID userId) {
        Task task = taskRepository.findByIdAndTenantIdAndUserId(id, tenantId, userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Task not found"));

        if (req.getTitle() != null) task.setTitle(req.getTitle());
        if (req.getImportance() != null) {
            task.setImportance(Priority.valueOf(req.getImportance().name()));
        }
        if (req.getUrgency() != null) {
            task.setUrgency(Priority.valueOf(req.getUrgency().name()));
        }
        if (req.getDueDate() != null) task.setDueDate(req.getDueDate());

        if (req.getState() != null) {
            if (req.getVersion() == null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "version is required when state is present");
            }
            // Explicit version check — setVersion() on a managed entity is ignored by Hibernate's
            // WHERE clause generation; compare manually to get the 409 on a stale version.
            if (task.getVersion() != req.getVersion()) {
                throw new ObjectOptimisticLockingFailureException(Task.class, id);
            }
            TaskState newState = TaskState.valueOf(req.getState().name());
            validateTransition(task.getState(), newState);

            TaskState previousState = task.getState();
            task.setState(newState);
            task = taskRepository.save(task);
            taskHistoryService.record(task, previousState.name(), newState.name(), userId);
            return task;
        }

        return taskRepository.save(task);
    }

    /**
     * Soft-delete: sets deletedAt = now(). The row is retained; @SQLRestriction hides it from
     * all subsequent queries. No TaskHistory row is written — deletedAt is the deletion record.
     * See CODING_PATTERNS.md §20.
     */
    @Transactional
    public void deleteTask(UUID id, UUID tenantId, UUID userId) {
        Task task = taskRepository.findByIdAndTenantIdAndUserId(id, tenantId, userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Task not found"));
        task.setDeletedAt(Instant.now());
        taskRepository.save(task);
    }

    @Transactional(readOnly = true)
    public List<Task> getMatrix(UUID tenantId, UUID userId) {
        return taskRepository.findByTenantIdAndUserId(tenantId, userId);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private void validateTransition(TaskState from, TaskState to) {
        String key = from.name() + "→" + to.name();
        if (!LEGAL_USER_TRANSITIONS.contains(key)) {
            throw new IllegalStateTransitionException(from.name(), to.name());
        }
    }
}
