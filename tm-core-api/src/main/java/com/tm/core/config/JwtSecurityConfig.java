package com.tm.core.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.web.SecurityFilterChain;

import javax.crypto.spec.SecretKeySpec;
import java.util.Base64;

import static org.springframework.security.oauth2.jose.jws.MacAlgorithm.HS256;

/**
 * JWT security configuration for Core API.
 * Single SecurityFilterChain — STATELESS, CSRF disabled.
 * /internal/** is permitted at Spring level; network isolation is the primary gate.
 * See CODING_PATTERNS.md §15.
 */
@Configuration
@EnableMethodSecurity
public class JwtSecurityConfig {

    @Value("${app.jwt.secret}")
    private String jwtSecret;

    @Value("${app.base-url}")
    private String appBaseUrl;

    @Bean
    public JwtDecoder jwtDecoder() {
        SecretKeySpec key = new SecretKeySpec(
                Base64.getDecoder().decode(jwtSecret), "HmacSHA256");

        NimbusJwtDecoder decoder = NimbusJwtDecoder
                .withSecretKey(key)
                .macAlgorithm(HS256)   // pins algorithm — rejects none, RS256, etc.
                .build();

        // exp validated by default; iss must be added explicitly
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                new JwtTimestampValidator(),
                new JwtIssuerValidator(appBaseUrl)
        ));
        return decoder;
    }

    @Bean
    public SecurityFilterChain coreApiChain(HttpSecurity http) throws Exception {
        http
                .authorizeHttpRequests(auth -> auth
                        // Actuator probes — no auth
                        .requestMatchers("/actuator/health", "/actuator/info").permitAll()
                        // Internal endpoints — permit at Spring level; network isolation is the primary gate
                        .requestMatchers("/internal/**").permitAll()
                        // All public API endpoints require a valid app JWT
                        .anyRequest().authenticated()
                )
                .oauth2ResourceServer(oauth2 -> oauth2.jwt(jwt -> {
                    jwt.decoder(jwtDecoder());
                    jwt.jwtAuthenticationConverter(jwtAuthenticationConverter());
                }))
                // Resource server uses tokens, not sessions or cookies — disable both
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS));
        return http.build();
    }

    /**
     * Maps the "role" JWT claim to a Spring Security authority prefixed with ROLE_.
     * Enables @PreAuthorize("hasRole('ADMIN')") on controllers.
     */
    @Bean
    public JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtGrantedAuthoritiesConverter grantedAuthoritiesConverter = new JwtGrantedAuthoritiesConverter();
        grantedAuthoritiesConverter.setAuthoritiesClaimName("role");
        grantedAuthoritiesConverter.setAuthorityPrefix("ROLE_");

        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(grantedAuthoritiesConverter);
        return converter;
    }
}