package com.tm.bff.auth;

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;

/**
 * Rate limiting for auth endpoints via bucket4j-core (CODING_PATTERNS.md §6, AUTH_CONFIG.md §13).
 *
 * Limits applied:
 *   POST /auth/local/login        — 5 req / min per IP
 *   POST /auth/forgot-password    — 3 req / 15 min per IP
 *   All /auth/**                  — 20 req / min per IP (catch-all)
 *
 * Buckets are in-memory (ConcurrentHashMap). Sufficient for single-instance dev deployment.
 * For multi-pod production use, replace with a Redis-backed ProxyManager from bucket4j-redis.
 *
 * Registered for /auth/** only via WebMvcConfig.
 */
@Component
public class RateLimitInterceptor implements HandlerInterceptor {

    private final Map<String, Bucket> loginBuckets       = new ConcurrentHashMap<>();
    private final Map<String, Bucket> forgotPassBuckets  = new ConcurrentHashMap<>();
    private final Map<String, Bucket> authGlobalBuckets  = new ConcurrentHashMap<>();

    @Override
    public boolean preHandle(HttpServletRequest req, HttpServletResponse res, Object handler)
            throws IOException {
        String ip   = req.getRemoteAddr();
        String path = req.getRequestURI();

        // Endpoint-specific limits checked first; global limit applied to all /auth/** requests.
        // Note: /auth/local/login is NOT in this switch — login is handled by LocalLoginFilter
        // in the security filter chain (which never reaches HandlerInterceptor). Rate-limiting
        // for login is applied via tryConsumeLogin() called directly from LocalLoginFilter.
        boolean allowed = switch (path) {
            case "/auth/forgot-password" ->
                tryConsume(forgotPassBuckets, ip, 3, Duration.ofMinutes(15));
            default -> true;
        };

        if (!allowed) {
            sendRateLimitResponse(res, "60");
            return false;
        }

        // Global /auth/** limit — 20 req / min per IP
        if (!tryConsume(authGlobalBuckets, ip, 20, Duration.ofMinutes(1))) {
            sendRateLimitResponse(res, "60");
            return false;
        }

        return true;
    }

    /**
     * Rate-limits POST /auth/local/login by IP.
     * Called directly from {@link LocalLoginFilter} before processing the login request,
     * because login is handled in the security filter chain and never reaches a HandlerInterceptor.
     *
     * @return {@code true} if the request is allowed; {@code false} if the limit is exceeded
     */
    public boolean tryConsumeLogin(String ip) {
        return tryConsume(loginBuckets, ip, 5, Duration.ofMinutes(1));
    }

    private boolean tryConsume(Map<String, Bucket> map, String key,
                                long capacity, Duration refillPeriod) {
        Bucket bucket = map.computeIfAbsent(key, k -> Bucket.builder()
                .addLimit(Bandwidth.simple(capacity, refillPeriod))
                .build());
        return bucket.tryConsume(1);
    }

    private void sendRateLimitResponse(HttpServletResponse res, String retryAfterSeconds)
            throws IOException {
        res.setStatus(429);
        res.setHeader("Retry-After", retryAfterSeconds);
        res.setContentType("application/json");
        res.getWriter().write("{\"status\":429,\"title\":\"Too Many Requests\"}");
    }
}
