package com.tm.core.domain;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.tm.core.domain.enums.AuthProvider;
import com.tm.core.domain.enums.Theme;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import org.hibernate.annotations.Filter;

import java.time.Instant;
import java.util.UUID;

/**
 * Represents an authenticated user within a tenant.
 *
 * Tenant isolation: every query is automatically filtered by the Hibernate
 * "tenantFilter" (tenant_id = :tenantId). See MULTI_TENANCY.md §4.
 *
 * Sensitive fields (passwordHash, mfaSecret):
 * - @JsonProperty(WRITE_ONLY) prevents serialisation into any JSON response.
 * - @ToString.Exclude keeps them out of log output.
 * These are defence-in-depth measures — response DTOs should never include
 * these fields regardless (CODING_PATTERNS.md §14).
 *
 * Role is loaded EAGERLY because it is required for every authorisation
 * decision (JWT claim building, @PreAuthorize checks).
 */
@Entity
@Table(name = "users")
@Filter(name = "tenantFilter", condition = "tenant_id = :tenantId")
@Getter
@Setter
@NoArgsConstructor
@ToString
public class User extends AuditableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(updatable = false, nullable = false)
    private UUID id;

    @Column(nullable = false)
    private UUID tenantId;

    @Column(length = 255, nullable = false)
    private String email;

    @Column(length = 255)
    private String displayName;

    // Immutable after account creation. LOCAL | GOOGLE | MICROSOFT.
    @Enumerated(EnumType.STRING)
    @Column(length = 20, nullable = false)
    private AuthProvider authProvider;

    // sub claim from IdP. NULL for LOCAL users.
    @Column(length = 255)
    private String externalUserId;

    // BCrypt hash. NULL for OAuth2 users.
    @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
    @ToString.Exclude
    @Column(length = 255)
    private String passwordHash;

    // NULL for OAuth2 users. Set on registration and every password change.
    @Column
    private Instant passwordChangedAt;

    // Updated on every successful login.
    @Column
    private Instant lastLoginAt;

    // Loaded eagerly — required for every authorisation decision.
    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "role_id", nullable = false)
    private Role role;

    @Column(nullable = false)
    private boolean isMfaEnabled = false;

    // AES-256/GCM encrypted TOTP secret. NULL when MFA is disabled.
    // Encryption handled by MfaService (CODING_PATTERNS.md §19).
    @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
    @ToString.Exclude
    @Column(length = 255)
    private String mfaSecret;

    @Enumerated(EnumType.STRING)
    @Column(length = 10, nullable = false)
    private Theme theme = Theme.LIGHT;
}