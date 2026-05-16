# API Contract

The machine-readable contract is `tm-core-api/api-spec/openapi.yaml` (OpenAPI v3, YAML-first). This document is its human-readable companion. Keep both in sync at each milestone.

---

## URL Structure

| Prefix | Owner | Description |
| :--- | :--- | :--- |
| `/oauth2/**`, `/login/**`, `/logout` | BFF | OAuth2 handshake routes (Spring Security managed) |
| `/auth/**` | BFF | Session management, local auth, MFA during login |
| `/api/v1/**` | Core API (proxied by BFF) | All business logic endpoints |
| `/internal/**` | Core API (BFF only) | Not in public OpenAPI spec; internal network only |

The BFF injects `Authorization: Bearer <app-jwt>` and `X-Tenant-ID` on every `/api/**` proxy call.

---

## Error Format
All errors follow RFC 7807:
```json
{ "type": "...", "title": "...", "status": 404, "detail": "...", "instance": "/api/v1/tasks/..." }
```
`404` is returned for cross-tenant resource access (not `403`) to prevent tenant enumeration.

---

## Pagination (list endpoints)
Cursor-based: `?cursor=<opaque>&limit=20` (max 100). Response: `{ data, nextCursor, totalCount }`.

---

## BFF Auth Routes

| Method | Path | Auth | Description |
| :--- | :--- | :--- | :--- |
| `POST` | `/auth/local/register` | No | Register with email + password. `409` if email exists. |
| `POST` | `/auth/local/login` | No | Login with email + password. Sets `TM_SESSION` cookie. `401` on failure (includes correct-provider hint). |
| `POST` | `/auth/forgot-password` | No | Request password reset email. Always `200`. |
| `POST` | `/auth/reset-password` | No | Execute reset with token. `400` if invalid/expired. |
| `POST` | `/auth/mfa/verify` | Partial (`mfa_pending`) | Submit TOTP code during login. Completes session on success. |
| `GET` | `/auth/session` | Yes | Returns `{ userId, email, role, isAuthenticated, mfaPending, passwordWarning }`. |
| `GET` | `/logout` | Yes | Invalidates session. |

---

## Internal API (BFF → Core API only)

Not in the public OpenAPI spec. All endpoints are network-isolated — reachable from BFF only.

| Method | Path | Request body | Response body |
| :--- | :--- | :--- | :--- |
| `POST` | `/internal/auth/token` | `{ iss, sub, email, name }` (OIDC claims) | `{ token, userId, tenantId, role, mfaRequired }` |
| `POST` | `/internal/auth/register` | `{ email, password, tenantId }` | `{ token, userId, tenantId, role }` |
| `POST` | `/internal/auth/validate` | `{ email, password, tenantId }` | `{ token, userId, tenantId, role, mfaRequired, passwordWarning }` |
| `POST` | `/internal/auth/mfa/validate` | `{ userId, code }` | `{ token, tenantId, role, passwordWarning }` |
| `POST` | `/internal/auth/refresh` | `{ userId }` | `{ token }` |
| `POST` | `/internal/auth/forgot-password` | `{ email, tenantId }` | `204` (always, even if email not found) |
| `POST` | `/internal/auth/reset-password` | `{ token, newPassword }` | `204` on success; `400` if invalid/expired |

**`mfaRequired`** — BFF uses this to branch into the MFA partial-session flow (see `09-CODING-PATTERNS.md §3`).
**`passwordWarning`** — BFF stores in session; surfaced via `GET /auth/session`.

---

## Tasks (`/api/v1/tasks`)

All endpoints require a valid session. All are tenant-scoped.

| Method | Path | Description |
| :--- | :--- | :--- |
| `GET` | `/api/v1/tasks` | List tasks. Filters: `state`, `importance`, `urgency`. Paginated. Soft-deleted tasks are never returned. |
| `POST` | `/api/v1/tasks` | Create task. |
| `GET` | `/api/v1/tasks/{id}` | Get task. `404` if not found or belongs to another user/tenant. |
| `PUT` | `/api/v1/tasks/{id}` | Full update. |
| `PATCH` | `/api/v1/tasks/{id}` | Partial update (e.g., state transition only). |
| `DELETE` | `/api/v1/tasks/{id}` | Soft-delete task (sets `deleted_at = now()`). The row is retained; the task disappears from all queries. Returns `204`. Hard deletes are not supported. |
| `GET` | `/api/v1/tasks/matrix` | All tasks grouped by importance × urgency cell. No pagination. |

**Task schema:** `id` (UUID, read-only), `title` (required, max 255), `description` (optional), `state` (`PLANNED`/`IN_PROGRESS`/`COMPLETED`/`OVERDUE`), `importance` (`LOW`/`MEDIUM`/`HIGH`), `urgency` (`LOW`/`MEDIUM`/`HIGH`), `dueDate` (ISO 8601 date), `createdAt`, `updatedAt` (both read-only). `deletedAt` exists in the database but is never included in API responses — filtering is handled at the persistence layer via Hibernate `@Where`.

State transitions are enforced by the API — see `01-PROJECT-OVERVIEW.md §3`.

---

## Users (`/api/v1/users`)

| Method | Path | Description |
| :--- | :--- | :--- |
| `GET` | `/api/v1/users/me` | Get profile. Includes `authProvider`, `passwordWarning`. |
| `PUT` | `/api/v1/users/me` | Update display name or theme. `email` and `authProvider` are immutable. |
| `PUT` | `/api/v1/users/me/password` | Change password (LOCAL users only). Body: `{ currentPassword, newPassword }`. `400` for OAuth2 users. |
| `POST` | `/api/v1/users/me/mfa/enable` | Initiate TOTP enrollment. Returns `{ secret, otpauthUri }`. |
| `POST` | `/api/v1/users/me/mfa/verify` | Confirm enrollment with a TOTP code. |
| `DELETE` | `/api/v1/users/me/mfa` | Disable MFA (requires current TOTP code). |

---

## Admin (`/api/v1/admin`)

All require `ADMIN` role. Returns `403` otherwise.

| Method | Path | Tenant-Scoped | Description |
| :--- | :--- | :--- | :--- |
| `POST` | `/api/v1/admin/tenants` | No | Create a tenant. |
| `GET` | `/api/v1/admin/stats` | No | Aggregate stats across all tenants. |
| `GET` | `/api/v1/admin/users` | No | Paginated user list. |
| `PATCH` | `/api/v1/admin/users/{id}/role` | No | Promote/demote user role (`STANDARD` ↔ `ADMIN`). |
| `GET` | `/api/v1/admin/reports/tasks` | Optional (`tenantId` param) | Export. `format`: `xlsx` or `pdf`. |