package com.tm.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tm.core.infrastructure.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.http.MediaType;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.containers.PostgreSQLContainer;

import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Integration tests for user profile endpoints:
 *   GET  /api/v1/users/me          — read own profile
 *   PUT  /api/v1/users/me          — update display name / theme
 *   PUT  /api/v1/users/me/password — change password (LOCAL users only)
 *
 * No @Transactional — MockMvc requests run in their own committed transactions.
 * See CODING_PATTERNS.md §9.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@ActiveProfiles("test")
@Sql(scripts = "/db/test-seed.sql", executionPhase = Sql.ExecutionPhase.BEFORE_TEST_CLASS)
class ProfileIntegrationTest {

    static final PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>(System.getProperty("postgresql.test.image", "postgres:17-alpine"));

    static {
        postgres.start();
    }

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    MockMvc mockMvc;
    @Autowired WebApplicationContext webApplicationContext;
    @Autowired FilterChainProxy springSecurityFilterChain;
    private final ObjectMapper objectMapper = new ObjectMapper();
    @Autowired UserRepository userRepository;

    @MockitoBean
    JavaMailSender mailSender;

    private static final String TENANT_ID    = "00000000-0000-0000-0000-000000000001";
    private static final String TEST_EMAIL   = "profileuser@example.com";
    private static final String TEST_PASSWORD = "Str0ng!Pass#1";

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
                .addFilters(springSecurityFilterChain)
                .build();
        userRepository.deleteAll();
    }

    // -------------------------------------------------------------------------
    // GET /api/v1/users/me
    // -------------------------------------------------------------------------

    @Test
    void getProfile_authenticatedUser_returns200WithEmailAndRole() throws Exception {
        String token = registerAndGetToken(TEST_EMAIL);

        mockMvc.perform(get("/api/v1/users/me")
                        .header("Authorization", "Bearer " + token)
                        .header("X-Tenant-ID", TENANT_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value(TEST_EMAIL))
                .andExpect(jsonPath("$.role").value("STANDARD"))
                .andExpect(jsonPath("$.isMfaEnabled").value(false))
                .andExpect(jsonPath("$.authProvider").value("LOCAL"));
    }

    @Test
    void getProfile_unauthenticated_returns401() throws Exception {
        mockMvc.perform(get("/api/v1/users/me")
                        .header("X-Tenant-ID", TENANT_ID))
                .andExpect(status().isUnauthorized());
    }

    // -------------------------------------------------------------------------
    // PUT /api/v1/users/me
    // -------------------------------------------------------------------------

    @Test
    void updateProfile_validDisplayName_returns200WithUpdatedName() throws Exception {
        String token = registerAndGetToken(TEST_EMAIL);

        mockMvc.perform(put("/api/v1/users/me")
                        .header("Authorization", "Bearer " + token)
                        .header("X-Tenant-ID", TENANT_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "displayName", "Alice Smith",
                                "theme", "DARK"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.displayName").value("Alice Smith"));
    }

    @Test
    void updateProfile_themeChange_returns200WithUpdatedTheme() throws Exception {
        String token = registerAndGetToken(TEST_EMAIL);

        mockMvc.perform(put("/api/v1/users/me")
                        .header("Authorization", "Bearer " + token)
                        .header("X-Tenant-ID", TENANT_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "theme", "DARK"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.theme").value("DARK"));
    }

    // -------------------------------------------------------------------------
    // PUT /api/v1/users/me/password
    // -------------------------------------------------------------------------

    @Test
    void changePassword_correctOldPassword_returns204() throws Exception {
        String token = registerAndGetToken(TEST_EMAIL);

        mockMvc.perform(put("/api/v1/users/me/password")
                        .header("Authorization", "Bearer " + token)
                        .header("X-Tenant-ID", TENANT_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "currentPassword", TEST_PASSWORD,
                                "newPassword", "NewStr0ng!Pass#99"))))
                .andExpect(status().isNoContent());

        // Old password should no longer validate
        mockMvc.perform(post("/internal/auth/validate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "email", TEST_EMAIL,
                                "password", TEST_PASSWORD,
                                "tenantId", TENANT_ID))))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void changePassword_wrongOldPassword_returns401() throws Exception {
        String token = registerAndGetToken(TEST_EMAIL);

        // Wrong currentPassword → BadCredentialsException → 401
        mockMvc.perform(put("/api/v1/users/me/password")
                        .header("Authorization", "Bearer " + token)
                        .header("X-Tenant-ID", TENANT_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "currentPassword", "WrongCurrentPass!1",
                                "newPassword", "NewStr0ng!Pass#99"))))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void changePassword_weakNewPassword_returns422() throws Exception {
        String token = registerAndGetToken(TEST_EMAIL);

        // New password is too short → PasswordPolicyViolationException → 422
        mockMvc.perform(put("/api/v1/users/me/password")
                        .header("Authorization", "Bearer " + token)
                        .header("X-Tenant-ID", TENANT_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "currentPassword", TEST_PASSWORD,
                                "newPassword", "short"))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.title").value("Password Policy Violation"));
    }

    // -------------------------------------------------------------------------
    // MFA enrollment and disable
    // -------------------------------------------------------------------------

    @Test
    void mfaEnrollment_initiate_returns200WithSecretAndOtpauthUri() throws Exception {
        String token = registerAndGetToken(TEST_EMAIL);

        mockMvc.perform(post("/api/v1/users/me/mfa/enable")
                        .header("Authorization", "Bearer " + token)
                        .header("X-Tenant-ID", TENANT_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.secret").isString())
                .andExpect(jsonPath("$.otpauthUri").isString());
    }

    @Test
    void mfaEnrollment_confirmWithInvalidCode_returns400() throws Exception {
        String token = registerAndGetToken(TEST_EMAIL);

        // Step 1: initiate enrollment (stores encrypted secret)
        mockMvc.perform(post("/api/v1/users/me/mfa/enable")
                        .header("Authorization", "Bearer " + token)
                        .header("X-Tenant-ID", TENANT_ID))
                .andExpect(status().isOk());

        // Step 2: confirm with a clearly invalid code → 400 "Invalid TOTP code"
        mockMvc.perform(post("/api/v1/users/me/mfa/verify")
                        .header("Authorization", "Bearer " + token)
                        .header("X-Tenant-ID", TENANT_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("code", "000000"))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void mfaEnrollment_confirmWithoutInitiating_returns400() throws Exception {
        String token = registerAndGetToken(TEST_EMAIL);

        // No initiation step → mfaSecret is null → 400 "MFA enrollment not initiated"
        mockMvc.perform(post("/api/v1/users/me/mfa/verify")
                        .header("Authorization", "Bearer " + token)
                        .header("X-Tenant-ID", TENANT_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("code", "123456"))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void mfaDisable_whenMfaNotEnabled_returns400() throws Exception {
        String token = registerAndGetToken(TEST_EMAIL);

        // Fresh user has MFA disabled → 400 "MFA is not enabled"
        mockMvc.perform(delete("/api/v1/users/me/mfa")
                        .header("Authorization", "Bearer " + token)
                        .header("X-Tenant-ID", TENANT_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("code", "123456"))))
                .andExpect(status().isBadRequest());
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private String registerAndGetToken(String email) throws Exception {
        MvcResult result = mockMvc.perform(post("/internal/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "tenantId", TENANT_ID,
                                "email", email,
                                "password", TEST_PASSWORD))))
                .andExpect(status().isOk())
                .andReturn();
        Map<?, ?> body = objectMapper.readValue(result.getResponse().getContentAsString(), Map.class);
        return (String) body.get("token");
    }
}
