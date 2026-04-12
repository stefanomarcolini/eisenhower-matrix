package com.tm.bff.client.dto;

import java.util.UUID;

/** Mirrors Core API RegisterResponse (POST /internal/auth/register). */
public record RegisterResponse(
        String token,
        UUID userId,
        UUID tenantId,
        String role
) {}
