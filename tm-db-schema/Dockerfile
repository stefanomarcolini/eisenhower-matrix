# ARG declarations that appear in FROM lines must be in the global scope
# (before any FROM) — see https://docs.docker.com/engine/reference/builder/#arg
# Override at build time: docker build --build-arg LIQUIBASE_IMAGE_TAG=5.1 ...
ARG LIQUIBASE_IMAGE_TAG=5.0

# ── Stage 1: Fetch PostgreSQL JDBC driver via Maven (layer-cached) ────────────
# Copying pom.xml first means this layer is only invalidated when pom.xml changes,
# not when changelogs change (applies the dependency:go-offline caching pattern
# from CODING_PATTERNS.md §11 to this non-compiled module).
FROM maven:3.9-eclipse-temurin-17-alpine AS deps
WORKDIR /build
COPY pom.xml .
RUN mvn dependency:copy-dependencies \
      -DincludeArtifactIds=postgresql \
      -DoutputDirectory=/deps \
      -q

# ── Stage 2: Liquibase runtime ────────────────────────────────────────────────
# LIQUIBASE_IMAGE_TAG must match the major.minor of <liquibase.version> in pom.xml.
# pom.xml uses 5.0.2 → tag is 5.0.
FROM liquibase/liquibase:${LIQUIBASE_IMAGE_TAG}

# Add the PostgreSQL JDBC driver explicitly (official image ships its own copy,
# but pinning our version avoids surprises on driver/server version mismatches).
COPY --from=deps /deps/postgresql-*.jar /liquibase/lib/

# Copy changelogs
COPY src/main/resources/db/changelog/ /liquibase/changelog/

# Copy and enable the entrypoint script
COPY docker-entrypoint.sh /docker-entrypoint.sh
USER root
# Strip Windows CRLF line endings that break the shebang on Linux, then make executable.
# apt-get upgrade patches OS-level CVEs in deb packages.
# The Liquibase base image installs the JDK at /opt/java/openjdk via the eclipse-temurin
# base layer — it is NOT an APT-managed package so apt-get upgrade alone cannot update it.
# We add the Adoptium APT repository, install the latest temurin-17-jdk (17.0.18+),
# remove the old bundled JDK, and update JAVA_HOME so Liquibase picks up the patched binary.
# This resolves HIGH openjdk CVEs: CVE-2024-21147, CVE-2025-21587, CVE-2025-30749,
# CVE-2025-50106, CVE-2025-53066, CVE-2026-21945, CVE-2026-21932 (fixed in 17.0.18).
# Remove unused Liquibase-bundled JDBC drivers (mssql-jdbc, snowflake-jdbc) and the lpm binary.
RUN sed -i 's/\r$//' /docker-entrypoint.sh && chmod +x /docker-entrypoint.sh \
 && apt-get update && apt-get upgrade -y \
 && apt-get install -y --no-install-recommends wget gpg \
 && wget -qO- https://packages.adoptium.net/artifactory/api/gpg/key/public \
      | gpg --dearmor -o /usr/share/keyrings/adoptium.gpg \
 && printf 'deb [signed-by=/usr/share/keyrings/adoptium.gpg] https://packages.adoptium.net/artifactory/deb %s main\n' \
      "$(awk -F= '/^VERSION_CODENAME/{print$2}' /etc/os-release)" \
      > /etc/apt/sources.list.d/adoptium.list \
 && apt-get update && apt-get install -y temurin-17-jdk \
 && rm -rf /opt/java/openjdk \
 && ln -sf "$(readlink -f "$(which java)" | sed 's|/bin/java$||')" /usr/lib/jvm/temurin-17-current \
 && rm -rf /var/lib/apt/lists/* \
 && find /liquibase -name "mssql-jdbc*.jar" -delete \
 && find /liquibase -name "snowflake-jdbc*.jar" -delete \
 && rm -f /liquibase/bin/lpm

# Point JAVA_HOME at the stable symlink created above.
# The symlink /usr/lib/jvm/temurin-17-current is resolved at build time to the
# actual Adoptium JDK install path (which varies across Ubuntu releases, e.g.
# temurin-17-jdk-amd64 on Jammy vs temurin-17 on Focal), ensuring the Liquibase
# startup script always finds a valid java binary via ${JAVA_HOME}/bin/java.
ENV JAVA_HOME=/usr/lib/jvm/temurin-17-current
ENV PATH="${JAVA_HOME}/bin:${PATH}"

USER liquibase

ENTRYPOINT ["/docker-entrypoint.sh"]