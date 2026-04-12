package com.tm.core.web;

import com.tm.core.application.AdminService;
import com.tm.core.domain.Tenant;
import com.tm.core.domain.User;
import com.tm.core.web.api.AdminApiDelegate;
import com.tm.core.web.model.AdminStats;
import com.tm.core.web.model.AdminUserItem;
import com.tm.core.web.model.AuthProvider;
import com.tm.core.web.model.CreateTenantRequest;
import com.tm.core.web.model.PagedUserResponse;
import com.tm.core.web.model.Role;
import com.tm.core.web.model.UpdateRoleRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

/**
 * Implements AdminApiDelegate — thin adapter between generated REST controllers and AdminService.
 * All methods require ADMIN role (@PreAuthorize guard here — defence-in-depth).
 * Cross-tenant queries are handled inside AdminService (tenantFilter disabled there).
 * See CODING_PATTERNS.md §14 and API_CONTRACT.md §Admin endpoints.
 */
@Service
@RequiredArgsConstructor
public class AdminApiDelegateImpl implements AdminApiDelegate {

    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 100;

    private final AdminService adminService;

    // -------------------------------------------------------------------------
    // AdminApiDelegate implementation
    // -------------------------------------------------------------------------

    @Override
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<com.tm.core.web.model.Tenant> createTenant(CreateTenantRequest req) {
        Tenant tenant = adminService.createTenant(req.getName());
        return ResponseEntity.status(201).body(mapTenantToModel(tenant));
    }

    @Override
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<AdminStats> getAdminStats() {
        AdminStats stats = new AdminStats();
        stats.setTotalTenants((int) adminService.countTenants());
        stats.setTotalUsers((int) adminService.countUsers());
        stats.setTotalTasks((int) adminService.countActiveTasks());
        stats.setTasksByState(adminService.taskCountsByState());
        return ResponseEntity.ok(stats);
    }

    @Override
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<PagedUserResponse> listAdminUsers(String cursor, Integer limit) {
        int pageSize = resolveLimit(limit);
        int pageIndex = decodeCursor(cursor);

        Page<User> page = adminService.listUsers(PageRequest.of(pageIndex, pageSize));

        PagedUserResponse response = new PagedUserResponse();
        response.setData(page.getContent().stream().map(this::mapUserToModel).toList());
        response.setTotalCount((int) page.getTotalElements());
        response.setNextCursor(page.hasNext() ? encodeCursor(pageIndex + 1) : null);
        return ResponseEntity.ok(response);
    }

    @Override
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<AdminUserItem> updateUserRole(UUID id, UpdateRoleRequest req) {
        User user = adminService.updateUserRole(id, req.getRole().name());
        return ResponseEntity.ok(mapUserToModel(user));
    }

    /**
     * Export not implemented in v1 — requires Apache POI / iText dependencies.
     * Returns 501 Not Implemented.
     */
    @Override
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<org.springframework.core.io.Resource> exportTaskReport(
            String format, UUID tenantId) {
        return ResponseEntity.<org.springframework.core.io.Resource>status(HttpStatus.NOT_IMPLEMENTED).build();
    }

    // -------------------------------------------------------------------------
    // Mapping helpers
    // -------------------------------------------------------------------------

    private com.tm.core.web.model.Tenant mapTenantToModel(Tenant tenant) {
        com.tm.core.web.model.Tenant dto = new com.tm.core.web.model.Tenant();
        dto.setId(tenant.getId());
        dto.setName(tenant.getName());
        dto.setCreatedAt(tenant.getCreatedAt() != null
                ? OffsetDateTime.ofInstant(tenant.getCreatedAt(), ZoneOffset.UTC) : null);
        return dto;
    }

    private AdminUserItem mapUserToModel(User user) {
        AdminUserItem dto = new AdminUserItem();
        dto.setId(user.getId());
        dto.setEmail(user.getEmail());
        dto.setDisplayName(user.getDisplayName());
        dto.setRole(Role.valueOf(user.getRole().getName()));
        dto.setAuthProvider(AuthProvider.valueOf(user.getAuthProvider().name()));
        dto.setTenantId(user.getTenantId());
        dto.setCreatedAt(user.getCreatedAt() != null
                ? OffsetDateTime.ofInstant(user.getCreatedAt(), ZoneOffset.UTC) : null);
        return dto;
    }

    // -------------------------------------------------------------------------
    // Cursor pagination (Base64-encoded page index)
    // -------------------------------------------------------------------------

    private int decodeCursor(String cursor) {
        if (cursor == null) return 0;
        try {
            return Integer.parseInt(new String(java.util.Base64.getUrlDecoder().decode(cursor)));
        } catch (Exception e) {
            return 0;
        }
    }

    private String encodeCursor(int pageIndex) {
        return java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString(String.valueOf(pageIndex).getBytes());
    }

    private int resolveLimit(Integer limit) {
        if (limit == null) return DEFAULT_PAGE_SIZE;
        return Math.min(Math.max(limit, 1), MAX_PAGE_SIZE);
    }
}
