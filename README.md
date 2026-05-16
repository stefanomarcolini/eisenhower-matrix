# Eisenhower Matrix Task Manager

A multi-tenant task management application built around the [Eisenhower Matrix](https://en.wikipedia.org/wiki/Time_management#The_Eisenhower_Method) — organising tasks by **importance** and **urgency** to help users focus on what matters.

[![Core API Pipeline](https://github.com/stefanomarcolini/eisenhower-matrix/actions/workflows/pipeline-core-api.yml/badge.svg)](https://github.com/stefanomarcolini/eisenhower-matrix/actions/workflows/pipeline-core-api.yml)
[![BFF Pipeline](https://github.com/stefanomarcolini/eisenhower-matrix/actions/workflows/pipeline-bff.yml/badge.svg)](https://github.com/stefanomarcolini/eisenhower-matrix/actions/workflows/pipeline-bff.yml)
[![DB Schema Pipeline](https://github.com/stefanomarcolini/eisenhower-matrix/actions/workflows/pipeline-db-schema.yml/badge.svg)](https://github.com/stefanomarcolini/eisenhower-matrix/actions/workflows/pipeline-db-schema.yml)
[![E2E Tests](https://github.com/stefanomarcolini/eisenhower-matrix/actions/workflows/e2e.yml/badge.svg)](https://github.com/stefanomarcolini/eisenhower-matrix/actions/workflows/e2e.yml)

---

## Overview

| Layer | Technology |
| :--- | :--- |
| Backend API | Java 17, Spring Boot 4.x, PostgreSQL 17 |
| BFF + SPA | Spring Boot 4.x, React 18, TypeScript 5, Vite 8 |
| Database migrations | Liquibase 5.0.2 |
| Infrastructure | Docker Compose, Helm (Kubernetes) |
| Auth | OAuth2/OIDC (Google, Microsoft), local email + password, TOTP MFA |
| Session | Redis-backed Spring Session |
| CI/CD | GitHub Actions — build, scan (Grype/Syft), test, publish to GHCR |

---

## Repository Structure

This is a Maven monorepo. All modules share one git history and one pull-request flow.

```
eisenhower-matrix/
├── .github/workflows/        ← CI/CD pipelines (per-module + E2E + release)
├── tm-core-api/              ← Business logic and data persistence (Spring Boot)
├── tm-db-schema/             ← Liquibase migrations (Docker init-container)
├── tm-ui-bff/                ← BFF (Spring Boot) + React SPA (Vite)
├── tm-orchestrator/          ← Docker Compose, Helm chart, Selenium E2E suite
└── tm-documentation/         ← Architecture and design documentation
```

---

## Quickstart

**Prerequisites:** JDK 17, Maven 3.9+, Node 20+, Docker (Rancher Desktop, Docker Desktop, or equivalent).

```bash
# 1. Clone
git clone https://github.com/stefanomarcolini/eisenhower-matrix.git
cd eisenhower-matrix

# 2. Configure secrets
cp tm-orchestrator/.env.example tm-orchestrator/.env
# Edit .env — generate INTERNAL_JWT_SECRET and MFA_ENCRYPTION_KEY:
#   openssl rand -base64 32
# Generate BOOTSTRAP_ADMIN_BCRYPT_HASH:
#   docker run --rm httpd:2.4-alpine htpasswd -bnBC 12 "" yourpassword | tr -d ':\n'

# 3. Start the full stack (app available at http://localhost:8080)
cd tm-orchestrator
docker compose up -d
docker compose logs -f db-migrations   # wait for "update was executed successfully"

# 4. Log in
# Default admin: admin@task-manager.local  /  <password used above>
```

See [`tm-documentation/03-GETTING-STARTED.md`](tm-documentation/03-GETTING-STARTED.md) for the full setup guide, OAuth2 configuration, and troubleshooting.

---

## Architecture

The application follows a **BFF (Backend for Frontend)** pattern:

```
Browser
  │
  ▼
frontend-bff :8080          ← only port exposed to the host
  │   Spring Boot + Redis session (TM_SESSION HttpOnly cookie)
  │   React SPA served as static assets
  │
  ▼ (internal network, Authorization: Bearer <jwt>)
core-api :8080              ← stateless JWT resource server
  │
  ▼
PostgreSQL 17               ← row-level multi-tenant isolation (tenant_id)
```

- **Auth flow:** user authenticates via OAuth2 or local email/password → Core API issues an app-scoped HS256 JWT → BFF stores it in Redis session → every subsequent request proxied with `Authorization: Bearer` and `X-Tenant-ID` headers. Tokens never reach the browser.
- **Multi-tenancy:** row-level isolation via Hibernate `@Filter`. All user-scoped queries are always scoped by both `tenantId` and `userId`.
- **Schema migrations:** `tm-db-schema` runs as an init container, applies Liquibase changesets, and exits before `core-api` starts.

Full details in [`tm-documentation/02-TECHNICAL-ARCHITECTURE.md`](tm-documentation/02-TECHNICAL-ARCHITECTURE.md).

---

## Development

```bash
# Core API — local dev with auto-provisioned PostgreSQL (Testcontainers)
cd tm-core-api && mvn spring-boot:test-run

# BFF + React — live reload (requires infrastructure services running first)
cd tm-orchestrator && docker compose up -d db redis db-migrations mock-oauth2 mailpit core-api
cd tm-ui-bff/bff-service && mvn spring-boot:run -Dspring-boot.run.profiles=dev
cd tm-ui-bff/frontend-client && npm install && npm run dev   # http://localhost:5173

# Run all tests
mvn verify -pl tm-core-api        # unit + integration (Testcontainers)
mvn verify -pl tm-db-schema       # all changesets + rollback validation
cd tm-ui-bff/frontend-client && npm test

# E2E
cd tm-orchestrator && docker compose up -d && cd e2e && mvn verify
```

See [`tm-documentation/04-DEVELOPMENT-ENV.md`](tm-documentation/04-DEVELOPMENT-ENV.md) for the full local development guide.

---

## CI/CD

Each code module has a path-filtered pipeline at `.github/workflows/`:

| Pipeline | Stages |
| :--- | :--- |
| `pipeline-core-api.yml` | Build + test → Grype dependency scan → Integration tests (Testcontainers) → Docker build → Syft SBOM → Grype image scan → push to GHCR |
| `pipeline-bff.yml` | Same as above, plus frontend lint + Vitest |
| `pipeline-db-schema.yml` | Liquibase changeset validation + rollback → Docker build → scan → push |
| `e2e.yml` | Pull GHCR images → start stack → auth smoke test → Selenium suite |
| `release.yml` | Publish Helm chart to GHCR OCI registry on semver tag |

Images are published to `ghcr.io/stefanomarcolini/` and tagged `v1.0.<run-number>` + `latest`.

---

## Documentation

All architecture and design documentation lives in [`tm-documentation/`](tm-documentation/). Documents are numbered in recommended reading order:

| | |
| :- | :--- |
| [01](tm-documentation/01-PROJECT-OVERVIEW.md) | Project overview and functional requirements |
| [02](tm-documentation/02-TECHNICAL-ARCHITECTURE.md) | Technical architecture and dependency inventory |
| [03](tm-documentation/03-GETTING-STARTED.md) | Getting started guide |
| [04](tm-documentation/04-DEVELOPMENT-ENV.md) | Local development environment |
| [05–18](tm-documentation/) | Database schema, API contract, auth, security, CI/CD, and more |

---

## Licence

MIT
