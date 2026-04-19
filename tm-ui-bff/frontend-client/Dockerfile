# Multi-stage build: React SPA → Nginx runtime.
# Layer caching: package.json is copied first so the npm install layer is only
# rebuilt when dependencies change (CODING_PATTERNS.md §11).

# ── Stage 1: Build React SPA ─────────────────────────────────────────────────
FROM node:20-alpine AS build
WORKDIR /app

# Cache npm install — only reinstalled when package.json or package-lock.json changes
COPY tm-ui-bff/frontend-client/package*.json ./
RUN npm ci --prefer-offline || npm install --prefer-offline

# Copy source and build
COPY tm-ui-bff/frontend-client/ ./
RUN npm run build

# ── Stage 2: Nginx runtime ──────────────────────────────────────────────────
FROM nginx:alpine AS runtime

# Copy built React app to nginx html directory
COPY --from=build /app/dist /usr/share/nginx/html

# Copy custom nginx configuration if needed
# COPY nginx.conf /etc/nginx/nginx.conf

EXPOSE 80

# Use nginx default command
