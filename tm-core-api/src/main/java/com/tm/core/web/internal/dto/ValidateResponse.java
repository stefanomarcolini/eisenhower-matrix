package com.tm.core.web.internal.dto;

import java.util.UUID;

/**
 * Response for POST /internal/auth/validate.
 * token is null when mfaRequired=true.
 * passwordWarning=true when password age >= PASSWORD_AGE_WARNING_DAYS.
 */
public record ValidateResponse(
        String token,
        UUID userId,
        UUID tenantId,
        String role,
        boolean mfaRequired,
        boolean passwordWarning
) {}
