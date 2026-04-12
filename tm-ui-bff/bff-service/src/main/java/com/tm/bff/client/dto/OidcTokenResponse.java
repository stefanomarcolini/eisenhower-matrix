package com.tm.bff.client.dto;

import java.util.UUID;

/** Mirrors Core API OidcTokenResponse (POST /internal/auth/token). */
public record OidcTokenResponse(
        String token,
        UUID userId,
        UUID tenantId,
        String role,
        boolean mfaRequired
) {}
