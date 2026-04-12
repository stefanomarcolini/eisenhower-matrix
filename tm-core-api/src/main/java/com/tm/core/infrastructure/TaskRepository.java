package com.tm.core.infrastructure;

import com.tm.core.domain.Task;
import com.tm.core.domain.enums.Priority;
import com.tm.core.domain.enums.TaskState;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repository for Task entities.
 *
 * @SQLRestriction("deleted_at IS NULL") on Task ensures that all derived
 * queries here automatically exclude soft-deleted tasks.
 *
 * BOLA defence (CODING_PATTERNS.md §4, §14):
 * - findByIdAndTenantIdAndUserId is the primary lookup for user-scoped operations.
 *   It scopes to tenant AND user — preventing one user from accessing another
 *   user's tasks even within the same tenant (Object-Level Access Control).
 * - Admin cross-tenant queries must call session.disableFilter("tenantFilter")
 *   and be guarded by @PreAuthorize("hasRole('ADMIN')").
 */
public interface TaskRepository extends JpaRepository<Task, UUID>, JpaSpecificationExecutor<Task> {

    // Primary user-scoped lookup — BOLA-safe (tenant + user + id)
    Optional<Task> findByIdAndTenantIdAndUserId(UUID id, UUID tenantId, UUID userId);

    // Paginated task list for the authenticated user (GET /api/v1/tasks)
    Page<Task> findByTenantIdAndUserId(UUID tenantId, UUID userId, Pageable pageable);

    // Filtered list — individual filter params (combined filters handled via Specification in service layer)
    Page<Task> findByTenantIdAndUserIdAndState(UUID tenantId, UUID userId, TaskState state, Pageable pageable);
    Page<Task> findByTenantIdAndUserIdAndImportance(UUID tenantId, UUID userId, Priority importance, Pageable pageable);
    Page<Task> findByTenantIdAndUserIdAndUrgency(UUID tenantId, UUID userId, Priority urgency, Pageable pageable);

    // Matrix view — all active tasks for a user, no pagination (GET /api/v1/tasks/matrix)
    List<Task> findByTenantIdAndUserId(UUID tenantId, UUID userId);

    // Admin aggregate query — counts active (non-deleted) tasks grouped by state.
    // @SQLRestriction("deleted_at IS NULL") is applied automatically.
    // Caller must disable tenantFilter before invoking (CODING_PATTERNS.md §14).
    @Query("SELECT t.state, COUNT(t) FROM Task t GROUP BY t.state")
    List<Object[]> countGroupedByState();
}