# Infrastructure & Deployment

## 1. Containerization
- Each component has a dedicated `Dockerfile` where applicable.
- Multi-stage builds: `maven:3.9-eclipse-temurin-17` for building, `eclipse-temurin:17-jre-jammy` for running.
- **Image tags must be pinned to specific patch versions** (e.g., `postgres:17.2-alpine`, not `postgres:17-alpine` or `latest`). Unpinned tags are a supply-chain risk. Update pins deliberately as part of a planned dependency upgrade.

## 2. Orchestration

### Production (`docker-compose.yml`)

| Service | Image | Exposed to Host | Notes |
| :--- | :--- | :--- | :--- |
| `db` | `postgres:17.x-alpine` | No | Volume `postgres-data`. Health-checked via `pg_isready`. |
| `redis` | `redis:7.x-alpine` | No | Volume `redis-data` (AOF). Health-checked via `redis-cli ping`. |
| `db-migrations` | `tm-db-schema` (GHCR) | No | Runs Liquibase, exits 0 on success. `core-api` depends on it. |
| `core-api` | `tm-core-api` (GHCR) | No | Scalable via `--scale`. |
| `frontend-bff` | `tm-ui-bff` (GHCR) | `8080:8080` | Scalable via `--scale`. |

Single internal bridge network `tm-network`. Only `frontend-bff` binds a host port.

### `depends_on` startup conditions (CRITICAL)

Use `condition:` keys — plain `depends_on` only checks that the container has *started*, not that it is *ready*. Without conditions, Core API will crash on startup if it connects to Postgres before it is ready to accept connections, or before migrations have completed.

```yaml
services:
  db-migrations:
    depends_on:
      db:
        condition: service_healthy   # waits for pg_isready to pass

  core-api:
    depends_on:
      db-migrations:
        condition: service_completed_successfully  # waits for Liquibase exit 0
      db:
        condition: service_healthy

  frontend-bff:
    depends_on:
      core-api:
        condition: service_healthy   # waits for /actuator/health 200
      redis:
        condition: service_healthy
```

Health check examples for `db` and `redis`:
```yaml
  db:
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U $$POSTGRES_USER -d $$POSTGRES_DB"]
      interval: 5s
      timeout: 5s
      retries: 10

  redis:
    healthcheck:
      test: ["CMD", "redis-cli", "ping"]
      interval: 5s
      timeout: 3s
      retries: 10
```

### Dev Overrides (`docker-compose.override.yml`)
Applied automatically with `docker-compose up`:

| Service | Image | Exposed to Host | Purpose |
| :--- | :--- | :--- | :--- |
| `mock-oauth2` | `ghcr.io/navikt/mock-oauth2-server:x.y.z` | `9000:8080` | Mock OIDC — no real IdP credentials needed |
| `mailpit` | `axllent/mailpit:vX.Y` | `8025:8025` (UI), `1025:1025` (SMTP) | Captures all outbound emails. Web UI at `http://localhost:8025`. |

Also sets `SPRING_PROFILES_ACTIVE=dev`, exposes JVM debug ports (`5005` Core API, `5006` BFF). See `OBSERVABILITY.md §4`.

For stop commands, see `DEVELOPMENT_ENV.md §7`.

## 3. Scalability
- Core API is stateless: `docker-compose up --scale core-api=3`.
- BFF externalises session to Redis — no sticky sessions: `docker-compose up --scale frontend-bff=3`.
- Helm chart provides `HorizontalPodAutoscaler` for both.

## 4. Helm Chart
Lives exclusively in `tm-orchestrator/helm/task-manager/`. Published to `oci://ghcr.io/sm-task-manager/charts/task-manager` by the `tm-orchestrator` release pipeline.

## 5. Email Service
- **Production:** External SMTP relay (e.g., AWS SES, SendGrid, Mailgun). Configured via `SMTP_*` env vars (see `ENV_VARS.md`).
- **Local dev:** Mailpit (`axllent/mailpit`). Drop-in SMTP server; no credentials required. Set `SMTP_HOST=mailpit`, `SMTP_PORT=1025`, `SMTP_TLS_ENABLED=false`.
- **CI / integration tests:** WireMock stubs outbound SMTP calls. No real emails sent.

## 6. Database Versioning
Liquibase YAML changesets in `tm-db-schema`. Init image pattern — see `DATABASE_SCHEMA.md` and `tm-db-schema/README.md`.

## 7. Environment Variables & Secrets
All configuration via environment variables. Local: `.env` (gitignored). CI: GitHub Actions secrets/vars. Production: Kubernetes Secrets + ConfigMaps. See `ENV_VARS.md`.