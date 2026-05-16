# Coding Patterns & Implementation Guide

Concrete patterns for the technically complex parts of the application. Read this before implementing those components to avoid mid-implementation rework.

---

## 1. OpenAPI Delegate Pattern (Core API)

Use the **delegate pattern** in `openapi-generator-maven-plugin`. This generates a `@RestController` that delegates to an interface you implement as a `@Service` — you never touch the generated controller.

### `pom.xml` configuration
```xml
<plugin>
  <groupId>org.openapitools</groupId>
  <artifactId>openapi-generator-maven-plugin</artifactId>
  <configuration>
    <inputSpec>${project.basedir}/api-spec/openapi.yaml</inputSpec>
    <generatorName>spring</generatorName>
    <configOptions>
      <delegatePattern>true</delegatePattern>
      <useSpringBoot3>true</useSpringBoot3>
      <apiPackage>com.tm.core.web.api</apiPackage>
      <modelPackage>com.tm.core.web.model</modelPackage>
      <interfaceOnly>false</interfaceOnly>
    </configOptions>
  </configuration>
</plugin>
```

### Generated vs. written code
| Generated (do not edit) | You write |
| :--- | :--- |
| `TasksApi` — `@RestController` stub | `TasksApiDelegate` impl — a `@Service` |
| `TasksApiController` — wires to delegate | Your service logic in `TasksApiDelegateImpl` |
| DTO model classes | Nothing — use the generated models |

```java
// You write this; the rest is generated:
@Service
@RequiredArgsConstructor
public class TasksApiDelegateImpl implements TasksApiDelegate {
    private final TaskService taskService;

    @Override
    public ResponseEntity<TaskDto> createTask(CreateTaskRequest request) {
        return ResponseEntity.status(201).body(taskService.create(request));
    }
}
```

Add `target/generated-sources/openapi/` to `.gitignore`.

---

## 2. BFF Security Filter Chain

The hardest single configuration file in the project. One `SecurityFilterChain` handles OAuth2, local auth, CSRF, MFA gating, and session management.

### Structure of `OAuth2SecurityConfig.java`
```java
@Configuration
@EnableWebSecurity
public class OAuth2SecurityConfig {

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http,
                                           LocalLoginFilter localLoginFilter,
                                           CustomOAuth2SuccessHandler oAuth2SuccessHandler) throws Exception {
        http
            // 1. Authorization rules
            .authorizeHttpRequests(auth -> auth
                .requestMatchers(
                    "/auth/local/register", "/auth/local/login",
                    "/auth/forgot-password", "/auth/reset-password",
                    "/oauth2/**", "/login/**", "/logout",
                    "/actuator/health", "/actuator/info"
                ).permitAll()
                .requestMatchers("/auth/mfa/verify")
                    .access(new MfaPendingAuthorizationManager()) // see §3
                .anyRequest().authenticated()
            )
            // 2. OAuth2 login — redirect-based
            .oauth2Login(oauth2 -> oauth2
                .successHandler(oAuth2SuccessHandler) // see §3
            )
            // 3. Disable form login (local auth is handled by our own filter)
            .formLogin(AbstractHttpConfigurer::disable)
            // 4. CSRF
            .csrf(csrf -> csrf
                .csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
                .csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler())
            )
            // 5. Session — always create (Redis-backed via spring-session-data-redis)
            .sessionManagement(session -> session
                .sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED)
            );

        // 6. Local login filter — runs before UsernamePasswordAuthenticationFilter
        http.addFilterBefore(localLoginFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }
}
```

### Key points
- Never disable CSRF for `/auth/**` — these are state-changing POST endpoints in the browser.
- `CsrfTokenRequestAttributeHandler` (not `XorCsrfTokenRequestAttributeHandler`) — simpler, compatible with React reading the `XSRF-TOKEN` cookie directly.
- Permit `/actuator/health` and `/actuator/info` explicitly — these are used by CI smoke tests and K8s liveness probes.
- **Permit static files** — without this, unauthenticated users get 401 on `GET /index.html` and the login page never loads.
- **Configure logout explicitly** — Spring Session's Redis store is not cleared by the default logout handler unless configured.

### Full `permitAll` and logout configuration (add to the chain above)
```java
// Add to authorizeHttpRequests:
.requestMatchers(
    "/", "/index.html", "/favicon.ico",
    "/assets/**",        // Vite output directory
    "/*.js", "/*.css"    // root-level bundles
).permitAll()

// Add logout block (after sessionManagement):
.logout(logout -> logout
    .logoutUrl("/logout")
    .invalidateHttpSession(true)   // removes Redis session entry
    .deleteCookies("TM_SESSION")   // clears browser cookie
    .logoutSuccessUrl("/login")
)
```

---

## 3. MFA Partial Session State

After primary auth (OAuth2 or local) succeeds, if the user has MFA enabled, the BFF must NOT issue a full session yet. A partial session is created and the user is gated at `/mfa/verify`.

### Session attribute keys (constants class)
```java
public final class SessionKeys {
    public static final String APP_JWT        = "APP_JWT";
    public static final String TENANT_ID      = "TENANT_ID";
    public static final String MFA_PENDING    = "MFA_PENDING";
    public static final String PENDING_USER_ID = "PENDING_USER_ID";
    public static final String PASSWORD_WARNING = "PASSWORD_WARNING";
}
```

### OAuth2 success handler pattern
```java
@Component
public class CustomOAuth2SuccessHandler implements AuthenticationSuccessHandler {

    @Override
    public void onAuthenticationSuccess(HttpServletRequest req,
                                        HttpServletResponse res,
                                        Authentication auth) throws IOException {
        OidcUser oidcUser = (OidcUser) auth.getPrincipal();
        AppJwtResponse jwtResponse = coreApiClient.exchangeOidcToken(oidcUser);

        if (jwtResponse.isMfaRequired()) {  // boolean field `mfaRequired` → getter `isMfaRequired()` per Java Bean spec
            req.getSession().setAttribute(SessionKeys.MFA_PENDING, true);
            req.getSession().setAttribute(SessionKeys.PENDING_USER_ID, jwtResponse.getUserId());
            // Do NOT store APP_JWT yet
            res.sendRedirect("/mfa/verify");
        } else {
            req.getSession().setAttribute(SessionKeys.APP_JWT, jwtResponse.getToken());
            req.getSession().setAttribute(SessionKeys.TENANT_ID, jwtResponse.getTenantId());
            res.sendRedirect("/");
        }
    }
}
```

### MFA gate — custom `AuthorizationManager`
```java
public class MfaPendingAuthorizationManager
        implements AuthorizationManager<RequestAuthorizationContext> {

    @Override
    public AuthorizationDecision check(Supplier<Authentication> auth,
                                       RequestAuthorizationContext ctx) {
        HttpSession session = ctx.getRequest().getSession(false);
        boolean pending = session != null
            && Boolean.TRUE.equals(session.getAttribute(SessionKeys.MFA_PENDING));
        return new AuthorizationDecision(pending);
    }
}
```

The `/auth/mfa/verify` endpoint clears `MFA_PENDING`, stores `APP_JWT`, and returns success.

---

## 4. Tenant Filter Activation (Core API)

### Pattern: `HandlerInterceptor` + `@PersistenceContext` proxy
The `@PersistenceContext` proxy is request-scoped — injecting it into a singleton interceptor is safe because Spring wraps it.

```java
@Component
public class TenantInterceptor implements HandlerInterceptor {

    @PersistenceContext
    private EntityManager entityManager;

    @Override
    public boolean preHandle(HttpServletRequest req, HttpServletResponse res, Object handler) {
        String tenantId = req.getHeader("X-Tenant-ID");
        if (tenantId == null || tenantId.isBlank()) {
            res.setStatus(400);
            return false;
        }
        entityManager.unwrap(Session.class)
            .enableFilter("tenantFilter")
            .setParameter("tenantId", tenantId);
        TenantContext.set(tenantId);
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest req, HttpServletResponse res,
                                Object handler, Exception ex) {
        TenantContext.clear();  // always clear ThreadLocal
    }
}
```

Register it for `/api/**` only — the `/internal/**` endpoints handle tenancy differently (tenantId comes from the JWT payload, not the header):
```java
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(tenantInterceptor).addPathPatterns("/api/**");
    }
}
```

### Defense-in-depth: repository naming convention
Even with the filter active, all user-scoped repository methods must include both `tenantId` and `userId` to make isolation explicit at two levels:
```java
// Correct — scoped to both tenant AND user (prevents BOLA within the same tenant)
Optional<Task> findByIdAndTenantIdAndUserId(UUID id, UUID tenantId, UUID userId);
```
Admin and scheduler code that must query across users must explicitly call `session.disableFilter("tenantFilter")` and be protected by `@PreAuthorize("hasRole('ADMIN')")`. See §14.

---

## 5. Proxy Controller + JWT Refresh (BFF)

### Proxy implementation
The BFF proxy is a `@RestController` that forwards all `/api/**` requests to Core API via `RestClient`, injecting session-stored credentials. Use Spring Boot 3.2+ `RestClient` (not `RestTemplate`).

```java
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class ProxyController {

    private final RestClient restClient;
    private final JwtRefreshService jwtRefreshService;

    @RequestMapping(value = "/**", consumes = MediaType.ALL_VALUE)
    public ResponseEntity<byte[]> proxy(HttpServletRequest request,
                                        HttpSession session,
                                        @RequestBody(required = false) byte[] body) {
        String jwt = jwtRefreshService.getValidJwt(session);  // refreshes if near expiry
        String tenantId = (String) session.getAttribute(SessionKeys.TENANT_ID);

        return restClient
            .method(HttpMethod.valueOf(request.getMethod()))
            .uri(coreApiBaseUrl + extractPath(request))  // path + query string; see helper below
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + jwt)
            .header("X-Tenant-ID", tenantId)
            .header("X-Request-ID", getOrGenerateRequestId(request))
            .contentType(resolveContentType(request))
            .body(body != null ? body : new byte[0])
            .retrieve()
            .toEntity(byte[].class);
    }
}
```

### JWT refresh service
```java
@Service
public class JwtRefreshService {

    private static final int REFRESH_THRESHOLD_SECONDS = 120;

    public String getValidJwt(HttpSession session) {
        String jwt = (String) session.getAttribute(SessionKeys.APP_JWT);
        if (isNearExpiry(jwt)) {
            String userId = extractUserId(jwt);
            String refreshed = coreApiClient.refreshJwt(userId);  // POST /internal/auth/refresh
            session.setAttribute(SessionKeys.APP_JWT, refreshed);
            return refreshed;
        }
        return jwt;
    }

    private boolean isNearExpiry(String jwt) {
        Instant expiry = extractExpiry(jwt);  // parse JWT claims without verifying (BFF trusts it)
        return Instant.now().isAfter(expiry.minusSeconds(REFRESH_THRESHOLD_SECONDS));
    }
}
```

The Core API `/internal/auth/refresh` endpoint (see `06-API-CONTRACT.md`) accepts `{ userId }` and returns a fresh JWT using the same claims from the current user record.

### `extractPath` helper
```java
private String extractPath(HttpServletRequest request) {
    // Must include query string for filters, sorting, pagination params
    String qs = request.getQueryString();
    return qs != null ? request.getRequestURI() + "?" + qs : request.getRequestURI();
}
```

> Do not forward `Cookie`, `Host`, or `Authorization` headers from the incoming request — the proxy sets these explicitly from session state.

---

## 6. Rate Limiting with `bucket4j-core`

`bucket4j-core` has no Spring Boot auto-configuration — wire it as a `HandlerInterceptor`.

```java
@Component
public class RateLimitInterceptor implements HandlerInterceptor {

    // In-memory buckets — sufficient for single-instance dev; see note below for prod
    private final Map<String, Bucket> loginBuckets = new ConcurrentHashMap<>();
    private final Map<String, Bucket> forgotPasswordBuckets = new ConcurrentHashMap<>();

    @Override
    public boolean preHandle(HttpServletRequest req, HttpServletResponse res, Object handler)
            throws IOException {
        String path = req.getRequestURI();
        String key = getClientKey(req, path);

        Bucket bucket = switch (path) {
            case "/auth/local/login" ->
                loginBuckets.computeIfAbsent(key, k -> Bucket.builder()
                    .addLimit(Bandwidth.simple(5, Duration.ofMinutes(1)))
                    .build());
            case "/auth/forgot-password" ->
                forgotPasswordBuckets.computeIfAbsent(key, k -> Bucket.builder()
                    .addLimit(Bandwidth.simple(3, Duration.ofMinutes(15)))
                    .build());
            default -> null;
        };

        if (bucket != null && !bucket.tryConsume(1)) {
            res.setStatus(429);
            res.setHeader("Retry-After", "60");
            return false;
        }
        return true;
    }

    private String getClientKey(HttpServletRequest req, String path) {
        // Key by IP for all endpoints — simple and effective for v1.
        // Future improvement: key forgot-password by email address to prevent
        // an attacker rotating IPs, but that requires parsing the JSON body here.
        return req.getRemoteAddr() + ":" + path;
    }
}
```

> **Production note:** In-memory buckets reset on pod restart and are not shared across scaled BFF instances. For multi-pod deployments, replace `ConcurrentHashMap` with a Redis-backed `ProxyManager` from `bucket4j-redis` (Apache 2.0). For v1 single-instance, in-memory is acceptable.

Register the interceptor for `/auth/**` only.

---

## 7. Scheduled Jobs — Bypassing the Tenant Filter (Core API)

Both scheduled jobs (`overdue task updater`, `token cleanup`) run without a tenant context. The Hibernate tenant filter must be explicitly disabled.

```java
@Service
@RequiredArgsConstructor
public class ScheduledJobService {

    @PersistenceContext
    private EntityManager entityManager;
    private final TaskHistoryService taskHistoryService;  // see §20

    @Scheduled(cron = "0 5 0 * * *", zone = "UTC")  // 00:05 UTC daily
    @Transactional
    public void markOverdueTasks() {
        Session session = entityManager.unwrap(Session.class);
        session.disableFilter("tenantFilter");
        // Fetch first so TaskHistoryService can record a history row per transition (see §20).
        // IMPORTANT: @SQLRestriction is NOT applied to bulk DML — the deletedAt IS NULL predicate must be explicit here.
        List<Task> toTransition = entityManager.createQuery(
            "SELECT t FROM Task t WHERE t.dueDate < CURRENT_DATE " +
            "AND t.state IN :states AND t.deletedAt IS NULL", Task.class)
            .setParameter("states", List.of(TaskState.PLANNED, TaskState.IN_PROGRESS))
            .getResultList();
        for (Task task : toTransition) {
            TaskState previous = task.getState();
            task.setState(TaskState.OVERDUE);
            // .name() converts enum to the string stored in task_history (fromState/toState are Strings)
            taskHistoryService.record(task, previous.name(), TaskState.OVERDUE.name(), task.getUserId());
        }
        // JPA flushes all dirty entities at transaction commit — no explicit save() call needed.
    }

    @Scheduled(cron = "0 10 0 * * *", zone = "UTC")  // 00:10 UTC daily
    @Transactional
    public void cleanExpiredResetTokens() {
        entityManager.unwrap(Session.class).disableFilter("tenantFilter");
        // delete tokens where (used_at IS NOT NULL OR expires_at < now()) AND created_at < now() - 7 days
    }
}
```

Add to the Core API `@SpringBootApplication` class (or a `@Configuration`):
```java
@EnableScheduling
@SpringBootApplication
public class CoreApiApplication { ... }
```

And configure the thread pool in `application.yml`:
```yaml
spring:
  task:
    scheduling:
      pool:
        size: 2
```

---

## 8. BCrypt Profile Configuration

BCrypt cost 12 is correct for production but makes integration tests that hash passwords take ~1 second each. Use a `@Configuration` with a profile-aware bean.

```java
@Configuration
public class SecurityConfig {

    @Bean
    @Profile("!test")
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12);
    }

    @Bean
    @Profile("test")
    public PasswordEncoder testPasswordEncoder() {
        return new BCryptPasswordEncoder(4);  // fast for tests; same algorithm
    }
}
```

Set `spring.profiles.active=test` in `src/test/resources/application-test.yml` (or on the test class via `@ActiveProfiles("test")`).

---

## 9. Redis Session Serialization

Spring Session defaults to Java serialization — fragile and unreadable. Configure Jackson from the start.

```java
@Configuration
@EnableRedisHttpSession  // timeout is controlled by spring.session.timeout in application.yml
public class RedisSessionConfig {

    @Bean
    public RedisSerializer<Object> springSessionDefaultRedisSerializer() {
        return new Jackson2JsonRedisSerializer<>(Object.class);
    }
}
```

The session timeout comes from `application.yml` — do not hardcode it in the annotation:
```yaml
spring:
  session:
    timeout: ${SESSION_TIMEOUT_MINUTES:30}m
```

> This must be configured **before** any sessions are created in production. Existing Java-serialized sessions in Redis will be unreadable after switching, so set this up from day one.

---

## 10. React Library Stack

All libraries are MIT-licensed. Add all to `frontend-client/package.json`.

| Library | Version | Purpose | Licence |
| :--- | :--- | :--- | :--- |
| `react-router-dom` | v6 | Client-side routing, auth guards | MIT |
| `@tanstack/react-query` | v5 | Server state, data fetching, caching | MIT |
| `react-hook-form` | v7 | Form state management, validation | MIT |
| `tailwindcss` | v3 | Utility-first CSS, dark mode via `dark:` prefix | MIT |
| `@radix-ui/react-*` | latest | Accessible UI primitives (dialog, dropdown, etc.) | MIT |
| `axios` | v1 | HTTP client (consistent error handling with interceptors) | MIT |
| `@zxcvbn-ts/zxcvbn` | latest | Password strength scoring | Apache 2.0 |
| `lucide-react` | latest | Icon set | ISC |

### TypeScript type generation
Generate TypeScript types from the OpenAPI spec **before writing any component**. This is the single source of truth for all API shapes.

```bash
# Install once (MIT licence):
npm install --save-dev openapi-typescript

# Add to frontend-client/package.json scripts:
"codegen": "openapi-typescript ../../../tm-core-api/api-spec/openapi.yaml -o src/api/schema.d.ts"
```

Run `npm run codegen` whenever `openapi.yaml` changes. Import types directly — do not hand-write interfaces that duplicate the spec.

```ts
import type { components } from './api/schema';

type Task       = components['schemas']['Task'];
type CreateTask = components['schemas']['CreateTaskRequest'];
```

> Add `src/api/schema.d.ts` to `.gitignore` — it is always regenerated from the spec.

---

### Routing structure
```
/                          → redirect to /dashboard (if authenticated) or /login
/login                     → login page (local + OAuth2 buttons)
/register                  → registration form
/auth/reset-password       → password reset form (token in query param)
/mfa/verify                → MFA TOTP input (only accessible with mfa_pending session)
/dashboard                 → main task matrix view (protected)
/settings                  → settings page (protected)
  /settings/profile
  /settings/security       → change password, MFA toggle
/admin                     → admin panel (ADMIN role only)
```

### Auth guard pattern
```tsx
// Use TanStack Query to fetch /auth/session — cached, auto-refetched
function ProtectedRoute({ children }: { children: React.ReactNode }) {
    const { data: session, isLoading } = useSession();  // calls GET /auth/session
    if (isLoading) return <Spinner />;
    if (!session?.isAuthenticated) return <Navigate to="/login" />;
    if (session.mfaPending) return <Navigate to="/mfa/verify" />;
    return <>{children}</>;
}
```

### Dark mode
Configure Tailwind with `darkMode: 'class'` in `tailwind.config.ts`. Toggle by adding/removing the `dark` class on `<html>`. Persist the preference via `PUT /api/v1/users/me` (`theme` field).

### Password age warning banner
On every authenticated page load, `GET /auth/session` returns `passwordWarning: boolean`. If true, render a dismissible banner component. Dismiss stores the dismissed state in React state only (re-appears on next login as designed).

---

## 11. Dockerfile Layer Caching (tm-ui-bff)

The multi-stage build must cache Maven and npm dependencies as separate layers. Copy dependency manifests first, install, then copy source. This reduces rebuild time from ~5 minutes to ~20 seconds on source-only changes.

```dockerfile
# ── Stage 1: Build React ────────────────────────────────────────────────────
FROM node:20-alpine AS frontend
WORKDIR /app
COPY frontend-client/package*.json ./
RUN npm ci --prefer-offline                   # layer cached until package.json changes
COPY frontend-client/ .
RUN npm run build

# ── Stage 2: Build Spring Boot JAR ──────────────────────────────────────────
FROM maven:3.9-eclipse-temurin-17 AS backend
WORKDIR /app
# Copy parent pom first (cache layer)
COPY pom.xml ./
COPY bff-service/pom.xml bff-service/
RUN mvn dependency:go-offline -q -f pom.xml   # layer cached until pom.xml changes
# Copy source and static assets
COPY bff-service/src bff-service/src
COPY --from=frontend /app/dist bff-service/src/main/resources/static
RUN mvn clean package -DskipTests -f pom.xml

# ── Stage 3: Runtime ─────────────────────────────────────────────────────────
FROM eclipse-temurin:17-jre-jammy AS runtime
RUN addgroup -S app && adduser -S app -G app   # non-root user
USER app
COPY --from=backend /app/bff-service/target/*.jar app.jar
ENTRYPOINT ["java", "-jar", "/app.jar"]
```

Apply the same `dependency:go-offline` pattern to the `tm-core-api` and `tm-db-schema` Dockerfiles.

---

## 12. Bootstrap Admin User

On a fresh deployment, no users exist — but `POST /api/v1/admin/tenants` requires `ADMIN` role. This is a catch-22 resolved by seeding a bootstrap admin via Liquibase.

### In `002-create-roles.yaml` (already exists)
Seeds `STANDARD` and `ADMIN` roles — no change needed.

### New changeset: `007-bootstrap-admin.yaml`
```yaml
databaseChangeLog:
  - changeSet:
      id: 007-bootstrap-admin
      author: system
      context: prod,dev    # runs in ALL environments
      changes:
        - insert:
            tableName: tenants
            columns:
              - column: { name: id,   value: "00000000-0000-0000-0000-000000000001" }
              - column: { name: name, value: "Default" }
        - insert:
            tableName: users
            columns:
              - column: { name: id,           value: "00000000-0000-0000-0000-000000000002" }
              - column: { name: tenant_id,     value: "00000000-0000-0000-0000-000000000001" }
              - column: { name: email,         value: "admin@task-manager.local" }
              - column: { name: auth_provider, value: "LOCAL" }
              - column: { name: password_hash, value: "${BOOTSTRAP_ADMIN_BCRYPT_HASH}" }
              - column: { name: role_id,       selectQuery: "SELECT id FROM roles WHERE name='ADMIN'" }
              - column: { name: is_mfa_enabled, valueBoolean: false }
              - column: { name: theme,          value: "LIGHT" }
              - column: { name: password_changed_at, valueComputed: "now()" }
      rollback:
        - delete: { tableName: users,   where: "id='00000000-0000-0000-0000-000000000002'" }
        - delete: { tableName: tenants, where: "id='00000000-0000-0000-0000-000000000001'" }
```

`BOOTSTRAP_ADMIN_BCRYPT_HASH` is a Liquibase substitution variable injected via env var (BCrypt hash of the initial admin password). **Change the password immediately after first login.** Document the initial credentials in `03-GETTING-STARTED.md §6` for local dev.

Add `BOOTSTRAP_ADMIN_BCRYPT_HASH` to `12-ENV-VARS.md` under `tm-db-schema`.

---

## 13. `application.yml` Key Stubs

The most error-prone configuration sections. Start from these stubs.

### Core API (`tm-core-api/src/main/resources/application.yml`)
```yaml
spring:
  datasource:
    url: jdbc:postgresql://${DB_HOST}:${DB_PORT:5432}/${DB_NAME}
    username: ${DB_USERNAME}
    password: ${DB_PASSWORD}
    hikari:
      maximum-pool-size: 10
  liquibase:
    enabled: false                # tm-db-schema owns all migrations — Core API must NEVER run them
  jpa:
    hibernate:
      ddl-auto: validate          # Liquibase owns the schema — never let Hibernate change it
    properties:
      hibernate.session_factory.statement_inspector: com.tm.core.infrastructure.TenantStatementInspector  # optional, for debug
  mail:
    host: ${SMTP_HOST}
    port: ${SMTP_PORT:587}
    username: ${SMTP_USERNAME:}
    password: ${SMTP_PASSWORD:}
    properties:
      mail.smtp.starttls.enable: ${SMTP_TLS_ENABLED:true}

app:
  jwt:
    secret: ${INTERNAL_JWT_SECRET}
    expiry-minutes: ${JWT_EXPIRY_MINUTES:15}
  mfa:
    encryption-key: ${MFA_ENCRYPTION_KEY}
  base-url: ${APP_BASE_URL}

management:
  endpoints.web.exposure.include: health,info
  endpoint.health.show-details: when-authorized

server:
  port: ${SERVER_PORT:8080}
  error:
    include-stacktrace: never
    include-message: never
    include-exception: false
```

### BFF (`tm-ui-bff/bff-service/src/main/resources/application.yml`)
```yaml
spring:
  session:
    redis:
      flush-mode: on-save
      namespace: tm:session
    timeout: ${SESSION_TIMEOUT_MINUTES:30}m
  data:
    redis:
      host: ${REDIS_HOST}
      port: ${REDIS_PORT:6379}
      password: ${REDIS_PASSWORD:}
  security:
    oauth2:
      client:
        registration:
          google:
            client-id: ${GOOGLE_CLIENT_ID:disabled}
            client-secret: ${GOOGLE_CLIENT_SECRET:disabled}
            scope: openid,email,profile
          microsoft:
            client-id: ${MICROSOFT_CLIENT_ID:disabled}
            client-secret: ${MICROSOFT_CLIENT_SECRET:disabled}
            scope: openid,email,profile,offline_access
        provider:
          microsoft:
            authorization-uri: https://login.microsoftonline.com/consumers/v2.0/oauth2/v2.0/authorize
            token-uri:         https://login.microsoftonline.com/consumers/v2.0/oauth2/v2.0/token
            jwk-set-uri:       https://login.microsoftonline.com/consumers/v2.0/discovery/v2.0/keys
            user-info-uri:     https://graph.microsoft.com/oidc/userinfo
            user-name-attribute: sub

app:
  core-api-base-url: ${CORE_API_BASE_URL}

server:
  port: ${SERVER_PORT:8080}
```

Setting client IDs to `:disabled` as a default means the BFF starts cleanly in environments where OAuth2 is not configured, rather than crashing on missing env vars. Spring Security will simply not register those OAuth2 clients.

### Profile-specific overrides

#### `src/test/resources/application-test.yml` (Core API + BFF)
Applied when `@ActiveProfiles("test")` is set. Overrides the main `application.yml`.
```yaml
spring:
  jpa:
    show-sql: true    # helpful during test debugging; never enable in prod or dev

app:
  # BCrypt cost 4 is configured via SecurityConfig @Profile("test") bean — no yml entry needed
  jwt:
    secret: dGVzdC1zZWNyZXQtbXVzdC1iZS0zMi1ieXRlcy1sb25n    # test-only key (Base64, 32 bytes)
  mfa:
    encryption-key: dGVzdC1lbmNyeXB0aW9uLWtleS0zMi1ieXRlcw==  # test-only key (Base64, 32 bytes)
  base-url: http://localhost:8080
```

#### `src/main/resources/application-dev.yml` (Core API + BFF)
Applied when `SPRING_PROFILES_ACTIVE=dev`. Enables verbose output for local development.
```yaml
spring:
  jpa:
    show-sql: false   # keep false even in dev; use query logging only when needed

logging:
  level:
    com.tm: DEBUG
    org.springframework.security: DEBUG  # remove when auth flow is confirmed working
```

---

## 14. DTO Security Design (Mass Assignment & Sensitive Field Exposure)

Two distinct DTO types per resource. Never use the JPA entity directly as a request or response body.

### Request DTO rules
- Only contains fields the client is **allowed to set**.
- Never contains: `id`, `tenantId`, `userId`, `createdAt`, `updatedAt`, `authProvider`, `role`, `passwordHash`, `mfaSecret`.
- Annotate with `@JsonIgnoreProperties(ignoreUnknown = true)` — silently drops unknown fields instead of failing.

```java
@JsonIgnoreProperties(ignoreUnknown = true)
public record CreateTaskRequest(
    @NotBlank @Size(max = 255) String title,
    String description,
    @NotNull TaskImportance importance,
    @NotNull TaskUrgency urgency,
    LocalDate dueDate  // optional
) {}
```

### Response DTO rules
- Only contains fields safe to expose to the caller.
- Never includes: `passwordHash`, `mfaSecret`, `tokenHash`.
- Use `@JsonProperty(access = Access.WRITE_ONLY)` on any sensitive field that must exist on the entity for Jackson binding but must never be serialised out.
- Use Lombok `@ToString.Exclude` on all sensitive fields to keep them out of logs.

```java
@Entity
public class User {
    // ...
    @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
    @ToString.Exclude
    private String passwordHash;

    @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
    @ToString.Exclude
    private String mfaSecret;
}
```

### `updated_at` auto-update
`updated_at` must be kept current on every JPA-managed write. Use Spring Data's `@LastModifiedDate` (requires `@EnableJpaAuditing` + `@EntityListeners(AuditingEntityListener.class)`):

```java
@MappedSuperclass
@EntityListeners(AuditingEntityListener.class)
public abstract class AuditableEntity {
    @CreatedDate
    @Column(updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    private Instant updatedAt;
}
```

All entities (`Task`, `User`) extend `AuditableEntity`. The scheduler's bulk UPDATE sets `updated_at = now()` explicitly in JPQL (Spring Data auditing only fires on JPA-managed entity saves, not bulk queries).

### Optimistic locking (`@Version`)
Prevents lost updates when two sessions edit the same task concurrently. The `version` column in the `tasks` table maps to a `@Version` field:

```java
// Hibernate 6+ replacement for the deprecated @Where annotation.
// Applied to all SELECT queries — NOT to bulk DML (see §7, §20).
@SQLRestriction("deleted_at IS NULL")
@Entity
public class Task extends AuditableEntity {
    // ...
    @Version
    private int version;       // maps to tasks.version INT DEFAULT 0

    private Instant deletedAt; // null = active; set on soft-delete, never managed by JPA auditing
}
```

JPA automatically increments `version` on every UPDATE and adds `WHERE version = ?` to the query. If the version has changed since the entity was read, JPA throws `OptimisticLockException`. Handle in `GlobalExceptionHandler` — return `HTTP 409 Conflict` with a message telling the client to reload and retry.

```java
@ExceptionHandler(OptimisticLockException.class)
public ResponseEntity<ProblemDetail> handleOptimisticLock(OptimisticLockException ex) {
    ProblemDetail pd = ProblemDetail.forStatus(HttpStatus.CONFLICT);
    pd.setTitle("Conflict");
    pd.setDetail("The resource was modified by another request. Reload and try again.");
    return ResponseEntity.status(HttpStatus.CONFLICT).body(pd);
}
```

### BOLA defence: always include userId in user-scoped queries
Tasks belong to a user within a tenant. Standard users must only read/modify their own tasks. Repository methods must include all three scope identifiers:

```java
// Correct — scoped to tenant AND user
Optional<Task> findByIdAndTenantIdAndUserId(UUID id, UUID tenantId, UUID userId);

// Wrong — leaks tasks across users in the same tenant
Optional<Task> findByIdAndTenantId(UUID id, UUID tenantId);
```

The `userId` is read from the validated JWT claim (`sub`) in the service layer — never from the request body.

Admin service methods that query cross-user must explicitly call `session.disableFilter("tenantFilter")` and be guarded by `@PreAuthorize("hasRole('ADMIN')")`.

---

## 15. JWT Validation in Core API (Algorithm Pinning)

Core API must explicitly pin the algorithm to `HS256` and validate all claims. The single `SecurityFilterChain` covers all paths with explicit per-path rules.

```java
@Configuration
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
            .macAlgorithm(MacAlgorithm.HS256)  // pins algorithm — rejects none, RS256, etc.
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
            .oauth2ResourceServer(oauth2 -> oauth2.jwt(jwt -> jwt.decoder(jwtDecoder())))
            // Resource server uses tokens, not sessions or cookies — disable both
            .csrf(AbstractHttpConfigurer::disable)
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS));
        return http.build();
    }
}
```

**Why `permitAll()` on `/internal/**` at the Spring Security level?**
The BFF calls `/internal/**` without a user JWT (no JWT exists yet during login flows). Network isolation (Kubernetes `NetworkPolicy` / Docker bridge network) is the primary access control. Spring Security's `permitAll()` here means: "I trust the network boundary; do not require a JWT for these paths." If the network boundary is ever misconfigured, the endpoints still only accept calls from within the compose/cluster network.

---

## 16. Error Response Sanitization

Stack traces in error responses expose implementation details and class names. Configure this in `application.yml` for all profiles.

### Core API `application.yml` (add to existing stubs in §13)
```yaml
server:
  error:
    include-stacktrace: never       # never send stack traces to clients
    include-message: never          # suppress exception messages in prod
    include-exception: false        # do not include exception class name

spring:
  jpa:
    show-sql: false                 # never log SQL in any shared environment
```

### Custom error handler
Spring Boot's default `/error` endpoint formats RFC 7807. Override it to ensure consistent format and to strip any residual detail:

```java
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ProblemDetail> handleValidation(ConstraintViolationException ex) {
        ProblemDetail pd = ProblemDetail.forStatus(HttpStatus.UNPROCESSABLE_ENTITY);
        pd.setTitle("Validation Failed");
        pd.setDetail(buildValidationMessage(ex));  // only field-level messages, no stack
        return ResponseEntity.unprocessableEntity().body(pd);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ProblemDetail> handleGeneric(Exception ex, HttpServletRequest req) {
        log.error("Unhandled exception on {}", req.getRequestURI(), ex);  // log internally
        ProblemDetail pd = ProblemDetail.forStatus(HttpStatus.INTERNAL_SERVER_ERROR);
        pd.setTitle("Internal Server Error");
        // No detail, no stack trace sent to client
        return ResponseEntity.internalServerError().body(pd);
    }
}
```

---

## 17. MFA Failed Attempt Lockout

TOTP has a 30-second validity window with 6 digits. Five wrong guesses must invalidate the partial session — the user must re-authenticate from the primary step.

This is implemented in the `/auth/mfa/verify` controller (not in the rate-limit interceptor) because it tracks per-session state:

```java
@PostMapping("/auth/mfa/verify")
public ResponseEntity<Void> verifyMfa(@RequestBody MfaVerifyRequest request,
                                       HttpSession session) {
    Integer attempts = (Integer) session.getAttribute("MFA_ATTEMPTS");
    if (attempts == null) attempts = 0;

    if (attempts >= 5) {
        session.invalidate();   // force full re-authentication
        return ResponseEntity.status(401)
            .header("X-MFA-Lockout", "true")
            .build();
    }

    boolean valid = coreApiClient.validateMfa(
        (String) session.getAttribute(SessionKeys.PENDING_USER_ID),
        request.code()
    );

    if (!valid) {
        session.setAttribute("MFA_ATTEMPTS", attempts + 1);
        return ResponseEntity.status(401).build();
    }

    // Success — clear MFA state, store full JWT
    session.removeAttribute(SessionKeys.MFA_PENDING);
    session.removeAttribute(SessionKeys.PENDING_USER_ID);
    session.removeAttribute("MFA_ATTEMPTS");
    // ... store APP_JWT, TENANT_ID from Core API response
    return ResponseEntity.ok().build();
}

---

## 18. SPA Fallback Controller (BFF)

React Router handles all client-side navigation. When a user refreshes the browser on `/dashboard` or `/settings/security`, the BFF receives a GET request for that path and must return `index.html` — not a 404.

Without this controller, any page refresh on a non-root route will return 404, breaking navigation.

```java
@Controller
public class SpaFallbackController {

    /**
     * Forward any non-API, non-asset GET request to index.html so React Router
     * can handle the route client-side.
     *
     * Exclusions (handled before this controller):
     *  - /api/**         → ProxyController
     *  - /auth/**        → auth controllers
     *  - /oauth2/**      → Spring Security OAuth2
     *  - /login/**       → Spring Security
     *  - /logout         → Spring Security
     *  - /actuator/**    → Actuator
     *  - /assets/**      → static files (served by Spring's ResourceHttpRequestHandler)
     *  - /*.js, /*.css   → static files
     */
    @GetMapping(value = {
        "/",
        "/login",
        "/register",
        "/mfa/verify",
        "/dashboard",
        "/settings",
        "/settings/**",
        "/admin",
        "/admin/**",
        "/auth/reset-password"
    })
    public String spa() {
        return "forward:/index.html";
    }
}
```

> Register this **after** all other `@RequestMapping` routes so it only catches routes not handled elsewhere. Spring MVC processes more specific mappings first, so the explicit path list above is safe.

---

## 19. AES-256 TOTP Secret Encryption

TOTP secrets are stored AES-256 encrypted in `users.mfa_secret`. Use AES/GCM/NoPadding — it provides authenticated encryption (integrity + confidentiality) and is available in every JDK without additional libraries.

```java
@Service
public class MfaService {

    private static final String ALGORITHM = "AES/GCM/NoPadding";
    private static final int IV_LENGTH_BYTES = 12;   // 96-bit IV recommended for GCM
    private static final int TAG_LENGTH_BITS  = 128;

    private final SecretKey encryptionKey;

    public MfaService(@Value("${app.mfa.encryption-key}") String base64Key) {
        byte[] keyBytes = Base64.getDecoder().decode(base64Key);
        this.encryptionKey = new SecretKeySpec(keyBytes, "AES");
        // Validate key length at startup — fail fast rather than silently using a weak key
        if (keyBytes.length != 32) {
            throw new IllegalStateException("MFA_ENCRYPTION_KEY must be exactly 32 bytes (256 bits)");
        }
    }

    public String encrypt(String plaintext) {
        try {
            byte[] iv = new byte[IV_LENGTH_BYTES];
            new SecureRandom().nextBytes(iv);

            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.ENCRYPT_MODE, encryptionKey,
                        new GCMParameterSpec(TAG_LENGTH_BITS, iv));

            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));

            // Prepend IV to ciphertext for storage: Base64(IV || ciphertext)
            byte[] ivAndCiphertext = new byte[iv.length + ciphertext.length];
            System.arraycopy(iv, 0, ivAndCiphertext, 0, iv.length);
            System.arraycopy(ciphertext, 0, ivAndCiphertext, iv.length, ciphertext.length);

            return Base64.getEncoder().encodeToString(ivAndCiphertext);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("MFA encryption failed", e);
        }
    }

    public String decrypt(String base64Encoded) {
        try {
            byte[] ivAndCiphertext = Base64.getDecoder().decode(base64Encoded);
            byte[] iv         = Arrays.copyOfRange(ivAndCiphertext, 0, IV_LENGTH_BYTES);
            byte[] ciphertext = Arrays.copyOfRange(ivAndCiphertext, IV_LENGTH_BYTES, ivAndCiphertext.length);

            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.DECRYPT_MODE, encryptionKey,
                        new GCMParameterSpec(TAG_LENGTH_BITS, iv));

            return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("MFA decryption failed", e);
        }
    }
}
```

**Key generation** — generate with `openssl rand -base64 32` and set as `MFA_ENCRYPTION_KEY`. The `12-ENV-VARS.md` entry includes this command. If the key is ever rotated, all existing `mfa_secret` values must be re-encrypted in a migration script before the new key is deployed.

---

## 20. Soft-Delete & Task History (Core API)

### `@SQLRestriction` on the `Task` entity
Hibernate 6 replaces the deprecated `@Where` with `@SQLRestriction`. It appends a SQL predicate to every SELECT generated for the annotated entity, making soft-deleted rows invisible to all standard JPA queries without any change to repository or service code.

```java
// @SQLRestriction is the Hibernate 6+ replacement for @Where (Spring Boot 3.x / Hibernate 6.2+).
@SQLRestriction("deleted_at IS NULL")
@Entity
public class Task extends AuditableEntity {
    private Instant deletedAt;  // null = active; never set by JPA auditing
    // ... other fields
}
```

**Critical caveat:** `@SQLRestriction` is NOT applied to bulk JPQL `UPDATE` or `DELETE` statements. Any bulk DML query on `Task` must include `AND t.deletedAt IS NULL` explicitly. See §7 for the scheduler pattern.

---

### Soft-delete in `TaskService`
Do not call `taskRepository.delete()`. Set `deletedAt` and save. The `@Where` filter makes the row disappear from all subsequent queries automatically.

```java
@Transactional
public void deleteTask(UUID id, UUID tenantId, UUID userId) {
    Task task = taskRepository.findByIdAndTenantIdAndUserId(id, tenantId, userId)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    task.setDeletedAt(Instant.now());
    taskRepository.save(task);
    // No task_history row on deletion — history tracks state transitions only.
    // The deletedAt timestamp is the deletion record.
}
```

---

### `TaskHistoryService` — write-only in v1
Records a row in `task_history` on every state transition. Call it from `TaskService` on every state change and from `ScheduledJobService` for scheduler-driven transitions. There is no read API for task history in v1.

The `TaskHistory` entity must carry the Hibernate tenant filter (consistent with `Task` — see `08-MULTI-TENANCY.md §4`):
```java
@Filter(name = "tenantFilter", condition = "tenant_id = :tenantId")
@Entity
public class TaskHistory {
    // id, taskId, tenantId, changedBy, fromState, toState, changedAt
}
```

```java
@Service
@RequiredArgsConstructor
public class TaskHistoryService {

    private final TaskHistoryRepository taskHistoryRepository;

    /**
     * @param task      the task after the transition (used for taskId, tenantId, userId)
     * @param fromState null on initial task creation (PLANNED is the first state)
     * @param toState   the new state
     * @param changedBy the user who triggered the transition; for scheduler use task.getUserId()
     */
    public void record(Task task, String fromState, String toState, UUID changedBy) {
        TaskHistory history = new TaskHistory();
        history.setTaskId(task.getId());
        history.setTenantId(task.getTenantId());
        history.setChangedBy(changedBy);
        history.setFromState(fromState);
        history.setToState(toState);
        // changedAt is DEFAULT now() in the DB; no need to set it explicitly
        taskHistoryRepository.save(history);
    }
}
```

Call sites in `TaskService`:
```java
// On create — fromState is null (task did not exist before).
// .name() converts the TaskState enum to the String stored in task_history.
taskHistoryService.record(savedTask, null, TaskState.PLANNED.name(), userId);

// On state transition (PATCH /tasks/{id})
taskHistoryService.record(task, previousState.name(), newState.name(), userId);
```