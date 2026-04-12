package com.tm.core.application;

import com.tm.core.domain.InvalidTokenException;
import com.tm.core.domain.enums.AuthProvider;
import com.tm.core.domain.PasswordResetToken;
import com.tm.core.domain.User;
import com.tm.core.infrastructure.PasswordResetTokenRepository;
import com.tm.core.infrastructure.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;

/**
 * Handles the forgot-password and reset-password flows.
 * Raw tokens are never stored — only SHA-256(token) is persisted.
 * See PASSWORD_POLICY.md §4 and AUTH_CONFIG.md §9.
 */
@Service
@RequiredArgsConstructor
public class PasswordResetService {

    // Singleton: SecureRandom instances are thread-safe and expensive to seed.
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final PasswordResetTokenRepository tokenRepository;
    private final UserRepository userRepository;
    private final PasswordValidationService passwordValidationService;
    private final EmailService emailService;
    private final PasswordEncoder passwordEncoder;

    @Value("${app.password.reset-token-expiry-hours}")
    private int resetTokenExpiryHours;

    /**
     * Initiates a password reset for a LOCAL user.
     * Always returns silently — callers must return 204 regardless of outcome (prevents enumeration).
     */
    @Transactional
    public void initiateReset(String email, UUID tenantId) {
        Optional<User> userOpt = userRepository.findByTenantIdAndEmail(tenantId, email);
        if (userOpt.isEmpty() || userOpt.get().getAuthProvider() != AuthProvider.LOCAL) {
            // Not found or OAuth2 user — return silently. Endpoint must always return 204.
            return;
        }
        User user = userOpt.get();

        String rawToken = generateRawToken();
        String tokenHash = hashToken(rawToken);

        PasswordResetToken token = new PasswordResetToken();
        token.setUserId(user.getId());
        token.setTokenHash(tokenHash);
        token.setExpiresAt(Instant.now().plusSeconds((long) resetTokenExpiryHours * 3600));
        tokenRepository.save(token);

        emailService.sendPasswordResetEmail(user.getEmail(), rawToken);
    }

    /**
     * Validates the reset token and updates the user's password.
     * Throws InvalidTokenException (HTTP 400) if the token is invalid, used, or expired.
     */
    @Transactional
    public void resetPassword(String rawToken, String newPassword) {
        String tokenHash = hashToken(rawToken);
        PasswordResetToken token = tokenRepository.findByTokenHash(tokenHash)
                .orElseThrow(() -> new InvalidTokenException(
                        "This reset link is invalid or has expired. Please request a new one."));

        if (token.getUsedAt() != null || token.getExpiresAt().isBefore(Instant.now())) {
            throw new InvalidTokenException(
                    "This reset link is invalid or has expired. Please request a new one.");
        }

        passwordValidationService.validate(newPassword);

        User user = userRepository.findById(token.getUserId())
                .orElseThrow(() -> new IllegalStateException(
                        "User not found for valid reset token — data integrity error"));
        user.setPasswordHash(passwordEncoder.encode(newPassword));
        user.setPasswordChangedAt(Instant.now());
        userRepository.save(user);

        // Mark this token as used, then clear all remaining unused tokens for this user.
        token.setUsedAt(Instant.now());
        tokenRepository.save(token);
        tokenRepository.deleteUnusedByUserId(user.getId());
    }

    private String generateRawToken() {
        byte[] bytes = new byte[32];
        SECURE_RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private String hashToken(String rawToken) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(rawToken.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}