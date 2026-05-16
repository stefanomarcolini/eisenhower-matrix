# Development & Testing Environment

## 1. Local Container Engine: Rancher Desktop
- **Runtime:** `dockerd (moby)` mode (required for Testcontainers compatibility).
- **Postgres image:** `postgres:17.x-alpine` (both compose and Testcontainers).
- **Windows / macOS:** add to shell profile: `export DOCKER_HOST=unix:///var/run/docker.sock`

## 2. Testcontainers Configuration (Java)

| Service | Testcontainers module | Used by |
| :--- | :--- | :--- |
| PostgreSQL 17 | `testcontainers-postgresql` | `tm-core-api` integration tests |
| Redis 7 | `testcontainers-redis` | `tm-ui-bff` integration tests (session store) |
| WireMock | `org.wiremock.integrations:wiremock-spring-boot` | Both — stubs SMTP, JWKS, Core API, mock IdP |

- Use `@ServiceConnection` (Spring Boot 3.1+) to auto-wire dynamic ports for Postgres and Redis.
- WireMock stubs all outbound HTTP: SMTP, any JWKS endpoints, Core API calls from BFF tests.
- BFF integration tests do **not** start a real Core API container — WireMock stubs all `/internal/**` and `/api/**` calls.

## 3. Core API — Local Run (no compose needed)
```bash
cd tm-core-api
mvn spring-boot:test-run   # Spring Boot 3.2+; Testcontainers provides the DB
```

## 4. Dev-Mode Vite Proxy and CORS

### Problem
`npm run dev` starts Vite on `http://localhost:5173`. API calls to the BFF at `http://localhost:8080` are cross-origin — cookies and CORS break.

### Solution
`vite.config.ts` proxies these paths to `http://localhost:8080`: `/api/**`, `/oauth2/**`, `/login/**`, `/logout`, `/auth/**`. Requests appear same-origin to the browser; session cookies work without `SameSite=None`.

```ts
// vite.config.ts
export default defineConfig({
  server: {
    proxy: {
      '/api':    { target: 'http://localhost:8080', changeOrigin: true },
      '/auth':   { target: 'http://localhost:8080', changeOrigin: true },
      '/oauth2': { target: 'http://localhost:8080', changeOrigin: true },
      '/login':  { target: 'http://localhost:8080', changeOrigin: true },
      '/logout': { target: 'http://localhost:8080', changeOrigin: true },
    },
  },
});
```

> **`changeOrigin: true` is required.** Without it, the `Host` header sent to the BFF is `localhost:5173` — Spring Security's CSRF and session cookie validation may reject the request.

### BFF CORS (dev profile only)
`CorsConfig.java` allows `http://localhost:5173`. Active only under `SPRING_PROFILES_ACTIVE=dev`. In production the BFF serves the built React bundle from the same origin — no CORS needed.

### Frontend env files
- `.env.development`: `VITE_API_BASE_URL=` (empty — proxy handles routing)
- `.env.production`: `VITE_API_BASE_URL=` (empty — BFF serves static files)

## 5. Email Testing (Mailpit)
Mailpit starts automatically via `docker-compose.override.yml`. SMTP on `localhost:1025`, web UI at `http://localhost:8025`. Core API env: `SMTP_HOST=mailpit`, `SMTP_PORT=1025`, `SMTP_TLS_ENABLED=false`.

## 6. Full Stack Local Run

### Option A — Full compose (no live-reload)
```bash
cd tm-orchestrator
docker-compose up -d
# App: http://localhost:8080 | Mailpit: http://localhost:8025 | Mock OAuth2: http://localhost:9000
```

### Option B — Infrastructure via compose + live-reload processes
```bash
# Terminal 1 — from tm-orchestrator/:
docker-compose up -d db redis db-migrations mock-oauth2 mailpit core-api

# Terminal 2 — BFF:
cd tm-ui-bff/bff-service
mvn spring-boot:run -Dspring-boot.run.profiles=dev

# Terminal 3 — React:
cd tm-ui-bff/frontend-client
npm run dev
# Live-reload at http://localhost:5173 (proxied through BFF)
```

## 7. Stopping
```bash
# Stop all (data preserved)
docker-compose down

# Stop all + delete all data (clean slate)
docker-compose down -v

# Stop a single service
docker-compose stop <service>

# Dev-mode processes: Ctrl+C in each terminal
```
> `docker-compose down -v` permanently deletes all PostgreSQL and Redis data.

## 8. Debugging

### Java Remote Debugger

`docker-compose.override.yml` exposes JVM debug ports when running in dev mode:

| Service | JDWP port | IDE remote-debug config |
| :--- | :--- | :--- |
| Core API | `5005` | Host `localhost`, port `5005` |
| BFF | `5006` | Host `localhost`, port `5006` |

Attach from IntelliJ: **Run → Edit Configurations → Remote JVM Debug** → set the matching port. Breakpoints in BFF auth code (e.g. `LocalLoginFilter`, `SessionController`) are reachable as soon as you submit the login form in the browser.

When running BFF in live-reload mode (Option B above), pass the debug agent directly:
```bash
cd tm-ui-bff/bff-service
mvn spring-boot:run \
  -Dspring-boot.run.profiles=dev \
  -Dspring-boot.run.jvmArguments="-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=*:5006"
```

### Spring Security Trace Logging

Add to `tm-ui-bff/bff-service/src/main/resources/application.yml` (or pass as a system property) to see every security decision in the BFF:

```yaml
logging:
  level:
    org.springframework.security: TRACE
```

This logs filter chain selection, authorization decisions, CSRF validation, and session management. Useful when a request returns 401/403 and the cause is not obvious from the response body.

For Core API security decisions:
```yaml
logging:
  level:
    org.springframework.security: TRACE
```
Add to `tm-core-api/src/main/resources/application.yml`.

> **Remove TRACE logging before committing.** At TRACE level, Spring Security logs token values and session attributes — not safe for shared or persistent logs.

### Cookie and Session Inspection (Browser DevTools)

Open **DevTools → Application → Cookies → localhost:5173** and check:

| Cookie | Set by | Cleared by | Purpose |
| :--- | :--- | :--- | :--- |
| `TM_SESSION` | BFF (Spring Session) on login | `POST /logout` (`deleteCookies`) | Identifies the Redis-backed session |
| `XSRF-TOKEN` | BFF (Spring CSRF filter) on first GET | `POST /logout` (`CsrfLogoutHandler`) | CSRF double-submit; React reads and re-sends as `X-XSRF-TOKEN` |

**Common symptom → root cause mapping:**

- `TM_SESSION` present but login returns 401 → stale ghost session in Redis; `GET /auth/session` before logout returned a new empty session. Cleared by: log out, or `docker-compose restart redis`.
- `XSRF-TOKEN` absent after logout → expected; the next `POST /auth/local/login` is CSRF-excluded so it will succeed without the token.
- `TM_SESSION` absent after a successful login → BFF returned a session cookie with `Secure=true` but Vite is serving over plain HTTP. Check `spring.session.cookie.secure` in `application.yml` (`false` for local dev).

### Redis Session Inspection

Inspect live sessions directly in the running Redis container:

```bash
# List all TM session keys
docker exec -it tm-redis redis-cli KEYS "tm:session:*"

# Inspect a specific session (replace <id> with the TM_SESSION cookie value)
docker exec -it tm-redis redis-cli HGETALL "tm:session:sessions:<id>"

# Delete a single session (forces re-login for that browser)
docker exec -it tm-redis redis-cli DEL "tm:session:sessions:<id>"

# Flush all sessions (equivalent to everyone logging out)
docker exec -it tm-redis redis-cli FLUSHDB
```

> `FLUSHDB` wipes the entire Redis database, including the rate-limit buckets. All in-memory Bucket4j state is cleared.

### Auth Flow Debugging (Vite Proxy + BFF)

The Vite dev proxy (`localhost:5173`) forwards requests to the BFF (`localhost:8080`). A few things worth knowing when tracing auth issues:

1. **The proxy rewrites `Host` but not `Location`.** `changeOrigin: true` makes the BFF see `Host: localhost:8080`, but any `302 Location` header the BFF returns still points to `localhost:8080`. The browser follows the redirect directly (bypassing the proxy), which can cause cookie inconsistency. The BFF logout is intentionally configured to return `200` JSON rather than a redirect for this reason.

2. **Network tab filtering.** In DevTools → Network, filter by `/auth` to see session checks, login, and logout calls. Check the **Response Headers** for `Set-Cookie` and the **Request Headers** for `Cookie` and `X-XSRF-TOKEN` on each call.

3. **Unauthenticated `GET /auth/session` must not create a session.** If it does (visible as a new `TM_SESSION` cookie appearing before you log in), the BFF has regressed to injecting `HttpSession` via Spring MVC instead of using `request.getSession(false)`. See `SessionController.java`.

4. **Rate-limit 429 vs auth 401.** `POST /auth/local/login` returns 429 when the Bucket4j per-IP bucket is exhausted (5 attempts/min). If you see 429 during manual testing, wait 60 seconds or restart the BFF process to reset the in-memory buckets.

### npm audit Fails on picomatch HIGH / brace-expansion Moderate

**Symptom:** `tm-ui-bff` `scan-frontend` job fails with `npm audit --audit-level=high` reporting `picomatch <=2.3.1 || 4.0.0-4.0.3` HIGH (ReDoS / method injection) and `brace-expansion <=1.1.12 || 4.0.0-5.0.4` moderate (DoS). Both are transitive devDependencies from `eslint` and `@redocly/openapi-core`.
**Root cause:** Indirect package-lock.json pin to vulnerable ranges. The vulnerable versions are not directly referenced in `package.json`; they surface as transitive dependencies.
**Fix:** Run `npm audit fix` inside `tm-ui-bff/frontend-client/`. This updates `package-lock.json` to resolve the affected ranges to patched versions. Only `package-lock.json` is modified — `package.json` is unchanged. Regression check: `npm audit --audit-level=high` inside `frontend-client/` must print `found 0 vulnerabilities`.



**Symptom:** Pipeline scan job fails with `UpdateException: NVD returned a 403 or 404 error` + `NoDataException: No documents exist`.
**Root cause:** OWASP dependency-check-maven requires an NVD API key; without it the NVD API rate-limits unauthenticated requests. An empty `NVD_API_KEY` secret causes 403s; an empty local cache then fails with `NoDataException`.
**Fix:** Replaced all three OWASP scan steps with `aquasecurity/trivy-action@0.24.0` in `scan-type: fs` mode. Trivy uses its own bundled database (GitHub Advisory + OSV + NVD mirror) — no API key required. The action version is already pinned in Stage 4 image scans, so no new tooling is introduced. Regression check: the `scan` / `scan-java` jobs must not reference `org.owasp:dependency-check-maven` or `NVD_API_KEY`.

### OAuth2 / Mock IdP Issues

**Symptom:** Clicking **Continue with Google** or **Continue with Microsoft** on `localhost` opens a plain `Mock OAuth2 Server Sign-in` page instead of the real Google/Microsoft login screen.
**Root cause:** This is the intended local-development setup — the BFF redirects to `mock-oauth2` so no real IdP credentials are required.
**Fix:** Enter any email/subject on the mock form and submit. Regression check: localhost login page should remind users that local OAuth uses the mock provider.

**Symptom:** Clicking "Sign in with Google/Microsoft" in local dev shows `ERR_NAME_NOT_RESOLVED` or "This site can't be reached" in the browser.
**Root cause:** The authorization redirect was pointing to `http://mock-oauth2:8080/...` — the internal Docker network hostname, not resolvable from the host browser.
**Fix:** `SPRING_SECURITY_OAUTH2_CLIENT_PROVIDER_GOOGLE_AUTHORIZATION_URI` and the Microsoft equivalent must use `http://localhost:9000/...` (host-accessible). The token, JWK, and user-info URIs must use `http://mock-oauth2:8080/...` (BFF-to-mock server-side). Regression check: after `docker-compose up`, clicking "Sign in with Google" should open `http://localhost:9000/default/authorize` in the browser tab.

**Symptom:** OAuth2 login completes mock flow but BFF redirects to `/login?error=oauth2`.
**Root cause:** Core API's `resolveProvider()` rejects the mock issuer (`http://mock-oauth2:8080/default`) with "Unknown OIDC issuer". The mock issues tokens with the issuer derived from the request host (internal Docker URL), which is not `https://accounts.google.com`.
**Fix:** Set `APP_OAUTH2_DEV_ISSUER=http://mock-oauth2:8080` in the core-api environment (docker-compose.override.yml). `AuthService.resolveProvider()` maps any issuer starting with this prefix to `AuthProvider.GOOGLE`. Empty default ensures no effect in production. Regression check: `APP_OAUTH2_DEV_ISSUER` must remain absent (or empty) in the production `.env`.

### Registration / Password Policy

**Symptom:** Registration fails with "Unprocessable Entity" error shown in the form.
**Root cause:** Frontend only validated minimum 8 characters; backend `PasswordValidationService` also requires uppercase, lowercase, digit, and special character. Users entering e.g. `password123` would always fail at the server.
**Fix:** `RegisterPage.tsx` now validates all five rules client-side via react-hook-form `validate` map, giving immediate feedback before any API call. Regression check: a password like `Abc123!` (7 chars) must show "at least 8 characters"; `password123` must show the uppercase and special character errors.

### IntelliJ / DBeaver Database Connection

**Symptom:** Cannot connect to the PostgreSQL database from IntelliJ after `docker-compose up`.
**Root cause:** The `db` service in `docker-compose.yml` does not expose port 5432 to the host — it is only accessible inside the `tm-network` bridge.
**Fix:** `docker-compose.override.yml` now adds `ports: - "5432:5432"` to the `db` service. Connect from IntelliJ with: host=`localhost`, port=`5432`, database=`taskmanager`, user=`tm`, password from `.env`.

### Vulnerability Scan Failures (Trivy fs + image scan)

**Symptom:** `scan-java` CI job fails with HIGH/CRITICAL CVEs: Tomcat CVE-2025-24813 (CRITICAL), Spring Security CVE-2024-38821 (CRITICAL), Jackson GHSA-72hv-8253-57qq (HIGH), nimbus-jose-jwt CVE-2023-52428, netty-handler CVE-2025-24970.
**Root cause:** Spring Boot 3.2.5 is affected by all of these. The fixed versions require Spring Boot ≥ 3.3.x for most, and 3.4.5 for full coverage.
**Fix:** Upgrade `spring-boot-starter-parent` to `3.4.5` in both `tm-core-api/pom.xml` and `tm-ui-bff/pom.xml`. Regression check: `mvn test-compile` must succeed in both modules after the upgrade.

**Symptom:** `scan-java` CI job still fails after upgrading to Spring Boot 3.4.5 — new CVEs in Tomcat 10.1.40, Spring Security 6.4.5, Spring Framework 6.2.6, Jackson 2.18.3.
**Root cause:** Spring Boot 3.4.5 was released before these CVEs were published; the included versions have subsequent security patches. The Spring Boot BOM exposes well-known property names that override managed versions.
**Fix:** Add explicit version properties to both `pom.xml` files: `<tomcat.version>10.1.45</tomcat.version>`, `<spring-security.version>6.4.10</spring-security.version>`, `<spring-framework.version>6.2.11</spring-framework.version>`, `<jackson-bom.version>2.18.6</jackson-bom.version>`. Regression check: `mvn dependency:tree | grep tomcat-embed-core` must show `10.1.45`.

**Symptom:** `tm-core-api` `scan` job fails in Trivy fs scan with `org.postgresql:postgresql CVE-2025-49146` (installed `42.7.5`, fixed `42.7.7`).
**Root cause:** `tm-core-api` relied on the Spring Boot BOM-managed PostgreSQL JDBC version, which resolved to a vulnerable patch level.
**Fix:** Override the BOM property in `tm-core-api/pom.xml` with `<postgresql.version>42.7.7</postgresql.version>`. Regression check: `mvn -q help:evaluate -Dexpression=postgresql.version -DforceStdout` must print `42.7.7`, and Trivy fs scan must report no HIGH/CRITICAL findings for `org.postgresql:postgresql`.

**Symptom:** `tm-core-api` test job fails with OWASP `dependency-check-maven` `UpdateException: NVD returned a 403 or 404 error`.
**Root cause:** `dependency-check-maven` requires an NVD API key; without it the API rate-limits the plugin. The `scan` job already uses Trivy for the same purpose — running OWASP during `mvn verify` is redundant and broken.
**Fix:** Remove the `dependency-check-maven` plugin and its `<dependency-check.version>` property from `tm-core-api/pom.xml`. Trivy in the separate `scan` CI job provides equivalent coverage without an API key. Regression check: `mvn verify` must complete with only tests and packaging phases — no OWASP output.

**Symptom:** `tm-db-schema` Docker image scan fails with gpgv CVE-2025-68973 (HIGH), mssql-jdbc CVE-2025-59250 (HIGH), snowflake-jdbc CVE-2025-24789 (HIGH), and gobinary CVEs from `liquibase/bin/lpm`.
**Root cause:** The `liquibase/liquibase:4.27` base image (Ubuntu 22.04) ships an outdated `gpgv` OS package; Liquibase bundles JDBC drivers in `/liquibase/internal/lib/` (not `/liquibase/lib/`); and `lpm` is an unused binary with its own vulnerabilities.
**Fix:** Added `apt-get update && apt-get upgrade -y` for OS packages. Used `find /liquibase -name "mssql-jdbc*.jar" -delete` and `find /liquibase -name "snowflake-jdbc*.jar" -delete` — NOT `rm -f /liquibase/lib/*.jar` (wrong path, silently does nothing). Removed `lpm` with `rm -f /liquibase/bin/lpm`. Regression check: Trivy image scan must report zero HIGH/CRITICAL findings.

**Symptom:** All three pipelines fail to load with `Invalid workflow file ... Unrecognized named-value: 'secrets'` on step `if:` expressions.
**Root cause:** GitHub Actions expression parsing rejects direct `secrets.*` references in those `if` expressions.
**Fix:** Remove secret-gated dispatch expressions from code-repo pipelines and decouple E2E triggering from cross-repo dispatch. Regression check: `pipeline.yml` in `tm-core-api`, `tm-ui-bff`, and `tm-db-schema` must load with no annotation errors.

### TypeScript Build Failure in CI (schema.d.ts missing)

**Symptom:** CI TypeScript build step fails with `Cannot find module './schema'` in `profile.ts`, `tasks.ts`, `TaskMatrix.tsx`, and related files. Cascading `any` type errors appear in `AppLayout.tsx` and `TaskList.tsx`.
**Root cause:** `frontend-client/src/api/schema.d.ts` was listed in `tm-ui-bff/.gitignore`. The file is generated locally by `npm run codegen` (`openapi-typescript`) but was never committed. CI checkout has no `schema.d.ts`, causing all imports of `'./schema'` to fail.
**Fix:** Commit `frontend-client/src/api/schema.d.ts` and make CI verify it exists (`test -f src/api/schema.d.ts`) instead of checking out `tm-core-api` from another private repository during `test-frontend`. Local development still regenerates the file with `npm run codegen` against `../../tm-core-api/api-spec/openapi.yaml`. Regression check: `test-frontend` must pass without any cross-repo checkout step, and `npm run build` must produce no TypeScript errors.

### Cross-Repo Checkout Fails in BFF Frontend Job

**Symptom:** `actions/checkout` for `sm-task-manager/tm-core-api` fails with `Not Found - https://docs.github.com/rest/repos/repos#get-a-repository`.
**Root cause:** The workflow's default `GITHUB_TOKEN` is scoped to the current repository and cannot always read another private repository.
**Fix:** Remove cross-repo checkout from `tm-ui-bff` `test-frontend` and use committed generated schema types as the CI input. Regression check: `test-frontend` logs must not contain "Check out tm-core-api" and must still run `npm run build` + `npm test` successfully.

### Node Runtime Migration to 24 Complete

**Symptom:** Pipeline logs should not show Node.js 20 deprecation warnings since the runtime is Node 24.
**Root cause:** Workflows have `FORCE_JAVASCRIPT_ACTIONS_TO_NODE24: true` and upgraded actions.
**Fix:** If warnings appear, upgrade artifact upload to `actions/upload-artifact@v7`.
Regression check: code-repo publish jobs should no longer emit Node 20 deprecation warnings for upload-artifact.

### Tokenless Orchestrator E2E Triggering

**Symptom:** Cross-repo E2E triggering depended on dispatch credentials, so missing external tokens could block the flow.
**Root cause:** The previous model relied on `repository_dispatch` from each code repo to `tm-orchestrator`.
**Fix:** Run orchestrator E2E out of the box from `tm-orchestrator` itself (`workflow_dispatch` and `push` to `main`) and remove cross-repo dispatch steps from code pipelines. Regression check: code-repo publish jobs require no dispatch secret, and E2E runs on manual trigger or main-branch merges.

### Core API / BFF Image Scan Fails on Runtime OS CVEs

**Symptom:** `tm-core-api` or `tm-ui-bff` Grype image scan fails with HIGH CVEs from Alpine runtime packages (for example OpenSSL-related findings with no immediate patch in the Alpine channel).
**Root cause:** The runtime stage used `eclipse-temurin:17-jre-alpine`, and package availability/patch timing in Alpine can temporarily leave HIGH CVEs unresolved.
**Fix:** Migrate runtime stages to `eclipse-temurin:17-jre-jammy` and apply `apt-get update && apt-get upgrade -y` during image build. Keep Grype config files (`.grype.yaml`) with `ignore: []` (security-first default: no active ignore exceptions). Regression check: publish scan command `grype <image> --config .grype.yaml --fail-on high` must pass without CVE suppression entries in `tm-core-api/.grype.yaml` and `tm-ui-bff/.grype.yaml`.

**Symptom:** `tm-db-schema` Grype image scan fails with multiple HIGH CVEs in `openjdk 17.0.10+7` binary (e.g., CVE-2024-21147, CVE-2025-21587, CVE-2025-30749, CVE-2025-53066, CVE-2026-21945).
**Root cause:** The `liquibase/liquibase:4.27` base image bundles Eclipse Temurin 17 at `/opt/java/openjdk` via the `eclipse-temurin:17-jdk-jammy` base layer. This JDK was NOT installed via APT — `apt-get upgrade` cannot update it. The bundled version was `17.0.10+7` which predates the fix releases (`17.0.12`–`17.0.18`).
**Fix:** (1) Update `LIQUIBASE_IMAGE_TAG` from `4.27` to `5.0` (aligning with `pom.xml` `liquibase.version=5.0.2`). (2) In `tm-db-schema/Dockerfile`, add the Adoptium APT repository, install `temurin-17-jdk` (latest patched 17.x), remove `/opt/java/openjdk`, create a stable symlink `/usr/lib/jvm/temurin-17-current` pointing to the actual JDK path (detected via `readlink -f $(which java)`), and set `JAVA_HOME=/usr/lib/jvm/temurin-17-current`. This installs `17.0.18+` which has all HIGH CVE fixes. (3) Keep `tm-db-schema/.grype.yaml` at `ignore: []` (no active exceptions). Regression check: `grype <image> --config .grype.yaml --fail-on high` must report no HIGH CVEs in `openjdk`; `java -version` in the container must show 17.0.18 or later.

**Symptom:** `db-migrations` container exits with code 1 immediately after starting; no Liquibase output visible in logs.
**Root cause:** `JAVA_HOME=/usr/lib/jvm/temurin-17-amd64` was hardcoded in `tm-db-schema/Dockerfile` but the Adoptium `temurin-17-jdk` APT package on Ubuntu Jammy installs to a different path (e.g. `temurin-17-jdk-amd64` with the `-jdk` infix, or `temurin-17` without the architecture suffix). The Liquibase 5.0 startup script calls `exec "${JAVA_HOME}/bin/java" ...` directly — if the path does not exist the process exits with code 1 and no migration output is produced.
**Fix:** Replace the hardcoded `JAVA_HOME` path with a dynamically resolved symlink: add `ln -sf "$(readlink -f "$(which java)" | sed 's|/bin/java$||')" /usr/lib/jvm/temurin-17-current` immediately after `rm -rf /opt/java/openjdk` in the `RUN` command, then set `ENV JAVA_HOME=/usr/lib/jvm/temurin-17-current`. The symlink is created at build time using the actual install location reported by update-alternatives, so it works regardless of Ubuntu release or Adoptium package version.
**Diagnosis command:** `docker run --rm --entrypoint java ghcr.io/sm-task-manager/tm-db-schema:latest -version` — if this prints the JDK version the path is correct; if it exits with "No such file or directory" the JAVA_HOME path is wrong.
**Regression check:** After a new image push, `docker run --rm --entrypoint /bin/sh ghcr.io/sm-task-manager/tm-db-schema:latest -c 'ls -la $JAVA_HOME/bin/java'` must print the symlink target; `db-migrations` in the compose stack must exit 0.

### Orchestrator E2E Image Pull Denied (GHCR)

**Symptom:** `tm-orchestrator` `e2e` job fails at `docker compose pull` with `Head ... ghcr.io/.../manifests/latest: denied`.
**Root cause:** The workflow attempted to pull private GHCR images without logging into GHCR and without `packages: read` permission.
**Fix:** In `tm-orchestrator/.github/workflows/e2e.yml`, set job permissions to include `packages: read` and add `docker/login-action@v4` using `${{ secrets.GITHUB_TOKEN }}` before `docker compose pull`. Regression check: `Pull latest images` must succeed with no `denied` errors.

### Orchestrator E2E Artifact Upload Warning

**Symptom:** `Upload test reports on failure` logs `No files were found with the provided path` and emits a warning.
**Root cause:** The report directory may not exist when tests do not start or fail before Surefire writes output.
**Fix:** Use `actions/upload-artifact@v7` and set `if-no-files-found: ignore`. Regression check: failed runs without report files must not emit an artifact-path warning.

### Registration Always Fails ("Registration failed. Please try a different email.")

**Symptom:** All registration attempts fail with "Registration failed. Please try a different email." regardless of the email or password used. The error appears even for brand-new emails.
**Root cause:** The BFF had no `@RestControllerAdvice` global exception handler. Spring Boot's default `BasicErrorController` returns `{timestamp, status, error, path}` — no `title` field. The frontend reads `err.response?.data?.title` which is always `undefined`, falling through to the hardcoded fallback message. Any Core API error (authentication failure, conflict, connection error) would show this same misleading message.
**Fix:** Added `com.tm.bff.web.GlobalExceptionHandler` (@RestControllerAdvice) with two handlers: (1) `ResponseStatusException` — parses the reason string as JSON (Core API returns ProblemDetail JSON as the reason), extracts `title`/`detail` and forwards them in a new ProblemDetail response; (2) `Exception` catch-all — returns a 500 ProblemDetail with `title: "Service Unavailable"` so the browser always receives JSON. Also updated `RegisterPage.tsx` to read `data?.detail ?? data?.title` (Core API's `detail` is the human-readable message). Regression check: registering with an existing email must show "Email already registered in this tenant"; registering successfully must navigate to `/dashboard`.

### E2E Image Pull Denied from GHCR

**Symptom:** `tm-orchestrator` E2E pipeline fails at "Pull latest images" with `Error response from daemon: denied` for `frontend-bff`, `core-api`, or `tm-db-schema` images.
**Root cause:** The e2e.yml workflow attempted to pull private GHCR images without first logging into GHCR. GitHub's default runner can pull public images unauthenticated, but private organization images require authentication — even when using `GITHUB_TOKEN` that has `packages: read` permission.
**Fix:** Added `docker/login-action@v4` step before `docker compose pull`, using `${{ github.actor }}` (GitHub Actions bot) as the username and `${{ secrets.GITHUB_TOKEN }}` as the password. Also added explicit `permissions: { contents: read, packages: read }` to the job so the token has the required package-access scope. Regression check: E2E "Pull latest images" step must succeed without "denied" errors.

### Node.js 20 Deprecation in GitHub Actions

**Symptom:** Pipelines warn that JavaScript actions running on Node 20 will be forced to Node 24 after June 2, 2026.
**Root cause:** Workflows used older action majors and did not opt into Node 24 runtime yet.
**Fix:** Set `FORCE_JAVASCRIPT_ACTIONS_TO_NODE24=true` at workflow level and upgrade action majors (`actions/checkout@v5`, `actions/setup-java@v5`, `actions/setup-node@v5`, `docker/build-push-action@v6`, `docker/login-action@v4`, `docker/setup-buildx-action@v4`). Regression check: pipeline runs should no longer emit Node 20 deprecation warnings.

### Dependabot Docker Update Fails in tm-orchestrator

**Symptom:** Dependabot run `docker in /. - Update ...` fails with `dependency_file_not_found` and `No Dockerfiles nor Kubernetes YAML found in /`.
**Root cause:** `tm-orchestrator` has no root `Dockerfile` and does not build application images itself; Docker images are built in `tm-core-api`, `tm-ui-bff`, and `tm-db-schema`, so `package-ecosystem: docker` at `/` has no manifests to process.
**Fix:** Remove the `docker` entry from `tm-orchestrator/.github/dependabot.yml` and keep only `github-actions` and `maven` (`/e2e`). Regression check: Dependabot no longer creates failing `docker in /.` update runs for `tm-orchestrator`.

### Dependabot Node 20 Warning (`github/dependabot-action@main`)

**Symptom:** Dependabot update runs succeed but still emit `Node.js 20 actions are deprecated` mentioning `github/dependabot-action@main`.
**Root cause:** This action is executed by GitHub's Dependabot service, not from repository workflow files, so repository YAML pinning cannot change that runtime.
**Fix:** Set repository variable `FORCE_JAVASCRIPT_ACTIONS_TO_NODE24=true` in **Settings -> Secrets and variables -> Actions -> Variables** for each affected repository so Dependabot-run actions opt into Node 24. Regression check: future Dependabot runs should stop emitting Node 20 deprecation warnings.

### E2E GHCR Denied Despite Login Step

**Symptom:** `tm-orchestrator` E2E still fails at `docker compose pull --quiet` with `core-api Error denied` even though `docker/login-action` is present.
**Root cause:** `GITHUB_TOKEN` in `tm-orchestrator` may authenticate successfully to GHCR but still lack effective read access to private package images published by sibling repositories unless package-level Actions access is granted.
**Fix:** In `e2e.yml`, prefer `GHCR_USERNAME` (Actions variable) + `GHCR_TOKEN` (PAT secret with `read:packages`) for GHCR login, with `GITHUB_TOKEN` only as the fallback path when package-level Actions access is granted. Run a preflight `docker manifest inspect` for `tm-db-schema`, `tm-core-api`, and `tm-ui-bff` before `docker compose pull`. Regression check: preflight must pass for all three images and pull step must not emit `Error denied`.

### E2E Missing Secrets in tm-orchestrator

**Symptom:** E2E debug logs show blank `DB_NAME`, `DB_USERNAME`, `INTERNAL_JWT_SECRET`, `MFA_ENCRYPTION_KEY`, or admin credentials because `tm-orchestrator` repository secrets are unset.
**Root cause:** The original workflow wrote `.env` directly from repository secrets, so an unset secret became an empty runtime value and caused misleading later failures.
**Fix:** Generate non-production CI defaults inside `e2e.yml` for DB credentials, JWT secret, MFA key, and bootstrap admin credentials; if the admin bcrypt hash is absent, compute it at runtime from the resolved test password. Regression check: `Create .env` must produce usable values even when these secrets are absent.

### E2E Node Warning from Artifact Upload Step

**Symptom:** E2E run emits Node 20 deprecation warning tied to `actions/upload-artifact@v5`.
**Root cause:** GitHub runtime can still execute the action with Node 20 in some environments even when workflow opt-in is present.
**Fix:** Replace artifact upload action with shell-based failure diagnostics (`docker compose ps/logs` + Surefire tail) so the workflow no longer depends on a JavaScript action for failure reporting. Regression check: E2E annotations should no longer mention `actions/upload-artifact@v5`.

### E2E Selenium Ready Check Times Out While Selenium Is Running

**Symptom:** `Wait for Selenium ready` times out after repeated `not ready yet`, but compose logs show Selenium Standalone started and listening on `:4444`.
**Root cause:** The readiness probe only checked `/wd/hub/status` with a strict string match (`"ready":true`). Selenium 4 may expose readiness via `/status` and/or include whitespace in JSON (`"ready": true`), causing a false negative.
**Fix:** Probe both `http://localhost:4444/status` and `http://localhost:4444/wd/hub/status`, then match readiness with a whitespace-tolerant regex (`"ready"[[:space:]]*:[[:space:]]*true`) and increase retries to 30. Regression check: if Selenium logs show `Started Selenium Standalone ... :4444`, the readiness step must pass shortly after.

### E2E Compose Warning from BCrypt Hash Interpolation

**Symptom:** Admin login returns 401 in E2E (`SecurityIT` promote-user step), and Core API logs contain `Encoded password does not look like BCrypt` during `/internal/auth/validate`.
**Root cause:** CI normalized the bcrypt hash to canonical `$2y$...`, then re-escaped it with bash `${var//$/$$}` before writing `.env`. In bash replacement strings, `$$` expands to the shell PID, so the emitted value was PID-corrupted instead of the literal `$$2y$$...` form docker compose needs.
**Fix:** Re-escape the canonical hash with `printf '%s' "$HASH" | sed 's/\$/$$/g'` in `e2e.yml`, and keep `tm-db-schema/docker-entrypoint.sh` compatibility normalization for legacy `$$2y$$...` values. Regression check: no compose interpolation warnings and `POST /auth/local/login` for `admin@task-manager.local` returns 200 in E2E.

### tm-ui-bff `scan-frontend` Fails on `npm audit`

**Symptom:** `tm-ui-bff` pipeline fails in `scan-frontend` with `npm audit --audit-level=high`, reporting `esbuild <=0.24.2` via `vite` and `flatted <=3.4.1`.
**Root cause:** Frontend toolchain versions resolved to vulnerable ranges (`vite` 5.x family) and transitive `flatted` 3.4.1 from ESLint cache packages.
**Fix:** Upgrade frontend dev toolchain to `vite` 8.x + compatible `@vitejs/plugin-react` and `vitest`/`@vitest/coverage-v8` versions, and pin transitive `flatted` to 3.4.2 via `package.json` `overrides`. Regression check: `npm audit --audit-level=high` must return `found 0 vulnerabilities` in `frontend-client`.

### tm-ui-bff `scan-java` Fails on `github.com/docker/docker` Go-module CVEs

**Symptom:** `tm-ui-bff` `scan-java` Grype step fails on `github.com/docker/docker v28.5.2+incompatible` (`GHSA-x744-4wpc-v9h2`, `GHSA-pxq6-2prw-chj9`) even though the shipped BFF image does not contain Docker CLI/client code.
**Root cause:** `grype dir:.` catalogs the whole repository, including test-only Maven metadata from `org.testcontainers` / `docker-java`; Grype then surfaces Docker Go-module advisories from that non-runtime test stack. `docker-java` 3.7.1 is already the latest release, so there is no version-only remediation inside the current test chain.
**Fix:** In `tm-ui-bff/.github/workflows/pipeline.yml`, generate a CycloneDX SBOM with `mvn -pl bff-service -am -DskipTests package org.cyclonedx:cyclonedx-maven-plugin:2.9.1:makeAggregateBom -DincludeTestScope=false -DoutputFormat=json -DoutputName=sbom-bff-runtime`, then scan `sbom:target/sbom-bff-runtime.json` with Grype instead of `dir:.`

. Regression check: the generated `target/sbom-bff-runtime.json` must contain no `testcontainers`, `docker-java`, or `github.com/docker/docker` entries, and `scan-java` must still fail on real runtime HIGH findings.

### tm-ui-bff Pipeline Node 20 Warning from `actions/cache`

**Symptom:** Pipeline annotations show `Node.js 20 actions are deprecated` mentioning `actions/cache@...`, typically surfaced under `scan-java` even when the failing job is different.
**Root cause:** `actions/setup-java`/`actions/setup-node` cache integration invokes `actions/cache`, and GitHub runner annotations still flag its Node 20 runtime in current runs.
**Fix:** Remove cache inputs from setup actions in `.github/workflows/pipeline.yml` to stop invoking `actions/cache` until the runtime migration is complete. Regression check: subsequent pipeline runs should not show `actions/cache` Node 20 deprecation warnings.

### E2E Host-vs-Browser URL Mismatch

**Symptom:** `SecurityIT` fails with `ConnectException` / `UnresolvedAddressException`, OAuth browser redirects break, or reset-password links target the wrong host.
**Root cause:** The Maven process runs on the GitHub runner host, but Selenium drives a browser inside the `selenium` container on `tm-network`. Host-side HTTP calls must use `localhost`, while browser-side navigation and browser-followed redirects must use Docker-network hostnames like `frontend-bff` and `mock-oauth2`.
**Fix:** Split E2E endpoints by execution context: `e2e.app.url=http://frontend-bff:8080` for browser navigation, `e2e.api.url=http://localhost:8080` for host-side REST tests. In `docker-compose.override.yml`, make browser-facing OAuth authorization URIs configurable with `${MOCK_OAUTH2_BROWSER_BASE_URL:-http://localhost:9000}` and keep `APP_BASE_URL` overridable via `${APP_BASE_URL:-http://localhost:8080}`. Regression check: `SecurityIT` must no longer throw `UnresolvedAddressException`, OAuth redirects must reach mock-oauth2 in CI, and password reset links opened by Selenium must resolve.

### Registration Redirect Stays on `/register`

**Symptom:** Local-registration E2E submits `POST /auth/local/register` successfully but the browser stays on `/register` instead of reaching `/dashboard`.
**Root cause:** `RegisterPage.tsx` awaited `refetchQueries`, but TanStack Query v5 can re-throw when an active observer fetch fails; the throw short-circuited `navigate('/dashboard')`.
**Fix:** Wrap `await queryClient.refetchQueries({ queryKey: ['session'] })` in `try/catch` and always navigate in the success path. Regression check: registration-based E2E scenarios must land on `/dashboard` even if the warm-up refetch fails.

### MFA enable crashes with minified React error #130

**Symptom:** Opening **Settings -> Enable MFA** crashes the SPA with React error #130 (`Element type is invalid ... got: object`), and E2E `AuthIT.mfaEnableAndLoginWithTotp` times out waiting for `mfa-enroll-dialog`.
**Root cause:** MFA enrollment depended on brittle QR rendering paths (first component interop, later an external QR image URL), so runtime rendering could fail or show a blank dialog even though `POST /api/v1/users/me/mfa/enable` succeeded.
**Fix:** Generate the QR code locally in `MfaEnrollDialog.tsx` with the frontend `qrcode` package and keep a visible manual-secret fallback if QR generation fails. Regression check: click **Enable MFA** and verify `[data-testid='mfa-enroll-dialog']` and `[data-testid='mfa-qr-code']` render; if QR generation fails, the fallback message and manual secret must still be visible instead of a white page.

### `/favicon.ico` returns 500 instead of a harmless response

**Symptom:** Browser/monitor requests to `/favicon.ico` produce 500 responses and noisy `Unhandled exception on /favicon.ico` stack traces.
**Root cause:** Missing static icon raised `NoResourceFoundException`, which fell through to the generic exception handler (500).
**Fix:** Bundle favicon assets directly in BFF runtime (`bff-service/src/main/resources/static/favicon.ico` and `/favicon.svg`), serve `/favicon.ico` explicitly from BFF, and handle `NoResourceFoundException` as 404 for genuinely missing resources.
Regression check: `GET /favicon.ico` returns 200 consistently, and missing static paths still return 404 (not 500).

### Theme switches to dark immediately but not back to light

**Symptom:** Saving `DARK` applies instantly, but saving `LIGHT` appears delayed until a later refetch/navigation.
**Root cause:** Profile mutation cache updates were not optimistic/consistent across all update paths, so `ThemeSync` could read stale profile state transiently.
**Fix:** Use optimistic cache updates in `useUpdateProfile` (`onMutate`/rollback/onSuccess + invalidate on settle). Regression check: toggle `LIGHT -> DARK -> LIGHT` and verify `<html>` `dark` class is added and removed immediately after each successful save.

### OAuth2 E2E timeout on mock provider username field

**Symptom:** `AuthIT.loginWithMockOAuth2Google` times out waiting for `By.id("username")` on CI.
**Root cause:** Mock provider login pages can vary input IDs/names across versions (`username`, `subject`, etc.), so a single strict locator is brittle.
**Fix:** In E2E, resolve the first clickable identity input from a small selector list (`#username`, `[name='username']`, `#subject`, `[name='subject']`, fallback text input). Regression check: OAuth2 E2E completes to `/dashboard` without locator-specific flakes.

### OAuth2 E2E fails on missing submit selector

**Symptom:** `AuthIT.loginWithMockOAuth2Google` fails with `NoSuchElementException` for `button[type='submit']`.
**Root cause:** mock-oauth2 UI variants may expose submit as `input[type='submit']` or different button attributes.
**Fix:** Use fallback submit selectors (`button[type='submit']`, `input[type='submit']`, named/id buttons) and fallback to Enter key on the identity input. Regression check: OAuth2 E2E must pass across mock-oauth2 UI variants.

### OAuth2 callback returns `/login?error` with mock Google provider

**Symptom:** `AuthIT.loginWithMockOAuth2Google` completes the mock provider form but lands on `/login?error` instead of `/dashboard`.
**Root cause:** The mocked Google flow had two drift points: Spring Security still expected Google's issuer unless `SPRING_SECURITY_OAUTH2_CLIENT_PROVIDER_GOOGLE_ISSUER_URI` was overridden, and `CoreApiClient.exchangeOidcToken()` previously fabricated fallback emails with a mock domain instead of using provider-supplied email claims only.
**Fix:** Set the Google provider `issuer-uri` to `http://mock-oauth2:8080/default` in `docker-compose.override.yml`, and resolve OAuth2 email strictly from `email`, `preferred_username`, or `sub` only when those values are email-shaped; if none is email-like, fail fast and redirect to `/login?error=oauth2`. Regression check: `CoreApiClientTest.exchangeOidcToken_usesSubjectAsEmailWhenSubjectAlreadyContainsAtSymbol` and `CoreApiClientTest.exchangeOidcToken_throwsWhenProviderDoesNotSupplyAnyEmailLikeClaim` must stay green, and full mock Google login must reach `/dashboard` without `/login?error` in E2E.

### MFA E2E verify can bounce back to `/login`

**Symptom:** `AuthIT.mfaEnableAndLoginWithTotp` reaches `/mfa/verify` but times out waiting for `/dashboard`, ending on `/login`.
**Root cause:** Two issues combined: the E2E needs adjacent-window TOTP retries for CI clock skew, and `MfaVerifyPage.tsx` redirected to `/login` whenever `mfaPending` became false. After a successful verify, the refreshed session is `isAuthenticated:true` and `mfaPending:false`, so the guard could still bounce the user to `/login` before dashboard navigation completed.
**Fix:** Keep the adjacent-window retry in `AuthIT`, keep `await queryClient.refetchQueries({ queryKey: ['session'] })` before navigation, and gate the `/login` redirect on `!mfaPending && !isAuthenticated` so post-verify authenticated sessions are not treated as anonymous. Also keep saving the Spring Security context in `MfaController` so protected `/api/**` routes work immediately after MFA promotion. Regression check: successful MFA verify must land on `/dashboard`, and a follow-up authenticated proxy request must return 200 without an extra login.

### Forgot-password page times out in browser E2E

**Symptom:** Browser E2E reaches `/forgot-password` but never finds the `email` input, even though the React route exists.
**Root cause:** The React app defined `/forgot-password`, but the BFF did not list it in either `OAuth2SecurityConfig`'s public SPA routes or `SpaFallbackController`, so direct navigation was not served as a public SPA page.
**Fix:** Add `/forgot-password` to the Spring Security `permitAll` SPA route list and to `SpaFallbackController`'s forwarded paths. Regression check: navigating directly to `http://localhost:8080/forgot-password` (or `http://frontend-bff:8080/forgot-password` in E2E) must render the forgot-password form for anonymous users.

### SecurityIT `XSRF-TOKEN cookie not found`

**Symptom:** `SecurityIT` fails on the first mutating API call with `XSRF-TOKEN cookie not found — did you call ApiSession.create()?`.
**Root cause:** Spring Security 6 uses deferred CSRF tokens; a plain `GET /login` does not always materialize the token, so `CookieCsrfTokenRepository` may not emit `Set-Cookie: XSRF-TOKEN` unless something accesses `csrfToken.getToken()`.
**Fix:** Add a post-`CsrfFilter` `OncePerRequestFilter` in `OAuth2SecurityConfig` that forces token materialization by reading `CsrfToken` from request attributes and calling `getToken()`. Also keep `SecurityIT.ApiSession` aligned with the BFF contract: `/auth/local/register` and `/auth/local/login` are CSRF-exempt bootstrap calls, so the helper must not require an existing token before calling them, and those request bodies must include `tenantId` like the frontend does.
**Regression check:** `mvn -Dit.test=SecurityIT verify` from `tm-orchestrator/e2e` should pass against a current source-built stack; if it fails with `XSRF-TOKEN cookie not found after GET /auth/session and GET /login`, the runtime image is stale or the CSRF materialization contract regressed.

### Post-register `/api/**` calls return 401 until re-login

**Symptom:** Right after successful local registration, first protected API calls can be rejected as unauthenticated in full-stack flows.
**Root cause:** `LocalAuthController.register()` stored `APP_JWT`/`TENANT_ID` in session but did not save a Spring Security `Authentication` into `SecurityContextRepository`; routes gated by `.anyRequest().authenticated()` can fail until a later login path populates the context.
**Fix:** In `LocalAuthController.register()`, create `UsernamePasswordAuthenticationToken` with role authority, set it into `SecurityContextHolder`, and save via `HttpSessionSecurityContextRepository` (same pattern as `LocalLoginFilter`). Regression check: a newly registered user can call `/api/**` immediately without an extra login step.

### Transparent JWT refresh can fail on malformed 200 responses

**Symptom:** A proxied request that triggers JWT refresh can fail with a null-pointer-style error path even though Core API responded 200 from `/internal/auth/refresh`.
**Root cause:** `RestClient.body(...)` can return `null` for an empty 2xx body, and `{}` deserializes into a response object with `token == null`; the BFF refresh path did not reject either malformed payload shape explicitly.
**Fix:** Make `CoreApiClient.post(...)` fail fast on empty typed responses, and make `refreshJwt()` reject null/blank `token` values with clear `IllegalStateException` messages. Regression check: `CoreApiClientTest.refreshJwt_throwsWhenCoreApiOmitsToken` and `CoreApiClientTest.refreshJwt_throwsWhenCoreApiReturnsEmptyBody` must stay green, and `mvn test` in `tm-ui-bff/bff-service` must pass.

### Spring Boot 4 test stack: MockMvc is no longer auto-wired the old way

**Symptom:** Integration tests fail with missing `AutoConfigureMockMvc` package or `No qualifying bean of type 'MockMvc'`.
**Root cause:** With the upgraded Spring Boot/Spring Security stack, relying on `@AutoConfigureMockMvc` from previous package locations is no longer reliable in this codebase, and tests that expect implicit MockMvc wiring fail.
**Fix:** Build MockMvc explicitly in `@BeforeEach` with `MockMvcBuilders.webAppContextSetup(...)` and include required filters from the application context; for BFF auth/proxy tests, keep session continuity by reusing `MockHttpSession` from the login request. Regression check: `tm-core-api` and `tm-ui-bff` test suites must run without `No qualifying bean` errors and must keep authenticated session state between requests.

### Spring Security 7 `AuthorizationManager` method rename

**Symptom:** Compile fails in `MfaPendingAuthorizationManager` with `does not override abstract method authorize(...)`.
**Root cause:** Spring Security 7 expects `authorize(Supplier<? extends Authentication>, RequestAuthorizationContext)` instead of the previous `check(...)` override signature.
**Fix:** Rename/implement `authorize(...)` with the updated generic signature and return `AuthorizationDecision` as before. Regression check: `bff-service` compile must pass and `/auth/mfa/verify` must still be gated by `MFA_PENDING` session state.

### Jackson 3 namespace migration in BFF modules

**Symptom:** Compile fails with `package com.fasterxml.jackson... does not exist` after upgrading to the current Jackson 3-managed stack.
**Root cause:** The upgraded BOM resolves Jackson classes under `tools.jackson.*`; sources and tests still imported `com.fasterxml.jackson.*`.
**Fix:** Update imports to `tools.jackson.databind.ObjectMapper` and `tools.jackson.core.type.TypeReference`, and keep JSON/session code aligned with those types. Regression check: `tm-ui-bff` `mvn test` must compile and execute with no `com.fasterxml.jackson` missing-class errors.

### Orchestrator E2E `Wait for frontend-bff to be healthy` times out

**Symptom:** `tm-orchestrator` E2E stalls in `Wait for frontend-bff to be healthy` while BFF container keeps restarting, and logs show `NoClassDefFoundError: com/fasterxml/jackson/databind/JsonSerializer` from `RedisSessionConfig.springSessionDefaultRedisSerializer`.
**Root cause:** BFF runtime is on Jackson 3 (`tools.jackson.*`), but `RedisSessionConfig` used `GenericJackson2JsonRedisSerializer` (Jackson 2 API), which references `com.fasterxml.jackson.databind.JsonSerializer` at startup.
**Fix:** Switch to `GenericJacksonJsonRedisSerializer` and pass the Spring-managed `tools.jackson.databind.ObjectMapper` bean (`new GenericJacksonJsonRedisSerializer(objectMapper)`). Regression check: after rebuilding/pulling the updated BFF image, `frontend-bff` stays healthy and the E2E health-wait step passes.

### E2E auth smoke test fails with 500 on `/auth/local/register`

**Symptom:** `tm-orchestrator` E2E fails in `Auth contract smoke test` with `Registration smoke call failed with HTTP 500` and a generic ProblemDetail body from `POST /auth/local/register`.
**Root cause:** Startup timing window — the stack can be partially ready (or briefly unstable) while BFF-to-Core register calls are still transiently failing, so the first register call may hit backend/connection errors.
**Fix:** In `tm-orchestrator/.github/workflows/e2e.yml`, wait for both `frontend-bff` and `core-api` container health, add a warm-up probe for `/auth/session`, retry register on transient statuses (`5xx`/`000`) with backoff, and use curl connect/request timeouts (`--connect-timeout`, `--max-time`). Keep fail-fast behavior for deterministic `4xx` contract errors, and print `frontend-bff`/`core-api` logs when retries are exhausted. Regression check: smoke step should pass without flaky first-call 500s; if it still fails, step logs must include service traces for direct root-cause analysis.

### E2E auth smoke test fails with 500 — `KeyLengthException: The secret length must be at least 256 bits`

**Symptom:** `tm-orchestrator` E2E fails in `Auth contract smoke test` with persistent HTTP 500 on every register retry. Core API logs contain `com.nimbusds.jose.KeyLengthException: The secret length must be at least 256 bits` in the stack trace, thrown by `NimbusJwtEncoder` inside `JwtService`.
**Root cause:** `INTERNAL_JWT_SECRET` environment variable must be a Base64-encoded value that decodes to **at least 32 bytes (256 bits)**. The CI default fallback in `e2e.yml` was `0123456789abcdef0123456789abcdef` (32 Base64 chars), which decodes to only **24 bytes (192 bits)** — four bytes short. NimbusJWT enforces the 256-bit minimum at signing time, causing every `/auth/local/register` call to throw 500. The same error occurs if the GitHub Actions secret `INTERNAL_JWT_SECRET` is configured with a value that is too short.
**Fix:** Changed the CI fallback to `Y2tjb25seS1qd3Qtc2VjcmV0LWZvci10ZXN0aW5nISE=` (44 Base64 chars = 32 bytes = 256 bits exactly, encodes `ci-only-jwt-secret-for-testing!!`). Verify any actual GitHub Actions secret is also at least 44 Base64 chars; generate a valid one with `openssl rand -base64 32`.
**Diagnosis command:** Check core-api logs for `KeyLengthException` — `docker compose logs core-api | grep -i keylength`. Verify a candidate secret decodes to 32 bytes: `python -c "import base64; v='<value>'; print(len(base64.b64decode(v)), 'bytes')"`.
**Regression check:** The `INTERNAL_JWT_SECRET_VALUE` line in `tm-orchestrator/.github/workflows/e2e.yml` must use a 44-character (or longer) Base64 fallback; a 32-character value decodes to only 24 bytes and will always fail. The MFA key fallback (`MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=`) is 44 chars and already correct — use it as a length reference.

### Frontend Vitest logs flooded by non-actionable warnings

**Symptom:** `tm-ui-bff` `test-frontend` job logs are noisy with repeated React Router future-flag warnings, TanStack Query `Query data cannot be undefined` messages in `TaskMatrix` tests, Radix `DialogContent` missing description warnings, and `No routes matched location "/login"` during `AppLayout` logout test.
**Root cause:** Test harness defaults were too permissive: React Router warnings were not filtered in the shared test setup, `TaskMatrix` seeded query data was still considered stale and refetched without a mocked return, dialog components lacked `Dialog.Description`, and the `AppLayout` test router had no `/login` route despite logout navigation.
**Fix:** Add a targeted React Router warning filter in `frontend-client/src/test/setup.ts`, make `TaskMatrix` test QueryClient treat seeded cache as fresh (`staleTime: Infinity`, `refetchOnMount: false`), add `Dialog.Description` to `TaskDialog` and `MfaEnrollDialog`, and include a `/login` route in `AppLayout.test.tsx`. Regression check: `npm test` in `tm-ui-bff/frontend-client` should no longer print those warning classes while preserving failing-test visibility.

