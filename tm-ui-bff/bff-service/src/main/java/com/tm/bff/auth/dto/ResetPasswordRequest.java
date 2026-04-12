package com.tm.bff.auth.dto;

import jakarta.validation.constraints.NotBlank;

/** Request body for POST /auth/reset-password. */
public record ResetPasswordRequest(
        @NotBlank String token,
        @NotBlank String newPassword
) {}
