# Multi-stage build: Maven dependency layer + source build → minimal JRE runtime.
# Layer caching: pom.xml is copied before source so the dependency download layer
# is only invalidated when pom.xml changes, not on every source edit.
# See CODING_PATTERNS.md §11.

# ── Stage 1: Build Spring Boot JAR ───────────────────────────────────────────
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /app

# Cache Maven dependency download — only re-downloaded when pom.xml changes
COPY pom.xml .
RUN mvn dependency:go-offline -q

# Copy source and OpenAPI spec; build skipping tests
# api-spec/ is needed by openapi-generator-maven-plugin at compile time
COPY api-spec ./api-spec
COPY src ./src
RUN mvn clean package -DskipTests

# ── Stage 2: Minimal JRE runtime ─────────────────────────────────────────────
FROM eclipse-temurin:17-jre-jammy AS runtime

# Pull latest security patches from Ubuntu repositories at build time.
# Install wget for docker-compose healthchecks.
RUN apt-get update \
 && apt-get upgrade -y \
 && apt-get install -y --no-install-recommends wget \
 && rm -rf /var/lib/apt/lists/* \
 && addgroup --system app \
 && adduser --system --ingroup app app

# Non-root user — principle of least privilege
USER app

COPY --from=build /app/target/*.jar app.jar

EXPOSE 8080

ENTRYPOINT ["java", \
  "-XX:+UseContainerSupport", \
  "-XX:MaxRAMPercentage=75.0", \
  "-jar", "/app.jar"]
