package com.tm.core.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.Instant;
import java.util.UUID;

/**
 * Represents an isolated organisational unit.
 * All user-owned data (users, tasks, task_history) carries a tenant_id FK.
 * See DATABASE_SCHEMA.md §tenants and MULTI_TENANCY.md §1.
 *
 * Tenants are created via POST /api/v1/admin/tenants (ADMIN role required).
 * The default bootstrap tenant (UUID ...000001) is seeded by Liquibase changeset 007.
 */
@Entity
@Table(name = "tenants")
@EntityListeners(AuditingEntityListener.class)
@Getter
@Setter
@NoArgsConstructor
public class Tenant {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(updatable = false, nullable = false)
    private UUID id;

    @Column(length = 255, nullable = false, unique = true)
    private String name;

    @CreatedDate
    @Column(updatable = false, nullable = false)
    private Instant createdAt;
}