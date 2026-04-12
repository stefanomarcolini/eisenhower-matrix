# Task Manager — Architecture & Design Documentation

This folder contains architecture, design, and operational documentation for the Task Manager monorepo.

## Documents

| Document | Description |
| :--- | :--- |
| [PROJECT_OVERVIEW.md](PROJECT_OVERVIEW.md) | Functional requirements, user personas, task state machine |
| [TECHNICAL_ARCHITECTURE.md](TECHNICAL_ARCHITECTURE.md) | System components, tech stack, dependency inventory |
| [GITHUB_PROJECT_STRUCTURE.md](GITHUB_PROJECT_STRUCTURE.md) | Repository layout and local development folder structure |
| [GETTING_STARTED.md](GETTING_STARTED.md) | Clone, configure, and run the stack for the first time |
| [REPOSITORIES_AND_CICD.md](REPOSITORIES_AND_CICD.md) | CI/CD pipelines, GitHub Actions, deployment flow |
| [INFRASTRUCTURE_SPEC.md](INFRASTRUCTURE_SPEC.md) | Docker Compose and Kubernetes infrastructure |
| [DEVELOPMENT_ENV.md](DEVELOPMENT_ENV.md) | Local dev setup, Testcontainers, start/stop/debug |
| [AUTH_CONFIG.md](AUTH_CONFIG.md) | Authentication model: OAuth2, local auth, MFA, sessions |
| [PASSWORD_POLICY.md](PASSWORD_POLICY.md) | Password rules, age policy, reset flow |
| [API_CONTRACT.md](API_CONTRACT.md) | REST API reference (human-readable companion to `openapi.yaml`) |
| [DATABASE_SCHEMA.md](DATABASE_SCHEMA.md) | PostgreSQL schema, tables, indexes, migration map |
| [MULTI_TENANCY.md](MULTI_TENANCY.md) | Row-level tenancy design and enforcement |
| [ENV_VARS.md](ENV_VARS.md) | All environment variables across all services |
| [OBSERVABILITY.md](OBSERVABILITY.md) | Logging, audit events, health endpoints, local debugging |
| [IMPLEMENTATION_ROADMAP.md](IMPLEMENTATION_ROADMAP.md) | Ordered implementation steps |
| [CODING_PATTERNS.md](CODING_PATTERNS.md) | Concrete patterns for the hardest implementation problems |
| [API_SECURITY.md](API_SECURITY.md) | SQL injection prevention and OWASP API Top 10 threat model |
| [IMAGE_VERSIONING.md](IMAGE_VERSIONING.md) | Container image versioning, GitHub Actions updates, Dependabot automation |

## Scope

This folder documents the application modules in this monorepo:

| Module | Purpose |
| :--- | :--- |
| `tm-orchestrator` | Docker Compose, Helm chart, E2E tests |
| `tm-core-api` | Business logic and data persistence (Spring Boot) |
| `tm-ui-bff` | BFF + React SPA (Spring Boot + Vite) |
| `tm-db-schema` | Liquibase migrations |

See [GITHUB_PROJECT_STRUCTURE.md](GITHUB_PROJECT_STRUCTURE.md) for the full layout.
