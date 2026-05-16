# Image Versioning and Maintenance

> Guidance for managing container image versions, GitHub Actions dependencies, and manual dependency updates.

## 1. Container Image Versioning Strategy

### Version Scheme
All `tm-*` application images are tagged with:
- **`v1.0.<github-run-number>`** — stable release tag based on CI run (e.g., `v1.0.42`)
- **`latest`** — always points to the most recent stable release

### Example: tm-ui-bff Pipeline
```yaml
- name: Set image tag
  id: tag
  run: echo "value=v1.0.${{ github.run_number }}" >> $GITHUB_OUTPUT

- name: Push image to GHCR
  run: >-
    docker buildx build . \
      --push \
      --tag ghcr.io/stefanomarcolini/tm-ui-bff:${{ steps.tag.outputs.value }} \
      --tag ghcr.io/stefanomarcolini/tm-ui-bff:latest
```

**Rationale:**
- Every GitHub Actions run is numbered sequentially (`github.run_number`), making version history trivial to audit and revert.
- The `latest` tag always points to the most recent successful build from `main`, eliminating guessing about which version is in production.
- No need to manually bump version numbers in `pom.xml` or `package.json` — the CI generates them automatically.

### Base Image Versions
Keep base images pinned and up-to-date:

| Image | Current | Location |
| :--- | :--- | :--- |
| PostgreSQL | `17.2-alpine` | `docker-compose.yml` |
| Redis | `7.2.4-alpine` | `docker-compose.yml` |
| Java (Core API) | `eclipse-temurin:17-jre-jammy` | `tm-core-api/Dockerfile` runtime stage |
| Java (BFF) | `eclipse-temurin:17-jre-jammy` | `tm-ui-bff/Dockerfile` runtime stage |
| Selenium | `selenium/standalone-chrome:latest` | `docker-compose.override.yml` (dev only) |
| Mailpit | `axllent/mailpit:latest` | `docker-compose.override.yml` (dev only) |
| mock-oauth2 | `ghcr.io/navikt/mock-oauth2-server:latest` | `docker-compose.override.yml` (dev only) |

**Policy:**
- Production images (postgres, redis, application runtimes) use **specific patch versions** (e.g., `17.2-alpine`, `7.2.4-alpine`).
- Dev-only images (selenium, mailpit, mock-oauth2) can use `latest` since they don't affect production stability.
- Update production images only when critical security patches are released; test in dev before merging.

### Image Pull Failures in E2E

**Problem:** E2E tests fail if GHCR images are unavailable or if authentication fails.

**Solution:** `.github/workflows/e2e.yml` follows a four-step approach:
1. **Create `.env` first** so compose diagnostics have usable runtime values even if repository secrets are absent.
2. **Login to GHCR** using `docker/login-action@v4` with `GHCR_TOKEN` (PAT, `read:packages`), falling back to `GITHUB_TOKEN`.
3. **Verify package access** with `docker manifest inspect` for each application image.
4. **Pull latest images** only after access is confirmed.

The job requires `packages: read` permission:
```yaml
jobs:
  e2e:
    permissions:
      contents: read
      packages: read
```

Set `GHCR_TOKEN` in repository Settings → Secrets and variables → Actions. See `12-ENV-VARS.md §CI-Specific Secrets`.

---

## 2. GitHub Actions Dependency Management

### Node.js Runtime Deprecation (June 2, 2026)
GitHub is deprecating Node.js 20 on Actions runners and will force Node.js 24. All workflows must set:
```yaml
env:
  FORCE_JAVASCRIPT_ACTIONS_TO_NODE24: true
```

**Current action versions (Node 24 compatible):**

| Action | Version | Purpose |
| :--- | :--- | :--- |
| `actions/checkout` | `v6` | Clone the repository |
| `actions/setup-java` | `v5` | Install JDK |
| `actions/setup-node` | `v6` | Install Node.js |
| `actions/upload-artifact` | `v7` | Publish test reports |
| `docker/login-action` | `v4` | Authenticate to Docker registries |
| `docker/setup-buildx-action` | `v4` | Enable multi-platform Docker builds |
| `anchore/syft` (release binary) | `1.42.3` | Generate CycloneDX SBOMs (checksum-verified install) |
| `anchore/grype` (release binary) | `0.110.0` | Dependency/image vulnerability scanning (checksum-verified install) |

---

## 3. Manual Dependency Updates

Dependabot is not configured in this repository. Dependencies are managed manually. Update them when:
- A **critical security patch** is released (CVE in a dependency, OS package, or Java/Node runtime).
- A **compatibility fix** is required (e.g., upgrading Spring Boot to resolve a regression).
- A dependency reaches **end-of-life**.

### Steps
1. **Update the source** (e.g., `<parent><version>4.0.6</version></parent>` in `pom.xml`).
2. **Test locally** — build the image and run integration tests.
3. **Update documentation** — if the change is substantial, update `02-TECHNICAL-ARCHITECTURE.md §8` (dependency list).
4. **Commit on a branch, open a PR** — CI will build and verify before merge.

### Common Update Locations

| Component | File | Update Method |
| :--- | :--- | :--- |
| Java / Spring Boot | `tm-core-api/pom.xml`, `tm-ui-bff/pom.xml` | `<parent><version>` and `<properties>` |
| Database | `tm-orchestrator/docker-compose.yml` | `image: postgres:X.Y-alpine` |
| Redis | `tm-orchestrator/docker-compose.yml` | `image: redis:X.Y-alpine` |
| Node.js packages | `tm-ui-bff/frontend-client/package.json` | `npm update` or manual edit + `npm install` |
| GitHub Actions | `.github/workflows/*.yml` | Update `uses: action@version` |

### Audit Commands

```bash
# Maven — show available updates
mvn versions:display-dependency-updates
mvn dependency:tree

# npm — show outdated and vulnerable packages
npm outdated
npm audit
```

---

## 4. Rollback Scenarios

### Rollback an Application Image
If a newly deployed image has a critical bug:

1. **Identify the previous good version:**
   ```bash
   docker images ghcr.io/stefanomarcolini/tm-ui-bff | head -5
   # Shows: latest, v1.0.123, v1.0.122, v1.0.121, ...
   ```

2. **Update `docker-compose.yml`** to point to the known-good version:
   ```yaml
   frontend-bff:
     image: ghcr.io/stefanomarcolini/tm-ui-bff:v1.0.121
   ```

3. **Restart the service:**
   ```bash
   docker compose up -d frontend-bff
   ```

4. **Fix, commit, and merge** — the next merge to `main` generates a new tagged image.

### Rollback a GitHub Actions Version
If a newer action major version breaks the pipeline:

1. **Revert to the previous version** in `.github/workflows/*.yml`:
   ```yaml
   - uses: actions/setup-java@v4   # rollback from v5
   ```
2. **Push and re-run** the workflow.
3. **File an issue** with the action maintainers if the regression is not your fault.

---

## 5. Supply-Chain Security Best Practices

### Pin Action Versions
Always use **major-version tags** (e.g., `v6`, not `main`):
```yaml
# Good
- uses: actions/checkout@v6

# Bad
- uses: actions/checkout@main   # unpredictable — can change any time
```

### Verify Scanner Binary Checksums (Syft and Grype)
When a workflow installs Syft/Grype with `curl` from release assets, checksum verification is mandatory:

```bash
curl -fsSLO https://github.com/anchore/syft/releases/download/vX.Y.Z/syft_X.Y.Z_linux_amd64.tar.gz
curl -fsSLo syft_checksums.txt https://github.com/anchore/syft/releases/download/vX.Y.Z/syft_X.Y.Z_checksums.txt
grep ' syft_X.Y.Z_linux_amd64.tar.gz$' syft_checksums.txt > syft_checksum_line.txt
sha256sum -c syft_checksum_line.txt
```

Apply the same pattern to Grype. If checksum validation fails, the job must fail immediately.

**Evidence retention:** publish jobs upload the generated SBOM and checksum files as build artifacts so auditors can trace exactly which scanner versions produced the result.

---

## 6. Image Size and Build Time Targets

| Stage | Target |
| :--- | :--- |
| Compile + unit tests | < 10 minutes |
| Integration tests (Testcontainers) | < 15 minutes |
| Image build + scan + push | < 20 minutes |
| E2E tests (Selenium) | < 30 minutes |

**Target image sizes:**
- `tm-core-api`: < 500 MB (Spring Boot JAR + JRE)
- `tm-ui-bff`: < 600 MB (React SPA + Spring Boot JAR + JRE)
- `tm-db-schema`: < 300 MB (Liquibase + Postgres client + JRE)

---

## Checklist

- [ ] `GHCR_TOKEN` secret set in repository Actions secrets.
- [ ] All workflows set `FORCE_JAVASCRIPT_ACTIONS_TO_NODE24: true`.
- [ ] Action versions updated to Node 24-compatible releases (see table above).
- [ ] Base image versions pinned and documented in `docker-compose.yml`.
- [ ] No `latest` tag used for production infrastructure images.
