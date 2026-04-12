# Multi-Tenancy Design

## 1. Strategy: Row-Level Tenancy
The application uses a **row-level tenancy** model: a single database, a single schema, and a `tenant_id UUID NOT NULL` column on every table that contains user-owned data (`users`, `tasks`, `task_history`).

**Rationale:** Simpler to operate, easier to query across tenants for admin reporting, and sufficient for the target scale. Migration to schema-per-tenant is possible without changing the API contract if isolation requirements increase later.

---

## 2. Tenant Provisioning
1. A super-admin calls `POST /api/v1/admin/tenants` with a tenant `name`.
2. The Core API inserts a row into `tenants`.
3. A super-admin then calls `PATCH /api/v1/admin/users/{id}/role` to promote the designated tenant owner to `ADMIN`.

All users created via normal auth flows (OAuth2 or local registration) receive `role = STANDARD` by default. Role elevation to `ADMIN` is an explicit super-admin action only — there is no automatic first-user-gets-admin behaviour. Self-service tenant creation is out of scope for v1.

---

## 3. Tenant Resolution at Runtime

```
Browser → BFF → Core API
```

1. The user authenticates via OAuth2/OIDC. The IdP issues an ID token containing a `tenant_id` claim (or the BFF resolves the tenant from the user's email domain on first login).
2. The BFF stores the resolved `tenant_id` in the Redis-backed session.
3. On every proxied request, the BFF reads `tenant_id` from the session and injects it as the `X-Tenant-ID: <uuid>` request header.
4. The Core API reads `X-Tenant-ID` in a `TenantInterceptor` and stores it in a request-scoped `TenantContext` (ThreadLocal, cleared in `afterCompletion`).

The Core API must never trust a `X-Tenant-ID` header sent directly by an external client. The header is only accepted from the BFF, which is the only component allowed to call Core API (enforced by network policy in Kubernetes; by the internal Docker network in compose).

---

## 4. Core API Enforcement (Hibernate Filter)

A Hibernate named filter is defined on all tenant-scoped entities:

```java
@FilterDef(name = "tenantFilter", parameters = @ParamDef(name = "tenantId", type = String.class))
@Filter(name = "tenantFilter", condition = "tenant_id = :tenantId")
@Entity
public class Task { ... }
```

Every entity with a `tenant_id` column must carry both annotations — including `TaskHistory`. The `@FilterDef` is declared once (on any one entity or in a `package-info.java`) and the `@Filter` is applied to each entity individually.

An `OpenSessionInViewFilter`-style interceptor enables the filter after `TenantContext` is populated:

```
Request → TenantInterceptor (populates TenantContext)
        → Spring Data JPA / Hibernate (filter applied automatically)
        → Response
```

The filter is enabled on `EntityManager` open via a `HibernatePropertiesCustomizer` or an AOP aspect. It is **not bypassable** from regular service code unless explicitly disabled via `session.disableFilter("tenantFilter")`.

---

## 5. Admin Cross-Tenant Access
Admin endpoints that aggregate data across tenants (e.g., `GET /api/v1/admin/stats`) must:
1. Be guarded by `@PreAuthorize("hasRole('ADMIN')")`.
2. Explicitly call `session.disableFilter("tenantFilter")` in a dedicated `@Transactional` service method.
3. Never expose this method through a non-admin code path.

---

## 6. Data Isolation Guarantee
No query can return rows from a different tenant as long as the Hibernate filter is active. Defense-in-depth measures:
- All repository method names follow the convention `findByIdAndTenantId(...)` to make tenant scoping explicit even when the filter is active.
- PostgreSQL Row Level Security (RLS) may be added as a future defense-in-depth layer at the database level, but is not required for v1.

---

## 7. Tenant Isolation in Tests
Integration tests must verify that tenant isolation holds. The minimum test matrix:

| Test Case | Expected Result |
| :--- | :--- |
| User A (tenant 1) reads their own task | 200 OK |
| User A (tenant 1) reads a task owned by User B (tenant 2) | 404 Not Found |
| User A (tenant 1) updates a task owned by User B (tenant 2) | 404 Not Found |
| Admin reads tasks across both tenants | 200 OK (all tasks returned) |

Note: `404` (not `403`) is returned for cross-tenant resource access to prevent tenant enumeration.
