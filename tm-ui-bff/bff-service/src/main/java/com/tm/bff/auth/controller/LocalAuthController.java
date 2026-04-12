package com.tm.bff.auth.controller;

import com.tm.bff.auth.SessionKeys;
import com.tm.bff.auth.dto.ForgotPasswordRequest;
import com.tm.bff.auth.dto.LocalRegisterRequest;
import com.tm.bff.auth.dto.ResetPasswordRequest;
import com.tm.bff.client.CoreApiClient;
import com.tm.bff.client.CoreApiClient.CoreApiException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

/**
 * Local authentication endpoints — register, forgot/reset password.
 * Login is handled by LocalLoginFilter (runs in the Spring Security chain before this).
 * See AUTH_CONFIG.md §6, §9, API_CONTRACT.md, CODING_PATTERNS.md §3.
 */
@RestController
@RequiredArgsConstructor
public class LocalAuthController {

    private final CoreApiClient coreApiClient;

    // Saves the Spring Security context to the Redis-backed session so that
    // subsequent requests (e.g. /api/** proxy calls) see a fully authenticated
    // principal — identical to what LocalLoginFilter does for /auth/local/login.
    private final SecurityContextRepository securityContextRepository =
            new HttpSessionSecurityContextRepository();

    /**
     * Register a new local user.
     * On success: stores JWT in session, establishes Spring Security auth context,
     * and returns the user details.
     */
    @PostMapping("/auth/local/register")
    public ResponseEntity<Map<String, Object>> register(@Valid @RequestBody LocalRegisterRequest req,
                                                         HttpServletRequest request,
                                                         HttpServletResponse response) {
        try {
            var coreResp = coreApiClient.register(req);

            var session = request.getSession(true);
            session.setAttribute(SessionKeys.APP_JWT, coreResp.token());
            session.setAttribute(SessionKeys.TENANT_ID, coreResp.tenantId().toString());

            // Establish Spring Security authentication so that .anyRequest().authenticated()
            // permits subsequent /api/** proxy calls without requiring a separate login step.
            SecurityContext context = SecurityContextHolder.createEmptyContext();
            context.setAuthentication(new UsernamePasswordAuthenticationToken(
                    coreResp.userId().toString(),
                    null,
                    List.of(new SimpleGrantedAuthority("ROLE_" + coreResp.role()))));
            SecurityContextHolder.setContext(context);
            securityContextRepository.saveContext(context, request, response);

            return ResponseEntity.ok(Map.of(
                    "userId",   coreResp.userId().toString(),
                    "tenantId", coreResp.tenantId().toString(),
                    "role",     coreResp.role()));
        } catch (CoreApiException e) {
            throw new ResponseStatusException(HttpStatus.valueOf(e.status()), e.body());
        }
    }

    /**
     * Initiate password reset.
     * Always returns 204 — even for unknown email — to prevent email enumeration.
     */
    @PostMapping("/auth/forgot-password")
    public ResponseEntity<Void> forgotPassword(@Valid @RequestBody ForgotPasswordRequest req) {
        try {
            coreApiClient.forgotPassword(req);
        } catch (CoreApiException e) {
            // Swallow all Core API errors — never reveal whether the email exists
        }
        return ResponseEntity.noContent().build();
    }

    /**
     * Reset password using the one-time token from the reset email.
     * Returns 400 if the token is invalid or expired.
     */
    @PostMapping("/auth/reset-password")
    public ResponseEntity<Void> resetPassword(@Valid @RequestBody ResetPasswordRequest req) {
        try {
            coreApiClient.resetPassword(req);
            return ResponseEntity.noContent().build();
        } catch (CoreApiException e) {
            throw new ResponseStatusException(HttpStatus.valueOf(e.status()), e.body());
        }
    }

    /** Invalidate the current session (local logout). Spring Security handles /logout. */
    @PostMapping("/auth/local/logout")
    public ResponseEntity<Void> logout(HttpSession session) {
        session.invalidate();
        return ResponseEntity.noContent().build();
    }
}
