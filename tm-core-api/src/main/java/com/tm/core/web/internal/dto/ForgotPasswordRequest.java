package com.tm.core.web.internal.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

/**
 * Request body for POST /internal/auth/forgot-password.
 * See PASSWORD_POLICY.md §4 and API_CONTRACT.md §Internal API.
 */
public record ForgotPasswordRequest(
        @NotBlank @Email String email,
        @NotBlank String tenantId
) {}
