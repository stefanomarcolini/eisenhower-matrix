package com.tm.core.application;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import com.nimbusds.jose.proc.SecurityContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.stereotype.Service;

import javax.crypto.spec.SecretKeySpec;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

/**
 * Signs app-issued JWTs (HS256).
 * Claims: sub (userId), email, role, tenantId, iss, iat, exp.
 * See AUTH_CONFIG.md §1.
 */
@Service
public class JwtService {

    private final NimbusJwtEncoder encoder;
    private final String appBaseUrl;
    private final long expiryMinutes;

    public JwtService(
            @Value("${app.jwt.secret}") String jwtSecret,
            @Value("${app.base-url}") String appBaseUrl,
            @Value("${app.jwt.expiry-minutes}") long expiryMinutes) {
        SecretKeySpec key = new SecretKeySpec(
                Base64.getDecoder().decode(jwtSecret), "HmacSHA256");
        ImmutableSecret<SecurityContext> jwkSource = new ImmutableSecret<>(key);
        this.encoder = new NimbusJwtEncoder(jwkSource);
        this.appBaseUrl = appBaseUrl;
        this.expiryMinutes = expiryMinutes;
    }

    public String sign(UUID userId, String email, String role, UUID tenantId) {
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(appBaseUrl)
                .issuedAt(now)
                .expiresAt(now.plusSeconds(expiryMinutes * 60))
                .subject(userId.toString())
                .claim("email", email)
                .claim("role", role)
                .claim("tenantId", tenantId.toString())
                .build();
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        return encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    }
}