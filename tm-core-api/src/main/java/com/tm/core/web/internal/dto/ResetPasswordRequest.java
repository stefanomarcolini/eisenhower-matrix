package com.tm.core.web.internal.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * Request body for POST /internal/auth/reset-password.
 * See PASSWORD_POLICY.md §4 and API_CONTRACT.md §Internal API.
 */
public record ResetPasswordRequest(
        @NotBlank String token,
        @NotBlank String newPassword
) {}
