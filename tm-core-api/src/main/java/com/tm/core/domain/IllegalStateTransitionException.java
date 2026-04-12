package com.tm.core.domain;

/**
 * Thrown when a user attempts an illegal task state transition.
 * Mapped to HTTP 422 Unprocessable Entity in GlobalExceptionHandler.
 * Legal user-driven transitions: PLANNED→IN_PROGRESS, IN_PROGRESS→COMPLETED, OVERDUE→IN_PROGRESS.
 * COMPLETED is terminal. OVERDUE is set only by the scheduler, never by the user.
 * See PROJECT_OVERVIEW.md §3.
 */
public class IllegalStateTransitionException extends RuntimeException {

    public IllegalStateTransitionException(String from, String to) {
        super("Illegal state transition: " + from + " → " + to);
    }
}