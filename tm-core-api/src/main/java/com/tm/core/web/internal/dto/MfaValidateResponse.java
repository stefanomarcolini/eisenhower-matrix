package com.tm.core.web.internal.dto;

import java.util.UUID;

/** Response for POST /internal/auth/mfa/validate. */
public record MfaValidateResponse(
        String token,
        UUID tenantId,
        String role,
        boolean passwordWarning
) {}
