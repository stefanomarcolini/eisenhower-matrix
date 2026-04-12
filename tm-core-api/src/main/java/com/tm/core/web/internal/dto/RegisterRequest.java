package com.tm.core.web.internal.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

/**
 * Request body for POST /internal/auth/register.
 * See AUTH_CONFIG.md §6 and API_CONTRACT.md §Internal API.
 */
public record RegisterRequest(
        @NotBlank @Email String email,
        @NotBlank String password,
        @NotBlank String tenantId
) {}
