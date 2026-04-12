package com.tm.core.domain;

import jakarta.persistence.Column;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.MappedSuperclass;
import lombok.Getter;
import lombok.Setter;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.Instant;

/**
 * Base class for entities that require creation and last-modified timestamps.
 *
 * @CreatedDate is set once on first persist and never updated.
 * @LastModifiedDate is updated on every JPA-managed save.
 *
 * Note: the scheduler's bulk JPQL UPDATE does NOT trigger @LastModifiedDate —
 * bulk DML must set updated_at explicitly. See CODING_PATTERNS.md §14.
 *
 * Entities with only created_at (Tenant, TaskHistory, PasswordResetToken) do
 * NOT extend this class — they manage their timestamps directly.
 */
@MappedSuperclass
@EntityListeners(AuditingEntityListener.class)
@Getter
@Setter
public abstract class AuditableEntity {

    @CreatedDate
    @Column(updatable = false, nullable = false)
    private Instant createdAt;

    @LastModifiedDate
    @Column(nullable = false)
    private Instant updatedAt;
}