package com.tm.bff.auth.dto;

import jakarta.validation.constraints.NotBlank;

/** Request body for POST /auth/mfa/verify. */
public record MfaVerifyRequest(
        @NotBlank String code
) {}
