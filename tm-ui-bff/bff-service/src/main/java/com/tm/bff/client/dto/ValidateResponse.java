package com.tm.bff.client.dto;

import java.util.UUID;

/** Mirrors Core API ValidateResponse (POST /internal/auth/validate). */
public record ValidateResponse(
        String token,
        UUID userId,
        UUID tenantId,
        String role,
        boolean mfaRequired,
        boolean passwordWarning
) {}
