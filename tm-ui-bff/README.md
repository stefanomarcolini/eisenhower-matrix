# tm-ui-bff

BFF (Backend for Frontend) and React SPA. One containerized service, two sub-layers: a Spring Boot BFF and a Vite/React TypeScript frontend.

> Architecture and design documentation is in `../tm-documentation` in this monorepo.

## Why a BFF?
Keeps all tokens (OAuth2 and app-issued JWTs) off the browser. React delegates all authentication logic to the Java layer. Only a session cookie (`TM_SESSION`) reaches the browser.

## Directory Structure
```text
tm-ui-bff/
├── .github/workflows/
│   └── pipeline.yml
├── bff-service/
│   ├── src/main/java/
│   │   ├── config/
│   │   │   ├── RedisSessionConfig.java    # Spring Session backed by Redis
│   │   │   ├── CorsConfig.java            # Dev-profile only: allow localhost:5173
│   │   │   └── OAuth2SecurityConfig.java  # OAuth2/OIDC + local auth filter chain
│   │   ├── auth/                          # Local auth controllers (/auth/local/*)
│   │   └── proxy/                         # Core API proxy: injects X-Tenant-ID + Bearer
│   └── pom.xml
├── frontend-client/
│   ├── src/                   # Components, Hooks, State
│   ├── public/
│   ├── vite.config.ts         # Dev proxy: /api/**, /oauth2/**, /login/**, /logout, /auth/**
│   ├── .env.development       # VITE_API_BASE_URL= (empty)
│   ├── .env.production        # VITE_API_BASE_URL= (empty)
│   └── package.json
├── Dockerfile                 # Multi-stage: npm build → mvn build → JRE run
└── pom.xml
```

## Running Locally (Dev Mode with live-reload)
```bash
# Terminal 1 — start backend services (db, redis, mock-oauth2, mailpit, core-api)
# from tm-orchestrator/:
docker-compose up -d db redis db-migrations mock-oauth2 mailpit core-api

# Terminal 2 — start BFF
cd bff-service && mvn spring-boot:run -Dspring-boot.run.profiles=dev

# Terminal 3 — start React dev server
cd frontend-client && npm install && npm run dev
# App at http://localhost:5173 (proxied through BFF at :8080)
```

## Stopping
`Ctrl+C` in each dev-mode terminal. For compose: `docker-compose stop frontend-bff`. See `DEVELOPMENT_ENV.md §7` in `tm-documentation`.

## Session Management
Redis-backed Spring Session. No sticky sessions needed. Scalable with `docker-compose up --scale frontend-bff=3`. See `AUTH_CONFIG.md §8` in `tm-documentation`.

## Auth Flows
- **OAuth2:** Google personal accounts + Microsoft personal accounts (consumers tenant). See `AUTH_CONFIG.md §2–§5` in `tm-documentation`.
- **Local:** Email + password registration/login, password reset, password age warning. See `AUTH_CONFIG.md §6` and `PASSWORD_POLICY.md` in `tm-documentation`.
- **MFA:** TOTP via any Authenticator app. Supported for both auth methods.

## Docker Build
Multi-stage `Dockerfile`:
1. **Node stage:** `npm ci && npm run build` → `dist/`.
2. **Maven stage:** Copies `dist/` into `src/main/resources/static/`, then `mvn clean package` → fat JAR.
3. **JRE stage:** Copies JAR into `eclipse-temurin:17-jre-jammy`.

## Environment Variables
See `ENV_VARS.md` in `tm-documentation`.

## CI/CD
Pipeline: build + unit tests (React + Java) → dependency vulnerability scans (`grype dir` + `npm audit`) → Syft SBOM + Grype image scan → Docker push. Orchestrator E2E runs independently (manual + main push, no cross-repo dispatch token). See `REPOSITORIES_AND_CICD.md` in `tm-documentation`.
