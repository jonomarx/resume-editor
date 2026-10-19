# Backend tests

Two tiers, chosen by a JUnit tag.

| Command | Runs | Database |
| --- | --- | --- |
| `mvn -q test` | every test except `@Tag("postgres")` (fast loop, CI on every push) | H2 in PostgreSQL mode (`@ActiveProfiles("test")`) |
| `mvn verify -Ppostgres` | the fast loop, then only `@Tag("postgres")` tests (Failsafe) | real Postgres 16 via Testcontainers (needs Docker) |

Convention
- Anything using Postgres-only features (full-text, JSON columns, `SKIP LOCKED`, pgvector) and every
  concurrency test is tagged `@Tag("postgres")`.
- In `backend/app` tests, annotate the class with `@PostgresTest` (adds the tag, `@Testcontainers`,
  `@SpringBootTest`, profile `postgres-test`) and extend `PostgresContainerSupport` (shared container,
  wired to the datasource through `@ServiceConnection`). See `PostgresProfileSampleTests`.
- Name test classes `*Tests` or `*Test` so both Surefire and Failsafe find them.
- Fast tests use `@ActiveProfiles("test")` (see `H2ProfileSampleTests`).
- Scope to one module with `-pl backend/<module> -am`.
- `testcontainers.version` is pinned in the root `pom.xml` because the version in the Spring Boot BOM cannot
  talk to Docker 29 or newer.
