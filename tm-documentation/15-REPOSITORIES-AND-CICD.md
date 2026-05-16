# GitHub Repository & CI/CD Specification

## 1. Repository Structure (Monorepo)
All modules live in one Git repository: `eisenhower-matrix-repository`.

| Module Folder | Responsibility | Tech Stack |
| :--- | :--- | :--- |
| `tm-documentation` | Architecture documentation, design decisions, ADRs. | Markdown |
| `tm-orchestrator` | Docker Compose, Helm chart, Selenium E2E tests. | YAML, Java (Selenium) |
| `tm-db-schema` | Database migrations. Produces a Docker init image that runs Liquibase and exits. | Liquibase, PostgreSQL 17 |
| `tm-core-api` | Business logic and data persistence. | Java 17, Spring Boot, Maven |
| `tm-ui-bff` | BFF (Spring Boot) + React SPA (Vite). | Java, React, TypeScript, Maven |

`tm-documentation` contributes docs only; build/deploy pipelines are defined at repository root.

---

## 2. CI/CD Pipeline (GitHub Actions)
Workflows should live under root `.github/workflows/` and use `paths` filters per module (`tm-orchestrator/**`, `tm-db-schema/**`, `tm-core-api/**`, `tm-ui-bff/**`).

### Stage 1: Build & Unit Test
- **Java:** `mvn clean compile` + unit tests (`mvn test`)
- **React:** `npm ci && npm run build`

### Stage 2: Dependency Vulnerability Scan
Runs in parallel with or immediately after Stage 1. Build fails if high/critical vulnerabilities are found.

- **Java / container repos:** `grype dir:. --fail-on high` after checksum-verified Grype install from Anchore release assets.
- **Node:** `npm audit --audit-level=high` (BFF frontend). Fails on high or critical findings.

### Stage 3: Integration Testing
- **Core API:** `mvn verify` — real PostgreSQL 17 via Testcontainers (static container + `@DynamicPropertySource`). WireMock stubs SMTP and any outbound HTTP.
- **BFF:** `mvn verify` — WireMock stubs Core API and mock IdP endpoints. No real Core API container.
- **DB Schema:** `mvn verify` — applies all changesets to a fresh PostgreSQL 17 container; validates every changeset has a rollback block; runs `liquibase rollbackCount 1` for each.

### Stage 4: Containerization & Registry
- Build Docker image with the repository's `Dockerfile`.
- Tag: `v1.0.${GITHUB_RUN_NUMBER}` (Semantic Versioning).
- Generate SBOM (`syft ... -o cyclonedx-json=...`) and run `grype <image> --fail-on high`.
- Verify Syft and Grype release checksums before executing either binary.
- Upload SBOM + checksum files as CI artifact evidence for audit traceability.
- Push to **GitHub Container Registry (GHCR)**.

### Stage 5: Orchestrated E2E (triggered by `tm-orchestrator`)

#### E2E Trigger Model
`tm-orchestrator` `e2e.yml` runs on:
- `workflow_dispatch` (manual)
- `push` to `main` (merge-triggered)

No cross-repo dispatch secret is required for the trigger itself.

#### E2E Execution
1. `docker-compose pull` → `docker-compose up -d`
2. Selenium suite (`e2e/`)
3. `docker-compose down` (always, regardless of outcome)

`e2e.yml` generates non-production defaults for DB credentials, JWT/MFA secrets, and the bootstrap admin account when repo secrets are absent, so a standard E2E run does not require application runtime secrets.

#### GitHub Secrets / Variables
- No cross-repo dispatch secret is required for E2E triggering.
- GHCR image pulls use `GHCR_TOKEN` (PAT secret with `read:packages`) with `GITHUB_TOKEN` as fallback. Set `GHCR_TOKEN` in repository Settings → Secrets and variables → Actions. See `12-ENV-VARS.md §CI-Specific Secrets`.

---

## 3. Versioning Strategy
- **Git Tags** for releases.
- **Release Drafter** GitHub Action automates changelogs from PR labels.
- Helm chart versioned independently; published to `oci://ghcr.io/stefanomarcolini/charts/task-manager`.