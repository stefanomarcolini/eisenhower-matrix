package com.tm.bff.auth;

import tools.jackson.databind.ObjectMapper;
import com.tm.bff.auth.dto.LocalLoginRequest;
import com.tm.bff.client.CoreApiClient;
import com.tm.bff.client.dto.ValidateResponse;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * Intercepts POST /auth/local/login, delegates to Core API, and writes the session.
 *
 * Runs before UsernamePasswordAuthenticationFilter in the Spring Security chain.
 * Not registered as a servlet filter by Spring Boot (FilterRegistrationBean disabled
 * in WebMvcConfig) — only active inside the Spring Security chain.
 *
 * On success:   sets APP_JWT + TENANT_ID in session → 200 {mfaPending:false}
 * On MFA:       sets MFA_PENDING + PENDING_USER_ID  → 200 {mfaPending:true}
 * On bad creds: Core API returns 401                → 401 forwarded to client
 *
 * See CODING_PATTERNS.md §2 and §3.
 */
@Component
@RequiredArgsConstructor
public class LocalLoginFilter extends OncePerRequestFilter {

    private static final String LOGIN_PATH = "/auth/local/login";

    private final CoreApiClient        coreApiClient;
    private final ObjectMapper         objectMapper;
    private final RateLimitInterceptor rateLimitInterceptor;

    // Not injected — initialised directly so no Spring bean registration is needed.
    // HttpSessionSecurityContextRepository saves the SecurityContext to the Redis-backed
    // HTTP session (via Spring Session), making it available on all subsequent requests.
    private final SecurityContextRepository securityContextRepository =
            new HttpSessionSecurityContextRepository();

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !LOGIN_PATH.equals(request.getRequestURI())
                || !"POST".equalsIgnoreCase(request.getMethod());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res,
                                    FilterChain chain) throws ServletException, IOException {
        if (!rateLimitInterceptor.tryConsumeLogin(req.getRemoteAddr())) {
            res.setStatus(429);
            res.setHeader("Retry-After", "60");
            res.setContentType(MediaType.APPLICATION_JSON_VALUE);
            res.getWriter().write("{\"status\":429,\"title\":\"Too Many Requests\"}");
            return;
        }

        LocalLoginRequest loginReq;
        try {
            loginReq = objectMapper.readValue(req.getInputStream(), LocalLoginRequest.class);
        } catch (IOException e) {
            sendError(res, HttpStatus.BAD_REQUEST, "Malformed request body");
            return;
        }

        if (loginReq.email() == null || loginReq.password() == null || loginReq.tenantId() == null) {
            sendError(res, HttpStatus.BAD_REQUEST, "email, password, and tenantId are required");
            return;
        }

        ValidateResponse coreResp;
        try {
            coreResp = coreApiClient.validateCredentials(loginReq);
        } catch (CoreApiClient.CoreApiException e) {
            res.setStatus(e.status());
            res.setContentType(MediaType.APPLICATION_JSON_VALUE);
            res.getWriter().write(e.body());
            return;
        }

        if (coreResp.mfaRequired()) {
            // Partial session — store pending state; do NOT store the JWT yet
            req.getSession(true).setAttribute(SessionKeys.MFA_PENDING, true);
            req.getSession().setAttribute(SessionKeys.PENDING_USER_ID,
                    coreResp.userId().toString());
            writeJson(res, HttpStatus.OK, Map.of("mfaPending", true));
        } else {
            // Full session
            var session = req.getSession(true);
            session.setAttribute(SessionKeys.APP_JWT, coreResp.token());
            session.setAttribute(SessionKeys.TENANT_ID, coreResp.tenantId().toString());
            session.setAttribute(SessionKeys.PASSWORD_WARNING, coreResp.passwordWarning());

            // Establish Spring Security authentication so that .anyRequest().authenticated()
            // permits subsequent requests (e.g. proxy calls to /api/**).
            // The context is saved to the Redis-backed session so it is loaded on every
            // following request by SecurityContextHolderFilter.
            SecurityContext context = SecurityContextHolder.createEmptyContext();
            context.setAuthentication(new UsernamePasswordAuthenticationToken(
                    coreResp.userId().toString(),
                    null,
                    List.of(new SimpleGrantedAuthority("ROLE_" + coreResp.role()))));
            SecurityContextHolder.setContext(context);
            securityContextRepository.saveContext(context, req, res);

            writeJson(res, HttpStatus.OK, Map.of(
                    "mfaPending", false,
                    "passwordWarning", coreResp.passwordWarning()));
        }
    }

    private void sendError(HttpServletResponse res, HttpStatus status, String message)
            throws IOException {
        writeJson(res, status, Map.of("status", status.value(), "title", message));
    }

    private void writeJson(HttpServletResponse res, HttpStatus status, Object body)
            throws IOException {
        res.setStatus(status.value());
        res.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(res.getWriter(), body);
    }
}
