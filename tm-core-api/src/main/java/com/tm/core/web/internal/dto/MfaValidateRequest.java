package com.tm.core.web.internal.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * Request body for POST /internal/auth/mfa/validate.
 * See AUTH_CONFIG.md §7 and API_CONTRACT.md §Internal API.
 */
public record MfaValidateRequest(
        @NotBlank String userId,
        @NotBlank String code
) {}
