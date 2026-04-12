package com.tm.core.application;

import com.tm.core.domain.User;
import com.tm.core.domain.enums.AuthProvider;
import com.tm.core.domain.enums.Theme;
import com.tm.core.infrastructure.UserRepository;
import com.tm.core.web.model.UpdateProfileRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/**
 * Handles user profile operations: read, update, password change, and MFA lifecycle.
 * All operations are scoped to the authenticated user's tenantId (from JWT) — BOLA-safe.
 * See CODING_PATTERNS.md §14.
 */
@Service
@RequiredArgsConstructor
public class UserProfileService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final MfaService mfaService;
    private final PasswordValidationService passwordValidationService;

    @Value("${app.password.age-warning-days}")
    private long ageWarningDays;

    @Transactional(readOnly = true)
    public User getProfile(UUID userId, UUID tenantId) {
        return userRepository.findByIdAndTenantId(userId, tenantId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));
    }

    @Transactional
    public User updateProfile(UUID userId, UUID tenantId, UpdateProfileRequest req) {
        User user = userRepository.findByIdAndTenantId(userId, tenantId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));
        if (req.getDisplayName() != null) {
            user.setDisplayName(req.getDisplayName());
        }
        if (req.getTheme() != null) {
            user.setTheme(Theme.valueOf(req.getTheme().name()));
        }
        return userRepository.save(user);
    }

    /**
     * Changes password for LOCAL accounts only.
     * Validates the current password before accepting the new one.
     * OAuth2 users receive 400 (BOLA defence: prevent partial account takeover).
     */
    @Transactional
    public void changePassword(UUID userId, UUID tenantId,
                               String currentPassword, String newPassword) {
        User user = userRepository.findByIdAndTenantId(userId, tenantId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));

        if (user.getAuthProvider() != AuthProvider.LOCAL) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Password change is not available for OAuth2 accounts");
        }
        if (!passwordEncoder.matches(currentPassword, user.getPasswordHash())) {
            // Generic message — same as login failure; no info about which field is wrong.
            throw new BadCredentialsException("Invalid credentials");
        }
        passwordValidationService.validate(newPassword);
        user.setPasswordHash(passwordEncoder.encode(newPassword));
        user.setPasswordChangedAt(Instant.now());
        userRepository.save(user);
    }

    /**
     * Step 1 of MFA enrollment: generates a new TOTP secret, stores it encrypted,
     * and returns the plain secret + otpauth URI for QR display.
     * MFA is NOT yet enabled — that requires confirmMfaEnrollment().
     */
    @Transactional
    public String[] initiateMfaEnrollment(UUID userId, UUID tenantId) {
        User user = userRepository.findByIdAndTenantId(userId, tenantId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));

        String plainSecret = mfaService.generateSecret();
        user.setMfaSecret(mfaService.encrypt(plainSecret));
        userRepository.save(user);

        return new String[]{plainSecret, mfaService.buildOtpAuthUri(plainSecret, user.getEmail())};
    }

    /**
     * Step 2 of MFA enrollment: verifies the first TOTP code and sets isMfaEnabled = true.
     */
    @Transactional
    public void confirmMfaEnrollment(UUID userId, UUID tenantId, String code) {
        User user = userRepository.findByIdAndTenantId(userId, tenantId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));

        if (user.getMfaSecret() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "MFA enrollment not initiated");
        }
        if (!mfaService.verifyCode(user.getMfaSecret(), code)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid TOTP code");
        }
        user.setMfaEnabled(true);
        userRepository.save(user);
    }

    /**
     * Disables MFA after verifying the current TOTP code.
     * Clears the stored secret on success.
     */
    @Transactional
    public void disableMfa(UUID userId, UUID tenantId, String code) {
        User user = userRepository.findByIdAndTenantId(userId, tenantId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));

        if (!user.isMfaEnabled()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "MFA is not enabled");
        }
        if (!mfaService.verifyCode(user.getMfaSecret(), code)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid TOTP code");
        }
        user.setMfaEnabled(false);
        user.setMfaSecret(null);
        userRepository.save(user);
    }

    /**
     * Returns true when the LOCAL user's password is at or past the warning age threshold.
     * Matches the same logic as AuthService.isPasswordStale so both surfaces are consistent.
     * Always returns false for OAuth2 users (they have no password).
     */
    public boolean isPasswordWarning(User user) {
        if (user.getAuthProvider() != AuthProvider.LOCAL
                || user.getPasswordChangedAt() == null) {
            return false;
        }
        long daysOld = ChronoUnit.DAYS.between(user.getPasswordChangedAt(), Instant.now());
        return daysOld >= ageWarningDays;
    }
}
