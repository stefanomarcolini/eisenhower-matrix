# Database Schema

**Database:** PostgreSQL 17
**Migration tool:** Liquibase (YAML changesets, owned by `tm-db-schema`)
**Tenancy model:** Row-level (single schema, single database; see `08-MULTI-TENANCY.md`)

---

## Naming Conventions
- All identifiers: `snake_case`.
- Table names: plural nouns (e.g., `tasks`, `users`).
- Primary keys: `id UUID NOT NULL DEFAULT gen_random_uuid()`.
- Foreign keys: `{referenced_table_singular}_id` (e.g., `tenant_id`, `user_id`).
- Timestamps: `created_at TIMESTAMPTZ NOT NULL DEFAULT now()`, `updated_at TIMESTAMPTZ NOT NULL DEFAULT now()`.
- Boolean columns: prefixed with `is_` (e.g., `is_mfa_enabled`).

---

## Tables

### `tenants`
| Column | Type | Constraints | Notes |
| :--- | :--- | :--- | :--- |
| `id` | UUID | PK, NOT NULL, DEFAULT `gen_random_uuid()` | |
| `name` | VARCHAR(255) | NOT NULL, UNIQUE | Display name of the tenant organisation |
| `created_at` | TIMESTAMPTZ | NOT NULL, DEFAULT `now()` | |

---

### `roles`
Seeded at migration time. Not user-editable.

| Column | Type | Constraints | Notes |
| :--- | :--- | :--- | :--- |
| `id` | UUID | PK, NOT NULL, DEFAULT `gen_random_uuid()` | |
| `name` | VARCHAR(50) | NOT NULL, UNIQUE | Values: `STANDARD`, `ADMIN` |

---

### `users`
| Column | Type | Constraints | Notes |
| :--- | :--- | :--- | :--- |
| `id` | UUID | PK, NOT NULL, DEFAULT `gen_random_uuid()` | |
| `tenant_id` | UUID | FK → `tenants.id`, NOT NULL | Row-level tenancy column |
| `email` | VARCHAR(255) | NOT NULL | |
| `display_name` | VARCHAR(255) | | Optional |
| `auth_provider` | VARCHAR(20) | NOT NULL, CHECK IN `('LOCAL', 'GOOGLE', 'MICROSOFT')` | The method used to create this account. Immutable after creation. |
| `external_user_id` | VARCHAR(255) | NULLABLE | `sub` claim from IdP. NULL for `LOCAL` users. |
| `password_hash` | VARCHAR(255) | NULLABLE | BCrypt hash. NULL for OAuth2 users (`GOOGLE`, `MICROSOFT`). |
| `password_changed_at` | TIMESTAMPTZ | NULLABLE | Last time the password was set or changed. NULL for OAuth2 users. Set on registration and on every password change. |
| `last_login_at` | TIMESTAMPTZ | NULLABLE | Timestamp of the most recent successful login. Updated by Core API inside the login transaction in `/internal/auth/validate` (local login) and `/internal/auth/token` (OAuth2 login). |
| `role_id` | UUID | FK → `roles.id`, NOT NULL | |
| `is_mfa_enabled` | BOOLEAN | NOT NULL, DEFAULT `FALSE` | |
| `mfa_secret` | VARCHAR(255) | NULLABLE | AES-256 encrypted TOTP secret. NULL when MFA is disabled. |
| `theme` | VARCHAR(10) | NOT NULL, DEFAULT `'LIGHT'`, CHECK IN `('LIGHT', 'DARK')` | |
| `created_at` | TIMESTAMPTZ | NOT NULL, DEFAULT `now()` | |
| `updated_at` | TIMESTAMPTZ | NOT NULL, DEFAULT `now()` | |

**Unique constraint:** `(tenant_id, email)` — enforces that one email address maps to exactly one account per tenant, regardless of auth provider. If a user attempts to register with a provider different from their existing account, a helpful error is returned directing them to the correct login method.

**Unique constraint:** `(tenant_id, external_user_id)` — applies only to OAuth2 users; NULL values are excluded from uniqueness by PostgreSQL convention.

**Auth provider rules:**
- `LOCAL`: `external_user_id` = NULL, `password_hash` = NOT NULL, `password_changed_at` = NOT NULL.
- `GOOGLE` / `MICROSOFT`: `external_user_id` = NOT NULL, `password_hash` = NULL, `password_changed_at` = NULL.

Account linking (same email, multiple providers) is **out of scope for v1**. Each email maps to exactly one auth method.

---

### `password_reset_tokens`
Used only for `LOCAL` auth users. Tokens are single-use and time-limited.

| Column | Type | Constraints | Notes |
| :--- | :--- | :--- | :--- |
| `id` | UUID | PK, NOT NULL, DEFAULT `gen_random_uuid()` | |
| `user_id` | UUID | FK → `users.id`, NOT NULL | Must be a `LOCAL` user |
| `token_hash` | VARCHAR(255) | NOT NULL | SHA-256 hash of the raw token sent to the user by email. Never store the raw token. |
| `expires_at` | TIMESTAMPTZ | NOT NULL | Configurable via `PASSWORD_RESET_TOKEN_EXPIRY_HOURS` (default: 1 hour) |
| `used_at` | TIMESTAMPTZ | NULLABLE | NULL if the token has not been used yet. Set to `now()` on use. |
| `created_at` | TIMESTAMPTZ | NOT NULL, DEFAULT `now()` | |

**Index:** `idx_reset_tokens_user` on `(user_id)` for lookup by user.
**Index:** `idx_reset_tokens_hash` on `(token_hash)` for lookup during reset.

A cleanup job (Spring `@Scheduled`, daily) deletes expired and used tokens older than 7 days.

---

### `tasks`
| Column | Type | Constraints | Notes |
| :--- | :--- | :--- | :--- |
| `id` | UUID | PK, NOT NULL, DEFAULT `gen_random_uuid()` | |
| `tenant_id` | UUID | FK → `tenants.id`, NOT NULL | Row-level tenancy column |
| `user_id` | UUID | FK → `users.id`, NOT NULL | Owning user |
| `title` | VARCHAR(255) | NOT NULL | |
| `description` | TEXT | | Optional |
| `state` | VARCHAR(20) | NOT NULL, DEFAULT `'PLANNED'`, CHECK IN `('PLANNED', 'IN_PROGRESS', 'COMPLETED', 'OVERDUE')` | See state machine in `01-PROJECT-OVERVIEW.md §3` |
| `importance` | VARCHAR(10) | NOT NULL, CHECK IN `('LOW', 'MEDIUM', 'HIGH')` | |
| `urgency` | VARCHAR(10) | NOT NULL, CHECK IN `('LOW', 'MEDIUM', 'HIGH')` | |
| `due_date` | DATE | | Optional |
| `version` | INT | NOT NULL, DEFAULT `0` | Optimistic locking counter. Incremented by JPA on every update. Prevents lost updates under concurrent edits. |
| `created_at` | TIMESTAMPTZ | NOT NULL, DEFAULT `now()` | |
| `updated_at` | TIMESTAMPTZ | NOT NULL, DEFAULT `now()` | |
| `deleted_at` | TIMESTAMPTZ | NULLABLE, DEFAULT `NULL` | Soft-delete marker. `NULL` = active. Deleted rows are invisible to all standard JPA queries via Hibernate `@Where(clause = "deleted_at IS NULL")` on the entity. Hard deletes are not supported. |

**Soft deletes:** `DELETE /api/v1/tasks/{id}` sets `deleted_at = now()` — it does not remove the row. The Hibernate `@Where` annotation makes deleted rows invisible to all standard queries automatically. The overdue scheduler must also include `AND deleted_at IS NULL` in its native SQL.

---

### `task_history`
Records every state change on a task. Written by `TaskService` on every state transition and by the overdue scheduler. There is no public API to read task history in v1 — it is an internal audit trail and a foundation for future analytics and activity feeds.

| Column | Type | Constraints | Notes |
| :--- | :--- | :--- | :--- |
| `id` | UUID | PK, NOT NULL, DEFAULT `gen_random_uuid()` | |
| `task_id` | UUID | FK → `tasks.id`, NOT NULL | |
| `tenant_id` | UUID | FK → `tenants.id`, NOT NULL | Denormalised for tenant isolation and future cross-tenant analytics without a join |
| `changed_by` | UUID | FK → `users.id`, NOT NULL | The user who triggered the transition. For scheduler-driven transitions (`→ OVERDUE`), use the owning task's `user_id`. |
| `from_state` | VARCHAR(20) | NULLABLE | `NULL` on the initial creation event (`→ PLANNED`). |
| `to_state` | VARCHAR(20) | NOT NULL | |
| `changed_at` | TIMESTAMPTZ | NOT NULL, DEFAULT `now()` | |

---

## Indexes

| Name | Table | Columns | Condition | Purpose |
| :--- | :--- | :--- | :--- | :--- |
| `idx_tasks_tenant_user` | `tasks` | `(tenant_id, user_id)` | `deleted_at IS NULL` | Primary query path: list active tasks for a user |
| `idx_tasks_tenant_state` | `tasks` | `(tenant_id, state)` | `deleted_at IS NULL` | Filter active tasks by state within a tenant |
| `idx_tasks_due_date` | `tasks` | `(due_date)` | `state NOT IN ('COMPLETED') AND deleted_at IS NULL` | Partial index for the overdue scheduler |
| `idx_task_history_task_id` | `task_history` | `(task_id)` | — | Look up history entries for a task |
| `idx_users_tenant_email` | `users` | `(tenant_id, email)` | — | Login lookup (both LOCAL and OAuth2) |
| `idx_users_tenant_ext_id` | `users` | `(tenant_id, external_user_id)` | `external_user_id IS NOT NULL` | OIDC login matching (partial, skips LOCAL users) |
| `idx_reset_tokens_user` | `password_reset_tokens` | `(user_id)` | — | Look up active tokens for a user |
| `idx_reset_tokens_hash` | `password_reset_tokens` | `(token_hash)` | — | Token validation during reset |

---

## Overdue Task Automation
Tasks transition to `OVERDUE` automatically when their `due_date` passes and their state is `PLANNED` or `IN_PROGRESS`. This is **not** a database trigger; it is a Spring Boot `@Scheduled` job in `tm-core-api` that runs daily at 00:05 UTC.

The affected rows are those matching (conceptual SQL — for illustration only):
```sql
SELECT * FROM tasks
WHERE due_date < CURRENT_DATE
  AND state IN ('PLANNED', 'IN_PROGRESS')
  AND deleted_at IS NULL;
```

The actual implementation uses a JPQL `SELECT` followed by individual JPA entity updates (not a bulk `UPDATE`) so that a `task_history` row can be written per transition. See `09-CODING-PATTERNS.md §7` for the full implementation pattern.

The scheduler bypasses the Hibernate tenant filter (must call `session.disableFilter("tenantFilter")`). It must write a `task_history` row for each task it transitions (use the task's `user_id` as `changed_by`).

Both scheduled jobs (overdue updater and token cleanup) are **idempotent** — re-running them simultaneously on multiple instances produces the same database result and is safe for v1. If strict single-execution is required when scaling horizontally, add ShedLock (`net.javacrumbs.shedlock:shedlock-spring`, Apache 2.0) backed by the existing PostgreSQL instance. This is not required for v1.

---

## Liquibase Changeset Map

| File | Creates / Alters |
| :--- | :--- |
| `001-create-tenants.yaml` | `tenants` table |
| `002-create-roles.yaml` | `roles` table + seed rows (`STANDARD`, `ADMIN`) |
| `003-create-users.yaml` | `users` table (includes `auth_provider`, `password_hash`, `password_changed_at`, `last_login_at`) |
| `004-create-tasks.yaml` | `tasks` table |
| `005-create-indexes.yaml` | All indexes on `tasks` and `users` |
| `006-create-password-reset-tokens.yaml` | `password_reset_tokens` table + its indexes |
| `007-bootstrap-admin.yaml` | Default tenant + bootstrap ADMIN user (all environments). See `09-CODING-PATTERNS.md §12`. |
| `008-create-task-history.yaml` | `task_history` table + `idx_task_history_task_id` index |

---

## Rollback Policy
- Every changeset must include a `rollback` block.
- The `tm-db-schema` CI pipeline validates rollbacks by running `liquibase rollbackCount 1` after each migration step against a test container.
- Rollback removes the table or index created by that changeset. Seed-data changesets (`002`) rollback by deleting the seeded rows.
