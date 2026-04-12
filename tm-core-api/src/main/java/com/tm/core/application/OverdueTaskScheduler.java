package com.tm.core.application;

import com.tm.core.domain.Task;
import com.tm.core.domain.enums.TaskState;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.hibernate.Session;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Marks PLANNED and IN_PROGRESS tasks as OVERDUE when their due date has passed.
 * Runs daily at 00:05 UTC (before the token cleanup at 00:10).
 *
 * Design notes (CODING_PATTERNS.md §7):
 * - The tenant filter is disabled so the job operates across all tenants.
 * - @SQLRestriction("deleted_at IS NULL") is applied automatically to the SELECT
 *   query, but the predicate is also written explicitly for documentation clarity.
 * - Dirty tracking flushes all state changes at transaction commit — no explicit save() needed.
 * - TaskHistory rows are written for each transition so the audit trail is complete.
 * - Bulk JPQL UPDATE is deliberately avoided: fetching first allows TaskHistoryService
 *   to record an individual history row per task (CODING_PATTERNS.md §7, §20).
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class OverdueTaskScheduler {

    @PersistenceContext
    private EntityManager entityManager;

    private final TaskHistoryService taskHistoryService;

    @Scheduled(cron = "0 5 0 * * *", zone = "UTC")
    @Transactional
    public void markOverdueTasks() {
        Session session = entityManager.unwrap(Session.class);
        session.disableFilter("tenantFilter");

        // @SQLRestriction("deleted_at IS NULL") is always active on SELECT queries,
        // but the explicit predicate makes the exclusion visible and guards against
        // future refactoring that might remove @SQLRestriction.
        List<Task> toTransition = entityManager.createQuery(
                        "SELECT t FROM Task t " +
                        "WHERE t.dueDate < CURRENT_DATE " +
                        "AND t.state IN :states " +
                        "AND t.deletedAt IS NULL", Task.class)
                .setParameter("states", List.of(TaskState.PLANNED, TaskState.IN_PROGRESS))
                .getResultList();

        for (Task task : toTransition) {
            TaskState previous = task.getState();
            task.setState(TaskState.OVERDUE);
            // changedBy = task owner (scheduler acts on behalf of the owning user)
            taskHistoryService.record(task, previous.name(), TaskState.OVERDUE.name(),
                    task.getUserId());
        }

        // JPA dirty-checks all loaded entities and flushes UPDATE statements on commit.
        log.info("Overdue scheduler: transitioned {} task(s) to OVERDUE", toTransition.size());
    }
}
