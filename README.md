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

**Settings.** Operational settings live in `backend/app/src/main/resources/application.yml`, the only place with defaults; an environment variable overrides one where the file names it: `DB_URL`, `DB_USER`, `DB_PASSWORD`, `REDIS_HOST`, `REDIS_PORT`, `FILE_STORAGE`, `DATA_DIR`, `LLM_PROVIDER` (`fake` by default), `RENDERER_URL`, and `MAX_ORPHAN_KEY_ATTEMPTS` (default 20: how many orphaned storage keys one version number may skip before a save fails). Limits fixed by the API contract (password length, tag name, alias and slug lengths) are code constants, not settings.

### Whole stack in Docker (serves the app on :8080)

This runs everything, including the LaTeX renderer, so the live PDF preview works. The renderer is a container that runs a small Python HTTP wrapper around TeX Live (`renderer/app/server.py`). It sits on an internal-only network with no published port, so the app container must join that network too (a container on `--network host` cannot reach it).

```sh
export DB_PASSWORD=local-dev-pw        # use the same value every time: Postgres keeps the password it was first created with
docker compose up -d --build --wait postgres redis renderer     # the first renderer build is large (TeX Live)
docker compose ps                                              # postgres, redis and renderer should be running
docker build -t resume-editor-app .
P=$(basename "$PWD" | tr '[:upper:]' '[:lower:]')              # compose project name: the directory name
docker rm -f resume-app 2>/dev/null                            # clear any old app container
docker create --name resume-app --network "${P}_default" -p 8080:8080 \
  -v resume-data:/data \
  -e DB_PASSWORD \
  -e DB_URL=jdbc:postgresql://postgres:5432/resume -e REDIS_HOST=redis \
  -e RENDERER_URL=http://renderer:8080 resume-editor-app
docker network connect "${P}_render" resume-app
docker start resume-app
```

Open http://localhost:8080. The first render takes a few seconds while TeX Live compiles. The root `Dockerfile` builds the frontend, bakes it into the Spring jar, and runs it as the non-root user `app` (uid 10001).

Stop and restart without losing your work:

```sh
docker rm -f resume-app && docker compose down     # no -v: that would delete the Postgres data
```

To start completely clean, add `-v` to `docker compose down` and run `docker volume rm resume-data`.

**If the preview says "renderer_unavailable".** The message ("The renderer did not respond in time") is shown for any failure to get a usable answer from the renderer: the container is not running, the app is not on the `render` network, or a render really timed out. Check `docker compose ps` for the `renderer` service and that you ran the `docker network connect` line.

**Data directory.** Resume files and the render cache are written to `DATA_DIR`, which is `/data` in the image: it exists, is owned by the app user, and is declared a `VOLUME`, so the container works with no extra flags. The database only points at those files, so keep the same volume across containers (the `-v resume-data:/data` in the command above); a fresh empty `/data` leaves saved versions that cannot be loaded. A host directory instead of a named volume must be writable by uid 10001 (`chown 10001:10001`). Back up Postgres and `DATA_DIR` together. Outside Docker, `DATA_DIR` defaults to `./data`.

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

Without a frontend build baked into the jar, :8080 serves only the API; use :5173 for the UI. `docker compose down` when done (add `-v` only to delete the data).

**The live preview does not work in the dev loop.** The renderer is only reachable from inside the Docker network, and a jar running on your host has no route to it, so renders fail with `renderer_unavailable`. Use the "Whole stack in Docker" steps above when you want the preview.

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
