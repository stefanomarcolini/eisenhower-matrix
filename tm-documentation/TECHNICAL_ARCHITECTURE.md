# Technical Architecture & Standards

## 1. Backend Service (Core API)
- **Runtime:** Java 17+ / Spring Boot 3.x.
- **Patterns:** DDD, SOLID, DRY.
- **Documentation:** OpenAPI v3 (YAML-first). Generate interfaces via `openapi-generator-maven-plugin`.
- **Database:** PostgreSQL 17 (Liquibase for migrations).

## 2. Frontend Layer (BFF Pattern)
Two sub-layers in one container:
- **BFF (Spring Boot):** OAuth2/OIDC and local auth flows; Redis-backed session; proxies `/api/**` to Core API injecting `Authorization: Bearer` and `X-Tenant-ID`.
- **Client UI (React + TypeScript + Vite):** `@zxcvbn-ts/core` (+ language packs) for password strength scoring; renders the 3×3 task matrix, settings, and the password age warning banner.

**Session scalability:** `spring-session-data-redis` externalises all session state — BFF pods are stateless for load-balancing. See `AUTH_CONFIG.md §8`.

## 3. Security & Auth
Two auth methods (OAuth2/OIDC and local email+password) both produce an app-issued JWT stored in the BFF session. Core API is a single-issuer resource server validating only its own JWTs. See `AUTH_CONFIG.md`.

**Authorization approach:** Guard endpoints with `@PreAuthorize` using authority literals (e.g., `"hasAuthority('ROLE_ADMIN')"`) — not role-name string comparisons buried in service code. This keeps authorization logic at the controller boundary and makes it straightforward to extend the role model beyond `STANDARD`/`ADMIN` without a code-wide refactor. See `PROJECT_OVERVIEW.md §4` for the role extension path.

## 4. Email Service
Spring Mail for password reset. Mailpit in dev (no credentials needed). See `INFRASTRUCTURE_SPEC.md §5`.

## 5. Testing Strategy
- **Unit:** JUnit 5 + Mockito.
- **Integration — Core API:** `@SpringBootTest` + Testcontainers (PostgreSQL 17, `@ServiceConnection`). WireMock (`org.wiremock.integrations:wiremock-spring-boot`) stubs outbound HTTP.
- **Integration — BFF:** WireMock stubs Core API and mock IdP.
- **E2E:** Selenium in `tm-orchestrator/e2e/`.

## 6. Multi-Tenancy
Row-level tenancy (`tenant_id` on all user-data tables). See `MULTI_TENANCY.md`.

## 7. Observability
Structured JSON logging (prod), audit log, Spring Actuator (health + info only). See `OBSERVABILITY.md`.

## 8. Dependencies & Licensing

All dependencies must be open-source with a permissive licence (Apache 2.0, MIT, BSD, EPL 2.0, or equivalent). No paid or enterprise-tier libraries permitted.

| Library | Version | Scope | Licence | Notes |
| :--- | :--- | :--- | :--- | :--- |
| Spring Boot (all starters) | 3.4.5 | Java | Apache 2.0 | Upgraded from 3.2.5 to fix HIGH/CRITICAL CVEs. Explicit BOM overrides required: `tomcat.version=10.1.45`, `spring-security.version=6.4.10`, `spring-framework.version=6.2.11`, `jackson-bom.version=2.18.6` — 3.4.5 ships older patch versions for all four. |
| Hibernate (via Spring Data JPA) | 6.4.x (BOM) | Java | LGPL 2.1 | |
| Lombok | 1.18.32 (BOM) | Java | MIT | |
| PostgreSQL JDBC Driver | 42.7.7 (BOM override) | Java | BSD 2-clause | Pinned to 42.7.7 to address CVE-2025-49146 in 42.7.5 |
| Liquibase Community Edition | 5.0.2 | Java | Apache 2.0 | Community only — not Pro/Enterprise. Docker image tag `5.0` (aligned with `tm-db-schema/Dockerfile`). |
| `dev.samstevens.totp:totp` | 1.7.1 | Java | MIT | TOTP/MFA |
| `net.logstash.logback:logstash-logback-encoder` | 7.4 | Java | Apache 2.0 | Structured JSON logs |
| `com.bucket4j:bucket4j-core` | 8.7.0 | Java | Apache 2.0 | **Core module only** — the enterprise module is commercial |
| `org.apache.httpcomponents.client5:httpclient5` | 5.x (BOM) | Java | Apache 2.0 | BFF only — Spring Boot auto-selects `HttpComponentsClientHttpRequestFactory` over `SimpleClientHttpRequestFactory` when this is on the classpath, fixing 4xx POST error-response streaming |
| `spring-session-data-redis` | (BOM) | Java | Apache 2.0 | BFF session store — requires `spring-boot-starter-data-redis` |
| `openapi-generator-maven-plugin` | 7.4.0 | Java | Apache 2.0 | |
| JUnit 5 | 5.10.x (BOM) | Java (test) | EPL 2.0 | |
| Mockito | 5.x (BOM) | Java (test) | MIT | |
| Testcontainers (postgresql, base) | 1.20.6 (overrides BOM 1.19.x) | Java (test) | MIT | Use static block + `@DynamicPropertySource` — **not** `@ServiceConnection` (CODING_PATTERNS.md §10, §13) |
| `org.wiremock.integrations:wiremock-spring-boot` | 3.3.0 | Java (test) | Apache 2.0 | |
| `org.owasp:dependency-check-maven` | ~~9.1.0~~ removed | Java (CI) | Apache 2.0 | Removed from code pipelines — NVD API key requirement created unstable CI gating; replaced by Grype vulnerability scans. |
| `org.seleniumhq.selenium:selenium-java` | 4.27.0 | Java (E2E test) | Apache 2.0 | Browser automation — `tm-orchestrator/e2e/` |
| `com.fasterxml.jackson.core:jackson-databind` | 2.17.2 | Java (E2E test) | Apache 2.0 | JSON parsing in E2E tests (Mailpit API responses, BFF bodies) |
| React 18, Vite 8, TypeScript 5 | 18.3.1 / 8.0.1 / 5.5.3 | Frontend | MIT | Vite upgraded to clear GHSA-67mh-4wv8-2f99 (`esbuild` dev-server advisory). |
| `react-router-dom` | 6.26.2 | Frontend | MIT | Client-side routing, auth guards |
| `@tanstack/react-query` | 5.56.2 | Frontend | MIT | Server state, data fetching, caching |
| `react-hook-form` | 7.53.0 | Frontend | MIT | Form state management |
| `tailwindcss` | 3.4.11 | Frontend | MIT | Utility-first CSS; `darkMode: 'class'` |
| `@radix-ui/react-*` | 1.x | Frontend | MIT | Accessible UI primitives (dialog, dropdown, toast, label) |
| `axios` | 1.7.7 | Frontend | MIT | HTTP client; CSRF interceptor reads XSRF-TOKEN cookie |
| `lucide-react` | 0.441.0 | Frontend | ISC | Icon set |
| `qrcode` | 1.5.4 | Frontend | MIT | Generates local data-URL QR codes for MFA enrollment without external network calls |
| `@zxcvbn-ts/core` | 3.0.4 | Frontend | Apache 2.0 | **Use this (not `@zxcvbn-ts/zxcvbn` — that package does not exist)** |
| `@zxcvbn-ts/language-common` | 3.0.4 | Frontend | MIT | Required peer — provides shared dictionary data |
| `@zxcvbn-ts/language-en` | 3.0.2 | Frontend | MIT | English dictionary for password scoring |
| `openapi-typescript` | 7.3.0 | Frontend (dev) | MIT | Generates TS types from openapi.yaml (`npm run codegen`) |
| `@vitejs/plugin-react` | 5.1.1 | Frontend (dev) | MIT | Vite plugin — Babel-based React fast refresh |
| `autoprefixer` | 10.4.20 | Frontend (dev) | MIT | PostCSS plugin required by Tailwind CSS |
| `postcss` | 8.4.47 | Frontend (dev) | MIT | CSS transformation pipeline (required by Tailwind) |
| `eslint` | 9.9.0 | Frontend (dev) | MIT | Linter; flat config (`eslint.config.js`) |
| `@typescript-eslint/eslint-plugin` | 8.3.0 | Frontend (dev) | MIT | TypeScript lint rules |
| `@typescript-eslint/parser` | 8.3.0 | Frontend (dev) | MIT | TypeScript parser for ESLint |
| `eslint-plugin-react-hooks` | 5.1.0-rc.0 | Frontend (dev) | MIT | Enforces Rules of Hooks |
| `eslint-plugin-react-refresh` | 0.4.11 | Frontend (dev) | MIT | Validates fast-refresh component exports |
| `vitest` | 4.1.0 | Frontend (dev/test) | MIT | Unit test runner; configured via `vite.config.ts` |
| `@vitest/coverage-v8` | 4.1.0 | Frontend (dev/test) | MIT | V8-based coverage provider for vitest |
| `@testing-library/react` | 16.0.1 | Frontend (dev/test) | MIT | React component testing utilities |
| `@testing-library/dom` | 10.4.1 | Frontend (dev/test) | MIT | DOM queries; required by `@testing-library/react` types |
| `@testing-library/user-event` | 14.5.2 | Frontend (dev/test) | MIT | User interaction simulation |
| `@testing-library/jest-dom` | 6.5.0 | Frontend (dev/test) | MIT | Custom DOM matchers for vitest |
| `jsdom` | 25.0.1 | Frontend (dev/test) | MIT | DOM environment for vitest (`environment: 'jsdom'`) |
| `@types/react` | 18.3.5 | Frontend (dev) | MIT | TypeScript types for React |
| `@types/qrcode` | 1.5.5 | Frontend (dev) | MIT | TypeScript definitions for `qrcode` |
| `@types/react-dom` | 18.3.0 | Frontend (dev) | MIT | TypeScript types for ReactDOM |
| PostgreSQL 17 | 17-alpine | Infrastructure | PostgreSQL Licence | OSI-approved |
| Redis 7 | 7 | Infrastructure | BSD 3-clause | |
| Mailpit | latest | Dev / test | MIT | Replaces archived MailHog |
| `ghcr.io/navikt/mock-oauth2-server` | latest | Dev / test | MIT | |
| Syft (`anchore/syft`) | 1.42.3 | CI | Apache 2.0 | Generates CycloneDX SBOMs; release tarballs are checksum-verified before execution. |
| Grype (`anchore/grype`) | 0.110.0 | CI | Apache 2.0 | Filesystem/image vulnerability scans (`--fail-on high`); release tarballs are checksum-verified before execution. |
| `selenium/standalone-chrome` | latest | Infrastructure (E2E) | Apache 2.0 | Docker container — RemoteWebDriver endpoint at `:4444`, noVNC at `:7900` |

### Vulnerability Scanning
Automated scanning runs in every CI pipeline — see `REPOSITORIES_AND_CICD.md §2` for pipeline stages. No image tag should use `latest` in production; pin to specific patch versions (e.g., `postgres:17.2-alpine`).