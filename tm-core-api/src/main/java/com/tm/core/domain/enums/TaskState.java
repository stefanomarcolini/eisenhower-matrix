package com.tm.core.domain.enums;

/**
 * State machine values for a task.
 *
 * Legal user-driven transitions:
 *   PLANNED → IN_PROGRESS
 *   IN_PROGRESS → COMPLETED
 *   OVERDUE → IN_PROGRESS
 *
 * COMPLETED is terminal. OVERDUE is set only by the scheduler.
 * See PROJECT_OVERVIEW.md §3.
 */
public enum TaskState {
    PLANNED,
    IN_PROGRESS,
    COMPLETED,
    OVERDUE
}