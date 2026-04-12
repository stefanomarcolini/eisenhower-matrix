package com.tm.core.infrastructure;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.hibernate.Session;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.io.IOException;

/**
 * Activates the Hibernate tenant filter for every /api/** request.
 * Reads X-Tenant-ID from the request header and stores it in TenantContext.
 * Registered only for /api/** — /internal/** endpoints handle tenancy via JWT claims.
 * See CODING_PATTERNS.md §4.
 */
@Component
public class TenantInterceptor implements HandlerInterceptor {

    // @PersistenceContext proxy is request-scoped — safe to inject into a singleton interceptor.
    @PersistenceContext
    private EntityManager entityManager;

    @Override
    public boolean preHandle(HttpServletRequest req, HttpServletResponse res, Object handler) {
        String tenantId = req.getHeader("X-Tenant-ID");
        if (tenantId == null || tenantId.isBlank()) {
            writeProblem(res, "X-Tenant-ID header is required");
            return false;
        }
        entityManager.unwrap(Session.class)
                .enableFilter("tenantFilter")
                .setParameter("tenantId", tenantId);
        TenantContext.set(tenantId);
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest req, HttpServletResponse res,
                                Object handler, Exception ex) {
        TenantContext.clear();   // always remove ThreadLocal to prevent thread-pool leaks
    }

    /** RFC 7807 problem+json response — GlobalExceptionHandler can't intercept interceptor rejections. */
    private void writeProblem(HttpServletResponse res, String detail) {
        res.setStatus(HttpServletResponse.SC_BAD_REQUEST);
        res.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        try {
            res.getWriter().write(
                    "{\"type\":\"about:blank\",\"title\":\"Bad Request\",\"status\":400,\"detail\":\""
                    + detail + "\"}");
        } catch (IOException ignored) {
            // Client disconnected before the error could be written — nothing to do.
        }
    }
}