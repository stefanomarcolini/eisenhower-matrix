package com.tm.core.application;

import com.tm.core.domain.ConflictException;
import com.tm.core.domain.Tenant;
import com.tm.core.domain.User;
import com.tm.core.infrastructure.RoleRepository;
import com.tm.core.infrastructure.TaskRepository;
import com.tm.core.infrastructure.TenantRepository;
import com.tm.core.infrastructure.UserRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;
import org.hibernate.Session;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Admin operations: tenant management, cross-tenant user listing and role changes,
 * and aggregate statistics.
 *
 * All methods disable the Hibernate "tenantFilter" so they operate across all tenants.
 * Each method must be guarded by @PreAuthorize("hasRole('ADMIN')") in the delegate
 * (CODING_PATTERNS.md §14).
 *
 * See API_CONTRACT.md §Admin endpoints.
 */
@Service
@RequiredArgsConstructor
public class AdminService {

    @PersistenceContext
    private EntityManager entityManager;

    private final TenantRepository tenantRepository;
    private final UserRepository userRepository;
    private final TaskRepository taskRepository;
    private final RoleRepository roleRepository;

    // -------------------------------------------------------------------------
    // Tenant management
    // -------------------------------------------------------------------------

    @Transactional
    public Tenant createTenant(String name) {
        if (tenantRepository.existsByName(name)) {
            throw new ConflictException("Tenant name already in use: " + name);
        }
        Tenant tenant = new Tenant();
        tenant.setName(name);
        return tenantRepository.save(tenant);
    }

    // -------------------------------------------------------------------------
    // Cross-tenant statistics
    // -------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public long countTenants() {
        return tenantRepository.count();
    }

    @Transactional(readOnly = true)
    public long countUsers() {
        disableTenantFilter();
        return userRepository.count();
    }

    @Transactional(readOnly = true)
    public long countActiveTasks() {
        disableTenantFilter();
        // @SQLRestriction("deleted_at IS NULL") on Task applies automatically.
        return taskRepository.count();
    }

    /**
     * Returns a map of state name → count for all active (non-deleted) tasks
     * across all tenants.
     */
    @Transactional(readOnly = true)
    public Map<String, Integer> taskCountsByState() {
        disableTenantFilter();
        // @SQLRestriction("deleted_at IS NULL") ensures only active tasks are counted.
        List<Object[]> rows = taskRepository.countGroupedByState();
        Map<String, Integer> result = new HashMap<>();
        for (Object[] row : rows) {
            result.put(row[0].toString(), ((Number) row[1]).intValue());
        }
        return result;
    }

    // -------------------------------------------------------------------------
    // Cross-tenant user listing
    // -------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public Page<User> listUsers(Pageable pageable) {
        disableTenantFilter();
        return userRepository.findAll(pageable);
    }

    // -------------------------------------------------------------------------
    // Role management
    // -------------------------------------------------------------------------

    @Transactional
    public User updateUserRole(UUID userId, String roleName) {
        disableTenantFilter();
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));
        com.tm.core.domain.Role role = roleRepository.findByName(roleName)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Unknown role: " + roleName));
        user.setRole(role);
        return userRepository.save(user);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private void disableTenantFilter() {
        entityManager.unwrap(Session.class).disableFilter("tenantFilter");
    }
}
