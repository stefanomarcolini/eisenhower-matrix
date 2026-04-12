package com.tm.core.application;

import com.tm.core.infrastructure.PasswordResetTokenRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * Deletes expired and used password-reset tokens that are older than 7 days.
 * Runs daily at 00:10 UTC.
 * See PASSWORD_POLICY.md §4 and CODING_PATTERNS.md §7.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class TokenCleanupScheduler {

    private final PasswordResetTokenRepository tokenRepository;

    @Scheduled(cron = "0 10 0 * * *", zone = "UTC")
    public void deleteStaleTokens() {
        Instant now = Instant.now();
        Instant cutoff = now.minus(7, ChronoUnit.DAYS);
        int deleted = tokenRepository.deleteExpiredTokens(now, cutoff);
        log.info("Token cleanup: deleted {} stale password-reset tokens", deleted);
    }
}
