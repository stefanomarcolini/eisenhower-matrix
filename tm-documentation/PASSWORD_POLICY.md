# Password Policy

Applies to **LOCAL auth users only** (email + password registration). OAuth2 users (Google, Microsoft) do not have a password in this system and are not affected by this policy.

---

## 1. Password Strength Requirements

### Minimum Rules (enforced server-side by Core API on registration and change)
| Rule | Requirement |
| :--- | :--- |
| Minimum length | 8 characters |
| Uppercase letter | At least 1 |
| Lowercase letter | At least 1 |
| Digit | At least 1 |
| Special character | At least 1 from: `!@#$%^&*()_+-=[]{}|;':\",./<>?` |
| Maximum length | 128 characters |
| Common password check | Rejected if the password appears in the top-10,000 common passwords list (checked server-side) |

Any password that fails these rules returns `HTTP 422` with a descriptive error message listing which rules were violated.

### UI Strength Indicator (React component)
Displayed on the registration form and the "Change Password" settings form. The indicator is **purely informational** — it does not block submission (server-side rules are the gate).

| Score | Label | Colour | Criteria |
| :--- | :--- | :--- | :--- |
| 0 | Weak | Red | Fails minimum length or missing character classes |
| 1 | Fair | Orange | Meets minimum rules only (exactly 8 chars, all required classes) |
| 2 | Strong | Yellow-green | 12+ chars and meets all required rules |
| 3 | Very Strong | Green | 16+ chars, meets all required rules, not a dictionary word |

Scoring is done client-side using [`@zxcvbn-ts/zxcvbn`](https://github.com/zxcvbn-ts/zxcvbn) (Apache 2.0). The server does not return a score; it only validates/rejects.

---

## 2. Password Change (from Settings)

1. User navigates to Settings → Security → Change Password.
2. Form fields: Current Password, New Password (with strength indicator), Confirm New Password.
3. React `PUT /api/v1/users/me/password` with `{currentPassword, newPassword}`.
4. Core API validates:
   - `currentPassword` matches the stored BCrypt hash.
   - `newPassword` passes all strength rules.
   - `newPassword` is different from `currentPassword`.
5. Core API updates `password_hash = BCrypt(newPassword)` and sets `password_changed_at = now()`.
6. Core API clears any active `password_reset_tokens` for this user.
7. On success, the BFF clears the `passwordWarning` session flag.
8. The user remains logged in (session is not invalidated).

---

## 3. Password Age Policy

Configurable via environment variables in the Core API (see `ENV_VARS.md`):

| Variable | Default | Meaning |
| :--- | :--- | :--- |
| `PASSWORD_MAX_AGE_DAYS` | `90` | Days after which the password is considered stale |
| `PASSWORD_AGE_WARNING_DAYS` | `80` | Days after which a warning is surfaced at login |

### Behaviour
- The age is calculated as `now() - users.password_changed_at`.
- At login, if age ≥ `PASSWORD_AGE_WARNING_DAYS`: the `/internal/auth/validate` response includes `"passwordWarning": true`. The BFF stores this in the session.
- On first authenticated page load, the React app calls `GET /auth/session` and, if `passwordWarning` is true, displays a **non-blocking banner** at the top of the page: *"Your password is over 80 days old. We recommend updating it in [Settings → Security]."*
- The banner is dismissible per session (re-appears on next login).
- There is **no hard expiry** — the application never forcibly logs out a user or blocks access due to password age alone. The warning is advisory only.
- Once the user changes their password, `password_changed_at` is updated, and the banner no longer appears on subsequent logins.

---

## 4. Password Reset (Forgot Password)

### Flow
1. User clicks "Forgot Password?" on the login page.
2. User enters their email address → `POST /auth/forgot-password`.
3. BFF always returns `HTTP 200` regardless of whether the email exists (prevents email enumeration).
4. If the email exists AND belongs to a `LOCAL` user:
   a. Core API generates a cryptographically random token (32 bytes, URL-safe Base64).
   b. Core API stores `SHA-256(token)` in `password_reset_tokens` with `expires_at = now() + PASSWORD_RESET_TOKEN_EXPIRY_HOURS`.
   c. Core API calls the email service to send a reset email containing: `https://{domain}/auth/reset-password?token={raw_token}`.
5. If the email belongs to a `GOOGLE` or `MICROSOFT` user, no email is sent. Instead, the response (returned to the BFF only, not the React client) triggers a user-friendly message: *"This email is linked to a [Google/Microsoft] account. Please use that sign-in button."*

### Token Validation
When the user submits `POST /auth/reset-password`:
1. Core API looks up `SHA-256(token)` in `password_reset_tokens`.
2. Validates: `used_at IS NULL` AND `expires_at > now()`.
3. If invalid: returns `HTTP 400` — *"This reset link is invalid or has expired. Please request a new one."*
4. If valid: updates `password_hash`, sets `password_changed_at = now()`, sets `used_at = now()`. Clears other active tokens for this user.

| Variable | Default | Meaning |
| :--- | :--- | :--- |
| `PASSWORD_RESET_TOKEN_EXPIRY_HOURS` | `1` | Hours until a reset token expires |

### Security Notes
- The raw token is never stored or logged. Only its SHA-256 hash is persisted.
- Tokens are single-use (`used_at` is set on first use).
- A cleanup scheduler (daily, 00:10 UTC) deletes expired and used tokens older than 7 days.

---

## 5. Storage
Passwords are hashed using **BCrypt** with a cost factor of **12**. The hash is stored in `users.password_hash`. Plaintext passwords are never logged, stored, or transmitted after the initial POST body is processed.