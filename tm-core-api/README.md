# tm-core-api

Main business logic and data persistence service. Exposes a public REST API (generated from OpenAPI) and internal endpoints consumed only by the BFF.

> Architecture and design documentation is in `../tm-documentation` in this monorepo.

## Directory Structure
```text
tm-core-api/
├── .github/workflows/
│   └── pipeline.yml
├── src/
│   ├── main/java/com/tm/core/
│   │   ├── domain/            # Entities, Value Objects (DDD)
│   │   ├── application/       # Services, DTOs
│   │   ├── infrastructure/    # Repositories, TenantContext, email sender
│   │   └── web/               # Controllers (generated from OpenAPI) + internal auth controllers
│   └── test/java/
├── api-spec/
│   └── openapi.yaml           # Canonical API contract — see API_CONTRACT.md in tm-documentation
└── pom.xml
```

## API Contract
`api-spec/openapi.yaml` is the single source of truth for the public REST API. Interfaces are generated at build time — do not write controller classes by hand. See `API_CONTRACT.md` in `tm-documentation` for the human-readable reference including the internal endpoint specs.

## Running Locally (with Testcontainers)
```bash
mvn spring-boot:test-run
```
Requires Spring Boot 3.2+. Testcontainers spins up `postgres:17-alpine` automatically. Liquibase runs on startup. No manual `docker-compose up` needed.

**Windows / macOS (Rancher Desktop):** ensure the Docker socket is accessible:
```bash
export DOCKER_HOST=unix:///var/run/docker.sock
```

## Stopping
`Ctrl+C` in dev mode. For compose: `docker-compose stop core-api`. See `DEVELOPMENT_ENV.md §7` in `tm-documentation` for all stop options.

## Auth Model
- Validates app-issued JWTs using `INTERNAL_JWT_SECRET` (single-issuer resource server).
- Exposes `/internal/auth/*` endpoints (BFF-only, not in public OpenAPI spec) for token exchange, local credential validation, and password reset flows.
- BCrypt (cost 12) for local user password hashing.
- See `AUTH_CONFIG.md` in `tm-documentation` for the full auth model.

## Multi-Tenancy
All tenant-scoped queries are filtered by the `TenantInterceptor` + Hibernate `tenantFilter`. See `MULTI_TENANCY.md` in `tm-documentation`.

## Scheduled Jobs
- **00:05 UTC daily:** Marks non-deleted tasks as `OVERDUE` where `due_date < CURRENT_DATE` and state is `PLANNED` or `IN_PROGRESS`. Writes a `task_history` row for each transitioned task.
- **00:10 UTC daily:** Cleans up expired/used `password_reset_tokens` older than 7 days.

## Environment Variables
See `ENV_VARS.md` in `tm-documentation`.

## CI/CD
Pipeline stages: build + unit tests → Grype dependency vulnerability scan → image build + Syft SBOM + Grype image security checks → Docker image push to GHCR. The publish job verifies Syft and Grype release checksums before execution and uploads SBOM/checksum evidence artifacts for audit traceability. Orchestrator E2E runs independently (manual + main push, no cross-repo dispatch token). See `REPOSITORIES_AND_CICD.md` in `tm-documentation`.
