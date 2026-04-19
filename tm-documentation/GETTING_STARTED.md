# Getting Started

## 1. Prerequisites

| Tool | Min Version | Purpose |
| :--- | :--- | :--- |
| Rancher Desktop | Latest | Container runtime — use `dockerd (moby)` mode |
| JDK | 17 | Spring Boot services |
| Maven | 3.9 | Java builds |
| Node.js | 24 | React frontend |
| Git | 2.x | Version control |
| `kubectl` + `helm` | Latest / 3.x | Production Kubernetes (optional) |

**Shell setup (Windows / macOS):** add to `.bashrc` / `.zshrc`:
```bash
export DOCKER_HOST=unix:///var/run/docker.sock
```

---

## 2. Repository Setup (project owner, one-time)

Clone the monorepo once and work from the repository root:

```bash
git clone git@github.com:stefanomarcolini/eisenhower-matrix.git
cd eisenhower-matrix
```

All modules are part of this single repository and share one root Git history.

### GitHub Actions secrets
Add the secrets listed in `ENV_VARS.md §CI-Specific Secrets` to this repository (Settings -> Secrets -> Actions).

---

## 3. Cloning (collaborators)

```bash
git clone git@github.com:stefanomarcolini/eisenhower-matrix.git
cd eisenhower-matrix
```

---

## 4. First-Time Local Setup

```bash
cp tm-orchestrator/.env.example tm-orchestrator/.env
cp tm-core-api/.env.example      tm-core-api/.env
cp tm-ui-bff/.env.example        tm-ui-bff/.env
cp tm-db-schema/.env.example     tm-db-schema/.env
```

Key values for local dev (edit each `.env`):
- `INTERNAL_JWT_SECRET` and `MFA_ENCRYPTION_KEY`: `openssl rand -base64 32`
- `DB_PASSWORD`: any local value (e.g., `devpassword`)
- `BOOTSTRAP_ADMIN_BCRYPT_HASH`: generate with `htpasswd -bnBC 12 "" yourpassword | tr -d ':\n'` (requires `apache2-utils` / `httpd-tools`). Use cost 4 for local dev to keep migrations fast: `htpasswd -bnBC 4 "" yourpassword | tr -d ':\n'`. When storing in docker-compose `.env`, escape each `$` as `$$` (example: `$$2y$$12$$...`) so compose does not truncate the value.
- OAuth2 credentials: **not required** — mock OAuth2 server replaces real IdPs locally
- SMTP: `SMTP_HOST=mailpit`, `SMTP_PORT=1025`, `SMTP_TLS_ENABLED=false`

---

## 5. Running the Stack

```bash
cd tm-orchestrator
docker-compose up -d
docker-compose ps        # verify all services are healthy
```

| URL | Service |
| :--- | :--- |
| `http://localhost:8080` | Application |
| `http://localhost:8025` | Mailpit — captured emails |
| `http://localhost:9000` | Mock OAuth2 provider |

For live-reload dev mode, see `DEVELOPMENT_ENV.md §6`.

---

## 6. Logging In

- **Bootstrap admin (local):** Email `admin@task-manager.local`, password is the plaintext you hashed for `BOOTSTRAP_ADMIN_BCRYPT_HASH`. Change it immediately after first login via Settings → Security.
- **Mock OAuth2 (no credentials needed):** Click "Sign in with Google" or "Sign in with Microsoft" → mock server form → any email → logged in.
- **Real Google/Microsoft personal account:** Set `GOOGLE_CLIENT_ID`/`GOOGLE_CLIENT_SECRET` (or Microsoft equivalents) in `tm-ui-bff/.env`, restart the BFF.
- **Email + password (new user):** Click "Register" → fill the form (password strength indicator included).

---

## 7. Testing MFA Locally

1. Log in → **Settings → Security → Enable MFA**.
2. Scan the QR code with any Authenticator app (Google Authenticator, Authy, etc.).
3. Enter the 6-digit code → **Verify**.
4. Next login will require a TOTP code after the primary step.

---

## 8. Testing Password Reset Locally

1. Click **Forgot Password?** → enter a local-account email.
2. Open Mailpit at `http://localhost:8025` → click the reset link.
3. Enter a new password → log in.

---

## 9. Stopping

See `DEVELOPMENT_ENV.md §7`.

---

## 10. Debugging

See `OBSERVABILITY.md §4` for remote JVM debug, log viewing, DB/Redis access.

---

## 11. Troubleshooting

| Problem | Solution |
| :--- | :--- |
| Testcontainers fails | Set `export DOCKER_HOST=unix:///var/run/docker.sock` |
| Port 8080 in use | `docker-compose ps` → `docker-compose stop <name>` |
| Migrations fail | `docker-compose down -v && docker-compose up -d` |
| No email in Mailpit | `docker-compose logs core-api` — verify `SMTP_HOST=mailpit` |
| OAuth2 login fails locally | `docker-compose ps mock-oauth2` — must be running |
| CORS errors in dev mode | Verify Vite proxy in `vite.config.ts` and BFF running with `dev` profile |