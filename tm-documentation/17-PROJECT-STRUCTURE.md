# Project Directory Standards

## Repository Layout

`eisenhower-matrix-repository/` is the single Git repository for all modules.

```
eisenhower-matrix-repository/  ← single git repo
├── .github/
│   └── workflows/             ← all CI/CD pipelines live here
├── tm-documentation/
├── tm-orchestrator/
├── tm-core-api/
├── tm-ui-bff/
└── tm-db-schema/
```

All modules share one root `.git/` and one pull-request flow.

---

## `tm-documentation`
```
tm-documentation/
├── *.md               ← all architecture and design documents
├── .gitignore
└── README.md
```

---

## Root `.github/workflows/`
```
.github/workflows/
├── pipeline-core-api.yml  ← Core API: test → scan → publish
├── pipeline-bff.yml       ← BFF: test → scan → publish
├── pipeline-db-schema.yml ← DB Schema: test → scan → publish
├── e2e.yml                ← workflow_dispatch + main-push triggers
└── release.yml            ← Helm chart publish on semver tag
```

---

## `tm-orchestrator`
```
tm-orchestrator/
├── helm/task-manager/
│   ├── Chart.yaml
│   ├── values.yaml
│   ├── values-prod.yaml
│   └── templates/
├── e2e/                   ← Selenium E2E suite (Maven)
│   ├── src/test/java/
│   └── pom.xml
├── docker-compose.yml
├── docker-compose.override.yml   ← dev: mock-oauth2, mailpit, debug ports
├── .env.example
├── .gitignore
└── README.md
```

---

## `tm-core-api`
```
tm-core-api/
├── src/
│   ├── main/java/com/tm/core/
│   │   ├── domain/            ← Entities, Value Objects
│   │   ├── application/       ← Services, DTOs
│   │   ├── infrastructure/    ← Repositories, TenantContext, email sender
│   │   └── web/               ← Controllers (OpenAPI-generated) + internal auth
│   └── test/java/
├── api-spec/openapi.yaml      ← canonical public API contract
├── .env.example
├── .gitignore
└── pom.xml
```

---

## `tm-ui-bff`
```
tm-ui-bff/
├── bff-service/
│   ├── src/main/java/
│   │   ├── config/
│   │   │   ├── RedisSessionConfig.java
│   │   │   ├── CorsConfig.java            ← dev profile only
│   │   │   └── OAuth2SecurityConfig.java  ← OAuth2 + local auth + CSRF
│   │   ├── auth/                          ← /auth/local/*, /auth/mfa/verify, /auth/session
│   │   └── proxy/                         ← injects Bearer + X-Tenant-ID
│   └── pom.xml
├── frontend-client/
│   ├── src/
│   ├── vite.config.ts                     ← dev proxy
│   ├── .env.development
│   ├── .env.production
│   └── package.json
├── Dockerfile                             ← multi-stage: npm → mvn → JRE
├── .env.example
├── .gitignore
└── pom.xml
```

---

## `tm-db-schema`
```
tm-db-schema/
├── src/main/resources/db/changelog/
│   ├── db.changelog-master.yaml
│   ├── 001-create-tenants.yaml
│   ├── 002-create-roles.yaml
│   ├── 003-create-users.yaml
│   ├── 004-create-tasks.yaml
│   ├── 005-create-indexes.yaml
│   ├── 006-create-password-reset-tokens.yaml
│   ├── 007-bootstrap-admin.yaml
│   └── 008-create-task-history.yaml
├── Dockerfile
├── pom.xml                ← Liquibase Maven plugin + Testcontainers (for mvn verify in CI)
├── .env.example
├── .gitignore
└── README.md
```