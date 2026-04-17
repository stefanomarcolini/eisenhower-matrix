package com.tm.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tm.core.application.MfaService;
import com.tm.core.infrastructure.PasswordResetTokenRepository;
import com.tm.core.infrastructure.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.http.MediaType;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.testcontainers.containers.PostgreSQLContainer;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests for the internal auth layer (Session 5).
 * Covers: local register, local login, MFA enroll + verify, forgot/reset password,
 * password age warning.
 *
 * Uses real PostgreSQL via Testcontainers; JavaMailSender is mocked.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@ActiveProfiles("test")
// @Sql seeds roles and the test tenant once (ON CONFLICT DO NOTHING keeps it idempotent).
// No @Transactional here — MockMvc requests run in their own transactions; @BeforeEach
// cleans user/token rows between tests so each test starts with a blank slate.
@Sql(scripts = "/db/test-seed.sql", executionPhase = Sql.ExecutionPhase.BEFORE_TEST_CLASS)
class AuthIntegrationTest {

    // Container started in a static block so it is ready before Spring initialises its
    // ApplicationContext. @DynamicPropertySource then provides the JDBC URL to the context.
    // Using @Container + @Testcontainers here would cause a JUnit 5 extension ordering race
    // (SpringExtension.beforeAll runs first and tries to obtain the mapped port before
    // TestcontainersExtension has started the container). See CODING_PATTERNS.md §9.
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
    @Autowired PasswordResetTokenRepository tokenRepository;
    @Autowired MfaService mfaService;

    @MockitoBean
    JavaMailSender mailSender;

    private static final String TENANT_ID = "00000000-0000-0000-0000-000000000001";
    private static final String TEST_EMAIL = "testuser@example.com";
    private static final String TEST_PASSWORD = "Str0ng!Pass#1";

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
                .addFilters(springSecurityFilterChain)
                .build();
        userRepository.deleteAll();
        tokenRepository.deleteAll();
    }

    // -------------------------------------------------------------------------
    // LOCAL REGISTRATION
    // -------------------------------------------------------------------------

    @Test
    void register_validCredentials_returns200WithToken() throws Exception {
        mockMvc.perform(post("/internal/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "email", TEST_EMAIL,
                                "password", TEST_PASSWORD,
                                "tenantId", TENANT_ID))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").isNotEmpty())
                .andExpect(jsonPath("$.role").value("STANDARD"))
                .andExpect(jsonPath("$.tenantId").value(TENANT_ID));
    }

    @Test
    void register_duplicateEmail_returns409() throws Exception {
        // First registration
        mockMvc.perform(post("/internal/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "email", TEST_EMAIL,
                                "password", TEST_PASSWORD,
                                "tenantId", TENANT_ID))))
                .andExpect(status().isOk());

        // Duplicate
        mockMvc.perform(post("/internal/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "email", TEST_EMAIL,
                                "password", TEST_PASSWORD,
                                "tenantId", TENANT_ID))))
                .andExpect(status().isConflict());
    }

    @Test
    void register_weakPassword_returns422() throws Exception {
        mockMvc.perform(post("/internal/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "email", TEST_EMAIL,
                                "password", "short",
                                "tenantId", TENANT_ID))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.title").value("Password Policy Violation"));
    }

    @Test
    void register_passwordMissingUppercase_returns422() throws Exception {
        // str0ng!pass#1 — lowercase + digit + special, no uppercase
        mockMvc.perform(post("/internal/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "email", TEST_EMAIL,
                                "password", "str0ng!pass#1",
                                "tenantId", TENANT_ID))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.title").value("Password Policy Violation"));
    }

    @Test
    void register_passwordMissingLowercase_returns422() throws Exception {
        // STR0NG!PASS#1 — uppercase + digit + special, no lowercase
        mockMvc.perform(post("/internal/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "email", TEST_EMAIL,
                                "password", "STR0NG!PASS#1",
                                "tenantId", TENANT_ID))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.title").value("Password Policy Violation"));
    }

    @Test
    void register_passwordMissingDigit_returns422() throws Exception {
        // StrongPass!! — uppercase + lowercase + special, no digit
        mockMvc.perform(post("/internal/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "email", TEST_EMAIL,
                                "password", "StrongPass!!",
                                "tenantId", TENANT_ID))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.title").value("Password Policy Violation"));
    }

    @Test
    void register_passwordMissingSpecialChar_returns422() throws Exception {
        // Str0ngPass01 — uppercase + lowercase + digit, no special character
        mockMvc.perform(post("/internal/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "email", TEST_EMAIL,
                                "password", "Str0ngPass01",
                                "tenantId", TENANT_ID))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.title").value("Password Policy Violation"));
    }

    // -------------------------------------------------------------------------
    // LOCAL LOGIN
    // -------------------------------------------------------------------------

    @Test
    void validate_correctCredentials_returns200WithToken() throws Exception {
        register(TEST_EMAIL, TEST_PASSWORD);

        mockMvc.perform(post("/internal/auth/validate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "email", TEST_EMAIL,
                                "password", TEST_PASSWORD,
                                "tenantId", TENANT_ID))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").isNotEmpty())
                .andExpect(jsonPath("$.mfaRequired").value(false))
                .andExpect(jsonPath("$.passwordWarning").value(false));
    }

    @Test
    void validate_wrongPassword_returns401() throws Exception {
        register(TEST_EMAIL, TEST_PASSWORD);

        mockMvc.perform(post("/internal/auth/validate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "email", TEST_EMAIL,
                                "password", "WrongPass#9",
                                "tenantId", TENANT_ID))))
                .andExpect(status().isUnauthorized());
    }

    // -------------------------------------------------------------------------
    // PASSWORD AGE WARNING
    // -------------------------------------------------------------------------

    @Test
    void validate_oldPassword_returnsPasswordWarningTrue() throws Exception {
        register(TEST_EMAIL, TEST_PASSWORD);

        // Backdate password_changed_at to 85 days ago (> 80-day warning threshold)
        userRepository.findByTenantIdAndEmail(
                java.util.UUID.fromString(TENANT_ID), TEST_EMAIL).ifPresent(user -> {
            user.setPasswordChangedAt(java.time.Instant.now()
                    .minus(85, java.time.temporal.ChronoUnit.DAYS));
            userRepository.save(user);
        });

        mockMvc.perform(post("/internal/auth/validate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "email", TEST_EMAIL,
                                "password", TEST_PASSWORD,
                                "tenantId", TENANT_ID))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.passwordWarning").value(true));
    }

    // -------------------------------------------------------------------------
    // TOKEN REFRESH
    // -------------------------------------------------------------------------

    @Test
    void refresh_knownUser_returnsFreshToken() throws Exception {
        var registerResult = registerAndGetUserId(TEST_EMAIL, TEST_PASSWORD);
        String userId = registerResult;

        mockMvc.perform(post("/internal/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("userId", userId))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").isNotEmpty());
    }

    // -------------------------------------------------------------------------
    // FORGOT PASSWORD / RESET PASSWORD FLOW
    // -------------------------------------------------------------------------

    @Test
    void forgotPassword_existingLocalUser_sends204AndEmail() throws Exception {
        register(TEST_EMAIL, TEST_PASSWORD);

        mockMvc.perform(post("/internal/auth/forgot-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "email", TEST_EMAIL,
                                "tenantId", TENANT_ID))))
                .andExpect(status().isNoContent());

        // Verify email was sent
        ArgumentCaptor<SimpleMailMessage> captor = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mailSender).send(captor.capture());
        assertThat(captor.getValue().getTo()).contains(TEST_EMAIL);
        assertThat(captor.getValue().getText()).contains("/auth/reset-password?token=");
    }

    @Test
    void forgotPassword_unknownEmail_stillReturns204() throws Exception {
        mockMvc.perform(post("/internal/auth/forgot-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "email", "unknown@example.com",
                                "tenantId", TENANT_ID))))
                .andExpect(status().isNoContent());
    }

    @Test
    void resetPassword_validToken_changesPassword() throws Exception {
        register(TEST_EMAIL, TEST_PASSWORD);

        // Trigger forgot-password to create a token
        mockMvc.perform(post("/internal/auth/forgot-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "email", TEST_EMAIL,
                                "tenantId", TENANT_ID))))
                .andExpect(status().isNoContent());

        // Extract the raw token from the email
        ArgumentCaptor<SimpleMailMessage> captor = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mailSender).send(captor.capture());
        String emailBody = captor.getValue().getText();
        String rawToken = extractTokenFromEmailBody(emailBody);

        // Reset the password
        mockMvc.perform(post("/internal/auth/reset-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "token", rawToken,
                                "newPassword", "NewStr0ng!Pass#99"))))
                .andExpect(status().isNoContent());

        // Old password should no longer work
        mockMvc.perform(post("/internal/auth/validate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "email", TEST_EMAIL,
                                "password", TEST_PASSWORD,
                                "tenantId", TENANT_ID))))
                .andExpect(status().isUnauthorized());

        // New password should work
        mockMvc.perform(post("/internal/auth/validate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "email", TEST_EMAIL,
                                "password", "NewStr0ng!Pass#99",
                                "tenantId", TENANT_ID))))
                .andExpect(status().isOk());
    }

    @Test
    void resetPassword_invalidToken_returns400() throws Exception {
        mockMvc.perform(post("/internal/auth/reset-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "token", "invalid-token-value",
                                "newPassword", "NewStr0ng!Pass#99"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Invalid Token"));
    }

    // -------------------------------------------------------------------------
    // OIDC TOKEN EXCHANGE
    // -------------------------------------------------------------------------

    @Test
    void oidcToken_googleIssuer_createsUserAndReturns200() throws Exception {
        mockMvc.perform(post("/internal/auth/token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "iss", "https://accounts.google.com",
                                "sub", "google-sub-001",
                                "email", "googleuser@example.com",
                                "name", "Google User",
                                "tenantId", TENANT_ID))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").isNotEmpty())
                .andExpect(jsonPath("$.userId").isNotEmpty());
    }

    @Test
    void oidcToken_googleIssuer_existingUser_returnsTokenWithSameUserId() throws Exception {
        // First call creates the user
        MvcResult first = mockMvc.perform(post("/internal/auth/token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "iss", "https://accounts.google.com",
                                "sub", "google-sub-002",
                                "email", "googleuser2@example.com",
                                "name", "Google User 2",
                                "tenantId", TENANT_ID))))
                .andExpect(status().isOk())
                .andReturn();
        String firstUserId = objectMapper.readTree(first.getResponse().getContentAsString())
                .get("userId").asText();

        // Second call with same sub returns the same userId (idempotent user creation)
        MvcResult second = mockMvc.perform(post("/internal/auth/token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "iss", "https://accounts.google.com",
                                "sub", "google-sub-002",
                                "email", "googleuser2@example.com",
                                "name", "Google User 2",
                                "tenantId", TENANT_ID))))
                .andExpect(status().isOk())
                .andReturn();
        String secondUserId = objectMapper.readTree(second.getResponse().getContentAsString())
                .get("userId").asText();

        assertThat(secondUserId).isEqualTo(firstUserId);
    }

    @Test
    void oidcToken_unknownIssuer_returns409() throws Exception {
        mockMvc.perform(post("/internal/auth/token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "iss", "https://unknown-provider.example.com",
                                "sub", "unknown-sub-001",
                                "email", "unknown@example.com",
                                "name", "Unknown User",
                                "tenantId", TENANT_ID))))
                .andExpect(status().isConflict());
    }

    // -------------------------------------------------------------------------
    // MFA ENROLL + VERIFY FLOW
    // -------------------------------------------------------------------------

    @Test
    void validate_userWithMfaEnabled_returnsMfaRequired() throws Exception {
        register(TEST_EMAIL, TEST_PASSWORD);

        // Enable MFA directly on the user entity (simulates completing enrollment)
        userRepository.findByTenantIdAndEmail(
                java.util.UUID.fromString(TENANT_ID), TEST_EMAIL).ifPresent(user -> {
            String secret = mfaService.generateSecret();
            user.setMfaSecret(mfaService.encrypt(secret));
            user.setMfaEnabled(true);
            userRepository.save(user);
        });

        mockMvc.perform(post("/internal/auth/validate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "email", TEST_EMAIL,
                                "password", TEST_PASSWORD,
                                "tenantId", TENANT_ID))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mfaRequired").value(true))
                .andExpect(jsonPath("$.token").isEmpty());
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private void register(String email, String password) throws Exception {
        mockMvc.perform(post("/internal/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "email", email,
                                "password", password,
                                "tenantId", TENANT_ID))))
                .andExpect(status().isOk());
    }

    private String registerAndGetUserId(String email, String password) throws Exception {
        var result = mockMvc.perform(post("/internal/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "email", email,
                                "password", password,
                                "tenantId", TENANT_ID))))
                .andExpect(status().isOk())
                .andReturn();
        var node = objectMapper.readTree(result.getResponse().getContentAsString());
        return node.get("userId").asText();
    }

    private String extractTokenFromEmailBody(String body) {
        // URL format: /auth/reset-password?token=<raw_token>
        String marker = "token=";
        int idx = body.indexOf(marker);
        int start = idx + marker.length();
        int end = body.indexOf('\n', start);
        return end == -1 ? body.substring(start).trim() : body.substring(start, end).trim();
    }
}
