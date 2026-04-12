package com.tm.bff.auth;

import jakarta.servlet.http.HttpSession;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;

import java.util.function.Supplier;

/**
 * Guards /auth/mfa/verify — only accessible when the session contains MFA_PENDING=true.
 * This prevents unauthenticated users from reaching the MFA endpoint directly and ensures
 * the user completed primary auth before being allowed to submit a TOTP code.
 * See CODING_PATTERNS.md §3.
 */
public class MfaPendingAuthorizationManager
        implements AuthorizationManager<RequestAuthorizationContext> {

    @Override
    public AuthorizationDecision authorize(Supplier<? extends Authentication> authentication,
                                           RequestAuthorizationContext context) {
        HttpSession session = context.getRequest().getSession(false);
        boolean pending = session != null
                && Boolean.TRUE.equals(session.getAttribute(SessionKeys.MFA_PENDING));
        return new AuthorizationDecision(pending);
    }
}
