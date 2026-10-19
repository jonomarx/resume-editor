# Multi-stage build: Vite frontend -> Spring Boot jar (frontend served from static/) -> slim runtime.

FROM node:22-slim AS frontend
RUN corepack enable
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
WORKDIR /app
COPY --from=backend /build/backend/app/target/app-*-SNAPSHOT.jar app.jar
USER 10001
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
