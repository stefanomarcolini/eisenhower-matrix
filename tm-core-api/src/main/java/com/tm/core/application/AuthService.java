package com.tm.core.application;

import com.tm.core.domain.ConflictException;
import com.tm.core.domain.enums.AuthProvider;
import com.tm.core.domain.Role;
import com.tm.core.domain.User;
import com.tm.core.infrastructure.RoleRepository;
import com.tm.core.infrastructure.TenantRepository;
import com.tm.core.infrastructure.UserRepository;
import com.tm.core.web.internal.dto.MfaValidateRequest;
import com.tm.core.web.internal.dto.MfaValidateResponse;
import com.tm.core.web.internal.dto.OidcTokenRequest;
import com.tm.core.web.internal.dto.OidcTokenResponse;
import com.tm.core.web.internal.dto.RefreshRequest;
import com.tm.core.web.internal.dto.RefreshResponse;
import com.tm.core.web.internal.dto.RegisterRequest;
import com.tm.core.web.internal.dto.RegisterResponse;
import com.tm.core.web.internal.dto.ValidateRequest;
import com.tm.core.web.internal.dto.ValidateResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;

/**
 * Core authentication service for the internal API.
 * Handles registration, local login, OIDC exchange, MFA validation, and token refresh.
 * See AUTH_CONFIG.md §§5–11 and API_CONTRACT.md §Internal API.
 */
@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserRepository userRepository;
    private final TenantRepository tenantRepository;
    private final RoleRepository roleRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final MfaService mfaService;
    private final PasswordValidationService passwordValidationService;

    @Value("${app.password.age-warning-days}")
    private long ageWarningDays;

    @Value("${app.oauth2.dev-issuer:}")
    private String devIssuer;

    // -------------------------------------------------------------------------
    // LOCAL REGISTRATION
    // -------------------------------------------------------------------------

    @Transactional
    public RegisterResponse register(RegisterRequest request) {
        UUID tenantId = parseUuid(request.tenantId(), "tenantId");
        if (!tenantRepository.existsById(tenantId)) {
            throw new BadCredentialsException("Tenant not found");
        }
        if (userRepository.existsByTenantIdAndEmail(tenantId, request.email())) {
            throw new ConflictException("Email already registered in this tenant");
        }
        passwordValidationService.validate(request.password());

        Role standardRole = findStandardRole();

        User user = new User();
        user.setTenantId(tenantId);
        user.setEmail(request.email());
        user.setPasswordHash(passwordEncoder.encode(request.password()));
        user.setAuthProvider(AuthProvider.LOCAL);
        user.setPasswordChangedAt(Instant.now());
        user.setRole(standardRole);
        user = userRepository.save(user);

        String token = jwtService.sign(user.getId(), user.getEmail(),
                user.getRole().getName(), user.getTenantId());
        return new RegisterResponse(token, user.getId(), user.getTenantId(),
                user.getRole().getName());
    }

    // -------------------------------------------------------------------------
    // LOCAL LOGIN
    // -------------------------------------------------------------------------

    @Transactional
    public ValidateResponse validateCredentials(ValidateRequest request) {
        UUID tenantId = parseUuid(request.tenantId(), "tenantId");
        User user = userRepository.findByTenantIdAndEmail(tenantId, request.email())
                .orElseThrow(() -> new BadCredentialsException("Invalid credentials"));

        if (user.getAuthProvider() != AuthProvider.LOCAL) {
            // Return the same message as a bad password — prevents both email enumeration
            // and auth-provider disclosure (an attacker must not learn which IdP an email uses).
            throw new BadCredentialsException("Invalid credentials");
        }
        if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            throw new BadCredentialsException("Invalid credentials");
        }

        user.setLastLoginAt(Instant.now());
        userRepository.save(user);

        boolean mfaRequired = user.isMfaEnabled();
        boolean passwordWarning = isPasswordStale(user);

        // If MFA is required, do not issue the JWT yet — return mfaRequired=true.
        String token = mfaRequired ? null
                : jwtService.sign(user.getId(), user.getEmail(),
                        user.getRole().getName(), user.getTenantId());

        return new ValidateResponse(token, user.getId(), user.getTenantId(),
                user.getRole().getName(), mfaRequired, passwordWarning);
    }

    // -------------------------------------------------------------------------
    // OIDC TOKEN EXCHANGE
    // -------------------------------------------------------------------------

    @Transactional
    public OidcTokenResponse exchangeOidcToken(OidcTokenRequest request) {
        UUID tenantId = parseUuid(request.tenantId(), "tenantId");
        AuthProvider provider = resolveProvider(request.iss());

        // Try to find existing user by external ID, then by email.
        Optional<User> byExtId = userRepository.findByTenantIdAndExternalUserId(
                tenantId, request.sub());

        User user;
        if (byExtId.isPresent()) {
            user = byExtId.get();
        } else {
            Optional<User> byEmail = userRepository.findByTenantIdAndEmail(
                    tenantId, request.email());
            if (byEmail.isPresent()) {
                User existing = byEmail.get();
                if (existing.getAuthProvider() != provider) {
                    throw new ConflictException(
                            "Email is registered with " + existing.getAuthProvider().name()
                            + " login. Please use that sign-in method.");
                }
                user = existing;
            } else {
                // First OAuth2 login — create the user.
                String displayName = (request.name() != null && !request.name().isBlank())
                        ? request.name()
                        : request.email().split("@")[0];

                user = new User();
                user.setTenantId(tenantId);
                user.setEmail(request.email());
                user.setDisplayName(displayName);
                user.setAuthProvider(provider);
                user.setExternalUserId(request.sub());
                user.setRole(findStandardRole());
            }
        }

        user.setLastLoginAt(Instant.now());
        user = userRepository.save(user);

        boolean mfaRequired = user.isMfaEnabled();
        String token = mfaRequired ? null
                : jwtService.sign(user.getId(), user.getEmail(),
                        user.getRole().getName(), user.getTenantId());

        return new OidcTokenResponse(token, user.getId(), user.getTenantId(),
                user.getRole().getName(), mfaRequired);
    }

    // -------------------------------------------------------------------------
    // MFA VALIDATION
    // -------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public MfaValidateResponse validateMfa(MfaValidateRequest request) {
        User user = userRepository.findById(parseUuid(request.userId(), "userId"))
                .orElseThrow(() -> new BadCredentialsException("Invalid MFA request"));

        if (!user.isMfaEnabled() || user.getMfaSecret() == null) {
            throw new BadCredentialsException("MFA not configured for this user");
        }
        if (!mfaService.verifyCode(user.getMfaSecret(), request.code())) {
            throw new BadCredentialsException("Invalid MFA code");
        }

        String token = jwtService.sign(user.getId(), user.getEmail(),
                user.getRole().getName(), user.getTenantId());
        return new MfaValidateResponse(token, user.getTenantId(),
                user.getRole().getName(), isPasswordStale(user));
    }

    // -------------------------------------------------------------------------
    // TOKEN REFRESH
    // -------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public RefreshResponse refreshToken(RefreshRequest request) {
        User user = userRepository.findById(parseUuid(request.userId(), "userId"))
                .orElseThrow(() -> new BadCredentialsException("User not found"));

        String token = jwtService.sign(user.getId(), user.getEmail(),
                user.getRole().getName(), user.getTenantId());
        return new RefreshResponse(token);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private boolean isPasswordStale(User user) {
        if (user.getPasswordChangedAt() == null) {
            return false;
        }
        long daysOld = ChronoUnit.DAYS.between(user.getPasswordChangedAt(), Instant.now());
        return daysOld >= ageWarningDays;
    }

    private Role findStandardRole() {
        return roleRepository.findByName("STANDARD")
                .orElseThrow(() -> new IllegalStateException(
                        "STANDARD role not found — ensure seed migration has run"));
    }

    // Exact Google issuer and prefix-based Microsoft issuer validation.
    // Prefix matching covers all Microsoft tenant IDs and personal-accounts URL.
    // Using exact/prefix match (not contains) prevents spoofing via crafted issuer strings.
    private static final String GOOGLE_ISSUER = "https://accounts.google.com";
    private static final String MS_ISSUER_PREFIX = "https://login.microsoftonline.com/";
    private static final String MS_STS_PREFIX   = "https://sts.windows.net/";

    private AuthProvider resolveProvider(String iss) {
        if (GOOGLE_ISSUER.equals(iss)) {
            return AuthProvider.GOOGLE;
        }
        if (iss != null && (iss.startsWith(MS_ISSUER_PREFIX) || iss.startsWith(MS_STS_PREFIX))) {
            return AuthProvider.MICROSOFT;
        }
        // Dev-only: accept mock-oauth2 issuer when APP_OAUTH2_DEV_ISSUER is configured.
        // Never set devIssuer in production — empty string disables this check.
        if (!devIssuer.isBlank() && iss != null && iss.startsWith(devIssuer)) {
            return AuthProvider.GOOGLE;
        }
        throw new ConflictException("Unknown OIDC issuer");
    }

    /** Parses a UUID string, returning HTTP 400 (via BadCredentialsException) on bad format. */
    private UUID parseUuid(String value, String fieldName) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            throw new BadCredentialsException("Invalid " + fieldName + " format");
        }
    }
}