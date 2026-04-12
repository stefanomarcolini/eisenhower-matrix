package com.tm.bff.auth.controller;

import com.tm.bff.auth.SessionKeys;
import com.tm.bff.auth.dto.MfaVerifyRequest;
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

import java.util.List;

/**
 * MFA TOTP verification during the login flow.
 * Only reachable when session contains MFA_PENDING=true (guarded by MfaPendingAuthorizationManager).
 * Implements lockout after 5 failed attempts (CODING_PATTERNS.md §17).
 */
@RestController
@RequiredArgsConstructor
public class MfaController {

    private static final int MAX_ATTEMPTS = 5;

    private final CoreApiClient coreApiClient;
    private final SecurityContextRepository securityContextRepository =
            new HttpSessionSecurityContextRepository();

    @PostMapping("/auth/mfa/verify")
    public ResponseEntity<Void> verifyMfa(@Valid @RequestBody MfaVerifyRequest req,
                                           HttpServletRequest request,
                                           HttpServletResponse response,
                                           HttpSession session) {
        Integer attempts = (Integer) session.getAttribute(SessionKeys.MFA_ATTEMPTS);
        if (attempts == null) {
            attempts = 0;
        }

        if (attempts >= MAX_ATTEMPTS) {
            // Force full re-authentication — the partial session is worthless
            session.invalidate();
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .header("X-MFA-Lockout", "true")
                    .build();
        }

        String userId = (String) session.getAttribute(SessionKeys.PENDING_USER_ID);

        try {
            var coreResp = coreApiClient.validateMfa(userId, req.code());

            // Success — promote to full session
            session.removeAttribute(SessionKeys.MFA_PENDING);
            session.removeAttribute(SessionKeys.PENDING_USER_ID);
            session.removeAttribute(SessionKeys.MFA_ATTEMPTS);
            session.setAttribute(SessionKeys.APP_JWT, coreResp.token());
            session.setAttribute(SessionKeys.TENANT_ID, coreResp.tenantId().toString());
            session.setAttribute(SessionKeys.PASSWORD_WARNING, coreResp.passwordWarning());

            SecurityContext context = SecurityContextHolder.createEmptyContext();
            context.setAuthentication(new UsernamePasswordAuthenticationToken(
                    userId,
                    null,
                    List.of(new SimpleGrantedAuthority("ROLE_" + coreResp.role()))));
            SecurityContextHolder.setContext(context);
            securityContextRepository.saveContext(context, request, response);

            return ResponseEntity.ok().build();
        } catch (CoreApiException e) {
            // Wrong TOTP code — increment attempt counter
            session.setAttribute(SessionKeys.MFA_ATTEMPTS, attempts + 1);
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
    }
}
