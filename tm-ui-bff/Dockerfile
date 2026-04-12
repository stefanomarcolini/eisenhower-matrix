# Multi-stage build: React → Spring Boot JAR → minimal JRE runtime.
# Layer caching: dependency manifests are copied first so the install layer is only
# rebuilt when package.json or pom.xml changes (CODING_PATTERNS.md §11).

# ── Stage 1: Build React SPA ─────────────────────────────────────────────────
FROM node:20-alpine AS frontend
WORKDIR /app

# Cache npm install — only reinstalled when package.json or package-lock.json changes
COPY frontend-client/package*.json ./
RUN npm ci --prefer-offline

COPY frontend-client/ .
RUN npm run build

# ── Stage 2: Build Spring Boot JAR ───────────────────────────────────────────
FROM maven:3.9-eclipse-temurin-17 AS backend
WORKDIR /app

# Cache Maven dependency download — only re-downloaded when pom.xml files change
COPY pom.xml ./
COPY bff-service/pom.xml bff-service/
RUN mvn dependency:go-offline -q -f pom.xml

# Copy source and inject React build output as static resources
COPY bff-service/src bff-service/src
COPY --from=frontend /app/dist bff-service/src/main/resources/static

RUN mvn clean package -DskipTests -f pom.xml

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

COPY --from=backend /app/bff-service/target/*.jar app.jar

EXPOSE 8080

ENTRYPOINT ["java", \
  "-XX:+UseContainerSupport", \
  "-XX:MaxRAMPercentage=75.0", \
  "-jar", "/app.jar"]
