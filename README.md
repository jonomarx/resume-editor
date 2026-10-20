# Internship Resume & Job Tracker

A per-user web app where you keep one verbose LaTeX master resume (Jake's template), get a daily-refreshed feed of internships ranked by fit, and turn any posting into a one-page tailored draft resume. V1 goals: a structured master resume editor with live PDF preview; a job pipeline fed by career pages you provide plus an ingest API; tag-based ranking; per-job drafts with manual and AI-assisted cutdown to one page. The full design is in [`docs/DESIGN.md`](docs/DESIGN.md).

## Stack

React + Vite + TypeScript (pnpm) frontend; Spring Boot (Java 21) API built with Maven; Postgres (H2 in fast tests); Redis for sessions; a LaTeX renderer in its own container; Playwright for end-to-end tests. Spring serves the compiled frontend from the same origin.

## Repository layout

```
backend/         Maven multi-module build (common, core, jobs, app)
frontend/        React app
renderer/        LaTeX render container
api-contract/    openapi.yaml (plus lint and mock-server tests)
e2e/             Playwright tests against the running stack
docs/            DESIGN.md and decision records
AGENTS.md        guide for the AI agents that build this app
```

## Prerequisites

JDK 21, Maven, Node 24 LTS, pnpm 12.14.0, and Docker with Compose.

## Run it

`docker-compose.yml` starts Postgres and Redis (and the renderer). `DB_PASSWORD` is required; use any local value and never commit it. See `.env.example` for the other variables.

### Whole stack in Docker (serves the app on :8080)

```sh
export DB_PASSWORD=local-dev-pw
docker compose up -d --wait postgres redis
docker build -t resume-editor-app .
docker run -d --name resume-app --network host -e DB_PASSWORD \
  -e DB_URL=jdbc:postgresql://localhost:5432/resume resume-editor-app
```

Open http://localhost:8080. The root `Dockerfile` builds the frontend, bakes it into the Spring jar, and runs it as the non-root user `app` (uid 10001). Stop with:

```sh
docker rm -f resume-app
docker compose down -v
```

**Data directory.** Resume files and the render cache are written to `DATA_DIR`, which is `/data` in the image: it exists, is owned by the app user, and is declared a `VOLUME`, so the container works with no extra flags (a fresh anonymous volume is created per `docker run`). To keep the files across containers mount a named volume or a host directory writable by uid 10001, for example `-v resume-data:/data`; a host directory needs `chown 10001:10001`. Back up Postgres and `DATA_DIR` together. Outside Docker, `DATA_DIR` defaults to `./data`.

### Dev loop (Vite on :5173, API on :8080)

```sh
export DB_PASSWORD=local-dev-pw
docker compose up -d --wait postgres redis
mvn -q -B -DskipTests package -pl backend/app -am
java -jar backend/app/target/app-*-SNAPSHOT.jar        # API on :8080

# in another terminal
cd frontend
pnpm install
pnpm dev                                               # http://localhost:5173, proxies /api to :8080
```

Without a frontend build baked into the jar, :8080 serves only the API; use :5173 for the UI. `docker compose down -v` when done.

## Test it

| What | Command |
| --- | --- |
| Backend, fast (H2) | `mvn -q test` (the SPA-serving tests use `frontend/dist`: run `pnpm build` in `frontend/` first; CI sets `REQUIRE_FRONTEND_BUILD=true`) |
| Backend, Postgres (Docker, slow) | `mvn verify -Ppostgres` |
| Frontend | `pnpm install && pnpm test` in `frontend/` (also `pnpm lint`, `pnpm build`) |
| API contract | `pnpm install && pnpm test` in `api-contract/` |
| End to end | start the stack as above, then `pnpm install && pnpm exec playwright install chromium && pnpm test` in `e2e/` (details in [`e2e/README.md`](e2e/README.md)) |

## Working with the agents

The app is built by several AI agents from the [project board](https://github.com/users/jonomarx/projects/1). Roles: Backend, Frontend, Job List Manager, and Integration, each owning its own directories and a role branch (`role/backend`, `role/frontend`, `role/jobs`; Integration works on `integration`). Each story is done on a `story/US-###-name` branch and merged into its role branch by pull request. At a merge gate the coordinator merges role branches into `integration`, the Integration agent runs the epic's end-to-end story, and when the epic is complete `integration` is merged to `main`. Agents never merge into `integration` or `main` themselves. Read [`AGENTS.md`](AGENTS.md) for the rules and [`docs/DESIGN.md`](docs/DESIGN.md) for the design.
