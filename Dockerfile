FROM eclipse-temurin:21-jdk AS backend-build

WORKDIR /workspace

COPY backend/gradlew backend/gradlew
COPY backend/gradle backend/gradle
COPY backend/build.gradle backend/settings.gradle backend/

WORKDIR /workspace/backend
RUN sed -i 's/\r$//' gradlew && chmod +x gradlew

COPY backend/src src
RUN ./gradlew bootJar --no-daemon

FROM node:22-alpine AS frontend-deps

WORKDIR /workspace/frontend

COPY frontend/package.json frontend/package-lock.json ./
RUN npm ci

FROM node:22-alpine AS frontend-build

WORKDIR /workspace/frontend
ENV NEXT_TELEMETRY_DISABLED=1
ARG NEXT_PUBLIC_API_BASE_URL=
ENV NEXT_PUBLIC_API_BASE_URL=$NEXT_PUBLIC_API_BASE_URL
ARG BACKEND_INTERNAL_URL=http://127.0.0.1:8081
ENV BACKEND_INTERNAL_URL=$BACKEND_INTERNAL_URL

COPY --from=frontend-deps /workspace/frontend/node_modules ./node_modules
COPY frontend .
RUN npm run build

FROM node:22-bookworm-slim AS node-runtime

FROM eclipse-temurin:21-jre

WORKDIR /app

RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*

COPY --from=node-runtime /usr/local/bin/node /usr/local/bin/node
COPY --from=node-runtime /usr/local/lib/node_modules /usr/local/lib/node_modules
COPY --from=backend-build /workspace/backend/build/libs/*.jar /app/backend/app.jar
COPY --from=frontend-build /workspace/frontend/.next/standalone /app/frontend
COPY --from=frontend-build /workspace/frontend/.next/static /app/frontend/.next/static
COPY docker/start-cloud-run.sh /app/start-cloud-run.sh

RUN chmod +x /app/start-cloud-run.sh

EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=5s --start-period=30s --retries=5 \
  CMD curl -fsS http://127.0.0.1:${PORT:-8080}/actuator/health >/dev/null || exit 1

ENTRYPOINT ["/app/start-cloud-run.sh"]
