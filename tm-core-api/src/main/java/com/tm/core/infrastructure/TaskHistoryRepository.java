package com.tm.core.infrastructure;

import com.tm.core.domain.TaskHistory;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

/**
 * Repository for TaskHistory entities.
 *
 * Write-only in v1 — there is no public API to read task history.
 * Used by TaskHistoryService to record state transitions.
 * See CODING_PATTERNS.md §20 and DATABASE_SCHEMA.md §task_history.
 */
public interface TaskHistoryRepository extends JpaRepository<TaskHistory, UUID> {

    // Used for internal audit queries (not exposed via API in v1)
    List<TaskHistory> findByTaskIdOrderByChangedAtDesc(UUID taskId);
}