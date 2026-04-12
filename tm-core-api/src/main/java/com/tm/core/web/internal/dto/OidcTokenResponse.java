package com.tm.core.web.internal.dto;

import java.util.UUID;

/**
 * Response for POST /internal/auth/token.
 * token is null when mfaRequired=true (JWT not issued until TOTP is verified).
 */
public record OidcTokenResponse(
        String token,
        UUID userId,
        UUID tenantId,
        String role,
        boolean mfaRequired
) {}
