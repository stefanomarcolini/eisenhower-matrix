package com.tm.bff.auth;

import com.tm.bff.client.CoreApiClient;
import com.tm.bff.client.dto.OidcTokenResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.List;

/**
 * Handles successful OAuth2/OIDC login (Google, Microsoft).
 * Exchanges the OIDC claims for an app-issued JWT via Core API /internal/auth/token.
 * If MFA is required, creates a partial session; otherwise creates a full session.
 * See CODING_PATTERNS.md §3 and AUTH_CONFIG.md §5.
 */
@Component
@RequiredArgsConstructor
public class CustomOAuth2SuccessHandler implements AuthenticationSuccessHandler {

    private final CoreApiClient coreApiClient;
    private final SecurityContextRepository securityContextRepository =
            new HttpSessionSecurityContextRepository();

    @Value("${app.default-tenant-id}")
    private String defaultTenantId;

    @Override
    public void onAuthenticationSuccess(HttpServletRequest request,
                                        HttpServletResponse response,
                                        Authentication authentication) throws IOException {
        OidcUser oidcUser = (OidcUser) authentication.getPrincipal();

        // Resolve tenantId: prefer session-stored value (set before OAuth2 redirect),
        // fall back to the default tenant (single-tenant deployments).
        var session = request.getSession(false);
        String tenantId = session != null
                ? (String) session.getAttribute("OAUTH2_TENANT_ID")
                : null;
        if (tenantId == null) {
            tenantId = defaultTenantId;
        }

        OidcTokenResponse coreResp;
        try {
            coreResp = coreApiClient.exchangeOidcToken(oidcUser, tenantId);
        } catch (CoreApiClient.CoreApiException | IllegalArgumentException e) {
            // OAuth2 flow failed — redirect to login with error
            response.sendRedirect("/login?error=oauth2");
            return;
        }

        if (coreResp.mfaRequired()) {
            var s = request.getSession(true);
            s.setAttribute(SessionKeys.MFA_PENDING, true);
            s.setAttribute(SessionKeys.PENDING_USER_ID, coreResp.userId().toString());
            response.sendRedirect("/mfa/verify");
        } else {
            var s = request.getSession(true);
            s.setAttribute(SessionKeys.APP_JWT, coreResp.token());
            s.setAttribute(SessionKeys.TENANT_ID, coreResp.tenantId().toString());
            s.removeAttribute("OAUTH2_TENANT_ID");

            SecurityContext context = SecurityContextHolder.createEmptyContext();
            context.setAuthentication(new UsernamePasswordAuthenticationToken(
                    coreResp.userId().toString(),
                    null,
                    List.of(new SimpleGrantedAuthority("ROLE_" + coreResp.role()))));
            SecurityContextHolder.setContext(context);
            securityContextRepository.saveContext(context, request, response);

            response.sendRedirect("/");
        }
    }
}
