# tm-db-schema

Owns all database schema definitions and migrations. Produces a Docker init image that runs Liquibase changesets against PostgreSQL 17 and exits, ensuring the schema is ready before `tm-core-api` starts.

> Architecture and design documentation is in `../tm-documentation` in this monorepo.

## Directory Structure
```text
tm-db-schema/
├── .github/workflows/
│   └── pipeline.yml
├── src/
│   ├── main/resources/db/changelog/
│   │   ├── db.changelog-master.yaml
│   │   ├── 001-create-tenants.yaml
│   │   ├── 002-create-roles.yaml          # Includes seed rows: STANDARD, ADMIN
│   │   ├── 003-create-users.yaml          # Includes auth_provider, password_hash, etc.
│   │   ├── 004-create-tasks.yaml          # Includes deleted_at for soft deletes
│   │   ├── 005-create-indexes.yaml        # Partial indexes (deleted_at IS NULL)
│   │   ├── 006-create-password-reset-tokens.yaml
│   │   ├── 007-bootstrap-admin.yaml       # Default tenant + bootstrap ADMIN user
│   │   └── 008-create-task-history.yaml   # task_history table + index
│   └── test/java/com/tm/db/
│       └── MigrationIntegrationTest.java  # Applies all 8 + rollbackCount 1 per changeset
├── docker-entrypoint.sh               # Translates env vars → Liquibase CLI args
├── Dockerfile                         # Multi-stage: Maven (driver cache) + liquibase/liquibase
├── pom.xml                            # Liquibase Maven plugin + Testcontainers (mvn verify)
├── .env.example
├── .gitignore
└── README.md
```

## Init Container Pattern
The image is declared as a dependency of `core-api` in:
- `docker-compose.yml` (service `db-migrations`, `restart: on-failure`)
- `helm/task-manager/templates/` (Kubernetes init container on the `core-api` deployment)

The `core-api` service/pod does not start until this container exits with code 0.

## Schema Design
See `DATABASE_SCHEMA.md` in `tm-documentation` for the full specification (tables, columns, constraints, indexes, rollback policy).

## Maven Build (CI)
`pom.xml` configures `org.liquibase:liquibase-maven-plugin` and Testcontainers (PostgreSQL 17). `mvn verify` applies all 8 changesets to a fresh container, then runs `rollbackCount 1` for each individually — validating every rollback block in isolation. The PostgreSQL Docker image version is controlled by the `postgresql.test.image` Maven property (default: `postgres:17-alpine`).

## Changeset Conventions
- Naming: `NNN-verb-noun.yaml`.
- Every changeset must have a `rollback` block.
- Use Liquibase `contexts` to separate migration changesets (`prod`) from seed/demo data (`dev`).

## Local Testing
```bash
docker build -t tm-db-schema:local .

docker run --rm \
  -e DB_HOST=localhost \
  -e DB_PORT=5432 \
  -e DB_NAME=taskmanager \
  -e DB_USERNAME=tm_user \
  -e DB_PASSWORD=secret \
  -e LIQUIBASE_CONTEXTS=dev \
  tm-db-schema:local
```

## Re-running Migrations
The container exits after completing (one-shot). To re-run against an existing DB: `docker-compose run --rm db-migrations`. To reset the DB entirely, see `DEVELOPMENT_ENV.md §7` in `tm-documentation`.

## Environment Variables
See `ENV_VARS.md` in `tm-documentation`.
