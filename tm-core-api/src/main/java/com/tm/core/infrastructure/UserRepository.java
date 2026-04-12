package com.tm.core.infrastructure;

import com.tm.core.domain.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

/**
 * Repository for User entities.
 *
 * Defence-in-depth (MULTI_TENANCY.md §6, CODING_PATTERNS.md §4):
 * - findByIdAndTenantId scopes lookups to the caller's tenant even when the
 *   Hibernate filter is active — making tenant isolation explicit at two levels.
 * - All login lookups are (tenantId, email) or (tenantId, externalUserId) to
 *   ensure cross-tenant email collisions are impossible within a query.
 */
public interface UserRepository extends JpaRepository<User, UUID> {

    // Admin and internal lookups — scoped to tenant for defence-in-depth
    Optional<User> findByIdAndTenantId(UUID id, UUID tenantId);

    // Login lookup — LOCAL and OAuth2 (idx_users_tenant_email)
    Optional<User> findByTenantIdAndEmail(UUID tenantId, String email);

    // OIDC login matching (idx_users_tenant_ext_id)
    Optional<User> findByTenantIdAndExternalUserId(UUID tenantId, String externalUserId);

    boolean existsByTenantIdAndEmail(UUID tenantId, String email);
}