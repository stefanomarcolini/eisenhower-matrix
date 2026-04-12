package com.tm.core.infrastructure;

/**
 * Request-scoped tenant context backed by a ThreadLocal.
 *
 * Lifecycle:
 *   1. TenantInterceptor.preHandle()   → TenantContext.set(tenantId)
 *   2. Request processing uses TenantContext.get() where needed
 *   3. TenantInterceptor.afterCompletion() → TenantContext.clear()
 *
 * clear() uses ThreadLocal.remove() — not set(null) — to properly release
 * the thread-local value and prevent memory leaks in thread-pool environments.
 *
 * See CODING_PATTERNS.md §4 and MULTI_TENANCY.md §3.
 */
public final class TenantContext {

    private static final ThreadLocal<String> CURRENT_TENANT = new ThreadLocal<>();

    private TenantContext() {
        // Utility class — not instantiable
    }

    public static void set(String tenantId) {
        CURRENT_TENANT.set(tenantId);
    }

    public static String get() {
        return CURRENT_TENANT.get();
    }

    /** Must be called in HandlerInterceptor.afterCompletion() for every request. */
    public static void clear() {
        CURRENT_TENANT.remove();
    }
}