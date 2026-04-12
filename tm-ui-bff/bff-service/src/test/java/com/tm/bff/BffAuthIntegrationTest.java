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

import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests for BFF auth flow (Session 8).
 * WireMock stubs all outbound Core API calls; Redis is real via Testcontainers.
 *
 * Note on imports: static imports from com.github.tomakehurst.wiremock.client.WireMock
 * (urlEqualTo, okJson, etc.) and from MockMvcRequestBuilders (post, get) would clash.
 * WireMock's MappingBuilder-producing methods are used via WireMock.* static import
 * (e.g. post(urlEqualTo(...))); MockMvc builders are imported explicitly.
 *
 * Covers: local login → session created; MFA pending flow; 5 failed MFA → lockout;
 * registration; forgot-password; GET /auth/session returns correct state.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@EnableWireMock
class BffAuthIntegrationTest {

    static final GenericContainer<?> redis =
            new GenericContainer<>(System.getProperty("redis.test.image", "redis:7-alpine"))
                    .withExposedPorts(6379);

    static {
        redis.start();
    }

    @SuppressWarnings("java:S125")
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

    private static final String TENANT_ID = "00000000-0000-0000-0000-000000000001";
    private static final String USER_ID   = "00000000-0000-0000-0000-000000000099";
    // JWT with payload: {"sub":"<USER_ID>","email":"test@example.com","role":"STANDARD",
    //                    "tenantId":"<TENANT_ID>","exp":9999999999}
    // BFF never verifies the signature — it trusts its own session store
    private static final String TEST_JWT  =
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
    // LOCAL LOGIN
    // -------------------------------------------------------------------------

    @Test
    void localLogin_validCredentials_createsSessionAndReturns200() throws Exception {
        stubValidate(false, false);

        mockMvc.perform(post("/auth/local/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody("test@example.com", "Str0ng!Pass#1"))
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mfaPending").value(false))
                .andExpect(jsonPath("$.passwordWarning").value(false));
    }

    @Test
    void localLogin_mfaRequired_setsMfaPendingAndReturns200() throws Exception {
        stubValidateMfaRequired();

        mockMvc.perform(post("/auth/local/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody("test@example.com", "Str0ng!Pass#1"))
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mfaPending").value(true));
    }

    @Test
    void localLogin_wrongPassword_returns401() throws Exception {
        wireMock.stubFor(WireMock.post(urlEqualTo("/internal/auth/validate"))
                .willReturn(aResponse()
                        .withStatus(401)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"status\":401,\"title\":\"Unauthorized\"}")));

        mockMvc.perform(post("/auth/local/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody("test@example.com", "WrongPass"))
                        .with(csrf()))
                .andExpect(status().isUnauthorized());
    }

    // -------------------------------------------------------------------------
    // SESSION
    // -------------------------------------------------------------------------

    @Test
    void getSession_unauthenticated_returnsIsAuthenticatedFalse() throws Exception {
        mockMvc.perform(get("/auth/session"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.isAuthenticated").value(false));
    }

    @Test
    void getSession_authenticated_returnsSessionDetails() throws Exception {
        stubValidate(false, false);

        // Login — Spring Session (Redis-backed) sets TM_SESSION cookie on response.
        // We can't use loginResult.getRequest().getSession() — that returns the unwrapped
        // MockHttpServletRequest's session, which is null when Spring Session wraps it.
        // Pass the response cookie on the subsequent request instead.
        MvcResult loginResult = mockMvc.perform(post("/auth/local/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody("test@example.com", "Str0ng!Pass#1"))
                        .with(csrf()))
                .andExpect(status().isOk())
                .andReturn();

        MockHttpSession session = requireSession(loginResult);

        mockMvc.perform(get("/auth/session").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.isAuthenticated").value(true))
                .andExpect(jsonPath("$.tenantId").value(TENANT_ID));
    }

    // -------------------------------------------------------------------------
    // MFA FLOW
    // -------------------------------------------------------------------------

    @Test
    void mfaVerify_fiveFailedAttempts_invalidatesSessionAndReturns401WithLockout() throws Exception {
        stubValidateMfaRequired();

        // Login — get the session cookie from the response
        MvcResult loginResult = mockMvc.perform(post("/auth/local/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody("test@example.com", "Str0ng!Pass#1"))
                        .with(csrf()))
                .andExpect(status().isOk())
                .andReturn();

        MockHttpSession session = requireSession(loginResult);

        wireMock.stubFor(WireMock.post(urlEqualTo("/internal/auth/mfa/validate"))
                .willReturn(aResponse().withStatus(401).withBody("{\"status\":401}")));

        // 5 failed attempts — MFA_ATTEMPTS incremented in Redis session each time
        for (int i = 0; i < 5; i++) {
            mockMvc.perform(post("/auth/mfa/verify")
                            .session(session)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"code\":\"000000\"}")
                            .with(csrf()))
                    .andExpect(status().isUnauthorized());
        }

        // 6th attempt — attempts >= MAX_ATTEMPTS → session invalidated → lockout header
        mockMvc.perform(post("/auth/mfa/verify")
                        .session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"000000\"}")
                        .with(csrf()))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("X-MFA-Lockout", "true"));
    }

    // -------------------------------------------------------------------------
    // REGISTRATION
    // -------------------------------------------------------------------------

    @Test
    void register_validRequest_createsSessionAndReturnsUserId() throws Exception {
        wireMock.stubFor(WireMock.post(urlEqualTo("/internal/auth/register"))
                .willReturn(okJson(objectMapper.writeValueAsString(Map.of(
                        "token", TEST_JWT,
                        "userId", USER_ID,
                        "tenantId", TENANT_ID,
                        "role", "STANDARD")))));

        mockMvc.perform(post("/auth/local/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "email", "newuser@example.com",
                                "password", "Str0ng!Pass#1",
                                "tenantId", TENANT_ID)))
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value(USER_ID))
                .andExpect(jsonPath("$.role").value("STANDARD"));
    }

    // -------------------------------------------------------------------------
    // LOGOUT
    // -------------------------------------------------------------------------

    @Test
    void localLogout_clearsSessionAndSubsequentSessionCheckReturnsUnauthenticated() throws Exception {
        // Use a dedicated IP (10.0.3.1) — 127.0.0.1 bucket is exhausted by other tests.
        // See CODING_PATTERNS.md §18.
        stubValidate(false, false);

        MvcResult loginResult = mockMvc.perform(post("/auth/local/login")
                        .with(request -> { request.setRemoteAddr("10.0.3.1"); return request; })
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody("test@example.com", "Str0ng!Pass#1"))
                        .with(csrf()))
                .andExpect(status().isOk())
                .andReturn();

        MockHttpSession session = requireSession(loginResult);

        // Session is active before logout
        mockMvc.perform(get("/auth/session").session(session))
                .andExpect(jsonPath("$.isAuthenticated").value(true));

        // Spring Security's /logout endpoint invalidates the session and returns 200 JSON
        mockMvc.perform(post("/logout")
                        .session(session)
                        .with(csrf()))
                .andExpect(status().isOk());

        // Session must be gone after logout
        mockMvc.perform(get("/auth/session").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.isAuthenticated").value(false));
    }

    // -------------------------------------------------------------------------
    // MFA VERIFY — success path
    // -------------------------------------------------------------------------

    @Test
    void mfaVerify_validCode_promotesToFullSessionAndReturns200() throws Exception {
        // Use IP 10.0.4.1 — dedicated to this test to avoid rate-limit exhaustion
        stubValidateMfaRequired();

        MvcResult loginResult = mockMvc.perform(post("/auth/local/login")
                        .with(request -> { request.setRemoteAddr("10.0.4.1"); return request; })
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody("test@example.com", "Str0ng!Pass#1"))
                        .with(csrf()))
                .andExpect(status().isOk())
                .andReturn();

        MockHttpSession session = requireSession(loginResult);

        wireMock.stubFor(WireMock.post(urlEqualTo("/internal/auth/mfa/validate"))
                .willReturn(okJson(objectMapper.writeValueAsString(Map.of(
                        "token", TEST_JWT,
                        "tenantId", TENANT_ID,
                        "role", "STANDARD",
                        "passwordWarning", false)))));

        mockMvc.perform(post("/auth/mfa/verify")
                        .session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"123456\"}")
                        .with(csrf()))
                .andExpect(status().isOk());

        // After successful MFA, session must be fully authenticated
        mockMvc.perform(get("/auth/session").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.isAuthenticated").value(true))
                .andExpect(jsonPath("$.tenantId").value(TENANT_ID));

        wireMock.stubFor(WireMock.get(urlEqualTo("/api/v1/tasks"))
                .willReturn(okJson("[]")));

        // The promoted session must also satisfy Spring Security's authenticated()
        // check for protected proxy routes immediately after MFA verification.
        mockMvc.perform(get("/api/v1/tasks").session(session))
                .andExpect(status().isOk());
    }

    // -------------------------------------------------------------------------
    // PASSWORD WARNING
    // -------------------------------------------------------------------------

    @Test
    void localLogin_passwordWarning_returnsPasswordWarningTrue() throws Exception {
        // Use IP 10.0.5.1 — dedicated to this test to avoid rate-limit exhaustion
        stubValidate(false, true);  // passwordWarning = true

        mockMvc.perform(post("/auth/local/login")
                        .with(request -> { request.setRemoteAddr("10.0.5.1"); return request; })
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody("test@example.com", "Str0ng!Pass#1"))
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.passwordWarning").value(true));
    }

    // -------------------------------------------------------------------------
    // FORGOT / RESET PASSWORD
    // -------------------------------------------------------------------------

    @Test
    void forgotPassword_anyEmail_alwaysReturns204() throws Exception {
        wireMock.stubFor(WireMock.post(urlEqualTo("/internal/auth/forgot-password"))
                .willReturn(aResponse().withStatus(204)));

        mockMvc.perform(post("/auth/forgot-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "email", "unknown@example.com",
                                "tenantId", TENANT_ID)))
                        .with(csrf()))
                .andExpect(status().isNoContent());
    }

    @Test
    void resetPassword_validToken_returns204() throws Exception {
        wireMock.stubFor(WireMock.post(urlEqualTo("/internal/auth/reset-password"))
                .willReturn(aResponse().withStatus(204)));

        mockMvc.perform(post("/auth/reset-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "token", "valid-reset-token",
                                "newPassword", "NewStr0ng!Pass#99")))
                        .with(csrf()))
                .andExpect(status().isNoContent());
    }

    @Test
    void resetPassword_invalidToken_returns400() throws Exception {
        wireMock.stubFor(WireMock.post(urlEqualTo("/internal/auth/reset-password"))
                .willReturn(aResponse()
                        .withStatus(400)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"status\":400,\"title\":\"Invalid Token\"}")));

        mockMvc.perform(post("/auth/reset-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "token", "bad-token",
                                "newPassword", "NewStr0ng!Pass#99")))
                        .with(csrf()))
                .andExpect(status().isBadRequest());
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private void stubValidate(boolean mfaRequired, boolean passwordWarning) {
        wireMock.stubFor(WireMock.post(urlEqualTo("/internal/auth/validate"))
                .willReturn(okJson(buildValidateResponse(mfaRequired, passwordWarning))));
    }

    private void stubValidateMfaRequired() {
        wireMock.stubFor(WireMock.post(urlEqualTo("/internal/auth/validate"))
                .willReturn(okJson(buildValidateResponseMfaPending())));
    }

    private String loginBody(String email, String password) throws Exception {
        return objectMapper.writeValueAsString(Map.of(
                "email", email,
                "password", password,
                "tenantId", TENANT_ID));
    }

    private String buildValidateResponse(boolean mfaRequired, boolean passwordWarning) {
        try {
            return objectMapper.writeValueAsString(Map.of(
                    "token", TEST_JWT,
                    "userId", USER_ID,
                    "tenantId", TENANT_ID,
                    "role", "STANDARD",
                    "mfaRequired", mfaRequired,
                    "passwordWarning", passwordWarning));
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    private String buildValidateResponseMfaPending() {
        try {
            return objectMapper.writeValueAsString(Map.of(
                    "token", "",
                    "userId", USER_ID,
                    "tenantId", TENANT_ID,
                    "role", "STANDARD",
                    "mfaRequired", true,
                    "passwordWarning", false));
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    private MockHttpSession requireSession(MvcResult result) {
        HttpSession session = result.getRequest().getSession(false);
        if (session instanceof MockHttpSession mockHttpSession) {
            return mockHttpSession;
        }
        throw new IllegalStateException("No request session found after login");
    }
}
