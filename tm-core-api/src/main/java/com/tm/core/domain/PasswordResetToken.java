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

import java.time.Instant;
import java.util.UUID;

/**
 * Single-use, time-limited password reset token.
 * Only applicable to LOCAL auth users.
 *
 * Security design:
 * - Only the SHA-256 hash of the raw token is stored (token_hash column).
 *   The raw token is sent by email and never persisted. See DATABASE_SCHEMA.md §password_reset_tokens.
 * - usedAt is set to now() on single-use consumption — prevents replay.
 * - expiresAt is set by the service using PASSWORD_RESET_TOKEN_EXPIRY_HOURS (default 1 h).
 *
 * createdAt is DB-managed (DEFAULT now()) and is not set by the application.
 * A daily cleanup job deletes expired/used tokens older than 7 days.
 */
@Entity
@Table(name = "password_reset_tokens")
@Getter
@Setter
@NoArgsConstructor
public class PasswordResetToken {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(updatable = false, nullable = false)
    private UUID id;

    @Column(nullable = false)
    private UUID userId;

    // SHA-256 hash of the raw token. Never expose the raw token.
    @Column(length = 255, nullable = false)
    private String tokenHash;

    @Column(nullable = false)
    private Instant expiresAt;

    // NULL if not yet consumed. Set to now() on first (and only) use.
    @Column
    private Instant usedAt;

    // Set by DB DEFAULT now() — insertable=false prevents Hibernate from
    // including this column in INSERT statements.
    @Column(insertable = false, updatable = false)
    private Instant createdAt;
}