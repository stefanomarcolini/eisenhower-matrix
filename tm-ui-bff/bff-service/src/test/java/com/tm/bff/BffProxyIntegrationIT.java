package com.tm.bff;

import tools.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import jakarta.servlet.Filter;
import jakarta.servlet.http.HttpSession;
import org.springframework.mock.web.MockHttpSession;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.containers.GenericContainer;
import org.wiremock.spring.EnableWireMock;
import org.wiremock.spring.InjectWireMock;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests for BFF proxy functionality (Session 9).
 *
 * Verifies:
 *   - Authorization header is injected from the session JWT (not from the client)
 *   - X-Tenant-ID is taken from the session (not from any client-supplied header)
 *   - A near-expiry JWT triggers a transparent refresh before the proxied call
 *   - Cookie header is NOT forwarded to Core API
 *
 * Uses PROXY_IP instead of 127.0.0.1 to keep the login rate-limit bucket separate
 * from BffAuthIntegrationTest, which exhausts the 127.0.0.1 bucket with 5 logins.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@EnableWireMock
class BffProxyIntegrationTest {

    static final GenericContainer<?> redis =
            new GenericContainer<>(System.getProperty("redis.test.image", "redis:7-alpine"))
                    .withExposedPorts(6379);

    static {
        redis.start();
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
        registry.add("spring.data.redis.password", () -> "");
    }

    MockMvc mockMvc;
    @Autowired WebApplicationContext webApplicationContext;
    @Autowired ObjectMapper objectMapper;

    @InjectWireMock
    WireMockServer wireMock;

    /** Dedicated IP — avoids sharing the login rate-limit bucket with other test classes. */
    private static final String PROXY_IP       = "10.0.1.1";
    /** Separate IP for error-pass-through tests — keeps bucket counts independent. */
    private static final String PROXY_ERROR_IP = "10.0.2.1";
    private static final String TENANT_ID = "00000000-0000-0000-0000-000000000001";
    private static final String USER_ID   = "00000000-0000-0000-0000-000000000099";

    // JWT with exp=9999999999 (year 2286) — will never trigger the refresh path
    private static final String TEST_JWT =
            "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9" +
            ".eyJzdWIiOiIwMDAwMDAwMC0wMDAwLTAwMDAtMDAwMC0wMDAwMDAwMDAwOTkiLCJlbWFpbCI6InRlc3RAZXhhbXBsZS5jb20iLCJyb2xlIjoiU1RBTkRBUkQiLCJ0ZW5hbnRJZCI6IjAwMDAwMDAwLTAwMDAtMDAwMC0wMDAwLTAwMDAwMDAwMDAwMSIsImV4cCI6OTk5OTk5OTk5OX0" +
            ".signature";

    @BeforeEach
    void resetWireMock() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
                .addFilters(webApplicationContext.getBeansOfType(Filter.class)
                        .values().toArray(new Filter[0]))
                .build();
        wireMock.resetAll();
    }

    // -------------------------------------------------------------------------
    // AUTHORIZATION HEADER INJECTION
    // -------------------------------------------------------------------------

    @Test
    void proxy_authenticated_injectsAuthorizationHeaderFromSession() throws Exception {
        stubValidate(TEST_JWT, false, false);
        wireMock.stubFor(WireMock.get(urlPathEqualTo("/api/v1/tasks"))
                .willReturn(okJson("{\"items\":[]}")));

        MockHttpSession session = login();

        mockMvc.perform(get("/api/v1/tasks").session(session))
                .andExpect(status().isOk());

        wireMock.verify(getRequestedFor(urlPathEqualTo("/api/v1/tasks"))
                .withHeader("Authorization", equalTo("Bearer " + TEST_JWT)));
    }

    // -------------------------------------------------------------------------
    // X-TENANT-ID FROM SESSION (NOT CLIENT)
    // -------------------------------------------------------------------------

    @Test
    void proxy_authenticated_injectsXTenantIdFromSessionIgnoringClientHeader() throws Exception {
        stubValidate(TEST_JWT, false, false);
        wireMock.stubFor(WireMock.get(urlPathEqualTo("/api/v1/tasks"))
                .willReturn(okJson("{\"items\":[]}")));

        MockHttpSession session = login();

        // Client sends a different X-Tenant-ID — the proxy must use the session value instead
        mockMvc.perform(get("/api/v1/tasks")
                        .session(session)
                        .header("X-Tenant-ID", "ffffffff-ffff-ffff-ffff-ffffffffffff"))
                .andExpect(status().isOk());

        wireMock.verify(getRequestedFor(urlPathEqualTo("/api/v1/tasks"))
                .withHeader("X-Tenant-ID", equalTo(TENANT_ID)));
    }

    // -------------------------------------------------------------------------
    // JWT TRANSPARENT REFRESH
    // -------------------------------------------------------------------------

    @Test
    void proxy_jwtNearExpiry_refreshesTransparentlyAndForwardsNewToken() throws Exception {
        // exp = now + 60s is within the 120-second refresh threshold → isNearExpiry() == true
        String nearExpiryJwt = buildJwt(USER_ID, TENANT_ID, Instant.now().plusSeconds(60).getEpochSecond());
        String freshJwt      = buildJwt(USER_ID, TENANT_ID, Instant.now().plusSeconds(3600).getEpochSecond());

        stubValidate(nearExpiryJwt, false, false);
        wireMock.stubFor(WireMock.post(urlEqualTo("/internal/auth/refresh"))
                .willReturn(okJson("{\"token\":\"" + freshJwt + "\"}")));
        wireMock.stubFor(WireMock.get(urlPathEqualTo("/api/v1/tasks"))
                .willReturn(okJson("{\"items\":[]}")));

        MockHttpSession session = login();

        mockMvc.perform(get("/api/v1/tasks").session(session))
                .andExpect(status().isOk());

        // Refresh endpoint was called
        wireMock.verify(postRequestedFor(urlEqualTo("/internal/auth/refresh")));
        // The fresh (refreshed) JWT was forwarded — not the original near-expiry one
        wireMock.verify(getRequestedFor(urlPathEqualTo("/api/v1/tasks"))
                .withHeader("Authorization", equalTo("Bearer " + freshJwt)));
    }

    // -------------------------------------------------------------------------
    // UPSTREAM ERROR STATUS PASS-THROUGH
    // -------------------------------------------------------------------------

    @Test
    void proxy_coreApiErrorStatus_isPassedThroughUnchanged() throws Exception {
        // Regression test for the bug where RestClient's default error handler threw
        // HttpClientErrorException, causing the BFF to return 500 for all Core API errors.
        // After the fix (onStatus pass-through), the real status code reaches the frontend.
        stubValidate(TEST_JWT, false, false);
        wireMock.stubFor(WireMock.get(urlPathEqualTo("/api/v1/tasks/does-not-exist"))
                .willReturn(aResponse()
                        .withStatus(404)
                        .withHeader("Content-Type", "application/problem+json")
                        .withBody("{\"status\":404,\"title\":\"Not Found\"}")));

        MockHttpSession session = login(PROXY_ERROR_IP);

        mockMvc.perform(get("/api/v1/tasks/does-not-exist").session(session))
                .andExpect(status().isNotFound());
    }

    @Test
    void proxy_coreApiConflict_isPassedThroughAs409() throws Exception {
        stubValidate(TEST_JWT, false, false);
        wireMock.stubFor(WireMock.post(urlEqualTo("/api/v1/tasks"))
                .willReturn(aResponse()
                        .withStatus(409)
                        .withHeader("Content-Type", "application/problem+json")
                        .withBody("{\"status\":409,\"title\":\"Conflict\"}")));

        MockHttpSession session = login(PROXY_ERROR_IP);

        mockMvc.perform(post("/api/v1/tasks")
                        .session(session)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Task\",\"importance\":\"HIGH\",\"urgency\":\"LOW\"}"))
                .andExpect(status().isConflict());
    }

    // -------------------------------------------------------------------------
    // COOKIE HEADER NOT FORWARDED
    // -------------------------------------------------------------------------

    @Test
    void proxy_cookieHeaderNotForwardedToCoreApi() throws Exception {
        stubValidate(TEST_JWT, false, false);
        wireMock.stubFor(WireMock.get(urlPathEqualTo("/api/v1/tasks"))
                .willReturn(okJson("{\"items\":[]}")));

        MockHttpSession session = login();

        mockMvc.perform(get("/api/v1/tasks").session(session))
                .andExpect(status().isOk());

        // Cookie header (carrying the BFF session token) must never reach Core API
        wireMock.verify(getRequestedFor(urlPathEqualTo("/api/v1/tasks"))
                .withoutHeader("Cookie"));
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /** Performs a local login from PROXY_IP and returns the TM_SESSION cookie. */
    private MockHttpSession login() throws Exception {
        return login(PROXY_IP);
    }

    private MockHttpSession login(String ip) throws Exception {
        MvcResult result = mockMvc.perform(post("/auth/local/login")
                        .with(request -> { request.setRemoteAddr(ip); return request; })
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "email", "test@example.com",
                                "password", "Str0ng!Pass#1",
                                "tenantId", TENANT_ID))))
                .andExpect(status().isOk())
                .andReturn();
        HttpSession session = result.getRequest().getSession(false);
        if (session instanceof MockHttpSession mockHttpSession) {
            return mockHttpSession;
        }
        throw new IllegalStateException("No request session found after login");
    }

    private void stubValidate(String token, boolean mfaRequired, boolean passwordWarning) {
        try {
            wireMock.stubFor(WireMock.post(urlEqualTo("/internal/auth/validate"))
                    .willReturn(okJson(objectMapper.writeValueAsString(Map.of(
                            "token", token,
                            "userId", USER_ID,
                            "tenantId", TENANT_ID,
                            "role", "STANDARD",
                            "mfaRequired", mfaRequired,
                            "passwordWarning", passwordWarning)))));
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    /**
     * Builds a minimal unsigned JWT with the given expiry.
     * The BFF never verifies signatures — it trusts its own session store — so a
     * placeholder "signature" segment is sufficient for testing.
     */
    private String buildJwt(String userId, String tenantId, long expEpochSecond) throws Exception {
        String header  = Base64.getUrlEncoder().withoutPadding()
                .encodeToString("{\"alg\":\"HS256\",\"typ\":\"JWT\"}"
                        .getBytes(StandardCharsets.UTF_8));
        String payload = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(objectMapper.writeValueAsBytes(Map.of(
                        "sub",      userId,
                        "email",    "test@example.com",
                        "role",     "STANDARD",
                        "tenantId", tenantId,
                        "exp",      expEpochSecond)));
        return header + "." + payload + ".signature";
    }
}
