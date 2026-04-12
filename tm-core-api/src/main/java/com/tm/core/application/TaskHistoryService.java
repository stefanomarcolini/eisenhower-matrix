package com.tm.core.application;

import com.tm.core.domain.Task;
import com.tm.core.domain.TaskHistory;
import com.tm.core.infrastructure.TaskHistoryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * Write-only service that records task state transitions in task_history.
 * There is no read API for task history in v1 — all reads are internal.
 * See CODING_PATTERNS.md §20.
 */
@Service
@RequiredArgsConstructor
public class TaskHistoryService {

    private final TaskHistoryRepository taskHistoryRepository;

    /**
     * Records a state transition.
     *
     * @param task      the saved task after the transition (taskId + tenantId are read from it)
     * @param fromState null on initial task creation (task had no prior state)
     * @param toState   the new state as a String (use enum.name() at call sites)
     * @param changedBy user who triggered the transition; for scheduler-driven OVERDUE
     *                  transitions use task.getUserId() (the task owner)
     */
    public void record(Task task, String fromState, String toState, UUID changedBy) {
        TaskHistory history = new TaskHistory();
        history.setTaskId(task.getId());
        history.setTenantId(task.getTenantId());
        history.setChangedBy(changedBy);
        history.setFromState(fromState);
        history.setToState(toState);
        // changedAt is DB DEFAULT now() — @Column(insertable=false) keeps Hibernate from setting it.
        taskHistoryRepository.save(history);
    }
}
