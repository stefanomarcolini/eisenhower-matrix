package com.tm.bff.proxy;

import com.tm.bff.auth.SessionKeys;
import com.tm.bff.client.CoreApiClient;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Base64;
import java.util.Map;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * Transparently refreshes the app JWT when it is within the refresh window.
 * Called by ProxyController before every proxied request (CODING_PATTERNS.md §5).
 *
 * The BFF trusts its own session store (the JWT was placed there after Core API validation),
 * so JWT claims are parsed without re-verifying the signature.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class JwtRefreshService {

    private static final int REFRESH_THRESHOLD_SECONDS = 120;

    private final CoreApiClient coreApiClient;
    private final ObjectMapper  objectMapper;

    /**
     * Returns a valid JWT for the given session — refreshing it transparently if needed.
     *
     * @throws IllegalStateException if the session has no JWT (caller should never reach
     *                               the proxy without a valid session — security config blocks it)
     */
    public String getValidJwt(HttpSession session) {
        String jwt = (String) session.getAttribute(SessionKeys.APP_JWT);
        if (jwt == null) {
            throw new IllegalStateException("No JWT in session — authentication required");
        }

        if (isNearExpiry(jwt)) {
            String userId = extractSubject(jwt);
            log.debug("JWT near expiry for userId={}, refreshing", userId);
            String refreshed = coreApiClient.refreshJwt(userId);
            session.setAttribute(SessionKeys.APP_JWT, refreshed);
            return refreshed;
        }

        return jwt;
    }

    // -------------------------------------------------------------------------
    // Helpers — parse JWT payload without signature verification
    // -------------------------------------------------------------------------

    private boolean isNearExpiry(String jwt) {
        try {
            Map<String, Object> claims = parseClaims(jwt);
            Number exp = (Number) claims.get("exp");
            if (exp == null) return false;
            Instant expiry = Instant.ofEpochSecond(exp.longValue());
            return Instant.now().isAfter(expiry.minusSeconds(REFRESH_THRESHOLD_SECONDS));
        } catch (Exception e) {
            log.warn("Failed to parse JWT expiry, treating as not near expiry: {}", e.getMessage());
            return false;
        }
    }

    private String extractSubject(String jwt) {
        try {
            Map<String, Object> claims = parseClaims(jwt);
            return (String) claims.get("sub");
        } catch (Exception e) {
            throw new IllegalStateException("Failed to extract subject from JWT", e);
        }
    }

    private Map<String, Object> parseClaims(String jwt) throws Exception {
        String[] parts = jwt.split("\\.");
        byte[] payload = Base64.getUrlDecoder().decode(parts[1]);
        return objectMapper.readValue(payload, new TypeReference<>() {});
    }
}
