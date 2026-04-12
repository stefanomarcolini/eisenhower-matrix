package com.tm.bff.config;

import com.tm.bff.auth.CustomOAuth2SuccessHandler;
import com.tm.bff.auth.LocalLoginFilter;
import com.tm.bff.auth.MfaPendingAuthorizationManager;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * BFF Security Filter Chain — the hardest single configuration file in the project.
 * Handles OAuth2 login, local auth filter, CSRF, MFA gating, session management,
 * and static file access. See CODING_PATTERNS.md §2.
 *
 * Key design decisions:
 * - CSRF: CookieCsrfTokenRepository.withHttpOnlyFalse() — React reads XSRF-TOKEN cookie,
 *   sends as X-XSRF-TOKEN. CsrfTokenRequestAttributeHandler (not Xor variant) for compatibility.
 * - Form login is disabled — local login is handled by LocalLoginFilter.
 * - /auth/mfa/verify is gated by MfaPendingAuthorizationManager (not full auth).
 * - Returns 401 JSON on unauthorized (not redirect) so the React SPA can handle it.
 */
@Configuration
@EnableWebSecurity
public class OAuth2SecurityConfig {

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http,
                                            LocalLoginFilter localLoginFilter,
                                            CustomOAuth2SuccessHandler oAuth2SuccessHandler)
            throws Exception {
        http
            // ── Authorization ──────────────────────────────────────────────
            .authorizeHttpRequests(auth -> auth
                // Public endpoints — auth flows and health checks
                .requestMatchers(
                    "/auth/local/register",
                    "/auth/local/login",
                    "/auth/forgot-password",
                    "/auth/reset-password",
                    "/auth/session",            // returns unauthenticated state for anonymous users
                    "/oauth2/**", "/login/**", "/logout",
                    "/actuator/health", "/actuator/info"
                ).permitAll()
                // Static files — must be accessible before login for the SPA to load
                .requestMatchers(
                    "/", "/index.html", "/favicon.ico",
                    "/assets/**",
                    "/*.js", "/*.css", "/*.map"
                ).permitAll()
                // SPA routes (served by SpaFallbackController) — unauthenticated users
                // can reach these paths but the React app will redirect them to /login
                .requestMatchers(
                    "/login", "/register", "/forgot-password", "/auth/reset-password"
                ).permitAll()
                // MFA verify — not fully authenticated, but must have MFA_PENDING in session
                .requestMatchers("/auth/mfa/verify")
                    .access(new MfaPendingAuthorizationManager())
                // Everything else requires a full authenticated session (APP_JWT present)
                .anyRequest().authenticated()
            )

            // ── OAuth2 login — redirect-based ──────────────────────────────
            .oauth2Login(oauth2 -> oauth2
                .successHandler(oAuth2SuccessHandler)
            )

            // ── Disable form login — local auth uses LocalLoginFilter ───────
            .formLogin(form -> form.disable())

            // ── CSRF ────────────────────────────────────────────────────────
            // CookieCsrfTokenRepository.withHttpOnlyFalse() → React can read XSRF-TOKEN cookie.
            // CsrfTokenRequestAttributeHandler (not Xor variant) for straightforward
            // cookie-based CSRF without double-submit complexity.
            //
            // Pre-login endpoints are excluded: Spring's CsrfLogoutHandler clears the
            // XSRF-TOKEN cookie on logout; the very next POST to /auth/local/login has no
            // token, causing an InvalidCsrfTokenException → 401 for anonymous users.
            // CSRF does not protect these endpoints anyway — they operate on unauthenticated
            // state, so there is no existing session for an attacker to forge requests against.
            .csrf(csrf -> csrf
                .csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
                .csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler())
                .ignoringRequestMatchers(
                    "/auth/local/login",
                    "/auth/local/register",
                    "/auth/forgot-password",
                    "/auth/reset-password"
                )
            )

            // ── Session ─────────────────────────────────────────────────────
            .sessionManagement(session -> session
                .sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED)
            )

            // ── Logout ──────────────────────────────────────────────────────
            // Returns 200 JSON instead of a 302 redirect — the React SPA handles
            // navigation itself (useLogout.onSettled calls navigate('/login')).
            // A redirect response would cause axios to follow the redirect cross-origin
            // in dev (Vite proxy on :5173 → BFF on :8080), creating cookie inconsistency.
            .logout(logout -> logout
                .logoutUrl("/logout")
                .invalidateHttpSession(true)
                .deleteCookies("TM_SESSION")
                .logoutSuccessHandler((request, response, authentication) -> {
                    response.setStatus(HttpServletResponse.SC_OK);
                    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                    response.getWriter().write("{\"success\":true}");
                })
            )

            // ── Exception handling ──────────────────────────────────────────
            // Return 401 JSON instead of redirect — the React SPA handles auth state
            .exceptionHandling(ex -> ex
                .authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED))
            )

            // ── Local login filter ───────────────────────────────────────────
            // Runs before UsernamePasswordAuthenticationFilter; handles POST /auth/local/login
            .addFilterBefore(localLoginFilter, UsernamePasswordAuthenticationFilter.class)
            // Force the deferred CSRF token to materialise on every response so the
            // XSRF-TOKEN cookie is always present after the first request.
            // Spring Security 6 uses lazy/deferred CSRF loading: the cookie is only
            // written when something calls csrfToken.getToken(). Without this filter,
            // GET /login never triggers cookie generation and SecurityIT's
            // ApiSession.create() cannot find the XSRF-TOKEN cookie.
            .addFilterAfter(new OncePerRequestFilter() {
                @Override
                protected void doFilterInternal(HttpServletRequest req,
                                                HttpServletResponse resp,
                                                FilterChain chain)
                        throws ServletException, IOException {
                    CsrfToken csrfToken = (CsrfToken) req.getAttribute(
                            CsrfToken.class.getName());
                    if (csrfToken != null) {
                        // Accessing the token forces the deferred value to be resolved
                        // and the XSRF-TOKEN Set-Cookie header to be written.
                        csrfToken.getToken();
                    }
                    chain.doFilter(req, resp);
                }
            }, CsrfFilter.class);

        return http.build();
    }
}
