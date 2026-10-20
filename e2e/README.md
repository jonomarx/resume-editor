# End-to-end tests

Playwright tests that drive the real stack in a browser. Currently: sign up, log in, log out (US-008) and the master resume editor: edit, add nodes, tag, preview, reload persistence, version restore (US-019).

## Run

Prerequisites: Docker (with Compose), Node 24+, pnpm 12.14.0.

```sh
# 1. Start the stack: Postgres, Redis and the renderer from compose, and the app from the root Dockerfile.
#    The app joins the default network (so the browser reaches :8080 and it reaches postgres/redis) and the
#    internal `render` network (the renderer has no other route). DATA_DIR is a tmpfs owned by the app user
#    (uid 10001); see the note below.
export DB_PASSWORD=e2e-local-pw          # any value; never commit real secrets
docker compose up -d --build --wait postgres redis renderer
docker build -t resume-editor-app:e2e .
P=$(basename "$PWD" | tr '[:upper:]' '[:lower:]')   # compose project name (the directory name)
docker create --name resume-e2e-app --network "${P}_default" -p 8080:8080 \
  --tmpfs /data:rw,uid=10001 -e DATA_DIR=/data -e DB_PASSWORD \
  -e DB_URL=jdbc:postgresql://postgres:5432/resume -e REDIS_HOST=redis \
  -e RENDERER_URL=http://renderer:8080 resume-editor-app:e2e
docker network connect "${P}_render" resume-e2e-app
docker start resume-e2e-app

# 2. Run the tests (the first time, install the browser: it downloads to ~/.cache/ms-playwright).
cd e2e
pnpm install
pnpm exec playwright install chromium
pnpm test                                 # BASE_URL defaults to http://localhost:8080

# 3. Tear down (from the repository root).
cd ..
docker rm -f resume-e2e-app
docker compose down -v
```

Note: the image cannot write to its default data directory as its non-root user, hence the `/data` tmpfs (data is discarded with the container). The first render compiles with TeX Live and takes a few seconds.

Each run uses freshly generated usernames, so reruns against the same database do not collide.
Failure traces and the HTML report go to `test-results/` and `playwright-report/` (git-ignored).
