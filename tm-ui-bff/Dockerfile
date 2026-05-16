# Multi-stage build: React → Spring Boot JAR → minimal JRE runtime.
# Layer caching: dependency manifests are copied first so the install layer is only
# rebuilt when package.json or pom.xml changes (CODING_PATTERNS.md §11).

# ── Stage 1: Build React SPA ─────────────────────────────────────────────────
FROM node:20-alpine AS frontend
WORKDIR /app

# Cache npm install — only reinstalled when package.json or package-lock.json changes
COPY tm-ui-bff/frontend-client/package*.json ./
RUN npm ci --prefer-offline

COPY tm-ui-bff/frontend-client/ .
RUN npm run build

# ── Stage 2: Build Spring Boot JAR ───────────────────────────────────────────
FROM maven:3.9-eclipse-temurin-17 AS backend
WORKDIR /app

# Install root POM to local Maven repo (non-recursive — child modules not present in Docker context)
COPY pom.xml ./pom.xml
RUN mvn install -N -q

# Module poms go into subdirectory so relativePath=.. resolves to the root pom above.
COPY tm-ui-bff/pom.xml ./tm-ui-bff/pom.xml
COPY tm-ui-bff/bff-service/pom.xml ./tm-ui-bff/bff-service/pom.xml
WORKDIR /app/tm-ui-bff
RUN mvn dependency:go-offline -q

# Copy source and inject React build output as static resources
COPY tm-ui-bff/bff-service/src bff-service/src
COPY --from=frontend /app/dist bff-service/src/main/resources/static

RUN mvn clean package -DskipTests

# ── Stage 3: Minimal JRE runtime ─────────────────────────────────────────────
FROM eclipse-temurin:17-jre-jammy AS runtime

# Pull latest Ubuntu security patches at image build time.
# Install wget for docker-compose healthchecks.
RUN apt-get update \
 && apt-get upgrade -y \
 && apt-get install -y --no-install-recommends wget \
 && rm -rf /var/lib/apt/lists/* \
 && addgroup --system app \
 && adduser --system --ingroup app app

# Non-root user — principle of least privilege
USER app

COPY --from=backend /app/tm-ui-bff/bff-service/target/*.jar app.jar

EXPOSE 8080

ENTRYPOINT ["java", \
  "-XX:+UseContainerSupport", \
  "-XX:MaxRAMPercentage=75.0", \
  "-jar", "/app.jar"]
