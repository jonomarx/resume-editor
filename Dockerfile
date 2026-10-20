# Multi-stage build: Vite frontend -> Spring Boot jar (frontend served from static/) -> slim runtime.

FROM node:24-slim AS frontend
# Node 25+ no longer bundles corepack, so: install the pinned pnpm explicitly (keep in sync with PNPM_VERSION in ci.yml).
RUN npm install -g pnpm@12.14.0
WORKDIR /build
COPY api-contract ./api-contract
COPY frontend ./frontend
WORKDIR /build/frontend
RUN pnpm install --frozen-lockfile && pnpm build

FROM maven:3.9-eclipse-temurin-21 AS backend
WORKDIR /build
COPY pom.xml ./
COPY backend ./backend
COPY --from=frontend /build/frontend/dist ./backend/app/src/main/resources/static
RUN mvn -q -B -DskipTests package -pl backend/app -am

FROM eclipse-temurin:21-jre
RUN useradd --system --uid 10001 --no-create-home app
# Resume files and the render cache live in DATA_DIR. It exists in the image, owned by the app user, so the container
# works out of the box as a non-root user; mount a volume at /data to keep the files (back up Postgres and DATA_DIR together).
RUN mkdir -p /data && chown 10001:10001 /data
ENV DATA_DIR=/data
VOLUME /data
WORKDIR /app
COPY --from=backend /build/backend/app/target/app-*-SNAPSHOT.jar app.jar
USER 10001
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
