package com.tm.bff.proxy;

import com.tm.bff.auth.SessionKeys;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;

import java.util.UUID;

/**
 * Reverse proxy: forwards all /api/** requests to Core API, injecting the session JWT
 * and tenant ID. The browser never holds the JWT — it lives in the Redis session only.
 *
 * Header allowlist (CODING_PATTERNS.md §5, API_SECURITY.md §Request Smuggling):
 *   FORWARDED:  Content-Type, Accept, X-Request-ID (from client if present, else generated)
 *   INJECTED:   Authorization (Bearer JWT from session), X-Tenant-ID (from session)
 *   BLOCKED:    Cookie, Host, Authorization (client-supplied — never forwarded)
 *
 * See CODING_PATTERNS.md §5 and MULTI_TENANCY.md §3.
 */
@Slf4j
@RestController
@RequestMapping("/api")
public class ProxyController {

    private final RestClient        restClient;
    private final JwtRefreshService jwtRefreshService;

    public ProxyController(@Value("${app.core-api-base-url}") String coreApiBaseUrl,
                           RestClient.Builder restClientBuilder,
                           JwtRefreshService jwtRefreshService) {
        // Build once — RestClient is thread-safe
        this.restClient        = restClientBuilder.baseUrl(coreApiBaseUrl).build();
        this.jwtRefreshService = jwtRefreshService;
    }

    @RequestMapping(value = "/**", consumes = MediaType.ALL_VALUE)
    public ResponseEntity<byte[]> proxy(HttpServletRequest request,
                                         HttpSession session,
                                         @RequestBody(required = false) byte[] body) {
        String jwt      = jwtRefreshService.getValidJwt(session);
        String tenantId = (String) session.getAttribute(SessionKeys.TENANT_ID);
        String requestId = getOrGenerateRequestId(request);

        String contentType = request.getContentType() != null
                ? request.getContentType()
                : MediaType.APPLICATION_JSON_VALUE;

        return restClient
                .method(HttpMethod.valueOf(request.getMethod()))
                .uri(extractPath(request))
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + jwt)
                .header("X-Tenant-ID", tenantId)
                .header("X-Request-ID", requestId)
                .header(HttpHeaders.ACCEPT,
                        request.getHeader(HttpHeaders.ACCEPT) != null
                                ? request.getHeader(HttpHeaders.ACCEPT)
                                : MediaType.APPLICATION_JSON_VALUE)
                .contentType(MediaType.parseMediaType(contentType))
                .body(body != null ? body : new byte[0])
                .retrieve()
                // Pass upstream 4xx/5xx through unchanged — the frontend needs the real status codes
                // (404 for not-found, 409 for optimistic-lock conflict, 422 for illegal transition, etc.)
                // Without this, RestClient's default handler throws and the BFF returns 500 for all errors.
                .onStatus(HttpStatusCode::isError, (req, resp) -> { /* suppress throw; let toEntity() carry status + body */ })
                .toEntity(byte[].class);
    }

    /**
     * Includes query string so pagination, filter, and sort parameters reach Core API.
     */
    private String extractPath(HttpServletRequest request) {
        String qs = request.getQueryString();
        return qs != null
                ? request.getRequestURI() + "?" + qs
                : request.getRequestURI();
    }

    private String getOrGenerateRequestId(HttpServletRequest request) {
        String existing = request.getHeader("X-Request-ID");
        return (existing != null && !existing.isBlank())
                ? existing
                : UUID.randomUUID().toString();
    }
}
