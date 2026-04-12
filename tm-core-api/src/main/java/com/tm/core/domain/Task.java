package com.tm.core.domain;

import com.tm.core.domain.enums.Priority;
import com.tm.core.domain.enums.TaskState;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.Filter;
import org.hibernate.annotations.SQLRestriction;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * A user task in the 3×3 Importance × Urgency matrix.
 *
 * Soft deletes: setting deletedAt makes the row invisible to all standard JPA
 * queries via @SQLRestriction. Do NOT call taskRepository.delete() — set
 * deletedAt and save instead (CODING_PATTERNS.md §20).
 *
 * CRITICAL: @SQLRestriction is NOT applied to bulk JPQL UPDATE/DELETE.
 * Any bulk DML on Task must include AND t.deletedAt IS NULL explicitly.
 * See CODING_PATTERNS.md §7 (scheduler) and §20.
 *
 * Optimistic locking: @Version increments on every UPDATE and adds
 * WHERE version = ? to prevent lost updates under concurrent edits.
 * Stale version → OptimisticLockException → HTTP 409 (CODING_PATTERNS.md §14).
 *
 * Tenant isolation: filtered by the Hibernate "tenantFilter". See MULTI_TENANCY.md §4.
 */
@Entity
@Table(name = "tasks")
@SQLRestriction("deleted_at IS NULL")
@Filter(name = "tenantFilter", condition = "tenant_id = :tenantId")
@Getter
@Setter
@NoArgsConstructor
public class Task extends AuditableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(updatable = false, nullable = false)
    private UUID id;

    @Column(nullable = false)
    private UUID tenantId;

    @Column(nullable = false)
    private UUID userId;

    @Column(length = 255, nullable = false)
    private String title;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(length = 20, nullable = false)
    private TaskState state = TaskState.PLANNED;

    @Enumerated(EnumType.STRING)
    @Column(length = 10, nullable = false)
    private Priority importance;

    @Enumerated(EnumType.STRING)
    @Column(length = 10, nullable = false)
    private Priority urgency;

    @Column
    private LocalDate dueDate;

    // Optimistic locking counter. Incremented automatically by JPA on every update.
    // Maps to tasks.version INT DEFAULT 0.
    @Version
    @Column(nullable = false)
    private int version;

    // Soft-delete marker. NULL = active. Set by TaskService.deleteTask(), never by JPA auditing.
    @Column
    private Instant deletedAt;
}