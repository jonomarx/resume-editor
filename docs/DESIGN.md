# Internship Resume & Job Tracker: Design (v1)

Status: ready for implementation. Last updated 2026-10-18. Companion files: `AGENTS.md` (how agents work) and `FIRST_STEPS.md` (project setup).

## 1. What we are building

A per-user web app where you keep one verbose LaTeX **master resume** (Jake's template), get a daily-refreshed feed of internships ranked by fit, and turn any posting into a one-page tailored **draft** resume.

**Goals (v1):** structured master resume editor with live PDF preview; job pipeline fed by career pages you provide plus an ingest API; tag-based ranking; per-job drafts with manual and AI-assisted cutdown to one page.

**Non-goals (v1):** sharing postings or resumes between users (designed for, not built), auto-applying, load balancing, S3, server-side rendering of the web app.

**Principles**

1. Single user now, multi-user ready: every row carries a `user_id`; postings have `owner_user_id`.
2. Interfaces over implementations: file storage, matcher, LLM provider, and page fetcher are Java interfaces chosen by environment variable.
3. Contract first: `api-contract/openapi.yaml` and the `common` module are the only things roles must agree on.
4. Tests are the gate: a story is Done only when its tests pass and its review is finished.

## 2. Architecture

| Component | Choice | Notes |
| --- | --- | --- |
| Web app | React, Vite, TypeScript, pnpm | Compiled to static files; Vitest for tests |
| API server | Spring Boot (Java 21+), built with Maven | Spring Web, Security, Data JPA, Flyway, Spring Session |
| Database | Postgres | H2 (PostgreSQL mode) for fast local tests |
| Sessions | Redis | Spring Session; HttpOnly, SameSite=Lax cookie |
| File storage | Local disk behind `FileStorage` | `FILE_STORAGE=local`; S3 later is a new implementation |
| LaTeX renderer | TeX Live in its own container | No network, hard timeout, shell escape off |
| Scheduler | Spring `@Scheduled` | Daily validity check then crawl |
| LLM access | `LlmClient` interface | `LLM_PROVIDER` env var; used for tagging, extraction, cutdown |
| Page fetcher | `PageFetcher` interface, jsoup | Optional headless browser (Playwright) later for JavaScript-only pages |

No load balancer. Deployment target: Docker containers on another of your machines.

**How React is served.** Spring Boot serves the compiled Vite build from the same origin (no CORS, simple cookie sessions). Vite is the frontend build tool: a fast dev server with instant reload, and a production bundler that outputs static files. In dev, Vite runs on :5173 and proxies `/api` to Spring on :8080. In prod, a multi-stage Dockerfile builds `frontend/` and copies `dist/` into the jar's `static/`; a controller forwards non-`/api`, non-asset paths to `index.html`; hashed assets are cached long, `index.html` never. No server-side rendering.

**Repository layout**

```
/backend         Maven multi-module build
  common/        shared interfaces and DTOs (changed only via contract stories)
  core/          auth, resumes, render, drafts, tags, matching, job feed
  jobs/          sources, validity checks, crawl, ingest, job tagging
  app/           Spring Boot entry point assembling the modules
/frontend        React app
/renderer        LaTeX render container (Dockerfile + small HTTP wrapper)
/api-contract    openapi.yaml
/e2e             Playwright tests against the running stack
/docs            DESIGN.md, FIRST_STEPS.md, decision records
AGENTS.md        agent guide (root)
```

**Implementations chosen by environment variable.** Application code depends only on the interface.

| Interface | Variable | Now | Later |
| --- | --- | --- | --- |
| `FileStorage` | `FILE_STORAGE` | `local` | `s3` |
| `Matcher` | `MATCHER` | `tags` | `hybrid`, `llm-rerank` |
| `LlmClient` | `LLM_PROVIDER` | one provider (open question 1) | others |

## 3. Data and storage

Postgres holds records and pointers; resume content lives on disk behind `FileStorage`.

| Table | Purpose |
| --- | --- |
| `app_user` | Account (username, password hash) |
| `resume` | Master or draft; `storage_uri`, `content_hash`, `version`, `job_posting_id` for drafts |
| `resume_version` | Snapshot history of the master |
| `tag`, `resume_node_tag`, `job_posting_tag` | Shared tag vocabulary and its links; the resume file stays the source of truth, `resume_node_tag` is a derived index |
| `job_source` | Your career page links |
| `job_posting` | One posting; `owner_user_id`, `origin` (crawl or ingest), `canonical_url`, `url_hash`, `content_hash`, `status` (active, stale, closed), requirements, eligibility fields; unique on (owner_user_id, url_hash) |
| `job_match` | Optional score cache; scores are computed per request |
| `job_draft` | One tailored resume per job (status: in progress, ready, applied) |
| `crawl_run`, `ingest_event` | Audit trail for crawls and the ingest API |

**Resume files:** `${DATA_DIR}/users/{userId}/resumes/{resumeId}/v{n}.json`, immutable and versioned. The DB stores a URI (`file:` now, `s3:` later). `FileStorage`: `put(key, bytes)` returns a URI, `get`, `delete`, `exists`. Back up Postgres and `DATA_DIR` together.

**Sharing later:** make `job_posting.owner_user_id` nullable for shared rows and add a `user_job` link table.

## 4. Master resume editor (frontend part 1)

**Tree model.** Every node: `id`, `type`, `title`, `latex`, `tags`, ordered `children`. Subsections do not nest deeper than one level (Jake's template has nothing deeper).

| Node | Example | Jake template equivalent |
| --- | --- | --- |
| Header | Name and contact line | Centered title block |
| Section | Experience, Education, Misc | `\section{...}` |
| Section text (tagged) | University of Washington 2025-2028 | `\resumeSubheading` with no bullets |
| Subsection | T-Mobile Hiring Manager | `\resumeSubheading` |
| Subsection text (tagged) | What I did on the job | `\resumeItem` bullets |
| Footer | Anything after the last section | Text before `\end{document}` |

**Template status.** Jake's template is a **reference**, not the final template. Our actual template is a derivative of it and will differ (preamble, macros, layout details). The reference copy lives at `docs/reference/jake-resume-template.tex`; use it to understand structure and macro names, but do not hard-code its exact preamble or content. Keep the preamble, macro names, and starter tree in one replaceable place so the derivative template can be swapped in later. US-011 creates the derivative from the reference and records the differences in `docs/decision-002-template.md`; until then the reference is the stand-in. Mark any code that depends on Jake-specific details with a `TEMPLATE` comment.

Header and footer are separate top-level nodes. The document is assembled as: locked preamble from Jake's template, header, sections in order, footer, `\end{document}`. The preamble is visible only in an Advanced view. The assembler writes a comment marker per node so compile errors map back to nodes.

**Layout.** Left: outline tree (add, rename, reorder, delete, collapse). Center: LaTeX editor for the selected node (CodeMirror 6) with snippet buttons, plus the tag editor (pick from the vocabulary, create new, many per node). Right: PDF preview (PDF.js) with a page counter and an error panel.

**New nodes start with readable placeholder content** (valid LaTeX, so the preview renders at once), all strings in `frontend/src/config/constants.ts`:

- Section: "New Section".
- Section text: "University Name, Degree, Aug. 2025 -- May 2028".
- Subsection: "Company Name", "Job Title", "City, ST", "Mon. YYYY -- Mon. YYYY".
- Bullet: "Describe what you did, starting with a strong verb and ending with a result you can measure."

**Rendering.** After a pause in typing (`RENDER_DEBOUNCE_MS`, placeholder 10000, in the same constants file) the frontend calls `POST /api/render` with the assembled document. A "Render now" button skips the wait. The backend returns the PDF, page count, and compile log, cached by content hash. Autosave writes the tree through `FileStorage`; snapshots are taken on demand and before AI rewrites.

**Why the renderer is sandboxed.** LaTeX is a programming language: documents can read and write files, loop forever, and (with shell escape) run commands. Today the input is only yours, but cutdown will put LLM-written text into LaTeX and multi-user is planned. The renderer therefore runs in its own container with no network, shell escape off, restricted file access, a hard timeout, and a size limit, which also protects the JVM. A pure Java library is not an alternative: JLaTeXMath renders formulas only, not full documents with packages like `titlesec` and `hyperref`, and generating PDFs directly (PDFBox, OpenPDF) would drop LaTeX editing and Jake's template.

**Server or browser rendering.** SwiftLaTeX can run pdfTeX in WebAssembly, but it fetches TeX files from CTAN or a mirror on demand. The server is chosen for v1 because the cutdown loop must count pages without a browser, and one TeX Live gives identical output every time. First spike (US-012): confirm Jake's template compiles unmodified in the chosen image (`glyphtounicode`, `marvosym`).

## 5. Job feed and drafts (frontend part 2)

- **Feed:** `GET /api/jobs?sort=fit`. Cards show title, company, location, fit score, basic and preferred requirements, brief description, tags. Filters: tags (multi-select from the vocabulary), location, status (new, saved, dismissed, applied), freshness.
- **Click:** a posting with a draft opens `/drafts/:id`; otherwise `POST /api/jobs/{id}/drafts` forks the master and returns the draft.
- **Draft page:** job details left; shared `ResumeEditor` right, with per-node include toggles and a page meter. Export PDF or `.tex`. A draft never pulls changes from the master after creation.
- **Cutdown to one page:** (1) score nodes against the posting; (2) select the highest-scoring nodes, looping on the renderer until exactly one page (deterministic, cannot invent facts); (3) optional LLM tighten mode that may reword but never adds claims; (4) AI changes arrive as per-node suggestions with accept or reject, after a snapshot. Ship step 2 first.

## 6. Tags and matching (backend part 1)

**Tags.** Master resume nodes can be tagged (many per node). Tags come from one shared, user-extensible vocabulary: canonical name, slug, category (language, framework, domain, role type), aliases. Aliases (for example "reactjs" for React) are managed through the API: add and remove an alias on a tag, unique per user and never colliding with another tag's slug or alias. The v0 contract has no alias endpoint, so US-020 raises a `contract` story for it first. Free text is never stored as a tag; typing a new one creates a vocabulary entry first. The Job List Manager tags postings from the same list and may *propose* new entries, which wait for your approval. A story generates your starter list from your master resume for you to approve.

**Matching** is done by the web controller on each request, not by the Job List Manager. Order of build:

1. Tag overlap (weighted, rare tags count more) plus Postgres full-text and hard filters (season, location, authorization, class year), behind `Matcher`.
2. Embeddings plus nearest-neighbor search and requirement coverage (LLM extracts requirements, each matched to the best resume nodes).
3. LLM rerank of the top results with explanations.

**Is KNN viable?** Computationally yes, trivially: with dozens of resume nodes and at most thousands of postings, exact brute-force search takes milliseconds and needs no vector index. Embedding vectors have hundreds to a few thousand dimensions, but this is retrieval with pretrained embeddings, not training a model on dozens of examples, so the few-points-high-dimensions problem mostly does not apply; it would apply to using KNN as a classifier trained on a few labeled examples, which we do not do. Tag vectors have as many dimensions as the vocabulary (dozens to hundreds), and nearest-neighbor on them reduces to tag overlap, so KNN adds value only once text embeddings add semantic reach (stage 2).

## 7. Job pipeline (backend part 2, Job List Manager)

The Job List Manager only **adds, culls, receives, and tags** postings. It does not match.

**Sources.** Company career page links you provide. Pasted or forwarded postings (Handshake, LinkedIn emails, and so on) arrive once through the ingest API; they are never re-crawled for new content but are validity-checked daily.

**Extraction tiers (cheapest first)**

| Tier | Method |
| --- | --- |
| 0 | Public ATS feeds. Greenhouse's docs, as quoted by third-party tools, say its Job Board GET endpoints need no authentication; Lever and Ashby have similar public feeds (verify each before building) |
| 1 | `schema.org/JobPosting` JSON-LD and embedded framework data |
| 2 | Later: LLM-generated extraction recipe (a data file of CSS selectors validated on a saved sample and run by our own jsoup code), not generated code |
| 3 | LLM direct extraction of changed pages into the posting schema (default fallback) |

Recipes, not scripts: running LLM-written Python means executing untrusted code. At tens of pages, tiers 0, 1, and 3 with an unchanged-page skip are cheaper to build and survive redesigns better; build tier 2 only if cost or latency demands it. LLM output is schema-validated with sanity checks. Posting text is untrusted data, never instructions; the LLM has no tools during extraction.

**Daily run.** (1) Validity first: re-check every existing posting (crawled or ingested); close on 404, 410, or "no longer accepting applications". (2) Create a `crawl_run` per source. (3) Fetch and hash; stop if unchanged. (4) Extract with the source's tier. (5) Normalize and dedupe (strip tracking parameters, hash URL, company plus title plus location as a secondary key). (6) Add new, update changed, then tag and extract requirements and eligibility. (7) Cull: missing from the listing for two runs becomes `stale`, then `closed`; closed postings are hidden and removed after a retention period unless a draft references them. (8) Record stats for a source health view. Crawling respects `robots.txt`, rate-limits per domain, and sends an identifying user agent.

**Ingest API.** `POST /api/ingest` with `{kind: url, html, text, or email; payload; sourceHint}`, per-user revocable ingest-only token. Responds `202` with an `ingest_event` id, processes asynchronously, idempotent on URL or content hash. First inputs: a paste box and a bookmarklet; email forwarding later.

## 8. Concurrency, testing, conventions

**Concurrency is designed in, not automatic.**

| Resource | Rule |
| --- | --- |
| Resume files | Immutable versioned files, temp file then atomic rename, one writer per resume via a per-resume lock |
| Resume rows | Optimistic locking (`version`); a stale save returns 409 and the UI offers a reload |
| File versus row | Write file first, then commit the row; never delete before commit; a reconciliation job removes orphans |
| Render | Idempotent on content hash; atomic cache writes; stateless renderer |
| Daily run and ingest | Lock per source; unique (owner_user_id, url_hash) as final guard |
| Tags | Unique (user_id, slug); get-or-create |
| Sessions | Spring Session on Redis; no custom session state |
| Board cards | Assign, then re-read |

**Testing.** Fast loop (your machine, CI on every push): unit tests on H2 in PostgreSQL mode, Vitest for the frontend. Slow loop (when a feature is nearly complete and at each merge gate): Postgres tests via Testcontainers in Docker (`mvn verify -Ppostgres`). Anything using Postgres-only features (full-text, JSON columns, `SKIP LOCKED`, pgvector) and all concurrency tests are tagged `@Tag("postgres")`. End-to-end: Playwright in `/e2e`, run on `integration` only.

## 9. Roles, branches, merges

Roles: **Backend** (`role/backend`: `common`, `core`, `app`, `renderer`, `api-contract`), **Frontend** (`role/frontend`: `frontend`), **Job List Manager** (`role/jobs`: `jobs`), **Integration** (stories on `integration`: `e2e`), and **you** (backlog, spot checks, contract approval, merge approval).

Branches: `main` (always deployable) receives only from `integration` after the end-to-end suite passes; `integration` receives finished features from role branches after your review; role branches are long-lived; `story/US-###-short-name` branches are short-lived and merge into their role branch when tests pass and review is done. Role branches pull from `integration` at least daily. Shared files change only through `contract` stories. Migrations use timestamp Flyway versions.

Merge gate (role branch to `integration`) runs in two stages. Feature gate: all non-Integration stories of the epic Done; unit tests pass; each role branch current with `integration`; you review and merge the pull requests; dummy code for pieces now merged is swapped out. Then the Integration agent runs the epic's E2E story against `integration`; its pull request goes into `integration` and you merge it. The epic is complete when that story is Done, and you then merge `integration` to `main`. Gate checks: unit tests pass; branch current with `integration`; you review the pull request. After the merge the e2e suite runs; red creates a bug story for the owning role.

**Agents never edit another role's code.** If an interface they need is not ready, they code against dummy data; see `AGENTS.md` for the rule and for review, claiming, and Definition of Done. **Board:** GitHub Issues with a Projects board; columns BACKLOG, TODO, DOING, TO-REVIEW, DONE, ARCHIVE; whole epic moves BACKLOG to TODO when picked, DONE to ARCHIVE when merged.

## 10. Decisions and open questions

**Decided:** Spring serves the Vite build, no SSR; server-side LaTeX; GitHub Projects; Maven multi-module; pnpm; username and password sign-in (other methods later); H2 then Postgres in Docker; interfaces by environment variable; Job List Manager does not match; matching by tags in the controller; career pages you provide; drafts never pull from master; no nesting below one subsection level; contract stories get deep review by another agent plus your approval.

**Open**

1. Which LLM provider and monthly budget, and which embeddings provider if we reach US-055?
2. Which career pages go in first? (Needed before Epic 2 is useful.)

## Appendix A: stories

Roles: B Backend, F Frontend, J Job List Manager, I Integration, H you. **Depends on**: prerequisites. Those in the story's own role must be Done (reviewed, merged to the role branch); a `contract` prerequisite must be merged into `integration` (the owner merges contract stories as soon as they are approved). A prerequisite owned by another role never blocks: the story builds against a dummy implementation until the real one is ready, and only the first two kinds become blocked-by relations on the board. **Dummy until ready**: other roles' pieces the story needs; use dummy data until the real implementation is ready (see `AGENTS.md`). `review:deep` applies to US-003, 006, 013, 014, 028, 037, 041, 060, 064 and the last story before each integration story; all others get a light review.

### Epic 0: Foundations (merge M0)

| ID | Role | Story | Depends on | Dummy until ready |
| --- | --- | --- | --- | --- |
| US-000 | H | Repo, branches, labels, board, and branch protection per `FIRST_STEPS.md` | none | none |
| US-001 | B | Maven skeleton (`common`, `core`, `jobs`, `app`), Postgres, Redis, Flyway baseline, health endpoint, Docker Compose | none | none |
| US-002 | F | React, Vite, TypeScript, pnpm skeleton: routing, lint, Vitest, dev proxy, constants file | none | none |
| US-003 | B | OpenAPI v0: auth, master resume, tags, render; plus mock server script | none | none |
| US-004 | B | CI (H2 unit tests per push, Postgres profile on demand, frontend job that runs once `frontend/` exists), board helper scripts (move epic to TODO, archive epic), list of `DUMMY` markers on pull requests | US-001 | Frontend skeleton (US-002) |
| US-005 | B | Spring serves the Vite build with SPA fallback; multi-stage Dockerfile | US-001 | Vite build output (US-002): a placeholder `index.html` fixture for tests |
| US-006 | B | Username and password sign-up, login, logout; Spring Session on Redis; `GET /api/me` | US-001, US-003 | none |
| US-007 | F | Login page, route guard, typed API client from OpenAPI | US-002, US-003 | Auth endpoints (US-006) |
| US-008 | I | E2E: sign up, log in, log out | US-005, US-006, US-007 | none |
| US-009 | B | Test setup: H2 profile, `postgres` Maven profile with Testcontainers, `@Tag("postgres")` convention | US-001 | none |

### Epic 1: Master resume (merge M1)

| ID | Role | Story | Depends on | Dummy until ready |
| --- | --- | --- | --- | --- |
| US-010 | B | `FileStorage` plus local-disk implementation; immutable versions, atomic writes, per-resume lock, concurrency tests | US-001 | none |
| US-011 | B | Resume tree model with tags, JSON schema, tree-to-LaTeX assembler with node markers, and creation of our derivative template from the Jake reference (one replaceable resource plus a decision record) | US-001 | none |
| US-012 | B | Renderer container (TeX Live, no network, timeout) and spike proving Jake's template compiles | none | none |
| US-013 | B | `POST /api/render` with cache, page count, error-to-node mapping | US-006, US-011, US-012 | none |
| US-014 | B | Master resume read and write with version check (409), versions, restore; Jake-template starter tree | US-006, US-010, US-011 | none |
| US-015 | F | Outline tree component | US-002, US-003 | Master resume endpoints (US-014) |
| US-016 | F | Node LaTeX editor (CodeMirror) with snippet buttons | US-015 | none |
| US-017 | F | PDF preview, debounced render (`RENDER_DEBOUNCE_MS`), "Render now" button, error panel, page counter | US-016, US-003 | Render endpoint (US-013) |
| US-018 | F | Autosave, 409 conflict handling, version history UI | US-016, US-003 | Master resume endpoints (US-014) |
| US-020 | B | Tag vocabulary: table, normalization, aliases (add and remove, via a contract story for the endpoints), list and create endpoints | US-006, US-003 | none |
| US-021 | B | Tags on resume nodes: save, read, derived `resume_node_tag` index | US-014, US-020 | none |
| US-022 | F | Tag editor on nodes | US-016, US-003 | Tag endpoints (US-020, US-021) |
| US-023 | F | Placeholder content when a node is created | US-015 | none |
| US-019 | I | E2E: edit, tag, preview, reload keeps changes, restore a version | US-013, US-014, US-017, US-018, US-021, US-022 | none |

### Epic 2: Job pipeline (merge M2)

| ID | Role | Story | Depends on | Dummy until ready |
| --- | --- | --- | --- | --- |
| US-028 | B | OpenAPI v1: sources, ingest, jobs, drafts, cutdown | US-003 | none |
| US-030 | J | Migrations: sources, postings, posting tags, crawl runs, ingest events | US-001 | none |
| US-031 | J | Posting normalizer and deduper | US-030 | none |
| US-032 | J | Source CRUD API | US-028, US-030 | Auth (US-006) |
| US-033 | J | `PageFetcher`, jsoup fetcher, JSON-LD adapter with HTML fixtures | US-031 | none |
| US-034 | J | ATS feed adapters (Greenhouse, Lever, Ashby; verify endpoints first) | US-033 | none |
| US-037 | B | `LlmClient` in `common`, `LLM_PROVIDER`, schema-validated output | US-001 | none |
| US-038 | J | LLM direct extraction (tier 3) with untrusted-content handling | US-033 | `LlmClient` (US-037) |
| US-040 | J | Tagging and enrichment: tags from vocabulary (new ones proposed), requirements, eligibility fields | US-031 | Tag vocabulary (US-020), `LlmClient` (US-037) |
| US-042 | J | Daily validity checker for all existing postings | US-031, US-033 | none |
| US-035 | J | Daily run: validity first, then crawl; `crawl_run`; add, update, stale, close; per-source lock | US-032, US-033, US-042 | none |
| US-041 | J | Ingest API, per-user tokens, async processing, one-time postings | US-028, US-031, US-038 | Auth (US-006) |
| US-043 | J | Source health endpoint | US-035 | none |
| US-044 | F | Sources page | US-007, US-028 | Source endpoints (US-032, US-043) |
| US-045 | F | Paste box and bookmarklet generator | US-007, US-028 | Ingest endpoint (US-041) |
| US-046 | I | E2E: add source (local fixture site) then see postings; paste a posting and see it | US-035, US-041, US-044, US-045 | none |
| US-036 | J | Later: headless-browser fetcher (Playwright) | US-033 | none |
| US-039 | J | Later: LLM-generated extraction recipes (tier 2) | US-033, US-038 | `LlmClient` (US-037) |

### Epic 3: Feed and matching (merge M3)

| ID | Role | Story | Depends on | Dummy until ready |
| --- | --- | --- | --- | --- |
| US-050 | B | Jobs list, detail, status endpoints with filters, reading postings through an interface in `common` | US-006, US-028 | Posting data and tags (US-030, US-040) |
| US-051 | B | `Matcher` (`MATCHER`): tag overlap, full-text, hard filters, per-request scoring | US-021, US-050 | none |
| US-052 | B | Starter tag list: LLM proposes tags and categories from your master resume; you approve or edit | US-020, US-021 | `LlmClient` (US-037) |
| US-053 | F | Job feed page with tag multi-select and other filters, sort by fit, save and dismiss | US-007, US-028 | Jobs endpoints (US-050) |
| US-054 | I | E2E: seeded master and postings produce a ranked feed | US-050, US-051, US-053 | none |
| US-055 | B | Later: embeddings with pgvector and requirement coverage | US-051 | none |
| US-056 | B | Later: LLM rerank with explanations | US-037, US-055 | none |

### Epic 4: Drafts and cutdown (merge M4)

| ID | Role | Story | Depends on | Dummy until ready |
| --- | --- | --- | --- | --- |
| US-060 | B | Draft create (fork), read, write, export PDF or `.tex` | US-013, US-014, US-050 | none |
| US-061 | F | Shared `ResumeEditor` with master and draft modes and include toggles | US-017, US-018 | none |
| US-062 | F | Draft page: job details left, editor and page meter right | US-028, US-053, US-061 | Draft endpoints (US-060) |
| US-064 | B | Deterministic cutdown: score, select, loop on render to exactly one page | US-051, US-060 | none |
| US-065 | F | Cutdown UI: run, review per-node changes, accept or reject, undo | US-028, US-062 | Cutdown endpoint (US-064) |
| US-066 | B | LLM tighten mode with a no-new-claims check | US-037, US-064 | none |
| US-067 | I | E2E: click a job, get a draft, cut down, export a one-page PDF | US-060, US-062, US-064, US-065 | none |

### Epic 5: Hardening (after M4)

| ID | Role | Story | Depends on | Dummy until ready |
| --- | --- | --- | --- | --- |
| US-070 | B | Backups for Postgres and `DATA_DIR`; orphan reconciliation | US-014, US-060 | none |
| US-071 | B | Rate and size limits on render and ingest | US-013 | Ingest endpoint (US-041) |
| US-072 | B | Structured logs and metrics for render and LLM usage | US-013 | Crawl records (US-035) |
| US-073 | F | Loading, empty, and error states everywhere | US-067 | none |
| US-074 | I | Concurrency tests on Postgres: simultaneous saves, crawl versus ingest, claim locks | US-014, US-035, US-041 | none |
| US-075 | I | Dummy-data sweep: find any remaining `DUMMY` marker and open a swap card for its owner | US-067 | none |

**Suggested build order:** M0, M1, paste ingest plus feed (M3), drafts with manual cutdown, deterministic cutdown, then the daily run over your career pages.
