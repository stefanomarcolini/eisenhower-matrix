# Image Versioning and Automation

> Guidance for managing container image versions, GitHub Actions dependencies, and automated updates.

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
      --tag ghcr.io/sm-task-manager/tm-ui-bff:${{ steps.tag.outputs.value }} \
      --tag ghcr.io/sm-task-manager/tm-ui-bff:latest
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

**Problem:** E2E tests in `tm-orchestrator` fail if GHCR images are unavailable or if authentication fails.

**Solution:** The `tm-orchestrator/.github/workflows/e2e.yml` performs a four-step approach:
1. **Create `.env` first** so compose diagnostics and the stack have usable runtime values even if repository secrets are absent.
2. **Login to GHCR** before any pull (using `docker/login-action@v4`). Prefer `GHCR_USERNAME` + `GHCR_TOKEN` for cross-repo package reads; fall back to `GITHUB_TOKEN` only when package-level Actions access is granted.
3. **Verify package access** with `docker manifest inspect` for `tm-db-schema`, `tm-core-api`, and `tm-ui-bff`.
4. **Pull latest images** only after access is confirmed.

The job permissions must include `packages: read`:
```yaml
jobs:
  e2e:
    permissions:
      contents: read
      packages: read  # Required to pull from GHCR
```

If the images remain private, configure one of these GitHub-side access models:
- grant `tm-orchestrator` Actions access to each GHCR package in package settings, or
- define `GHCR_USERNAME` (Actions variable) and `GHCR_TOKEN` (PAT secret with `read:packages`) in `tm-orchestrator`.

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
| `actions/checkout` | `v5` | Clone the repository |
| `actions/setup-java` | `v5` | Install JDK |
| `actions/setup-node` | `v5` | Install Node.js |
| `actions/upload-artifact` | `v7` | Publish test reports |
| `docker/login-action` | `v4` | Authenticate to Docker registries |
| `docker/setup-buildx-action` | `v4` | Enable multi-platform Docker builds |
| `docker/build-push-action` | `v6` | Build and push Docker images |
| `anchore/syft` (release binary) | `1.42.3` | Generate CycloneDX SBOMs (checksum-verified install) |
| `anchore/grype` (release binary) | `0.110.0` | Dependency/image vulnerability scanning (checksum-verified install) |

**Regression check:** Repository-owned workflows should not emit Node.js 20 deprecation warnings. A warning mentioning `github/dependabot-action@main` is GitHub-managed and may persist until GitHub migrates that service runtime.

## 3. Automated Dependency Updates (Dependabot)

GitHub provides **free** dependency scanning and auto-updating via Dependabot. To enable:

### Setup (One-time, per repository)
1. Go to **Settings → Code security and analysis → Dependabot**.
2. Enable **Dependabot alerts**, **Dependabot security updates**, and **Dependabot version updates**.

### Configuration File
Create `.github/dependabot.yml` in each repository:

```yaml
version: 2
updates:
  # Maven dependencies (Java)
  - package-ecosystem: maven
    directory: /
    schedule:
      interval: weekly
      day: monday
      time: '03:00'
    open-pull-requests-limit: 5
    reviewers:
      - claude
    labels:
      - dependencies
      - maven
    commit-message:
      prefix: 'deps(maven):'

  # npm dependencies (Node.js / TypeScript)
  - package-ecosystem: npm
    directory: /frontend-client
    schedule:
      interval: weekly
      day: monday
      time: '03:00'
    open-pull-requests-limit: 5
    reviewers:
      - claude
    labels:
      - dependencies
      - npm

  # GitHub Actions
  - package-ecosystem: github-actions
    directory: /
    schedule:
      interval: weekly
      day: monday
      time: '03:00'
    open-pull-requests-limit: 5
    reviewers:
      - claude
    labels:
      - dependencies
      - github-actions
```

**Location:** This file should exist in each repository:
- `sm-task-manager/tm-core-api/.github/dependabot.yml`
- `sm-task-manager/tm-ui-bff/.github/dependabot.yml`
- `sm-task-manager/tm-db-schema/.github/dependabot.yml`
- `sm-task-manager/tm-orchestrator/.github/dependabot.yml`
- `sm-task-manager/tm-documentation/` (no dependencies, skip)

**How it works:**
- Dependabot scans `pom.xml` (Maven), `package.json` + `package-lock.json` (npm), and `.github/workflows/*.yml` (Actions).
- When a new version is available, Dependabot creates a pull request (1 per package per interval).
- The PR automatically runs the full test suite (via your pipelines).
- If tests pass, the PR is ready to review and merge.
- Security updates can be configured to auto-merge if tests pass (optional).

### GitHub Secrets for Dependabot
Dependabot uses `GITHUB_TOKEN` by default, which has access to the current repository only. If your tests require secrets (e.g., `INTERNAL_JWT_SECRET`), add them to **Settings → Secrets and variables → Repository secrets**. Dependabot will use them during PR test runs.

## 4. Container Image Update Automation

### Docker Digest Pinning
For extra supply-chain security, pin Docker images to their immutable SHA256 digest instead of semver tags:

Before (tag-based, can change):
```yaml
image: postgres:17.2-alpine
```

After (digest-based, immutable):
```yaml
image: postgres:17.2-alpine@sha256:abc123def456...
```

To get the digest:
```bash
docker pull postgres:17.2-alpine
docker inspect --format='{{.RepoDigests}}' postgres:17.2-alpine
# Returns: [postgres@sha256:abc123def456...]
```

**Dependabot supports digest pinning** — when you enable `docker` package ecosystem in `dependabot.yml`, it can update digest hashes when the underlying image is rebuilt (e.g., when Alpine OS patches are released).

Enable `docker` updates only in repositories that actually contain Docker manifests (`Dockerfile` or supported Kubernetes YAML). Do **not** enable `docker` updates in `tm-orchestrator` because it has no root Docker manifest and Dependabot will fail with `No Dockerfiles nor Kubernetes YAML found in /`.

```yaml
  - package-ecosystem: docker
    directory: /
    schedule:
      interval: weekly
      day: monday
```

## 5. Manual Version Updates

### When to Update Versions Manually
- **Critical security patches** (CVE in dependencies, OS packages, Java/Node runtime).
- **Compatibility fixes** (e.g., Spring Boot 3.2.x → 3.3.x to fix a regression).
- **End-of-life** (e.g., Java 11 no longer supported; must upgrade to 17).

### Steps
1. **Update the source** (e.g., `<parent><version>3.4.5</version></parent>` in `pom.xml`).
2. **Test locally** — build the image and run integration tests.
3. **Update documentation** — if the change is substantial, update `TECHNICAL_ARCHITECTURE.md §8` (dependency list).
4. **Commit and push** — include "chore: update X to Y" in the commit message; let CI build and push the new image tag.

### Common Update Locations
| Component | File | Update Method |
| :--- | :--- | :--- |
| Java / Spring Boot | `tm-core-api/pom.xml`, `tm-ui-bff/pom.xml` | `<parent><version>` and `<properties>` |
| Database | `docker-compose.yml` | `image: postgres:X.Y-alpine` |
| Redis | `docker-compose.yml` | `image: redis:X.Y-alpine` |
| Node.js packages | `tm-ui-bff/frontend-client/package.json` | `npm update` or manual edit + `npm install` |
| GitHub Actions | `.github/workflows/*.yml` | Update `uses: action@version` |

## 6. Monitoring Image Sizes and Build Times

### Image Size Report
Every publish pipeline should log the final image size:

```yaml
- name: Report image size
  run: |
    SIZE=$(docker image inspect tm-ui-bff:${{ steps.tag.outputs.value }} \
      --format='{{.Size}}' | awk '{print $1/1024/1024 "MB"}')
    echo "Image size: $SIZE"
```

**Target sizes (rough guides):**
- `tm-core-api`: < 500MB (Spring Boot JAR + JRE)
- `tm-ui-bff`: < 600MB (React SPA + Spring Boot JAR + JRE)
- `tm-db-schema`: < 300MB (Liquibase + Postgres client + JRE)

If images exceed these, investigate:
- Unused dependencies in `pom.xml` / `package.json`.
- Multi-stage Dockerfile optimizations (are you excluding `maven-compiler-plugin` cache, etc.?).
- Build artifact layers (ensure `target/` is not copied into the final image).

### Build Time Targets
Each pipeline should complete within:
- **Compile + unit tests:** < 10 minutes
- **Integration tests (Testcontainers):** < 15 minutes
- **Image build + scan + push:** < 20 minutes
- **E2E tests (Selenium):** < 30 minutes (overall job timeout)

If exceeds, check:
- Dependency download times (Maven central mirror slow?).
- Testcontainer image pulls (network latency?).
- Docker image build (multi-stage optimization?).

## 7. Rollback Scenarios

### Rollback Image to Previous Version
If a newly deployed image has a critical bug:

1. **Identify the previous good version:**
   ```bash
   docker images ghcr.io/sm-task-manager/tm-ui-bff | head -5
   # Shows: latest, v1.0.123, v1.0.122, v1.0.121, ...
   ```

2. **Update `docker-compose.yml`** (production) to point to the known-good version:
   ```yaml
   frontend-bff:
     image: ghcr.io/sm-task-manager/tm-ui-bff:v1.0.121  # rollback to 121
   ```

3. **Restart the service:**
   ```bash
   docker compose up -d frontend-bff
   ```

4. **Debug and fix** the issue in the code.

5. **Rebuild** — once fixed, the next merge to `main` will generate a new image (v1.0.N) with the fix.

### Rollback GitHub Actions Version
If a newer action major version breaks the pipeline:

1. **Revert to the previous version** in `.github/workflows/*.yml`:
   ```yaml
   - uses: actions/setup-java@v4   # rollback from v5
   ```

2. **Push and re-run** the workflow.

3. **File an issue** with the action maintainers if the regression is not your fault.

## 8. Supply-Chain Security Best Practices

### Pin Action Versions
Always use **exact** action versions (e.g., `v5`, not `v5.0.0` or `main`):
```yaml
# Good
- uses: actions/checkout@v5

# Bad
- uses: actions/checkout@main   # unpredictable
- uses: actions/checkout@v5.0.0 # avoid semver in Actions
```

### Verify Action Checksums
For critical security-sensitive actions (e.g., `docker/login-action`), GitHub recommends pinning to the **commit SHA**:
```yaml
- uses: docker/login-action@e6cf5d06d4255d47e5d6e9fac3ef08dccb25a14e  # v4 = this commit
```

Get the SHA from the action's releases page.

### Verify Scanner Binary Checksums (Syft and Grype)
When a workflow installs Syft/Grype with `curl` from release assets, checksum verification is mandatory before executing either binary.

Minimum control pattern:
```bash
curl -fsSLO https://github.com/anchore/syft/releases/download/vX.Y.Z/syft_X.Y.Z_linux_amd64.tar.gz
curl -fsSLo syft_checksums.txt https://github.com/anchore/syft/releases/download/vX.Y.Z/syft_X.Y.Z_checksums.txt
grep ' syft_X.Y.Z_linux_amd64.tar.gz$' syft_checksums.txt > syft_checksum_line.txt
sha256sum -c syft_checksum_line.txt
```

Apply the same pattern to Grype (`grype_X.Y.Z_linux_amd64.tar.gz` + `grype_X.Y.Z_checksums.txt`). If checksum validation fails, the job must fail immediately.

**Audit rationale:** vulnerability scan reports are only defensible evidence if the scanner binaries themselves are integrity-verified. This control closes a supply-chain gap where tampered scanner binaries could produce false negatives while appearing to pass CI.

**Evidence retention:** publish jobs should upload the generated SBOM and checksum files as build artifacts so auditors can trace exactly which scanner versions and digests produced the result.

### Audit Dependencies Regularly
Run this command in each module to identify outdated or vulnerable packages:
```bash
# Maven
mvn versions:display-dependency-updates
mvn dependency:tree

# npm
npm outdated
npm audit
```

## 9. Disaster Recovery

If GHCR becomes unavailable and E2E cannot pull images:

### Fallback: Build Images Locally in CI
Edit `.github/workflows/e2e.yml` to build instead of pull:

```yaml
- name: Build images (if pull failed)
  run: |
    if ! docker compose pull --quiet 2>/dev/null; then
      echo "GHCR unavailable; building images locally..."
      docker build -t ghcr.io/sm-task-manager/tm-ui-bff:latest tm-ui-bff/
      docker build -t ghcr.io/sm-task-manager/tm-core-api:latest tm-core-api/
      docker build -t ghcr.io/sm-task-manager/tm-db-schema:latest tm-db-schema/
    fi
```

This requires cloning the source repositories into the `tm-orchestrator` workflow (not ideal, but viable as a disaster-recovery last resort).

---

## Checklist

- [ ] Dependabot enabled in all repositories.
- [ ] `.github/dependabot.yml` deployed in each repo.
- [ ] GHCR login configured in `e2e.yml` (and other jobs that pull from GHCR).
- [ ] All workflows set `FORCE_JAVASCRIPT_ACTIONS_TO_NODE24: true`.
- [ ] Action versions updated to Node 24-compatible releases.
- [ ] Base image versions pinned and documented in `docker-compose.yml`.
- [ ] Image size and build time targets set and monitored.
- [ ] `DEVELOPMENT_ENV.md §8` updated with debugging tips for image pull failures.

