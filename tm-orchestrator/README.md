# tm-orchestrator

Composition and E2E testing root. Owns Docker Compose, Helm chart, and the Selenium suite.

> Architecture and design documentation is in `../tm-documentation` in this monorepo.

## Directory Structure
```
.github/workflows/
  e2e.yml            ← manual + main-push triggers
  release.yml        ← Helm chart publish
helm/task-manager/   ← Kubernetes Helm chart
e2e/                 ← Selenium E2E (Maven)
docker-compose.yml
docker-compose.override.yml   ← mock-oauth2, mailpit, JVM debug ports
.env.example
README.md
```
Related modules (`tm-core-api/`, `tm-ui-bff/`, `tm-db-schema/`) are sibling folders in this monorepo — see `GITHUB_PROJECT_STRUCTURE.md` in `tm-documentation`.

## Start
```bash
docker-compose up -d
```
| URL | Service |
| :--- | :--- |
| `http://localhost:8080` | Application |
| `http://localhost:8025` | Mailpit |
| `http://localhost:9000` | Mock OAuth2 |

## Stop
See `DEVELOPMENT_ENV.md §7` in `tm-documentation`.

## Debug
See `OBSERVABILITY.md §4` in `tm-documentation` (remote debug ports, log viewing, DB/Redis access).

## E2E Tests
```bash
docker-compose up -d && cd e2e && mvn verify && docker-compose down
```

## Helm Deployment
```bash
helm upgrade --install task-manager \
  oci://ghcr.io/sm-task-manager/charts/task-manager \
  --values helm/task-manager/values-prod.yaml \
  --namespace task-manager --create-namespace
```

## CI/CD
`e2e.yml` runs on manual `workflow_dispatch` and on `main` pushes. It generates non-production runtime defaults for E2E, but it still needs GHCR access to pull `tm-db-schema`, `tm-core-api`, and `tm-ui-bff` images — either via package-level Actions access or `GHCR_USERNAME` + `GHCR_TOKEN`. See `REPOSITORIES_AND_CICD.md §2` in `tm-documentation`.
