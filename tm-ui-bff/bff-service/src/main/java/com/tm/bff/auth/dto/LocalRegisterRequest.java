package com.tm.bff.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

/** Request body for POST /auth/local/register. */
public record LocalRegisterRequest(
        @NotBlank @Email String email,
        @NotBlank String password,
        @NotBlank String tenantId
) {}
