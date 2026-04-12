package com.tm.core.web.internal;

import com.tm.core.application.AuthService;
import com.tm.core.application.PasswordResetService;
import com.tm.core.web.internal.dto.ForgotPasswordRequest;
import com.tm.core.web.internal.dto.MfaValidateRequest;
import com.tm.core.web.internal.dto.MfaValidateResponse;
import com.tm.core.web.internal.dto.OidcTokenRequest;
import com.tm.core.web.internal.dto.OidcTokenResponse;
import com.tm.core.web.internal.dto.RefreshRequest;
import com.tm.core.web.internal.dto.RefreshResponse;
import com.tm.core.web.internal.dto.RegisterRequest;
import com.tm.core.web.internal.dto.RegisterResponse;
import com.tm.core.web.internal.dto.ResetPasswordRequest;
import com.tm.core.web.internal.dto.ValidateRequest;
import com.tm.core.web.internal.dto.ValidateResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Internal authentication endpoints — BFF only, not in the public OpenAPI spec.
 * Network isolation (Kubernetes NetworkPolicy / Docker bridge) is the primary access gate.
 * Spring Security permits all /internal/** at the application level (JwtSecurityConfig).
 * See API_CONTRACT.md §Internal API and AUTH_CONFIG.md §11.
 */
@RestController
@RequestMapping("/internal/auth")
@RequiredArgsConstructor
public class AuthInternalController {

    private final AuthService authService;
    private final PasswordResetService passwordResetService;

    /** Exchange OIDC claims for an app-issued JWT (Google / Microsoft login). */
    @PostMapping("/token")
    public ResponseEntity<OidcTokenResponse> token(@Valid @RequestBody OidcTokenRequest request) {
        return ResponseEntity.ok(authService.exchangeOidcToken(request));
    }

    /** Register a new LOCAL user and return an app JWT. */
    @PostMapping("/register")
    public ResponseEntity<RegisterResponse> register(@Valid @RequestBody RegisterRequest request) {
        return ResponseEntity.ok(authService.register(request));
    }

    /** Validate LOCAL credentials and return an app JWT (or mfaRequired=true). */
    @PostMapping("/validate")
    public ResponseEntity<ValidateResponse> validate(@Valid @RequestBody ValidateRequest request) {
        return ResponseEntity.ok(authService.validateCredentials(request));
    }

    /** Validate a TOTP code during the MFA login step and return an app JWT. */
    @PostMapping("/mfa/validate")
    public ResponseEntity<MfaValidateResponse> mfaValidate(
            @Valid @RequestBody MfaValidateRequest request) {
        return ResponseEntity.ok(authService.validateMfa(request));
    }

    /** Issue a fresh app JWT for a known user (called by BFF near expiry). */
    @PostMapping("/refresh")
    public ResponseEntity<RefreshResponse> refresh(@Valid @RequestBody RefreshRequest request) {
        return ResponseEntity.ok(authService.refreshToken(request));
    }

    /**
     * Initiate a password reset.
     * Always returns 204 — even if the email is not found — to prevent email enumeration.
     * See PASSWORD_POLICY.md §4.
     */
    @PostMapping("/forgot-password")
    public ResponseEntity<Void> forgotPassword(
            @Valid @RequestBody ForgotPasswordRequest request) {
        passwordResetService.initiateReset(request.email(), UUID.fromString(request.tenantId()));
        return ResponseEntity.noContent().build();
    }

    /**
     * Reset the password using a valid token.
     * Returns 400 if the token is invalid or expired.
     */
    @PostMapping("/reset-password")
    public ResponseEntity<Void> resetPassword(
            @Valid @RequestBody ResetPasswordRequest request) {
        passwordResetService.resetPassword(request.token(), request.newPassword());
        return ResponseEntity.noContent().build();
    }
}
