package com.tm.core.domain.enums;

/**
 * Priority level used for both task importance and task urgency.
 * Maps to the 3×3 Importance × Urgency matrix. See PROJECT_OVERVIEW.md §2.
 */
public enum Priority {
    LOW,
    MEDIUM,
    HIGH
}