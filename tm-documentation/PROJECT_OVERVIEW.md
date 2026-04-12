# Project Overview: Containerized Task Manager (BFF Pattern)

## 1. Core Objective
Build a horizontally scalable, multi-tenant task management application with a focus on Eisenhower-style task prioritization (3×3 Matrix) and secure OAuth2/OIDC authentication.

## 2. User Personas & Permissions
- **Standard User:** - Self-registration with role selection.
    - Full CRUD on personal tasks.
    - Access to settings (MFA, Theme).
- **Admin User:** - All Standard User capabilities.
    - Can switch context/role between Admin and Standard.
    - Access to global statistics and reporting (Excel/PDF export).

## 3. Functional Requirements
### Task Management
- Fields: Title, Description (Free text), Created Date, Due Date, State, Importance (Low/Med/High), Urgency (Low/Med/High).
- States: `Planned`, `In Progress`, `Completed`, `Overdue`.
    - **Note:** Only four states are defined. "Started" has been removed as it was redundant with "In Progress".
    - `Overdue` is a system-driven state, not set manually by the user (see State Transition Rules below).

#### State Transition Rules
Legal transitions enforced by the API and the database check constraint:

| From | To | Trigger |
| :--- | :--- | :--- |
| `Planned` | `In Progress` | User action |
| `In Progress` | `Completed` | User action |
| `Planned` or `In Progress` | `Overdue` | System scheduler (due date has passed) |
| `Overdue` | `In Progress` | User action (re-opens the task) |

Tasks in `Completed` state are terminal and cannot be transitioned further.

- **The Matrix UI:** A 3×3 grid for Importance vs. Urgency.
    - Users click a cell to set both values.
    - Features: Toggle axes, change sort order (High->Low or Low->High).

### Settings & UI
- Support for Light/Dark mode (state persisted).
- MFA Toggle (using TOTP/Authenticator App).
- Responsive Design: Mobile, Tablet, and Desktop optimization.

---

## 4. v1 Design Constraints & Future Extension Points

Explicit decisions made during v1 planning. Each entry documents what is in scope, what is out of scope, and why — so future phases have clear extension points rather than surprise constraints.

| Concern | v1 Decision | Out of scope for v1 | Extension path |
| :--- | :--- | :--- | :--- |
| **Task ownership** | Tasks are personal — owned by a single user. No assignment or sharing. | Task assignment, shared task lists, team boards | Add `assigned_to UUID FK → users.id NULLABLE` to `tasks`; extend task list API to filter by assignee |
| **Deletion** | Soft deletes only — `DELETE` sets `deleted_at`, row is retained. Hard deletes are not supported. | Permanent deletion, recycle bin UI | Recycle bin is a query on `deleted_at IS NOT NULL`; permanent delete is a privileged admin action |
| **Task history** | Every state transition is recorded in `task_history` (internal, no public API). | Activity feed UI, history export | Add `GET /api/v1/tasks/{id}/history` endpoint when UI is ready |
| **Role model** | Binary: `STANDARD` and `ADMIN`. Authorization uses `@PreAuthorize` with authority literals, not role-name string comparisons in service code. | Team roles, per-resource permissions, custom roles | Roles table and authority-based checks are already designed for extension; add permission columns or a `permissions` join table without touching service logic |
| **Account linking** | One email maps to exactly one auth provider. Linking the same email across Google/Microsoft/Local is not supported. | Multi-provider account linking | Introduce an `account_links` table mapping multiple `(provider, external_user_id)` pairs to one `users.id` |
| **File attachments** | Not supported. | Attaching files to tasks | Add `task_attachments` table + object storage (S3/MinIO) integration; no schema changes to `tasks` needed |
| **Notifications** | No push/email notifications for task events. Password reset email is the only outbound mail. | Overdue reminders, assignment notifications, digest emails | Introduce a message broker (e.g., RabbitMQ) or a `notifications` outbox table; the existing `EmailService` and `task_history` table provide the foundation |
| **Real-time updates** | No WebSocket or SSE. UI reflects server state on page load or manual refresh. | Live task board updates, collaborative editing | Add Spring WebSocket support to the BFF; `task_history` provides the event stream |
| **Self-service tenants** | Tenants are created by a super-admin only. | Self-registration of new organisations | Add a tenant sign-up flow; the multi-tenancy model requires no structural change |