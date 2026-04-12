package com.tm.bff.client.dto;

import java.util.UUID;

/** Mirrors Core API MfaValidateResponse (POST /internal/auth/mfa/validate). */
public record MfaValidateResponse(
        String token,
        UUID tenantId,
        String role,
        boolean passwordWarning
) {}
