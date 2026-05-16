# Task Manager — Architecture & Design Documentation

This folder contains architecture, design, and operational documentation for the Eisenhower Matrix Task Manager monorepo.

Documents are numbered in recommended reading order — start with `01` for an overview and follow the sequence to build a complete picture of the system.

---

## Reading Order

| # | Document | What it covers |
| :- | :--- | :--- |
| 01 | [Project Overview](01-PROJECT-OVERVIEW.md) | Functional requirements, user personas, task state machine, v1 scope |
| 02 | [Technical Architecture](02-TECHNICAL-ARCHITECTURE.md) | System components, tech stack, dependency inventory and licences |
| 03 | [Getting Started](03-GETTING-STARTED.md) | Prerequisites, clone, configure, and run the stack for the first time |
| 04 | [Development Environment](04-DEVELOPMENT-ENV.md) | Local dev setup, live-reload, Testcontainers, debugging |
| 05 | [Database Schema](05-DATABASE-SCHEMA.md) | PostgreSQL schema, tables, indexes, Liquibase changeset map |
| 06 | [API Contract](06-API-CONTRACT.md) | REST API reference — human-readable companion to `openapi.yaml` |
| 07 | [Auth Config](07-AUTH-CONFIG.md) | OAuth2/OIDC, local auth, MFA, sessions, password reset |
| 08 | [Multi-Tenancy](08-MULTI-TENANCY.md) | Row-level tenancy design, Hibernate filter, test isolation |
| 09 | [Coding Patterns](09-CODING-PATTERNS.md) | Concrete patterns for the hardest implementation problems |
| 10 | [API Security](10-API-SECURITY.md) | OWASP API Top 10 threat model, SQL injection prevention |
| 11 | [Password Policy](11-PASSWORD-POLICY.md) | Strength requirements, age policy, reset flow |
| 12 | [Environment Variables](12-ENV-VARS.md) | All environment variables across every service and CI |
| 13 | [Observability](13-OBSERVABILITY.md) | Structured logging, audit events, health endpoints, local debugging |
| 14 | [Infrastructure Spec](14-INFRASTRUCTURE-SPEC.md) | Docker Compose, Helm chart, startup dependency chain |
| 15 | [Repositories & CI/CD](15-REPOSITORIES-AND-CICD.md) | GitHub Actions pipelines, stages, versioning, secrets |
| 16 | [Image Versioning](16-IMAGE-VERSIONING.md) | Container image tagging, manual dependency updates, rollback |
| 17 | [Project Structure](17-PROJECT-STRUCTURE.md) | Repository layout, module folders, workflow file locations |
| 18 | [Implementation Roadmap](18-IMPLEMENTATION-ROADMAP.md) | Phased build plan — historical reference for design intent |

---

## Modules Covered

| Module | Purpose | Stack |
| :--- | :--- | :--- |
| `tm-core-api` | Business logic and data persistence | Java 17, Spring Boot 4.x, PostgreSQL 17 |
| `tm-db-schema` | Schema migrations (init container) | Liquibase 5.0.2, PostgreSQL 17 |
| `tm-ui-bff` | BFF (Spring Boot) + React SPA | Java 17, Spring Boot 4.x, React 18, Vite 8, TypeScript 5 |
| `tm-orchestrator` | Docker Compose, Helm chart, Selenium E2E | YAML, Java |
