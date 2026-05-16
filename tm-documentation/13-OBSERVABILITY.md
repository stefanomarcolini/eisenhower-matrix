# Observability

## 1. Application Logging

### Format
- **Production** (`prod` profile): Structured JSON via `logstash-logback-encoder`. Fields: `timestamp`, `level`, `service`, `requestId`, `tenantId`, `userId`, `message`, `stackTrace`.
- **Development** (`dev` profile): Human-readable Logback pattern (console only).

### Correlation
`X-Request-ID` (client UUID, or BFF-generated if absent) is stored in MDC at request entry and emitted on every log line. The BFF propagates it to Core API via the proxy.

### Log Levels
| Profile | Root | `com.tm` package |
| :--- | :--- | :--- |
| `prod` | `WARN` | `INFO` |
| `dev` | `INFO` | `DEBUG` |
| `test` | `WARN` | `DEBUG` |

### What NOT to Log
Never log: passwords, JWT tokens, session IDs, TOTP secrets, encryption keys, or any field named `password`, `secret`, `token`, `key`, or `credential`. Enforce with Lombok `@ToString.Exclude` and `@JsonProperty(access = WRITE_ONLY)` on sensitive DTO fields. Auth endpoint bodies (`/auth/**`, `/internal/auth/**`) must never be logged at DEBUG level.

---

## 2. Audit Log

Security events are written to a dedicated audit appender (category `audit`) at `INFO` level, regardless of root log level. Each event includes: `timestamp`, `event`, `userId` (or `email` if pre-auth), `tenantId`, `ipAddress`, `requestId`, `outcome` (`SUCCESS` / `FAILURE` + reason).

| Event | Trigger |
| :--- | :--- |
| `AUTH_LOGIN_SUCCESS` | Successful login (any method) |
| `AUTH_LOGIN_FAILURE` | Failed attempt (bad credentials / wrong provider / MFA failure) |
| `AUTH_LOGOUT` | Session invalidated |
| `AUTH_MFA_ENABLED` / `AUTH_MFA_DISABLED` | User toggles MFA |
| `AUTH_PASSWORD_CHANGED` | Password changed from Settings |
| `AUTH_PASSWORD_RESET_REQUESTED` / `AUTH_PASSWORD_RESET_COMPLETED` | Password reset flow |
| `ADMIN_TENANT_CREATED` | Admin creates a tenant |
| `USER_ROLE_CHANGED` | Admin changes a user's role |

---

## 3. Health Endpoints

Spring Actuator — only `health` and `info` exposed externally:
```yaml
management.endpoints.web.exposure.include: health,info
management.endpoint.health.show-details: when-authorized
```

| Endpoint | External | Purpose |
| :--- | :--- | :--- |
| `GET /actuator/health` | Yes | Liveness + readiness (DB and Redis sub-checks) |
| `GET /actuator/info` | Yes | Build version and git commit |

All other Actuator endpoints restricted to the internal network. CI smoke test hits `/actuator/health` after image build.

---

## 4. Local Debugging

### Remote JVM Debug
In `docker-compose.override.yml`:
```yaml
core-api:
  environment:
    JAVA_TOOL_OPTIONS: "-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=*:5005"
  ports:
    - "5005:5005"

frontend-bff:
  environment:
    JAVA_TOOL_OPTIONS: "-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=*:5006"
  ports:
    - "5006:5006"
```
Connect IDE remote debugger to `localhost:5005` (Core API) or `localhost:5006` (BFF).

### Viewing Logs
```bash
docker-compose logs -f                  # all services
docker-compose logs -f core-api         # single service
docker-compose logs --tail=100 core-api # last 100 lines
```

### Database Access
```bash
docker-compose exec db psql -U $DB_USERNAME -d $DB_NAME
```

### Redis Inspection
```bash
docker-compose exec redis redis-cli
KEYS *        # list session keys
TTL <key>     # check session expiry
DEL <key>     # invalidate a session manually
```

### Mailpit
All dev emails captured at `http://localhost:8025`.