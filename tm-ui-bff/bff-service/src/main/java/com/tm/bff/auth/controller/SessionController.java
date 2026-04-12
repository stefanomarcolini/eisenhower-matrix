package com.tm.bff.auth.controller;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import com.tm.bff.auth.SessionKeys;
import com.tm.bff.auth.dto.SessionResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Base64;
import java.util.Map;

/**
 * Returns the current session state to the React SPA.
 * Consumed by the ProtectedRoute guard (CODING_PATTERNS.md §10).
 * The JWT is never sent to the browser — only derived claims are returned.
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class SessionController {

    private final ObjectMapper objectMapper;

    @GetMapping("/auth/session")
    public ResponseEntity<SessionResponse> session(HttpServletRequest request) {
        // getSession(false) — never create a session for an unauthenticated probe.
        // Spring MVC's default HttpSession injection calls getSession() (without false),
        // which creates a new empty session and sets TM_SESSION on every unauthenticated
        // call, producing a ghost session in Redis right after logout.
        HttpSession session = request.getSession(false);

        if (session == null) {
            return ResponseEntity.ok(new SessionResponse(
                    false, null, null, null, null, false, false));
        }

        String jwt = (String) session.getAttribute(SessionKeys.APP_JWT);

        if (jwt == null) {
            // Check for MFA partial session
            boolean mfaPending = Boolean.TRUE.equals(session.getAttribute(SessionKeys.MFA_PENDING));
            return ResponseEntity.ok(new SessionResponse(
                    false, null, null, null, null, mfaPending, false));
        }

        // Extract JWT claims (Base64url-decoded payload — no signature verification needed;
        // the BFF stored this JWT itself after Core API validation)
        Map<String, Object> claims = parseClaims(jwt);
        if (claims == null) {
            // Malformed JWT in session — clear it
            session.invalidate();
            return ResponseEntity.ok(new SessionResponse(
                    false, null, null, null, null, false, false));
        }

        String tenantId        = (String) session.getAttribute(SessionKeys.TENANT_ID);
        boolean passwordWarning = Boolean.TRUE.equals(
                session.getAttribute(SessionKeys.PASSWORD_WARNING));

        return ResponseEntity.ok(new SessionResponse(
                true,
                (String) claims.get("sub"),
                (String) claims.get("email"),
                tenantId,
                (String) claims.get("role"),
                false,
                passwordWarning));
    }

    /**
     * Decodes the JWT payload (middle segment) without signature verification.
     * The BFF trusts its own session store — the JWT was placed there after Core API validated it.
     */
    private Map<String, Object> parseClaims(String jwt) {
        try {
            String[] parts = jwt.split("\\.");
            if (parts.length != 3) return null;
            byte[] payloadBytes = Base64.getUrlDecoder().decode(parts[1]);
            return objectMapper.readValue(payloadBytes, new TypeReference<>() {});
        } catch (Exception e) {
            log.warn("Failed to parse JWT claims from session: {}", e.getMessage());
            return null;
        }
    }
}
