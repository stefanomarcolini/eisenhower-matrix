# Environment Variables

All runtime configuration is injected via environment variables. No secrets or environment-specific values are hardcoded in source.

| Environment | How variables are provided |
| :--- | :--- |
| Local development | `.env` file per repo (gitignored). Each repo ships `.env.example`. |
| CI (GitHub Actions) | `secrets.*` for secrets, `vars.*` for non-sensitive config. |
| Production (Kubernetes) | `Secret` objects as env vars; `ConfigMap` for non-sensitive config. |

---

## Core API (`tm-core-api`)

| Variable | Required | Default | Description |
| :--- | :--- | :--- | :--- |
| `DB_HOST` | Yes | — | PostgreSQL hostname (e.g., `db` in compose) |
| `DB_PORT` | No | `5432` | |
| `DB_NAME` | Yes | — | |
| `DB_USERNAME` | Yes | — | |
| `DB_PASSWORD` | Yes | — | **Secret.** |
| `INTERNAL_JWT_SECRET` | Yes | — | HMAC-SHA256 key (min 32 bytes, Base64). **Secret.** |
| `JWT_EXPIRY_MINUTES` | No | `15` | App JWT lifetime |
| `MFA_ENCRYPTION_KEY` | Yes | — | AES-256 key (Base64, 32 bytes) for TOTP secrets. **Secret.** |
| `PASSWORD_MAX_AGE_DAYS` | No | `90` | Days until LOCAL password is stale |
| `PASSWORD_AGE_WARNING_DAYS` | No | `80` | Days until warning surfaced at login |
| `PASSWORD_RESET_TOKEN_EXPIRY_HOURS` | No | `1` | |
| `EMAIL_FROM_ADDRESS` | Yes | — | e.g., `noreply@task-manager.io` |
| `EMAIL_FROM_NAME` | No | `Task Manager` | |
| `SMTP_HOST` | Yes | — | In local dev: `mailpit` |
| `SMTP_PORT` | No | `587` | In local dev: `1025` |
| `SMTP_USERNAME` | No | (empty) | Not required for Mailpit |
| `SMTP_PASSWORD` | No | (empty) | **Secret** if set. |
| `SMTP_TLS_ENABLED` | No | `true` | Set to `false` for Mailpit |
| `APP_BASE_URL` | Yes | — | Public base URL for email links. In local dev: `http://localhost:8080` |
| `SERVER_PORT` | No | `8080` | |
| `SPRING_PROFILES_ACTIVE` | No | `prod` | `dev`, `test`, or `prod` |

---

## BFF (`tm-ui-bff`)

| Variable | Required | Default | Description |
| :--- | :--- | :--- | :--- |
| `REDIS_HOST` | Yes | — | e.g., `redis` in compose |
| `REDIS_PORT` | No | `6379` | |
| `REDIS_PASSWORD` | No | (empty) | **Secret** if set. |
| `SESSION_TIMEOUT_MINUTES` | No | `30` | Sliding window |
| `CORE_API_BASE_URL` | Yes | — | e.g., `http://core-api:8080` |
| `GOOGLE_CLIENT_ID` | If Google enabled | — | **Secret.** |
| `GOOGLE_CLIENT_SECRET` | If Google enabled | — | **Secret.** |
| `MICROSOFT_CLIENT_ID` | If Microsoft enabled | — | **Secret.** |
| `MICROSOFT_CLIENT_SECRET` | If Microsoft enabled | — | **Secret.** |
| `SERVER_PORT` | No | `8080` | |
| `SPRING_PROFILES_ACTIVE` | No | `prod` | |

> Microsoft endpoints use the `consumers` tenant and are hardcoded in `application.yml` — no `MICROSOFT_TENANT_ID` variable is needed. See `AUTH_CONFIG.md §3`.

---

## Database Init Image (`tm-db-schema`)

| Variable | Required | Default | Description |
| :--- | :--- | :--- | :--- |
| `DB_HOST` | Yes | — | |
| `DB_PORT` | No | `5432` | |
| `DB_NAME` | Yes | — | |
| `DB_USERNAME` | Yes | — | Must have DDL privileges |
| `DB_PASSWORD` | Yes | — | **Secret.** |
| `LIQUIBASE_CONTEXTS` | No | `prod` | Set to `dev` for seed data |
| `BOOTSTRAP_ADMIN_BCRYPT_HASH` | Yes | — | BCrypt hash (cost 12) of the initial admin password. Used by Liquibase changeset `007`. **Secret.** Change password immediately after first login. Generate with: `htpasswd -bnBC 12 "" yourpassword \| tr -d ':\n'`. In docker-compose `.env`, escape `$` as `$$` to avoid interpolation (`$$2y$$...`). |

---

## Orchestrator / Compose Helpers (`tm-orchestrator`)

| Variable | Required | Default | Description |
| :--- | :--- | :--- | :--- |
| `MOCK_OAUTH2_BROWSER_BASE_URL` | No | `http://localhost:9000` | Browser-facing base URL for mock OAuth2 authorization redirects in `docker-compose.override.yml`. Use `http://mock-oauth2:8080` in CI because the Selenium browser runs inside Docker; use `localhost:9000` for host-browser local development. |

---

## CI-Specific Secrets / Variables (GitHub)

| Name | Scope | Description |
| :--- | :--- | :--- |
| `GHCR_USERNAME` | `tm-orchestrator` (Actions variable) | GitHub username of the machine user that owns `GHCR_TOKEN`; used for cross-repo GHCR pulls when package-level Actions access is not granted. |
| `GHCR_TOKEN` | Each code repo | PAT with `packages: write` for GHCR pushes |
| `DB_PASSWORD` | Each code repo | Integration test containers |
| `INTERNAL_JWT_SECRET` | `tm-core-api` | Integration tests |
| `MFA_ENCRYPTION_KEY` | `tm-core-api` | Integration tests |

`tm-orchestrator` E2E can generate non-production defaults for DB/JWT/MFA/bootstrap-admin values when those repository secrets are absent. GHCR access is the only hard prerequisite for pulling sibling private images.

---

## `.env.example` Reminder
Each repository root must contain a `.env.example` with all variable names, placeholder values, and inline comments. `.env` is listed in `.gitignore` and must never be committed.