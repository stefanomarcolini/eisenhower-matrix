package com.tm.core.web;

import com.tm.core.application.UserProfileService;
import com.tm.core.domain.User;
import com.tm.core.web.api.UsersApiDelegate;
import com.tm.core.web.model.AuthProvider;
import com.tm.core.web.model.ChangePasswordRequest;
import com.tm.core.web.model.MfaCodeRequest;
import com.tm.core.web.model.MfaEnrollResponse;
import com.tm.core.web.model.Role;
import com.tm.core.web.model.Theme;
import com.tm.core.web.model.UpdateProfileRequest;
import com.tm.core.web.model.UserProfile;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

/**
 * Implements UsersApiDelegate — thin adapter between generated REST controllers and
 * UserProfileService.
 * Extracts userId and tenantId from JWT (SecurityContextHolder) — never from request body.
 * See CODING_PATTERNS.md §1 (delegate pattern) and §14 (BOLA defence).
 */
@Service
@RequiredArgsConstructor
public class UsersApiDelegateImpl implements UsersApiDelegate {

    private final UserProfileService userProfileService;

    // -------------------------------------------------------------------------
    // UsersApiDelegate implementation
    // -------------------------------------------------------------------------

    @Override
    public ResponseEntity<UserProfile> getMyProfile() {
        Jwt jwt = currentJwt();
        User user = userProfileService.getProfile(userId(jwt), tenantId(jwt));
        return ResponseEntity.ok(mapToModel(user));
    }

    @Override
    public ResponseEntity<UserProfile> updateMyProfile(UpdateProfileRequest req) {
        Jwt jwt = currentJwt();
        User user = userProfileService.updateProfile(userId(jwt), tenantId(jwt), req);
        return ResponseEntity.ok(mapToModel(user));
    }

    @Override
    public ResponseEntity<Void> changePassword(ChangePasswordRequest req) {
        Jwt jwt = currentJwt();
        userProfileService.changePassword(
                userId(jwt), tenantId(jwt),
                req.getCurrentPassword(), req.getNewPassword());
        return ResponseEntity.noContent().build();
    }

    @Override
    public ResponseEntity<MfaEnrollResponse> initiateMfaEnrollment() {
        Jwt jwt = currentJwt();
        String[] secretAndUri = userProfileService.initiateMfaEnrollment(
                userId(jwt), tenantId(jwt));
        MfaEnrollResponse response = new MfaEnrollResponse();
        response.setSecret(secretAndUri[0]);
        response.setOtpauthUri(secretAndUri[1]);
        return ResponseEntity.ok(response);
    }

    @Override
    public ResponseEntity<Void> confirmMfaEnrollment(MfaCodeRequest req) {
        Jwt jwt = currentJwt();
        userProfileService.confirmMfaEnrollment(userId(jwt), tenantId(jwt), req.getCode());
        return ResponseEntity.noContent().build();
    }

    @Override
    public ResponseEntity<Void> disableMfa(MfaCodeRequest req) {
        Jwt jwt = currentJwt();
        userProfileService.disableMfa(userId(jwt), tenantId(jwt), req.getCode());
        return ResponseEntity.noContent().build();
    }

    // -------------------------------------------------------------------------
    // Mapping helpers
    // -------------------------------------------------------------------------

    private UserProfile mapToModel(User user) {
        UserProfile dto = new UserProfile();
        dto.setId(user.getId());
        dto.setEmail(user.getEmail());
        dto.setDisplayName(user.getDisplayName());
        dto.setRole(Role.valueOf(user.getRole().getName()));
        dto.setAuthProvider(AuthProvider.valueOf(user.getAuthProvider().name()));
        dto.setIsMfaEnabled(user.isMfaEnabled());
        dto.setTheme(Theme.valueOf(user.getTheme().name()));
        // passwordWarning: only for LOCAL users; null for OAuth2
        if (user.getAuthProvider() == com.tm.core.domain.enums.AuthProvider.LOCAL) {
            dto.setPasswordWarning(userProfileService.isPasswordWarning(user));
        }
        dto.setCreatedAt(user.getCreatedAt() != null
                ? OffsetDateTime.ofInstant(user.getCreatedAt(), ZoneOffset.UTC) : null);
        dto.setUpdatedAt(user.getUpdatedAt() != null
                ? OffsetDateTime.ofInstant(user.getUpdatedAt(), ZoneOffset.UTC) : null);
        return dto;
    }

    // -------------------------------------------------------------------------
    // JWT helpers
    // -------------------------------------------------------------------------

    private Jwt currentJwt() {
        return (Jwt) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
    }

    private UUID userId(Jwt jwt) {
        return UUID.fromString(jwt.getSubject());
    }

    private UUID tenantId(Jwt jwt) {
        return UUID.fromString(jwt.getClaimAsString("tenantId"));
    }
}
