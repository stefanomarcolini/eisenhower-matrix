package com.tm.bff.client;

import com.tm.bff.auth.dto.ForgotPasswordRequest;
import com.tm.bff.auth.dto.LocalLoginRequest;
import com.tm.bff.auth.dto.LocalRegisterRequest;
import com.tm.bff.auth.dto.ResetPasswordRequest;
import com.tm.bff.client.dto.MfaValidateResponse;
import com.tm.bff.client.dto.OidcTokenResponse;
import com.tm.bff.client.dto.RegisterResponse;
import com.tm.bff.client.dto.ValidateResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.util.Map;
import java.util.Optional;

/**
 * Typed client for Core API internal endpoints.
 * Uses Spring Boot 3.2+ RestClient (not RestTemplate).
 * Wraps non-2xx responses in {@link CoreApiException} so callers can inspect
 * the status and body without depending on Spring's WebClientResponseException.
 *
 * All paths are under /internal/auth/** — these endpoints are only reachable
 * from the BFF (network isolation via Kubernetes NetworkPolicy / Docker bridge).
 */
@Slf4j
@Component
public class CoreApiClient {

    public static final String EMAIL = "email";
    public static final String PREFERRED_USERNAME = "preferred_username";
    public static final String NAME = "name";
    public static final String ISS = "iss";
    public static final String SUB = "sub";
    public static final String TENANT_ID = "tenantId";
    public static final String PASSWORD = "password";
    public static final String USER_ID = "userId";
    public static final String CODE = "code";
    public static final String TOKEN = "token";
    public static final String NEW_PASSWORD = "newPassword";
    private final RestClient restClient;

    public CoreApiClient(@Value("${app.core-api-base-url}") String baseUrl,
                         RestClient.Builder builder) {
        this.restClient = builder
                .baseUrl(baseUrl)
                .defaultStatusHandler(HttpStatusCode::isError, (req, resp) -> {
                    // Read body before the connection closes; callers re-throw as needed
                    byte[] body = resp.getBody().readAllBytes();
                    throw new CoreApiException(resp.getStatusCode().value(),
                            new String(body));
                })
                .build();
    }

    /** Exchange OIDC claims for an app-issued JWT. */
    public OidcTokenResponse exchangeOidcToken(OidcUser user, String tenantId) {
        String subject = user.getSubject();
        String email = resolveOidcEmail(user);
        String name = firstNonBlank(
                user.getFullName(),
                stringClaim(user, NAME),
                localPartOrNull(email),
                stringClaim(user, PREFERRED_USERNAME),
                subject
        );
        URI issuer = user.getIssuer() != null
                ? URI.create(user.getIssuer().toString())
                : parseIssuer(stringClaim(user, ISS));

        var payload = Map.of(
                ISS, issuer.toString(),
                SUB, subject,
                EMAIL, email,
                NAME, name,
                TENANT_ID, tenantId
        );
        return post("/internal/auth/token", payload, OidcTokenResponse.class);
    }

    /** Register a LOCAL user. */
    public RegisterResponse register(LocalRegisterRequest req) {
        return post("/internal/auth/register",
                Map.of(EMAIL, req.email(), PASSWORD, req.password(), TENANT_ID, req.tenantId()),
                RegisterResponse.class);
    }

    /** Validate LOCAL credentials. */
    public ValidateResponse validateCredentials(LocalLoginRequest req) {
        return post("/internal/auth/validate",
                Map.of(EMAIL, req.email(), PASSWORD, req.password(), TENANT_ID, req.tenantId()),
                ValidateResponse.class);
    }

    /** Validate a TOTP code during MFA login. */
    public MfaValidateResponse validateMfa(String userId, String code) {
        return post("/internal/auth/mfa/validate",
                Map.of(USER_ID, userId, CODE, code),
                MfaValidateResponse.class);
    }

    /** Refresh the JWT for a known user (called by JwtRefreshService near expiry). */
    public String refreshJwt(String userId) {
        record RefreshResponse(String token) {}
        RefreshResponse resp = post("/internal/auth/refresh", Map.of(USER_ID, userId), RefreshResponse.class);
        String token = resp.token();
        if (token == null || token.isBlank()) {
            throw new IllegalStateException("Core API returned a refresh response without a token");
        }
        return token;
    }

    /** Initiate a password reset (fire-and-forget; always 204). */
    public void forgotPassword(ForgotPasswordRequest req) {
        restClient.post()
                .uri("/internal/auth/forgot-password")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of(EMAIL, req.email(), TENANT_ID, req.tenantId()))
                .retrieve()
                .toBodilessEntity();
    }

    /** Reset the password with a valid token. */
    public void resetPassword(ResetPasswordRequest req) {
        restClient.post()
                .uri("/internal/auth/reset-password")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of(TOKEN, req.token(), NEW_PASSWORD, req.newPassword()))
                .retrieve()
                .toBodilessEntity();
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private <T> T post(String uri, Object body, Class<T> responseType) {
        return Optional.ofNullable(restClient.post()
                .uri(uri)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(responseType))
                .orElseThrow(() -> new IllegalStateException("Core API returned an empty response for " + uri));
    }

    private static String stringClaim(OidcUser user, String key) {
        Object value = user.getClaims().get(key);
        return value instanceof String stringValue && !stringValue.isBlank()
                ? stringValue
                : null;
    }

    private static String resolveOidcEmail(OidcUser user) {
        String directEmail = user.getEmail();
        if (isEmailLike(directEmail)) {
            return directEmail;
        }

        String emailClaim = stringClaim(user, EMAIL);
        if (isEmailLike(emailClaim)) {
            return emailClaim;
        }

        String preferredUsername = stringClaim(user, PREFERRED_USERNAME);
        if (isEmailLike(preferredUsername)) {
            return preferredUsername;
        }

        String subject = user.getSubject();
        if (isEmailLike(subject)) {
            return subject;
        }

        throw new IllegalArgumentException(
                "OIDC provider did not supply an email claim (expected email, preferred_username, or subject as email)");
    }

    private static boolean isEmailLike(String value) {
        return value != null && !value.isBlank() && value.contains("@");
    }

    private static URI parseIssuer(String issuer) {
        if (issuer == null || issuer.isBlank()) {
            throw new IllegalArgumentException("OIDC issuer claim is required");
        }
        return URI.create(issuer);
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private static String localPartOrNull(String email) {
        if (email == null || email.isBlank()) {
            return null;
        }
        return email.split("@", 2)[0];
    }

    // -------------------------------------------------------------------------
    // Exception type
    // -------------------------------------------------------------------------

    /**
     * Thrown when Core API returns a non-2xx response.
     * Callers decide whether to re-throw as a Spring ResponseStatusException
     * or handle the status/body directly.
     */
    public static class CoreApiException extends RuntimeException {
        private final int    statusCode;
        private final String responseBody;

        public CoreApiException(int statusCode, String responseBody) {
            super("Core API returned " + statusCode);
            this.statusCode   = statusCode;
            this.responseBody = responseBody;
        }

        public int    status() { return statusCode;   }
        public String body()   { return responseBody; }
    }
}
