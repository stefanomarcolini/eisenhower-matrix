package io.taskmanager.e2e;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.net.CookieManager;
import java.net.HttpCookie;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Security-level E2E scenarios (Scenarios 5–6 from IMPLEMENTATION_ROADMAP.md Session 16).
 *
 * These tests exercise REST-level security properties rather than browser UI flows,
 * using {@link java.net.http.HttpClient} directly against the BFF URL.
 *
 * 5. BOLA (Broken Object Level Authorisation) — user B cannot read user A's task (→ 404)
 * 6. Role elevation — admin promotes a user to ADMIN; elevated user can call admin-only
 *    endpoints that previously returned 403 (→ 200)
 *
 * The BFF uses cookie-based Spring Session (TM_SESSION) and requires a
 * CSRF token (XSRF-TOKEN cookie → X-XSRF-TOKEN request header) on all
 * mutating requests. {@link ApiSession} encapsulates this automatically.
 */
class SecurityIT {

    private static final String BASE = System.getProperty("e2e.api.url", "http://localhost:8080");
    private static final String ADMIN_EMAIL = System.getProperty("e2e.admin.email", "admin@task-manager.local");
    private static final String ADMIN_PASS  = System.getProperty("e2e.admin.password", "Admin1234!");
    private static final String TENANT_ID   = "00000000-0000-0000-0000-000000000001";
    private static final String SUFFIX      = Long.toHexString(System.currentTimeMillis());

    private final ObjectMapper mapper = new ObjectMapper();

    // ── Scenario 5: BOLA ─────────────────────────────────────────────────────

    @Test
    void bolaPreventsCrossUserTaskAccess() throws Exception {
        String emailA = "bola-a-" + SUFFIX + "@test.io";
        String emailB = "bola-b-" + SUFFIX + "@test.io";
        String pass   = "Password1!";

        // Register user A
        ApiSession sessionA = ApiSession.create(BASE);
        int regA = sessionA.post("/auth/local/register",
                """
                {"email":"%s","password":"%s","tenantId":"%s"}
                """.formatted(emailA, pass, TENANT_ID));
        assertEquals(200, regA, "Register user A should succeed");

        // User A creates a task → capture the task ID
        int createStatus = sessionA.post("/api/v1/tasks",
                """
                {"title":"BOLA task","importance":"HIGH","urgency":"HIGH","status":"PLANNED"}
                """);
        assertEquals(201, createStatus, "Create task should return 201");

        String taskId = sessionA.lastResponseBody()
                .map(body -> {
                    try { return mapper.readTree(body).path("id").asText(); }
                    catch (Exception e) { throw new RuntimeException(e); }
                })
                .orElseThrow(() -> new AssertionError("No response body from create-task"));
        assertNotNull(taskId, "Task ID must be present");

        // Register user B (separate session = separate tenant)
        ApiSession sessionB = ApiSession.create(BASE);
        int regB = sessionB.post("/auth/local/register",
                """
                {"email":"%s","password":"%s","tenantId":"%s"}
                """.formatted(emailB, pass, TENANT_ID));
        assertEquals(200, regB, "Register user B should succeed");

        // User B attempts to read user A's task by ID → must receive 404
        int status = sessionB.get("/api/v1/tasks/" + taskId);
        assertEquals(404, status,
                "Cross-tenant task access must return 404 (BOLA prevention)");
    }

    // ── Scenario 6: Admin role elevation ─────────────────────────────────────

    @Test
    void adminRoleElevationGrantsAdminAccess() throws Exception {
        String userEmail = "promote-" + SUFFIX + "@test.io";
        String userPass  = "Password1!";

        // Register a regular user and get their ID from /auth/session
        ApiSession userSession = ApiSession.create(BASE);
        int regStatus = userSession.post("/auth/local/register",
                """
                {"email":"%s","password":"%s","tenantId":"%s"}
                """.formatted(userEmail, userPass, TENANT_ID));
        assertEquals(200, regStatus, "Register user should return 200");

        String sessionJson = userSession.getBody("/auth/session");
        String userId = mapper.readTree(sessionJson).path("userId").asText();
        assertNotNull(userId, "userId must be present in session response");

        // Verify regular user cannot call admin endpoints (403)
        int beforePromotion = userSession.get("/api/v1/admin/users");
        assertEquals(403, beforePromotion,
                "Regular user must be denied admin endpoints (403)");

        // Admin logs in and promotes the user
        ApiSession adminSession = ApiSession.create(BASE);
        int adminLoginStatus = adminSession.post("/auth/local/login",
                """
                {"email":"%s","password":"%s","tenantId":"%s"}
                """.formatted(ADMIN_EMAIL, ADMIN_PASS, TENANT_ID));
        assertEquals(200, adminLoginStatus,
                "Admin login should return 200. Body=" + adminSession.lastResponseBody().orElse("<empty>"));

        int promoteStatus = adminSession.patch(
                "/api/v1/admin/users/" + userId + "/role",
                """
                {"role":"ADMIN"}
                """);
        assertEquals(200, promoteStatus,
                "Admin promote-user call should return 200");

        // User logs out and back in so the new role is reflected in their session
        userSession.post("/logout", "");

        ApiSession promotedSession = ApiSession.create(BASE);
        int promotedLoginStatus = promotedSession.post("/auth/local/login",
                """
                {"email":"%s","password":"%s","tenantId":"%s"}
                """.formatted(userEmail, userPass, TENANT_ID));
        assertEquals(200, promotedLoginStatus, "Promoted user login should return 200");

        // Promoted user can now call admin endpoints (200)
        int afterPromotion = promotedSession.get("/api/v1/admin/users");
        assertEquals(200, afterPromotion,
                "Promoted ADMIN user must be allowed admin endpoints (200)");
    }

    // ── ApiSession — per-user HTTP client with cookie + CSRF handling ─────────

    /**
     * Wraps {@link HttpClient} to provide:
     * <ul>
     *   <li>automatic cookie storage (TM_SESSION)</li>
     *   <li>CSRF token management (XSRF-TOKEN cookie → X-XSRF-TOKEN header)</li>
     * </ul>
     */
    static final class ApiSession {

        private final String       base;
        private final HttpClient   http;
        private final CookieManager cookieManager;
        private String lastBody;

        private ApiSession(String base) {
            this.base          = base;
            this.cookieManager = new CookieManager();
            this.http          = HttpClient.newBuilder()
                    .cookieHandler(cookieManager)
                    .connectTimeout(Duration.ofSeconds(10))
                    .build();
        }

        private static final java.util.Set<String> CSRF_EXEMPT_POST_PATHS = java.util.Set.of(
                "/auth/local/login",
                "/auth/local/register",
                "/auth/forgot-password",
                "/auth/reset-password"
        );

        /** Creates a new session; CSRF is materialised lazily on the first protected write. */
        static ApiSession create(String base) {
            return new ApiSession(base);
        }

        int get(String path) throws Exception {
            HttpResponse<String> resp = http.send(
                    HttpRequest.newBuilder().GET()
                            .uri(URI.create(base + path))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            lastBody = resp.body();
            return resp.statusCode();
        }

        String getBody(String path) throws Exception {
            get(path);
            return lastBody;
        }

        int post(String path, String jsonBody) throws Exception {
            HttpRequest.Builder request = HttpRequest.newBuilder()
                    .POST(HttpRequest.BodyPublishers.ofString(jsonBody.strip()))
                    .uri(URI.create(base + path))
                    .header("Content-Type", "application/json");

            if (requiresCsrf(path)) {
                request.header("X-XSRF-TOKEN", ensureCsrfToken());
            } else {
                currentCsrfToken().ifPresent(token -> request.header("X-XSRF-TOKEN", token));
            }

            HttpResponse<String> resp = http.send(
                    request.build(),
                    HttpResponse.BodyHandlers.ofString());
            lastBody = resp.body();
            return resp.statusCode();
        }

        int patch(String path, String jsonBody) throws Exception {
            HttpRequest.Builder request = HttpRequest.newBuilder()
                    .method("PATCH", HttpRequest.BodyPublishers.ofString(jsonBody.strip()))
                    .uri(URI.create(base + path))
                    .header("Content-Type", "application/json")
                    .header("X-XSRF-TOKEN", ensureCsrfToken());

            HttpResponse<String> resp = http.send(
                    request.build(),
                    HttpResponse.BodyHandlers.ofString());
            lastBody = resp.body();
            return resp.statusCode();
        }

        java.util.Optional<String> lastResponseBody() {
            return java.util.Optional.ofNullable(lastBody);
        }

        private boolean requiresCsrf(String path) {
            return !CSRF_EXEMPT_POST_PATHS.contains(path);
        }

        /**
         * Reads the current XSRF-TOKEN from the cookie jar, materialising it via a safe GET when
         * the first protected write happens after a CSRF-exempt auth bootstrap call.
         */
        private String ensureCsrfToken() throws Exception {
            java.util.Optional<String> existing = currentCsrfToken();
            if (existing.isPresent()) {
                return existing.get();
            }

            // /auth/session is permitAll and, in the fixed BFF source, should materialise the
            // deferred CookieCsrfTokenRepository token without changing auth state.
            get("/auth/session");
            existing = currentCsrfToken();
            if (existing.isPresent()) {
                return existing.get();
            }

            // Fallback to /login so the failure message makes it obvious that the runtime image
            // still does not match the expected CSRF contract.
            get("/login");
            return currentCsrfToken().orElseThrow(() -> new AssertionError(
                    "XSRF-TOKEN cookie not found after GET /auth/session and GET /login — runtime auth contract mismatch"));
        }

        private java.util.Optional<String> currentCsrfToken() {
            return cookieManager.getCookieStore().getCookies().stream()
                    .filter(c -> "XSRF-TOKEN".equals(c.getName()))
                    .map(HttpCookie::getValue)
                    .findFirst();
        }
    }
}
