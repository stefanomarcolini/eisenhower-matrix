package com.tm.bff.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

/** Request body for POST /auth/forgot-password. */
public record ForgotPasswordRequest(
        @NotBlank @Email String email,
        @NotBlank String tenantId
) {}
