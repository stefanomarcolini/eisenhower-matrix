package com.tm.core.web.internal.dto;

import java.util.UUID;

/** Response for POST /internal/auth/register. */
public record RegisterResponse(
        String token,
        UUID userId,
        UUID tenantId,
        String role
) {}
