# End-to-end tests

Playwright tests that drive the real stack in a browser. Currently: sign up, log in, log out (US-008).

## Run

Prerequisites: Docker (with Compose), Node 24+, pnpm 12.14.0.

```sh
# 1. Start Postgres and Redis, and the app (the root Dockerfile builds the SPA and serves it from Spring on :8080).
export DB_PASSWORD=e2e-local-pw          # any value; never commit real secrets
docker compose up -d --wait postgres redis
docker build -t resume-editor-app:e2e .
docker run -d --name resume-e2e-app --network host -e DB_PASSWORD \
  -e DB_URL=jdbc:postgresql://localhost:5432/resume resume-editor-app:e2e

# 2. Run the tests (the first time, install the browser: it downloads to ~/.cache/ms-playwright).
cd e2e
pnpm install
pnpm exec playwright install chromium
pnpm test                                 # BASE_URL defaults to http://localhost:8080

# 3. Tear down.
docker rm -f resume-e2e-app
docker compose down -v
```

Each run uses freshly generated usernames, so reruns against the same database do not collide.
Failure traces and the HTML report go to `test-results/` and `playwright-report/` (git-ignored).
