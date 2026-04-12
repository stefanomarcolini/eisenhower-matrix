package com.tm.core.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Profile-aware BCrypt encoder.
 * Cost 12 in production (PASSWORD_POLICY.md §5).
 * Cost 4 in tests to keep the test suite fast.
 */
@Configuration
public class PasswordEncoderConfig {

    @Bean
    @Profile("!test")
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12);
    }

    @Bean
    @Profile("test")
    public PasswordEncoder testPasswordEncoder() {
        return new BCryptPasswordEncoder(4);
    }
}