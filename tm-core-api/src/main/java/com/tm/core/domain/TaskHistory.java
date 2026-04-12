package com.tm.core.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.Filter;

import java.time.Instant;
import java.util.UUID;

/**
 * Immutable audit record of a task state transition.
 *
 * Written by TaskHistoryService on every state change (CODING_PATTERNS.md §20).
 * Written by the scheduler for OVERDUE transitions (CODING_PATTERNS.md §7).
 * There is no public read API for task history in v1.
 *
 * Tenant isolation: filtered by the Hibernate "tenantFilter" (tenant_id is
 * denormalised here to avoid a join on every history query). See MULTI_TENANCY.md §4.
 *
 * changedAt is DB-managed (DEFAULT now()) — not set by the application.
 * fromState is NULL on the initial task creation event (→ PLANNED).
 *
 * No @SQLRestriction — history rows are permanent records and must never be filtered out.
 */
@Entity
@Table(name = "task_history")
@Filter(name = "tenantFilter", condition = "tenant_id = :tenantId")
@Getter
@Setter
@NoArgsConstructor
public class TaskHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(updatable = false, nullable = false)
    private UUID id;

    @Column(nullable = false)
    private UUID taskId;

    // Denormalised for tenant isolation — avoids a join on every history query.
    @Column(nullable = false)
    private UUID tenantId;

    // User who triggered the transition. For scheduler-driven OVERDUE transitions,
    // use the owning task's userId. See DATABASE_SCHEMA.md §task_history.
    @Column(nullable = false)
    private UUID changedBy;

    // NULL on the initial creation event (task had no previous state).
    // String (not enum) — preserves history even if the enum changes in future.
    @Column(length = 20)
    private String fromState;

    @Column(length = 20, nullable = false)
    private String toState;

    // Set by DB DEFAULT now(). insertable=false prevents Hibernate from
    // including this column in INSERT statements.
    @Column(insertable = false, updatable = false)
    private Instant changedAt;
}