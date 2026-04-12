package com.tm.core.web.internal.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

/**
 * Request body for POST /internal/auth/token.
 * Fields are OIDC claims forwarded from the BFF after a successful OAuth2 login.
 * tenantId is required so Core API knows which tenant to associate the user with.
 * See AUTH_CONFIG.md §5 and API_CONTRACT.md §Internal API.
 */
public record OidcTokenRequest(
        @NotBlank String iss,
        @NotBlank String sub,
        @NotBlank @Email String email,
        String name,
        @NotBlank String tenantId
) {}
