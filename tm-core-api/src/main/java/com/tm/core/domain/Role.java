package com.tm.core.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.UUID;

/**
 * Role assigned to a user. Seeded at migration time — not user-editable.
 *
 * Fixed UUIDs (from Liquibase changeset 002):
 *   STANDARD → 00000000-0000-0000-0000-000000000010
 *   ADMIN    → 00000000-0000-0000-0000-000000000011
 *
 * No @GeneratedValue — roles are managed exclusively by Liquibase migrations.
 * No timestamps — roles are static reference data.
 */
@Entity
@Table(name = "roles")
@Getter
@Setter
@NoArgsConstructor
public class Role {

    @Id
    @Column(updatable = false, nullable = false)
    private UUID id;

    @Column(length = 50, nullable = false, unique = true)
    private String name;
}