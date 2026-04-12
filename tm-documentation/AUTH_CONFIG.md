# Authentication & Security Configuration

Two auth methods coexist: **OAuth2/OIDC** (Google, Microsoft personal accounts) and **local** (email + password). Both produce the same result: an app-issued JWT stored in the Redis-backed BFF session. Core API validates only these app JWTs — it never receives external IdP tokens.

---

## 1. App-Issued JWT (unified token model)

| Property | Value |
| :--- | :--- |
| Algorithm | HMAC-SHA256 |
| Signing key | `INTERNAL_JWT_SECRET` (min 32 bytes, Base64) |
| Expiry | `JWT_EXPIRY_MINUTES` (default 15) |
| Claims | `sub` (userId), `email`, `role`, `tenantId`, `iss` (app domain) |
| Refresh | BFF refreshes transparently within 2 min of expiry via `POST /internal/auth/refresh` (`{ userId }`) |

---

## 2. OAuth2/OIDC — Google
- Issuer: `https://accounts.google.com`
- Scopes: `openid`, `email`, `profile`
- All personal Google (Gmail) accounts supported.
- Setup: Google Cloud Console → OAuth 2.0 credentials → add redirect URIs.

## 3. OAuth2/OIDC — Microsoft Personal Accounts
Configure all four endpoints explicitly using the `consumers` tenant. Do **not** set a org-specific tenant UUID — that blocks personal Outlook/Hotmail/Live accounts.

```yaml
spring.security.oauth2.client.registration.microsoft:
  client-id: ${MICROSOFT_CLIENT_ID}
  client-secret: ${MICROSOFT_CLIENT_SECRET}
  scope: openid,email,profile,offline_access
spring.security.oauth2.client.provider.microsoft:
  authorization-uri: https://login.microsoftonline.com/consumers/v2.0/oauth2/v2.0/authorize
  token-uri:         https://login.microsoftonline.com/consumers/v2.0/oauth2/v2.0/token
  jwk-set-uri:       https://login.microsoftonline.com/consumers/v2.0/discovery/v2.0/keys
  user-info-uri:     https://graph.microsoft.com/oidc/userinfo
  user-name-attribute: sub
```
Setup: Azure App Registration → "Personal Microsoft accounts only".

## 4. Redirect URIs

| Environment | URI |
| :--- | :--- |
| Local dev | `http://localhost:8080/login/oauth2/code/{provider}` |
| Production | `https://{domain}/login/oauth2/code/{provider}` |

Register in the IdP application configuration.

---

## 5. OIDC Claims Mapping

| Claim | Field | Notes |
| :--- | :--- | :--- |
| `sub` | `users.external_user_id` | |
| `email` | `users.email` | |
| `name` | `users.display_name` | Falls back to email prefix |
| `iss` | Determines `auth_provider` | `accounts.google.com` → `GOOGLE`; Microsoft → `MICROSOFT` |

On first OAuth2 login: BFF calls `/internal/auth/token`. Core API creates user (`auth_provider = GOOGLE/MICROSOFT`, `role = STANDARD`) if no match. If same email exists for a different provider, returns `409` with a message directing to the correct login method. On every OAuth2 login (new or returning), Core API updates `last_login_at = now()` within the same transaction.

---

## 6. Local Authentication

### Registration
`POST /auth/local/register` → BFF → `POST /internal/auth/register`
Core API validates email uniqueness, BCrypt-hashes password (cost 12), sets `auth_provider = LOCAL`, `password_changed_at = now()`, returns app JWT.

### Login
`POST /auth/local/login` → BFF → `POST /internal/auth/validate`
Core API validates BCrypt hash. Returns `401` on wrong credentials or if email belongs to an OAuth2 provider (response includes the correct provider name). On success, updates `last_login_at = now()` within the same transaction, then returns app JWT + `passwordWarning: true` if `now() - password_changed_at >= PASSWORD_AGE_WARNING_DAYS`.

---

## 7. MFA (TOTP) — all users

### Enrollment (user already logged in)
1. `POST /api/v1/users/me/mfa/enable` → Core API generates TOTP secret (AES-256 encrypted in `users.mfa_secret`), returns secret + `otpauth://` QR URI.
2. User scans with any Authenticator app.
3. `POST /api/v1/users/me/mfa/verify` → Core API confirms code, sets `is_mfa_enabled = TRUE`.

### Login (MFA active)
1. Primary auth succeeds. BFF sets `mfa_pending = true`. No app JWT issued yet.
2. BFF redirects to `/mfa/verify` (React route).
3. User submits TOTP → BFF `POST /auth/mfa/verify` → BFF calls `POST /internal/auth/mfa/validate` (userId from partial session + code).
4. Core API validates, returns app JWT. BFF completes session.

---

## 8. Session Configuration
- Backend: `spring-session-data-redis`.
- Timeout: `SESSION_TIMEOUT_MINUTES` (default 30, sliding).
- Cookie: `TM_SESSION`, `HttpOnly`, `Secure` (prod), `SameSite=Lax`.

---

## 9. Password Reset (Local users only)
See `PASSWORD_POLICY.md §4`. BFF calls `/internal/auth/forgot-password` → Core API generates SHA-256-hashed token, stores in `password_reset_tokens`, sends email via Spring Mail. Reset link valid for `PASSWORD_RESET_TOKEN_EXPIRY_HOURS` (default 1h), single-use.

---

## 10. Password Age Warning (Local users only)
At login, if `now() - password_changed_at >= PASSWORD_AGE_WARNING_DAYS` (default 80), the login response includes `passwordWarning: true`. BFF stores in session; React shows a non-blocking banner. See `PASSWORD_POLICY.md §3`.

---

## 11. Internal Endpoints (BFF → Core API only)

| Method | Path | Description |
| :--- | :--- | :--- |
| `POST` | `/internal/auth/token` | Exchange OIDC claims for app JWT |
| `POST` | `/internal/auth/register` | Create LOCAL user, return app JWT |
| `POST` | `/internal/auth/validate` | Validate LOCAL credentials, return app JWT |
| `POST` | `/internal/auth/mfa/validate` | Validate TOTP during login (pre-JWT), return app JWT |
| `POST` | `/internal/auth/refresh` | Accept `{ userId }`, return a fresh app JWT |
| `POST` | `/internal/auth/forgot-password` | Generate reset token, trigger email |
| `POST` | `/internal/auth/reset-password` | Validate token, update password |

---

## 12. Dev / Test Setup
- **OAuth2:** `mock-oauth2-server` in `docker-compose.override.yml`. No real IdP credentials needed.
- **Email:** Mailpit (`axllent/mailpit`) captures all emails at `http://localhost:8025`.
- **Integration tests:** WireMock stubs all outbound HTTP.

---

## 13. Security Hardening

### HTTPS
Production must run behind TLS termination (Kubernetes Ingress or cloud LB). The BFF does not terminate TLS.

### Security Headers
Spring Security default `headers()` provides `X-Content-Type-Options`, `X-Frame-Options: DENY`. Add in production:
- `Content-Security-Policy: default-src 'self'`
- `Strict-Transport-Security: max-age=31536000; includeSubDomains`

### CSRF
`CookieCsrfTokenRepository.withHttpOnlyFalse()` — React reads the `XSRF-TOKEN` cookie and sends it as `X-XSRF-TOKEN` on state-changing requests.

### Rate Limiting
Via `bucket4j-core` (Apache 2.0 — **core module only, not the enterprise module**):

| Endpoint | Limit | Mechanism |
| :--- | :--- | :--- |
| `POST /auth/local/login` | 5 req / min per IP | `RateLimitInterceptor` |
| `POST /auth/forgot-password` | 3 req / 15 min per email | `RateLimitInterceptor` |
| All `/auth/**` | 20 req / min per IP | `RateLimitInterceptor` |
| `POST /auth/mfa/verify` | 5 failed attempts per session → session invalidated | Controller (session-scoped) |

Returns `HTTP 429` on rate-limit breach. MFA lockout returns `HTTP 401` with `X-MFA-Lockout: true`.

### Input Validation
Bean Validation (`@Valid`, `@NotBlank`, `@Size`, `@Email`) on all DTOs. Fails with `HTTP 422`. Validated at controller boundary only.

### Internal Network Isolation
`/internal/**` endpoints only reachable from the internal Docker/Kubernetes network. In Kubernetes a `NetworkPolicy` denies external traffic to Core API.

### BFF CORS
`CorsConfig.java` (dev profile only) allows `http://localhost:5173`. No CORS in production.

For the full SQL injection and OWASP API Security Top 10 threat model with implementation detail, see `API_SECURITY.md`.