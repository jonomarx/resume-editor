# Agent guide

Read this whole file at the start of every session, then the parts of `docs/DESIGN.md` your story touches. You are one of several AI agents building this app from a GitHub Projects board. The owner (a human) manages the backlog, approves contracts, and spot-checks merges. Your job: take one story at a time, deliver it with passing tests on your role's branch, get it reviewed, and move to the next.

The project board is https://github.com/users/jonomarx/projects/1 (project number 1, owner `jonomarx`).

## 1. Know your role

The owner tells you your role when starting the session. If unclear, ask once.

| Role | Label | Branch | You own | Commands |
| --- | --- | --- | --- | --- |
| Backend | `role:backend` | `role/backend` | `backend/common`, `backend/core`, `backend/app`, `renderer`, `api-contract` | `mvn -q test` (H2, fast); `mvn verify -Ppostgres` (Docker, slow) |
| Frontend | `role:frontend` | `role/frontend` | `frontend` | `pnpm install`, `pnpm dev`, `pnpm test`, `pnpm build` |
| Job List Manager | `role:jobs` | `role/jobs` | `backend/jobs` | same Maven commands, scoped with `-pl backend/jobs -am` |
| Integration | `role:integration` | `integration` | `e2e` | `pnpm --dir e2e test` against the running stack. Integration stories start only after the epic's feature gate (DESIGN section 9); open pull requests into `integration` for the owner to merge. |

The Job List Manager only adds, culls, receives, and tags postings. It never does matching.

## 2. The work loop

1. **Sync.** `git fetch`, then merge `integration` into your role branch.
2. **Pick.** Take any card in TODO with your role label that is not blocked (GitHub shows blocked-by relations). A card is blocked only by unfinished same-role prerequisites and unmerged contract stories; another role's unfinished work never blocks you (section 4). Order does not matter. A card already in DOING is claimed; never take it. Never take a card for another role.
3. **Claim.** Assign yourself (`gh issue edit <n> --add-assignee @me`), move the card to DOING, then re-read it. If the card was already in DOING or someone else was assigned first, back off and pick another. One card in DOING at a time.
4. **Branch.** `git switch -c story/US-###-short-name <your role branch>`.
5. **Build** the smallest change that meets the acceptance criteria. Prefix commit messages with `US-###:`. Do not add features the card does not ask for.
6. **Test.** Write the tests the story needs (where applicable) and make them pass. Run the fast loop constantly. Run the Postgres profile when the story touches database-specific behavior, locking, or when you finish a feature.
7. **Hand off.** Open a pull request into your role branch, move the card to TO-REVIEW, and comment with what changed, the test results, and anything surprising.
8. **Review.** Small stories (`review:light`): review your own diff against the checklist in section 6 and post the result on the card. Consequential stories (`review:deep`): stop and request a review by another agent (comment "review requested"); do not approve your own deep review. Contract stories also need the owner's approval.
9. **Finish.** After approval, merge the pull request into your role branch, move the card to DONE, close the issue (`gh issue close <n> -c "<one-line summary>"`; GitHub only auto-closes issues for merges into the default branch, so this is never automatic), and leave a short handoff note. Then go back to step 1.

If you are blocked, comment on the card with the exact reason and take another card. If a requirement is ambiguous, ask the owner on the card; do not guess on anything that changes an API, a data model, or user-visible behavior.

## 3. Boundaries

- Never edit another role's directories. If you need a change there, comment on the owner role's card or open a new card.
- Never edit `api-contract/openapi.yaml` or `backend/common` except in a story labeled `contract`. If you need an API change, open a `contract` issue describing it and wait.
- Never merge into `integration` or `main`. The owner does merges at the merge gates.
- Each module reaches the others only through interfaces in `backend/common`.

## 4. Dummy data rule

If a piece you need from another role is not ready, you write a temporary dummy implementation of it (for an API, a stand-in that returns plausible data and follows the contract) behind the interface, and keep going. You never wait on another role's non-contract work.

- **What blocks you:** only (a) prerequisites in your own role that are not DONE, and (b) a `contract` story (such as the OpenAPI spec) that is not yet merged into `integration`. The owner merges a contract story into `integration` as soon as it is approved, without waiting for the epic's merge gate, so other roles can pull it. Everything else listed under "Depends on" for another role is treated as "dummy until ready".
- A piece is **ready** when its implementing story is DONE, its tests pass, it is feature-complete for its contract, and it is merged into `integration` so you can pull it. Until then, **always** use dummy data.
- Mark dummy code with a `DUMMY(US-###)` comment (the story whose output it stands in for). Java beans go under `@Profile("dummy")`; the frontend's dummy API (an in-memory implementation of the typed API client, or the OpenAPI mock server once the spec is merged) lives in `frontend/src/dummy/`. Backend stand-ins for frontend output (for example a placeholder `index.html` build for the static-serving story) are also marked `DUMMY` and kept out of the real profile.
- When you write dummy code, open a "swap" card (label `dummy-swap`) for yourself in the same step.
- When the real piece is ready, switch to it and **delete** the dummy data. CI lists remaining `DUMMY` markers on every pull request; none may remain for pieces that are ready.

## 5. Conventions you must follow

- **Implementations by environment variable.** Never hard-code a concrete implementation. Use `FILE_STORAGE`, `MATCHER`, `LLM_PROVIDER`; application code depends on the interface only.
- **Constants.** Frontend tunables (`RENDER_DEBOUNCE_MS`, placeholder text) live in `frontend/src/config/constants.ts`, never inline.
- **Concurrency.** Follow section 8 of `docs/DESIGN.md`: immutable versioned files with atomic rename, per-resume lock, optimistic locking with 409, unique constraints, per-source locks. Any code that reads or writes files, rows, or caches must follow these rules and have a test.
- **Security.** No secrets in the repo. Posting text and LLM output are untrusted: validate against schemas, never execute them, never give the LLM tools during extraction. LaTeX is rendered only in the renderer container with shell escape off.
- **Migrations.** Flyway files with timestamp versions, never edited after merge.
- **Tests.** Anything needing Postgres-only features, and all concurrency tests, carry `@Tag("postgres")`.
- **Tags.** Always use the shared vocabulary; never store free-text tags.
- **Resume template.** `docs/reference/jake-resume-template.tex` is a reference only; our real template is a derivative and will differ. Do not hard-code Jake-specific preamble, macros, or sample content; keep them in one replaceable place and mark Jake-specific assumptions with a `TEMPLATE` comment (DESIGN section 4).
- **Git identity.** All commits are authored as `jonomarx <8230342+jonomarx@users.noreply.github.com>`. The repo-local git config (shared by every worktree) already sets this; never change it, never set a different `user.name` or `user.email`, and never use `--author` or a personal email. Before your first commit in a session, check `git config user.email`. Commit messages still end with the Co-Authored-By trailer your tooling requires.

## 6. Review checklist (author self-review and reviewing another agent)

- Every acceptance criterion is met, and nothing extra was added.
- Tests exist, are meaningful (they would fail if the feature were broken), and pass.
- No edits outside the author's role area; no contract change outside a `contract` story.
- No leftover dummy data for pieces that are ready; new dummy code is marked and has a swap card.
- Concurrency and security rules in section 5 are respected.
- API behavior matches `openapi.yaml`.
- Code is readable, and nothing is committed that should not be (secrets, build output).

When you are the reviewer: do not edit the author's code. Post `APPROVED` or `CHANGES REQUESTED` on the card with specific, actionable points; on changes requested, move the card back to DOING. A deep review means reading the full diff and running the tests yourself.

## 7. Definition of Done

Code merged into the role branch; required tests written and passing; contract respected; no stale dummy data; review finished (light or deep); card in DONE with links to the pull request and test results.

## 8. Do not

- Skip tests to move faster, or mark a card DONE with failing tests.
- Take more than one card at a time.
- Rewrite history on shared branches, or force-push.
- Change the design. If the design seems wrong, comment on the card and let the owner decide.
- Install tools or add dependencies the design does not name without recording the reason on the card.
