package com.tm.core.infrastructure;

import com.tm.core.domain.PasswordResetToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repository for PasswordResetToken entities.
 * Only applicable to LOCAL auth users. See DATABASE_SCHEMA.md §password_reset_tokens.
 */
public interface PasswordResetTokenRepository extends JpaRepository<PasswordResetToken, UUID> {

    // Token validation during the reset flow (idx_reset_tokens_hash)
    Optional<PasswordResetToken> findByTokenHash(String tokenHash);

    // Look up all tokens for a user — used to invalidate old tokens on new request
    List<PasswordResetToken> findByUserId(UUID userId);

    // After a successful password reset: clear all remaining unused tokens for the user.
    // See PASSWORD_POLICY.md §4 step 4.
    @Transactional
    @Modifying
    @Query("DELETE FROM PasswordResetToken t WHERE t.userId = :userId AND t.usedAt IS NULL")
    void deleteUnusedByUserId(@Param("userId") UUID userId);

    // Cleanup job (00:10 UTC daily) — deletes tokens that are either used or expired
    // AND were created more than 7 days ago. See DATABASE_SCHEMA.md §Overdue Task Automation.
    @Transactional
    @Modifying
    @Query("DELETE FROM PasswordResetToken t " +
           "WHERE (t.usedAt IS NOT NULL OR t.expiresAt < :now) " +
           "AND t.createdAt < :cutoff")
    int deleteExpiredTokens(@Param("now") Instant now, @Param("cutoff") Instant cutoff);
}