# API Security

Systematic coverage of SQL injection prevention and OWASP API Security Top 10 (2023). Each threat states how this application addresses it and where the implementation pattern lives.

---

## SQL Injection Prevention

### Rule: never concatenate user input into queries

Spring Data JPA and JPQL use parameterised queries when written correctly. The following rules apply to all repository and service code in `tm-core-api`.

| Pattern | Safe? | Example |
| :--- | :--- | :--- |
| Spring Data derived queries | ✅ Always safe | `findByIdAndTenantId(UUID id, UUID tenantId)` |
| `@Query` with named parameters | ✅ Safe | `WHERE t.id = :id AND t.tenantId = :tenantId` |
| `@Query` with positional parameters | ✅ Safe | `WHERE t.id = ?1 AND t.tenantId = ?2` |
| `@Query` with string concatenation | ❌ Never | `"WHERE t.title = '" + input + "'"` |
| Native SQL with `?1` / `:name` | ✅ Safe | `nativeQuery=true` + named/positional params |
| Native SQL with string concat | ❌ Never | Treat as critically vulnerable |
| JPA Criteria API | ✅ Always safe | Parameterised by design |

### Scheduled job queries
The two `@Scheduled` jobs (`markOverdueTasks`, `cleanExpiredResetTokens`) use JPQL with no user-controlled input — they compare against `CURRENT_DATE` and `now()`. No parameterisation required, but the zero-user-input rule must hold if these queries are ever extended.

### Search and filtering
If free-text search is added to task queries, use Spring Data JPA `Specification` with `CriteriaBuilder.parameter()` — never string interpolation into JPQL/HQL.

---

## OWASP API Security Top 10 (2023)

### API1 — Broken Object Level Authorization (BOLA)

**Threat:** User A reads or modifies a resource owned by User B by guessing its ID.

**How addressed:**
1. **Tenant filter:** Hibernate `@FilterDef("tenantFilter")` ensures all queries are scoped to the active `tenant_id`. No query returns rows from another tenant.
2. **User-scoped repository methods:** Tasks belong to a specific user within a tenant. All task repository methods must include `userId` in addition to `tenantId`:
   ```
   findByIdAndTenantIdAndUserId(UUID id, UUID tenantId, UUID userId)
   ```
   The currently-authenticated user's `userId` is read from the validated JWT — never from the request body or query param.
3. **404, not 403:** Cross-tenant and cross-user access returns `404` to prevent enumeration. See `08-MULTI-TENANCY.md §7`.

**Admin endpoints:** `GET /api/v1/admin/**` intentionally disable the tenant filter. These are guarded by `@PreAuthorize("hasRole('ADMIN')")`.

---

### API2 — Broken Authentication

**Threat:** Weak credentials, JWT vulnerabilities, session hijacking.

**How addressed:**
- BCrypt cost 12 for passwords. See `11-PASSWORD-POLICY.md §5`.
- HMAC-SHA256 for JWTs with a minimum 32-byte key (`INTERNAL_JWT_SECRET`).
- **Algorithm pinning:** Core API's JWT validator must explicitly specify `HS256` and reject any other algorithm, including `none`. See `09-CODING-PATTERNS.md §15`.
- **Full claims validation:** Validate `iss` (must match `APP_BASE_URL`), `exp` (must be in the future), and `sub` (must resolve to an existing user). Signature alone is insufficient.
- TOTP/MFA as a second factor. See `07-AUTH-CONFIG.md §7`.
- Rate limiting on login and MFA verify. See rate limiting table below.
- Session cookie: `HttpOnly`, `Secure` (prod), `SameSite=Lax`. No JWT in browser storage.
- Session fixation: Spring Security migrates the session on successful authentication by default (`sessionFixation().migrateSession()`). Explicit logout invalidates the session (`invalidateHttpSession(true)`).

---

### API3 — Broken Object Property Level Authorization

**Threat:** Mass assignment — client sends read-only or privileged fields that get persisted.

**How addressed:**
Two distinct DTO types per resource — one for input, one for output:

| DTO type | Rule |
| :--- | :--- |
| Request DTO (input) | Contains only fields the client is allowed to set. Never contains `id`, `tenantId`, `userId`, `createdAt`, `updatedAt`, `authProvider`, `role`. |
| Response DTO (output) | Contains only fields safe to expose. Never contains `passwordHash`, `mfaSecret`. |

Annotate request DTOs with `@JsonIgnoreProperties(ignoreUnknown = true)` to silently drop extra fields. Annotate sensitive entity fields with `@JsonProperty(access = Access.WRITE_ONLY)` and `@ToString.Exclude` (Lombok) to prevent accidental serialisation.

See `09-CODING-PATTERNS.md §14` for the DTO pattern.

---

### API4 — Unrestricted Resource Consumption

**Threat:** DoS via large payloads, unbounded queries, or exhausted rate limits.

**How addressed:**

| Control | Value | Where configured |
| :--- | :--- | :--- |
| Pagination max | 100 items per page | Enforced in service layer; `@Max(100)` on `limit` param |
| Request body size | 256 KB | `server.tomcat.max-http-form-post-size` + `spring.servlet.multipart.max-request-size` in `application.yml` |
| Rate limiting — login | 5 req / min per IP | `09-CODING-PATTERNS.md §6` |
| Rate limiting — forgot-password | 3 req / 15 min per email | `09-CODING-PATTERNS.md §6` |
| Rate limiting — all `/auth/**` | 20 req / min per IP | `09-CODING-PATTERNS.md §6` |
| Rate limiting — MFA verify | 5 attempts per session | `09-CODING-PATTERNS.md §17` |
| DB connection pool | max 10 connections | `spring.datasource.hikari.maximum-pool-size` |

---

### API5 — Broken Function Level Authorization

**Threat:** A standard user calls an admin-only endpoint.

**How addressed:**
- `@PreAuthorize("hasRole('ADMIN')")` on all `@RequestMapping` methods in admin controllers.
- `/internal/**` endpoints are unreachable from outside the Docker/Kubernetes internal network (enforced at infrastructure level — `NetworkPolicy` in Kubernetes, bridge network in compose). Spring Security additionally denies any request to `/internal/**` that arrives at the BFF.
- Role is read exclusively from the validated app JWT — never from a request header or body.

---

### API6 — Unrestricted Access to Sensitive Business Flows

**Threat:** Abuse of business logic — brute-force MFA, spam password reset emails.

**How addressed:**

| Flow | Protection |
| :--- | :--- |
| Login | 5 attempts / min per IP → `429` |
| Forgot-password | 3 requests / 15 min per email → `429`; always returns `200` (no enumeration) |
| MFA verify | 5 failed attempts per session → session invalidated, user must re-authenticate from scratch |
| Password reset submit | Token is single-use and expires in 1h; no brute force possible (SHA-256 hash, 32-byte token = 2²⁵⁶ space) |
| Registration | 20 req / min per IP (covered by all-`/auth/**` limit) |

The MFA session lockout (5 failures → invalidate session) is implemented in the `/auth/mfa/verify` controller, not in the rate-limit interceptor, because it must track per-session state rather than per-IP. See `09-CODING-PATTERNS.md §17`.

---

### API7 — Server Side Request Forgery (SSRF)

**Threat:** Server fetches a URL provided by the user, accessing internal services.

**Status: Not applicable.** The application never fetches a URL derived from user input. The BFF calls `CORE_API_BASE_URL` (env var, operator-controlled). Core API calls `SMTP_HOST` (env var, operator-controlled). No user-supplied URL is ever fetched.

---

### API8 — Security Misconfiguration

**Threat:** Verbose error messages, exposed debug endpoints, unsafe defaults.

**How addressed:**

| Misconfiguration | Control |
| :--- | :--- |
| Stack traces in error responses | `server.error.include-stacktrace: never` in `application.yml` (all profiles) |
| Exception messages in error responses | `server.error.include-message: never` in prod profile |
| SQL queries in logs | `spring.jpa.show-sql: false` in prod profile |
| Actuator endpoints | Only `health` and `info` exposed externally |
| Internal Actuator endpoints | Restricted to internal network |
| `X-Powered-By` header | Spring Boot does not emit this by default |
| HTTPS in production | TLS at ingress/LB layer — documented in `07-AUTH-CONFIG.md §13` |
| OAuth2 client IDs default | Set to `:disabled` — BFF starts cleanly without crashing on missing env vars (see `09-CODING-PATTERNS.md §13`) |

---

### API9 — Improper Inventory Management

**Threat:** Undocumented or stale API endpoints still accessible in production.

**How addressed:**
- `tm-core-api/api-spec/openapi.yaml` is the single source of truth. Interfaces are generated from it — no controller can exist that is not in the spec.
- `/internal/**` endpoints are deliberately excluded from the public OpenAPI spec. They are documented in `06-API-CONTRACT.md` only.
- API versioned under `/api/v1/`. New major versions get a new prefix (`/api/v2/`); old versions are deprecated via response headers before removal.
- CI pipeline validates the OpenAPI spec on every push.

---

### API10 — Unsafe Consumption of APIs

**Threat:** BFF trusts Core API responses blindly; Core API trusts external IdP tokens blindly.

**How addressed:**
- BFF validates the `Content-Type` of Core API responses before deserialising.
- BFF uses Spring's `RestClient` with explicit response type — malformed responses throw a deserialisation exception, not a silent data corruption.
- Core API validates OIDC tokens via Spring Security's OIDC support, which verifies the IdP's JWK signature, `iss`, `aud`, `exp`, and `sub` before accepting the token.
- Core API validates its own app JWTs with algorithm pinning (see API2 above). It does not accept JWTs issued by external IdPs.

---

## Additional Controls Not Covered by the Top 10

### Open Redirect
After OAuth2 login, Spring Security may redirect to a saved request URL. The saved URL must be validated against an allowlist to prevent `?redirect=https://evil.com`. Configure `SimpleUrlAuthenticationSuccessHandler` to only redirect to same-origin paths.

```java
// In CustomOAuth2SuccessHandler:
private String sanitiseRedirectTarget(String target) {
    if (target == null || target.startsWith("http")) return "/";
    return target;  // only allow relative paths
}
```

### Sensitive Data in Logs
- Never log request bodies for `/auth/**` and `/internal/auth/**` at any log level.
- Annotate sensitive entity fields with `@ToString.Exclude` (Lombok) to prevent accidental inclusion in log lines.
- Annotate sensitive DTO fields with `@JsonProperty(access = Access.WRITE_ONLY)`.
- MDC is cleared in `TenantInterceptor.afterCompletion()` to prevent tenant/user context leaking across requests. See `13-OBSERVABILITY.md §1`.

### Email Enumeration on Registration

> **Design decision:** `POST /auth/local/register` returns `409` on duplicate email. This leaks that the email address is already registered.
>
> **Accepted trade-off:** For a task management application, the UX value of a clear "email already exists" error outweighs the marginal security risk. Suppressing to `200` ("check your email") causes user confusion and support overhead. This trade-off is standard practice in the vast majority of web applications (GitHub, Google, etc.).
>
> **Mitigation in place:** `POST /auth/forgot-password` always returns `200` regardless of email existence — the higher-value enumeration target is protected.

### Request Smuggling / Header Injection
The BFF proxy must strip hop-by-hop headers and must not forward the `X-Tenant-ID` header from external clients. The proxy reads `tenantId` exclusively from the session. See `09-CODING-PATTERNS.md §5`.

### Dependency Vulnerabilities
Automated scanning in every CI pipeline run. See `15-REPOSITORIES-AND-CICD.md §2` (Stage 2: checksum-verified Grype dependency scans + `npm audit`; Stage 4: Syft SBOM + Grype image scan).
