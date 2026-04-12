package com.tm.bff;

import tools.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import jakarta.servlet.Filter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.containers.GenericContainer;
import org.wiremock.spring.EnableWireMock;
import org.wiremock.spring.InjectWireMock;

import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration test for login rate limiting (Session 8).
 *
 * Verifies that the 6th POST /auth/local/login from the same IP within the rate-limit
 * window returns 429 + Retry-After: 60.
 *
 * Uses a dedicated IP (RATE_LIMIT_IP) so the bucket is always fresh regardless of
 * the order in which test classes run.
 *
 * Rate limiting for login is enforced inside LocalLoginFilter (in the security filter chain),
 * not via RateLimitInterceptor — login never reaches a HandlerInterceptor because
 * LocalLoginFilter writes the response without calling chain.doFilter().
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@EnableWireMock
class BffRateLimitIntegrationTest {

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

    /** Dedicated IP — each test class must use its own to avoid depleting a shared bucket. */
    private static final String RATE_LIMIT_IP = "10.0.99.99";
    private static final String TENANT_ID     = "00000000-0000-0000-0000-000000000001";

    @BeforeEach
    void resetWireMock() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
                .addFilters(webApplicationContext.getBeansOfType(Filter.class)
                        .values().toArray(new Filter[0]))
                .build();
        wireMock.resetAll();
    }

    @Test
    void login_sixthAttemptWithinWindow_returns429WithRetryAfter() throws Exception {
        // Stub Core API to return 401 for all attempts (wrong password — doesn't matter for
        // this test; the rate check runs before the Core API call for attempts 1–5,
        // and the 6th is blocked before Core API is reached at all)
        wireMock.stubFor(WireMock.post(urlEqualTo("/internal/auth/validate"))
                .willReturn(aResponse()
                        .withStatus(401)
                        .withBody("{\"status\":401,\"title\":\"Unauthorized\"}")));

        String body = objectMapper.writeValueAsString(Map.of(
                "email",    "test@example.com",
                "password", "WrongPass",
                "tenantId", TENANT_ID));

        // Attempts 1–5: rate limit not yet exceeded; Core API returns 401
        for (int i = 1; i <= 5; i++) {
            mockMvc.perform(post("/auth/local/login")
                            .with(request -> { request.setRemoteAddr(RATE_LIMIT_IP); return request; })
                            .with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isUnauthorized());
        }

        // 6th attempt: bucket empty → blocked before Core API is called → 429
        mockMvc.perform(post("/auth/local/login")
                        .with(request -> { request.setRemoteAddr(RATE_LIMIT_IP); return request; })
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().is(429))
                .andExpect(header().string("Retry-After", "60"));
    }
}
