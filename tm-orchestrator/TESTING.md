# Manual Testing Guide

Covers all testable scenarios for a locally-running Task Manager instance.
Start the full stack first (`docker-compose up -d` from `tm-orchestrator/`).

App: http://localhost:8080 · Mailpit: http://localhost:8025 · Mock OAuth2: http://localhost:9000

---

## Quick Reference — Test Users

| # | Email | Password | Role | Scenario |
| :- | :--- | :--- | :--- | :--- |
| 1 | `admin@task-manager.local` | `Admin1234!` | ADMIN | Pre-seeded; admin panel + all features |
| 2 | `standard@task-manager.local` | `Admin1234!` | STANDARD | Seeded via SQL (§3); basic task CRUD |
| 3 | `mfa@task-manager.local` | `Admin1234!` | STANDARD | Seeded via SQL (§3); MFA enrollment + login |
| 4 | `warning@task-manager.local` | `Admin1234!` | STANDARD | Seeded via SQL (§3); password-age warning banner |
| 5 | *(any sub/email)* | *(none)* | STANDARD | Mock OAuth2 — enter claims in the browser form |

Users 2–4 are created by the seed script in §3.
User 1 (`admin`) is inserted automatically by Liquibase changeset 007 when `docker-compose up` runs.

---

## 1. Scenario Index

| Scenario | User(s) | Key thing to verify |
| :--- | :--- | :--- |
| [Local login](#4-local-login-happy-path) | any seeded user | Login → dashboard; cookies set |
| [Admin panel](#5-admin-panel) | user 1 (admin) | Stats, user list, role promotion |
| [Registration](#6-registration) | new email | Form validation, password strength, auto-login |
| [MFA enrollment + login](#7-mfa-enrollment--login) | user 3 (mfa) | QR scan, verify page, TOTP code |
| [Password warning banner](#8-password-warning-banner) | user 4 (warning) | Yellow banner visible after login |
| [Forgot / reset password](#9-forgot--reset-password) | any LOCAL user | Email in Mailpit, token expiry |
| [OAuth2 login](#10-oauth2-login-mock) | user 5 (OAuth2) | Google / Microsoft buttons → mock form |
| [Task CRUD + state machine](#11-task-crud--state-machine) | any | Create, move between states, delete |
| [OVERDUE tasks](#12-overdue-tasks) | any | Tasks past due_date appear in OVERDUE column |
| [Optimistic locking conflict](#13-optimistic-locking-conflict) | any | 409 on concurrent edit |
| [Logout + re-login](#14-logout--re-login) | any | Clean cookie teardown; first re-login succeeds |

---

## 2. Prerequisites

```bash
cd tm-orchestrator
docker-compose up -d
# Wait ~30 s for migrations (db-migrations container) to finish before seeding
docker-compose logs -f db-migrations   # wait for "Liquibase command 'update' was executed successfully"
```

---

## 3. SQL Seed Script (users 2–4)

Run once after migrations complete. Open a psql shell:

```bash
docker exec -it tm-db psql -U tm -d taskmanager
```

Then paste:

```sql
-- ── Tenant & role IDs (fixed — from Liquibase changesets 001/002/007) ──────
-- Default tenant: 00000000-0000-0000-0000-000000000001
-- STANDARD role:  00000000-0000-0000-0000-000000000010
-- ADMIN role:     00000000-0000-0000-0000-000000000011
--
-- All passwords: Admin1234!
-- Hash is the same bcrypt hash used for the bootstrap admin (same plaintext).

-- User 2 — standard user (normal CRUD testing)
INSERT INTO users (id, tenant_id, email, auth_provider, password_hash, role_id,
                   is_mfa_enabled, theme, password_changed_at, created_at, updated_at)
VALUES ('00000000-0000-0000-0000-000000000020',
        '00000000-0000-0000-0000-000000000001',
        'standard@task-manager.local', 'LOCAL',
        '$2y$12$F1Vs4QN6yISV1TjrjnVe9eaoj5Apwb0fgtJo.rFY9HdiUmbpYXYiO',
        '00000000-0000-0000-0000-000000000010',
        false, 'LIGHT', now(), now(), now())
ON CONFLICT DO NOTHING;

-- User 3 — MFA user (mfa_secret filled in after enrollment via Settings)
INSERT INTO users (id, tenant_id, email, auth_provider, password_hash, role_id,
                   is_mfa_enabled, theme, password_changed_at, created_at, updated_at)
VALUES ('00000000-0000-0000-0000-000000000030',
        '00000000-0000-0000-0000-000000000001',
        'mfa@task-manager.local', 'LOCAL',
        '$2y$12$F1Vs4QN6yISV1TjrjnVe9eaoj5Apwb0fgtJo.rFY9HdiUmbpYXYiO',
        '00000000-0000-0000-0000-000000000010',
        false, 'LIGHT', now(), now(), now())
ON CONFLICT DO NOTHING;

-- User 4 — password warning user (password_changed_at is 85 days ago → warning threshold is 80 days)
INSERT INTO users (id, tenant_id, email, auth_provider, password_hash, role_id,
                   is_mfa_enabled, theme, password_changed_at, created_at, updated_at)
VALUES ('00000000-0000-0000-0000-000000000040',
        '00000000-0000-0000-0000-000000000001',
        'warning@task-manager.local', 'LOCAL',
        '$2y$12$F1Vs4QN6yISV1TjrjnVe9eaoj5Apwb0fgtJo.rFY9HdiUmbpYXYiO',
        '00000000-0000-0000-0000-000000000010',
        false, 'LIGHT', now() - interval '85 days', now(), now())
ON CONFLICT DO NOTHING;
```

> The bcrypt hash is the same value used for `admin@task-manager.local` in `.env`.
> All three users authenticate with password `Admin1234!`.
>
> To reset test users to a clean state: `DELETE FROM users WHERE email LIKE '%@task-manager.local' AND email != 'admin@task-manager.local';` then re-run the seed.

---

## 4. Local Login — Happy Path

**User:** any seeded user
**URL:** http://localhost:8080/login

1. Enter email + password → click **Sign in**.
2. Dashboard loads. Verify in DevTools → Application → Cookies:
   - `TM_SESSION` is present and `HttpOnly`.
   - `XSRF-TOKEN` is present and readable by JavaScript.
3. Navigate between Dashboard and Settings — session persists.

**Expected errors to verify:**
- Wrong password → `"Invalid email or password."` inline (no page reload).
- 6th attempt within 1 minute from the same IP → HTTP 429 (rate limit).

---

## 5. Admin Panel

**User:** `admin@task-manager.local` / `Admin1234!`

After login, the admin sees an **Admin** section in the navigation (or accessible at `/admin`).

| What to test | Where |
| :--- | :--- |
| Aggregate stats (tenants, users, active tasks, state breakdown) | Admin dashboard |
| User list (all tenants, paginated) | Admin → Users |
| Promote `standard@task-manager.local` to ADMIN role | Admin → Users → change role |
| Create a new tenant | Admin → Tenants → New |
| Verify `standard@...` now sees admin UI after role change | Log in as standard user |

---

## 6. Registration

**User:** any email not already in the database
**URL:** http://localhost:8080/register

1. Submit with a weak password (e.g. `abc`) → password-strength indicator goes red; submit blocked.
2. Submit with a strong password (e.g. `Sunshine#2025!`) → account created, auto-login, redirect to `/dashboard`.
3. Attempt to register the same email again → conflict error.

**Password strength indicator** uses `@zxcvbn-ts/core` — score 0–1 = weak (red), 2 = fair (yellow), 3–4 = strong (green). Registration requires score ≥ 3.

---

## 7. MFA Enrollment + Login

**User:** `mfa@task-manager.local` / `Admin1234!`

### Enrollment
1. Log in as `mfa@task-manager.local`.
2. Settings → Security → **Enable MFA**.
3. A QR code appears. Scan it with any TOTP app (Google Authenticator, Authy, 1Password, etc.).
4. Enter the 6-digit code to confirm enrollment → MFA enabled.
5. Log out.

### MFA Login
1. Log in with `mfa@task-manager.local` / `Admin1234!`.
2. After correct password, redirected to `/mfa/verify` (not dashboard) — session is `mfaPending=true`.
3. Enter the current TOTP code → redirect to `/dashboard`.

**Edge cases:**
- Wrong TOTP code → error, stay on `/mfa/verify`.
- Navigating directly to `/dashboard` with `mfaPending` session → redirected back to `/mfa/verify`.

---

## 8. Password Warning Banner

**User:** `warning@task-manager.local` / `Admin1234!`

`password_changed_at` is seeded to 85 days ago.
`PASSWORD_AGE_WARNING_DAYS=80` in `.env` → warning fires at 80 days.

1. Log in as `warning@task-manager.local`.
2. A yellow warning banner appears at the top of the dashboard: *"Your password will expire in N days. Change it now."*
3. Click the link → Settings → Security → Change Password.
4. After a successful password change, log out and log back in — banner is gone.

> **If the banner is not visible:** verify `password_changed_at` in the DB:
> ```sql
> SELECT email, password_changed_at, now() - password_changed_at AS age
> FROM users WHERE email = 'warning@task-manager.local';
> ```

---

## 9. Forgot / Reset Password

**User:** any LOCAL user (e.g. `standard@task-manager.local`)
**URL:** http://localhost:8080/forgot-password

1. Enter the email address → **Send reset link**.
2. Open Mailpit at http://localhost:8025 — the reset email appears within seconds.
3. Click the reset link in the email → `/auth/reset-password?token=...` opens.
4. Enter a new strong password → success → redirect to `/login`.
5. Log in with the new password.

**Edge cases:**
- Submit an email that does not exist → same success message (no user enumeration).
- Click the link a second time after already resetting → token-expired/already-used error.
- Token expiry is `PASSWORD_RESET_TOKEN_EXPIRY_HOURS=1` (from `.env`). After 1 hour the link returns a 400.

---

## 10. OAuth2 Login (Mock)

**Requires:** full compose stack (mock-oauth2 service running at http://localhost:9000).
**URL:** http://localhost:8080/login

The "Continue with Google" and "Continue with Microsoft" buttons both route to the same mock server in dev (configured via env vars in `docker-compose.override.yml`).

1. Click **Continue with Google** (or Microsoft).
2. The mock-oauth2-server shows a simple login form at `http://localhost:9000/default/...`.
3. Fill in:
   - **Subject:** any unique string, e.g. `google-user-001` (becomes the `sub` claim / `external_user_id`).
   - **Claims (JSON):** add `"email": "oauthuser@example.com"` in the extra claims field.
4. Click **Sign in** → BFF exchanges the token with Core API, creates the user if new, redirects to `/dashboard`.
5. On subsequent logins with the same subject, the existing user record is retrieved (no duplicate created).

> **First login:** Core API registers the OAuth2 user automatically (no password set, `auth_provider = GOOGLE`).
> **Settings → Security:** password change and MFA are available for OAuth2 users too.
> **What is NOT available:** OAuth2 users cannot use the local login form — attempting to log in with their email + a password returns 401 (`auth_provider` mismatch check in `AuthService`).

---

## 11. Task CRUD + State Machine

**User:** any authenticated user
**URL:** http://localhost:8080/dashboard

The dashboard shows a 3×3 matrix: rows = Urgency (HIGH / MEDIUM / LOW), columns = State (PLANNED / IN_PROGRESS / COMPLETED). OVERDUE tasks have their own column.

### Create
1. Click **New Task** (or any empty matrix cell).
2. Fill in title, description (optional), importance, urgency, due date (optional) → **Save**.
3. Task card appears in the PLANNED column at the correct urgency row.

### State transitions
| Button on card | From → To |
| :--- | :--- |
| **Start** | PLANNED → IN_PROGRESS |
| **Complete** | IN_PROGRESS → COMPLETED |
| **Reopen** | COMPLETED → PLANNED |

### Edit + delete
- Click the card title / edit icon → edit dialog → update fields → **Save**.
- Delete icon on the card → soft-delete (task disappears from UI; `deleted_at` set in DB).

### List fallback
If there are more tasks than fit in the matrix cell, a **"Show all"** link switches the cell to a scrollable list view.

---

## 12. OVERDUE Tasks

The Core API scheduler runs every minute and moves tasks from PLANNED or IN_PROGRESS to OVERDUE when `due_date < CURRENT_DATE`.

**To seed OVERDUE tasks immediately (SQL):**

```sql
-- Replace <user_id> with the UUID of the logged-in test user
-- (SELECT id FROM users WHERE email = 'standard@task-manager.local')
INSERT INTO tasks (tenant_id, user_id, title, state, importance, urgency, due_date, created_at, updated_at)
VALUES
  ('00000000-0000-0000-0000-000000000001',
   '00000000-0000-0000-0000-000000000020',
   'Overdue task — high urgency', 'PLANNED', 'HIGH', 'HIGH',
   current_date - interval '3 days', now(), now()),
  ('00000000-0000-0000-0000-000000000001',
   '00000000-0000-0000-0000-000000000020',
   'Overdue task — in progress', 'IN_PROGRESS', 'MEDIUM', 'MEDIUM',
   current_date - interval '1 day', now(), now());
```

After the next scheduler tick (up to 60 seconds), both tasks will appear in the **OVERDUE** column.

> The scheduler does NOT move COMPLETED or already-OVERDUE tasks.
> OVERDUE → PLANNED transition is available via the **Reopen** button (manual only).

---

## 13. Optimistic Locking Conflict

**User:** any (open the same task in two browser tabs)

1. Open the dashboard in **Tab A** and **Tab B** (same user, same task).
2. In Tab A: open the edit dialog → change the title → do **not** save yet.
3. In Tab B: open the same task, change the description → **Save** (this increments `version`).
4. Back in Tab A: click **Save** — the request carries the old `version` value.
5. Expected response: **HTTP 409 Conflict** → the UI shows an error ("Task was modified by someone else. Please reload.").

---

## 14. Logout + Re-login

Verifies the fix for the post-logout 401 bug (ghost session prevention).

1. Log in as any user.
2. Click **Logout**.
3. Verify in DevTools → Cookies: `TM_SESSION` is gone, `XSRF-TOKEN` is gone.
4. Immediately log in again with the same credentials.
5. **Expected:** login succeeds on the first attempt (HTTP 200, redirect to `/dashboard`).

> **Symptom of regression:** the first post-logout login returns HTTP 401; the second attempt succeeds.
> **Root cause:** `GET /auth/session` called `request.getSession()` (without `false`), creating a ghost session in Redis between logout and re-login. Fixed in `SessionController.java` (guardrail §19 / `DEVELOPMENT_ENV.md §8`).

---

## 15. Useful Diagnostic Queries

```sql
-- All users with their role and last login
SELECT u.email, r.name AS role, u.auth_provider,
       u.is_mfa_enabled, u.password_changed_at,
       now() - u.password_changed_at AS password_age,
       u.last_login_at
FROM users u JOIN roles r ON u.role_id = r.id
ORDER BY u.created_at;

-- Active tasks by state for a given user
SELECT state, count(*) FROM tasks
WHERE user_id = '00000000-0000-0000-0000-000000000020'
  AND deleted_at IS NULL
GROUP BY state;

-- Soft-deleted tasks (not visible in the UI)
SELECT id, title, state, deleted_at FROM tasks WHERE deleted_at IS NOT NULL;

-- Sessions in Redis (run from host, requires redis-cli)
-- docker exec -it tm-redis redis-cli KEYS "tm:session:*"
```
