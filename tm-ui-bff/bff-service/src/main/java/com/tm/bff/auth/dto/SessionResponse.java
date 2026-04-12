package com.tm.bff.auth.dto;

/** Response for GET /auth/session — consumed by the React ProtectedRoute guard. */
public record SessionResponse(
        boolean isAuthenticated,
        String userId,
        String email,
        String tenantId,
        String role,
        boolean mfaPending,
        boolean passwordWarning
) {}
