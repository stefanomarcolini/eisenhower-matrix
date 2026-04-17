package com.tm.bff;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import jakarta.servlet.Filter;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.containers.GenericContainer;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Session 7 validation: BFF starts, connects to Redis via Testcontainers,
 * and GET /actuator/health returns 200.
 *
 * Uses the static block + @DynamicPropertySource pattern for Testcontainers lifecycle
 * (CODING_PATTERNS.md §10). Redis uses GenericContainer — there is no
 * org.testcontainers:redis module (CODING_PATTERNS.md §8).
 *
 * WireMock is not needed here — no Core API calls are made.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class BffHealthIntegrationTest {

    // Start Redis before Spring initialises its ApplicationContext.
    // Using static block avoids the Testcontainers/SpringExtension ordering race
    // described in CODING_PATTERNS.md §10.
    static final GenericContainer<?> redis =
            new GenericContainer<>(System.getProperty("redis.test.image", "redis:7-alpine"))
                    .withExposedPorts(6379);

    static {
        redis.start();
    }

    @DynamicPropertySource
    static void redisProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
        // No Redis password in tests
        registry.add("spring.data.redis.password", () -> "");
    }

    MockMvc mockMvc;
    @Autowired WebApplicationContext webApplicationContext;

    @BeforeEach
    void setUpMockMvc() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
                .addFilters(webApplicationContext.getBeansOfType(Filter.class)
                        .values().toArray(new Filter[0]))
                .build();
    }

    @Test
    void actuatorHealth_returns200() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    void unauthenticated_apiRequest_returns401() throws Exception {
        mockMvc.perform(get("/api/v1/tasks"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void unauthenticated_sessionEndpoint_returns200WithNotAuthenticated() throws Exception {
        mockMvc.perform(get("/auth/session"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.isAuthenticated").value(false));
    }

    @Test
    void faviconIsServed_withoutServerError() throws Exception {
        mockMvc.perform(get("/favicon.ico"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("image/x-icon"));
    }
}
