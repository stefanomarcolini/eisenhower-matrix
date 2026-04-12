# Implementation Roadmap

## Rules (read before starting)

- **Read `CODING_PATTERNS.md` and `API_SECURITY.md` before writing any code.** Every hard implementation decision is already resolved there.
- **Compile gate:** each session must produce code that compiles and its tests pass before the next session begins. Never carry a broken build forward.
- **Test alongside, not after:** write the integration test for each feature in the same session as the feature. Do not batch tests at the end.
- **Document scoping:** each session references only the documents listed for that session. Loading all 20 docs into context at once is wasteful and produces worse output.

---

## Phase 1 — Foundation (no application code; everything else depends on this)

### Session 1 · OpenAPI Contract
**Docs:** `API_CONTRACT.md`, `PROJECT_OVERVIEW.md §3`
**Deliverable:** `tm-core-api/api-spec/openapi.yaml` — all schemas for Task, User, Auth (public), and Admin endpoints.
**Validation:** run `mvn openapi-generator:generate` and confirm it produces clean Java interfaces with no errors before writing any service code. Fix spec errors here, not after services are built around it.

### Session 2 · Environment Files
**Docs:** `ENV_VARS.md`
**Deliverable:** `.env.example` in `tm-orchestrator`, `tm-core-api`, `tm-ui-bff`, `tm-db-schema`. All variable names, placeholder values, and inline comments. No application code depends on these being perfect, but they must exist before any `docker-compose up`.

### Session 3 · Database Migrations
**Docs:** `DATABASE_SCHEMA.md`, `CODING_PATTERNS.md §12`
**Deliverable:** `tm-db-schema` — all 8 Liquibase changesets (`001`–`008`), `db.changelog-master.yaml`, `pom.xml` (Liquibase Maven plugin + Testcontainers), `Dockerfile`.
**Validation:** `docker build -t tm-db-schema:local . && docker run --rm -e ... tm-db-schema:local` against a local Postgres. Migrations must apply and `liquibase rollbackCount 1` must succeed for each changeset. Do not proceed until this passes.

---

## Phase 2 — Core API

### Session 4 · Entities, Repositories, JPA Config
**Docs:** `DATABASE_SCHEMA.md`, `MULTI_TENANCY.md`, `CODING_PATTERNS.md §4, §7, §8, §20`
**Deliverable:** `pom.xml`; all JPA `@Entity` classes (`Tenant`, `Role`, `User`, `Task`, `TaskHistory`, `PasswordResetToken`); Spring Data repositories with correct scoped naming (e.g. `findByIdAndTenantIdAndUserId`); `TenantInterceptor`; `TenantContext` (ThreadLocal); `@FilterDef` on entities; profile-aware `PasswordEncoder` bean; `application.yml` stubs.
**Test:** repository-layer `@DataJpaTest` — verify tenant filter prevents cross-tenant row access. This is the most important test in the project.

### Session 5 · Auth Layer (internal endpoints)
**Docs:** `AUTH_CONFIG.md`, `API_CONTRACT.md §Internal API`, `CODING_PATTERNS.md §15`, `PASSWORD_POLICY.md`
**Deliverable:** `JwtService` (sign + validate, HS256, `NimbusJwtDecoder`); `/internal/auth/token`, `/internal/auth/register`, `/internal/auth/validate`, `/internal/auth/mfa/validate`, `/internal/auth/refresh`; `MfaService` (TOTP via `dev.samstevens.totp`); `PasswordResetService`; `EmailService` (Spring Mail); `JwtSecurityConfig` (single chain, STATELESS, CSRF disabled, `/internal/**` permitAll).
**Test:** `@SpringBootTest` + Testcontainers (Postgres) + WireMock (SMTP). Cover: local register, local login, MFA enroll + verify, forgot-password + reset flow, password age warning flag.

### Session 6 · Business Logic (tasks, users, admin)
**Docs:** `API_CONTRACT.md §Tasks §Users §Admin`, `PROJECT_OVERVIEW.md §3`, `CODING_PATTERNS.md §1, §14, §16, §20`
**Deliverable:** `TasksApiDelegateImpl`, `UsersApiDelegateImpl`, `AdminApiDelegateImpl`; `TaskService` (CRUD + state machine transitions; DELETE is a soft delete setting `deleted_at = now()`; every state transition writes a `TaskHistory` row); `TaskHistoryService` (write-only in v1 — records `changed_by`, `from_state`, `to_state`); `ScheduledJobService` (overdue updater — must filter `AND deleted_at IS NULL` and write `TaskHistory` rows for each transitioned task; token cleanup; tenant filter disabled for both); `GlobalExceptionHandler` (RFC 7807, no stack traces).
**Test:** MockMvc tests for all task state transitions (including illegal transitions returning `422`); `DELETE` returns `204` and the task is absent from subsequent `GET /api/v1/tasks` (soft-delete verified); a `task_history` row is written on every state change; cross-tenant access returning `404`; admin role guard returning `403`.

---

## Phase 3 — BFF

> Start Phase 3 only after all Phase 2 integration tests pass. The WireMock stubs for BFF tests must accurately mirror proven Core API behavior.

### Session 7 · BFF Foundation
**Docs:** `CODING_PATTERNS.md §2, §9, §13`, `ENV_VARS.md §BFF`, `AUTH_CONFIG.md §8`
**Deliverable:** `pom.xml`; `application.yml` stubs; `RedisSessionConfig` (Jackson serialization, timeout from env var); `OAuth2SecurityConfig` skeleton (permit-all first — security hardening in Session 8); `SessionKeys` constants; `CorsConfig` (`@Profile("dev")`).
**Validation:** BFF starts up, connects to Redis (Testcontainers), and `GET /actuator/health` returns 200. Verify before adding auth complexity.

### Session 8 · BFF Auth Controllers
**Docs:** `AUTH_CONFIG.md §2–§10`, `CODING_PATTERNS.md §3, §6, §17`, `API_SECURITY.md §API2, §API6`
**Deliverable:** `CustomOAuth2SuccessHandler`; `LocalLoginFilter`; `MfaPendingAuthorizationManager`; `/auth/local/register`, `/auth/local/login`, `/auth/forgot-password`, `/auth/reset-password`, `/auth/mfa/verify`, `/auth/session` controllers; `RateLimitInterceptor`; MFA lockout in verify controller; full `OAuth2SecurityConfig` (replace skeleton); `CSRF` configuration.
**Test:** WireMock stubs Core API internal endpoints. Cover: local login → session created; MFA pending flow; 5 failed MFA attempts → session invalidated; rate limit triggers 429; CSRF token present in response cookie.

### Session 9 · BFF Proxy + JWT Refresh
**Docs:** `CODING_PATTERNS.md §5, §6`, `API_SECURITY.md §Request Smuggling`, `MULTI_TENANCY.md §3`
**Deliverable:** `ProxyController` (`/api/**` → Core API); `JwtRefreshService` (refresh within 2 min of expiry); `extractPath` helper (URI + query string); header allowlist (forward only `Content-Type`, `Accept`, `X-Request-ID`; never forward `Cookie`, `Host`, client `Authorization`).
**Test:** WireMock stubs Core API task endpoints. Verify: tenant ID injected from session (not from client header); JWT refreshed transparently when near expiry; hop-by-hop headers stripped.

---

## Phase 4 — Frontend

> Start Phase 4 only after BFF integration tests pass. Generate TypeScript types from `openapi.yaml` before writing any component.

### Session 10 · Scaffold + Auth Shell
**Docs:** `CODING_PATTERNS.md §10`, `AUTH_CONFIG.md §6–§10`
**Deliverable:** Vite + React 18 + TypeScript scaffold; `tailwind.config.ts` (dark mode: class); React Router v6 routes (all paths from `CODING_PATTERNS.md §10`); TanStack Query provider + `useSession` hook (`GET /auth/session`); `ProtectedRoute` component; `axios` instance with CSRF interceptor (reads `XSRF-TOKEN` cookie, sets `X-XSRF-TOKEN` header on mutating requests).
**Validation:** `npm run dev` works, unauthenticated routes redirect to `/login`.

### Session 11 · Auth Pages
**Docs:** `AUTH_CONFIG.md §6–§10`, `PASSWORD_POLICY.md §1`, `CODING_PATTERNS.md §10`
**Deliverable:** Login page (local + OAuth2 buttons); registration form (`@zxcvbn-ts/zxcvbn` strength indicator); MFA verify page; forgot-password and reset-password pages.
**Test:** vitest unit tests for password strength scoring display; form validation.

### Session 12 · Task Matrix + CRUD
**Docs:** `API_CONTRACT.md §Tasks`, `PROJECT_OVERVIEW.md §3`
**Deliverable:** 3×3 matrix grid component (Importance × Urgency, axis-switching, sort-order toggle); task create/edit/delete dialogs (React Hook Form); state transition UI; paginated task list fallback.

### Session 13 · Settings + Polish
**Docs:** `API_CONTRACT.md §Users`, `PASSWORD_POLICY.md §2–§3`, `AUTH_CONFIG.md §7`
**Deliverable:** Profile settings; change-password form (strength indicator); MFA enable/disable flow (QR code + verify); light/dark mode toggle (persisted via API); password age warning banner (`passwordWarning` from `GET /auth/session`, dismissible per session).

---

## Phase 5 — Infrastructure + Delivery

### Session 14 · Dockerfiles + Compose
**Docs:** `INFRASTRUCTURE_SPEC.md`, `CODING_PATTERNS.md §11`, `DEVELOPMENT_ENV.md`
**Deliverable:** `tm-core-api/Dockerfile`; `tm-db-schema/Dockerfile`; `tm-ui-bff/Dockerfile` (multi-stage, layer-cached); `docker-compose.yml` (production services); `docker-compose.override.yml` (mailpit, mock-oauth2, debug ports).
**Validation:** `docker-compose up -d` from `tm-orchestrator`; `GET /actuator/health` returns 200 on both services; Mailpit UI accessible at `:8025`; mock-oauth2 at `:9000`.

### Session 15 · CI/CD Pipelines
**Docs:** `REPOSITORIES_AND_CICD.md`
**Deliverable:** `.github/workflows/pipeline.yml` for `tm-core-api`, `tm-ui-bff`, `tm-db-schema`; `e2e.yml` and `release.yml` for `tm-orchestrator`. Each code pipeline: build → vulnerability scan (`grype dir` / `npm audit`) → Syft SBOM + Grype image scan (checksum-verified binaries) → Docker push. Orchestrator E2E runs tokenlessly via manual trigger and `main` push.

### Session 16 · E2E Tests
**Docs:** `API_CONTRACT.md`, `AUTH_CONFIG.md §6–§7`
**Deliverable:** Selenium suite in `tm-orchestrator/e2e/` using `selenium/standalone-chrome` container. Required scenarios:

| Scenario | Covers |
| :--- | :--- |
| Local register → login → create task → verify in matrix | Happy path, full stack |
| Login with mock OAuth2 (Google) | OAuth2 flow |
| MFA enable → logout → login with TOTP | MFA full flow |
| Forgot password → reset via Mailpit link → login | Password reset full flow |
| Attempt cross-user task access → expect 404 | BOLA prevention |
| Admin promotes user → user accesses admin panel | Role elevation |
| Create task → delete task → verify absent from list and matrix | Soft-delete end-to-end |

---

## Dependency Graph (what blocks what)

```
Session 1 (OpenAPI)
  └── Session 4 (Entities) ──┐
Session 2 (Env files)        │
Session 3 (DB migrations)    ├── Session 5 (Auth layer)
  └── validates DB schema     │     └── Session 6 (Business logic)
                              │           └── Session 7 (BFF foundation)
                              │                 └── Session 8 (BFF auth)
                              │                       └── Session 9 (BFF proxy)
                              │                             └── Session 10 (Frontend scaffold)
                              │                                   ├── Session 11 (Auth pages)
                              │                                   ├── Session 12 (Task matrix)
                              │                                   └── Session 13 (Settings)
                              │                                         └── Session 14 (Dockerfiles)
                              │                                               ├── Session 15 (CI/CD)
                              └─────────────────────────────────────────────── Session 16 (E2E)
```

Sessions 2 and 3 can run in parallel with Session 1. Sessions 11, 12, 13 can run in parallel once Session 10 is done.