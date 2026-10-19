# Agent guide

Read this whole file at the start of every session, then the parts of `docs/DESIGN.md` your story touches. You are one of several AI agents building this app from a GitHub Projects board. The owner (a human) manages the backlog, approves contracts, and spot-checks merges. A coordinator session (section 1) acts for the owner. Your job: take one story at a time, deliver it with passing tests on your role's branch, get it reviewed, and move to the next.

The project board is https://github.com/users/jonomarx/projects/1 (project number 1, owner `jonomarx`).

## 1. Know your role

The owner or the coordinator tells you your role when starting the session. If unclear, ask once.

| Role | Label | Branch | You own | Commands |
| --- | --- | --- | --- | --- |
| Backend | `role:backend` | `role/backend` | `backend/common`, `backend/core`, `backend/app`, `renderer`, `api-contract`, `.github`, root build files | `mvn -q test` (H2, fast); `mvn verify -Ppostgres` (Docker, slow) |
| Frontend | `role:frontend` | `role/frontend` | `frontend` | `pnpm install`, `pnpm dev`, `pnpm test`, `pnpm build` |
| Job List Manager | `role:jobs` | `role/jobs` | `backend/jobs` | same Maven commands, scoped with `-pl backend/jobs -am` |
| Integration | `role:integration` | `integration` | `e2e` | `pnpm --dir e2e test` against the running stack. See the integration step in section 2. |

The Job List Manager only adds, culls, receives, and tags postings. It never does matching.

**Your worktree.** Work only in the worktree you were assigned (for example `../app-backend`). Never switch branches or edit files in another agent's worktree or in the coordinator's checkout.

### The coordinator

The coordinator is the owner's own Claude Code session, working in the main checkout on `main`. It is not a story role: it has no role label, no role branch, and takes no story cards. It:

- starts, resumes, and instructs the agents, and decides which agent works which card;
- starts independent reviewers when a deep review is pending (section 6);
- opens the pull requests for a merge gate, checks their CI, and merges into `integration` and `main` **only when the owner says so**; approved contract stories are merged into `integration` promptly once the owner approves them;
- moves epic cards (a whole epic BACKLOG to TODO, an epic to ARCHIVE) **only when the owner says so**, and sets Status and Epic on cards it creates;
- creates cards that no agent should create: bug cards after a gate, contract follow-ups, cross-role cards;
- edits `docs/DESIGN.md` and this file only as directed by the owner. Changing the design is a decision between the coordinator and the owner.

Agents take instructions from the coordinator the way they would from the owner, except that the coordinator cannot grant permissions the owner has denied. If you disagree with an instruction, comment on the card.

## 2. The work loop

1. **Sync.** `git fetch`, fast-forward your role branch to `origin/<your role branch>`, then merge `origin/integration` into it.
2. **Check for pending reviews** (section 6) before picking new work. If a card of another role is waiting for a review that nobody has claimed, review it first.
3. **Pick.** Take any card in TODO with your role label that is not blocked (GitHub shows blocked-by relations). A card is blocked only by unfinished same-role prerequisites and unmerged contract stories; another role's unfinished work never blocks you (section 4). Order does not matter. A card already in DOING is claimed; never take it. Never take a card for another role.
4. **Claim.** Move the card to DOING (commands below), then re-read it. If it was already in DOING, back off and pick another. The DOING column is the claim: all agents share one GitHub account, so assignees mean nothing. One card in DOING at a time.
5. **Branch.** `git switch -c story/US-###-short-name <your role branch>`.
6. **Build** the smallest change that meets the acceptance criteria. Prefix commit messages with `US-###:`. Do not add features the card does not ask for.
7. **Test.** Write the tests the story needs (where applicable) and make them pass. Run the fast loop constantly. Run the Postgres profile when the story touches database-specific behavior, locking, or when you finish a feature.
8. **Hand off.** Open a pull request into your role branch, move the card to TO-REVIEW, and comment with what changed, the test results, and anything surprising.
9. **Review.** Small stories (`review:light`): review your own diff against the checklist in section 6 and post the result on the card. Consequential stories (`review:deep`): comment "review requested" and stop work on that card; another agent reviews it (section 6). Do not approve your own deep review. Contract stories also need the owner's approval.
10. **Finish.** After approval, merge the pull request into your role branch with a merge commit (`gh pr merge --merge`), move the card to DONE, close the issue (`gh issue close <n> -c "<one-line summary>"`; GitHub only auto-closes issues for merges into the default branch, so this is never automatic), and leave a short handoff note. Then go back to step 1.

**The integration step.** The Integration agent's end-to-end story for an epic is its own step. It starts only after the epic's feature gate: every other story in the epic is DONE and the coordinator has merged the role branches into `integration` on the owner's word. The Integration agent works in its own worktree (the coordinator creates `../app-integration`), branches `story/US-###-short-name` off `integration`, tests against the running stack, and opens its pull request **into `integration`**. It does not merge it: the coordinator merges when the owner says so, and then the card moves to DONE. The Integration agent never merges into `main`.

If you are blocked, comment on the card with the exact reason and take another card. If a requirement is ambiguous, ask the owner on the card; do not guess on anything that changes an API, a data model, or user-visible behavior.

### Card commands

```
# Set Status: BACKLOG, TODO, DOING, TO-REVIEW, DONE, ARCHIVE
gh project item-edit 1 --owner jonomarx --url <issue url> --field Status --value DOING
# Set Epic (0 to 5)
gh project item-edit 1 --owner jonomarx --url <issue url> --field Epic --value 0
# Put a new issue on the board
gh project item-add 1 --owner jonomarx --url <issue url>
```

Every card you create (swap cards, bug cards) must be added to the board with a Status and an Epic set in the same step. You move only your own card through DOING, TO-REVIEW, and DONE. Epic-level moves belong to the coordinator: do not run `scripts/epic-to-todo.sh` or `scripts/archive-epic.sh`.

## 3. Boundaries

- Never edit another role's directories. If you need a change there, comment on the owner role's card or open a bug card (section 9).
- Never edit `api-contract/openapi.yaml` or `backend/common` except in a story labeled `contract`. If you need an API change, open a `contract` issue describing it and wait. Any story that changes the contract or `backend/common` must carry the `contract` label; the coordinator checks this whenever cards are created.
- Never merge into `integration` or `main`. The coordinator does the merges at the merge gates, when the owner says so.
- Each module reaches the others only through interfaces in `backend/common`.
- **Docs.** Any role may add or edit files under `docs/` for its own area, such as `docs/decision-NNN-*.md` records. Never edit `docs/DESIGN.md` or `AGENTS.md`: comment on the card and let the coordinator and owner decide.

## 4. Dummy data rule

If a piece you need from another role is not ready, you write a temporary dummy implementation of it (for an API, a stand-in that returns plausible data and follows the contract) behind the interface, and keep going. You never wait on another role's non-contract work.

- **What blocks you:** only (a) prerequisites in your own role that are not DONE, and (b) a `contract` story (such as the OpenAPI spec) that is not yet merged into `integration`. The owner merges a contract story into `integration` as soon as it is approved, without waiting for the epic's merge gate, so other roles can pull it. Everything else listed under "Depends on" for another role is treated as "dummy until ready".
- A piece is **ready** when its implementing story is DONE, its tests pass, it is feature-complete for its contract, and it is merged into `integration` so you can pull it. Until then, **always** use dummy data.
- Mark dummy code with a `DUMMY(US-###)` comment (the story whose output it stands in for). Java beans go under `@Profile("dummy")`; the frontend's dummy API (an in-memory implementation of the typed API client, or the OpenAPI mock server once the spec is merged) lives in `frontend/src/dummy/`. Backend stand-ins for frontend output (for example a placeholder `index.html` build for the static-serving story) are also marked `DUMMY` and kept out of the real profile.
- When you write dummy code, open a "swap" card (label `dummy-swap`) for yourself in the same step, with Status BACKLOG until the real piece reaches `integration`, and an Epic.
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
- **Merging.** Merge pull requests with merge commits (`gh pr merge --merge`). Never squash, rebase, rewrite history, or force-push shared branches.
- **System packages.** Never install system packages (pacman, apt, global tools) or work around a denied permission. If a tool is missing, comment on the card and stop; the owner installs it. Project dependencies the design does not name still need their reason recorded on the card.

## 6. Reviews

### Checklist (author self-review and reviewing another agent)

- Every acceptance criterion is met, and nothing extra was added.
- Tests exist, are meaningful (they would fail if the feature were broken), and pass.
- No edits outside the author's role area; no contract change outside a `contract` story.
- No leftover dummy data for pieces that are ready; new dummy code is marked and has a swap card.
- Concurrency and security rules in section 5 are respected.
- API behavior matches `openapi.yaml`.
- Code is readable, and nothing is committed that should not be (secrets, build output).

### Reviewer procedure

When you are the reviewer: do not edit the author's code, and do not merge. A deep review means reading the full diff and running the tests yourself.

1. Claim the review by commenting "Reviewing" on the card, so two reviewers do not duplicate work.
2. Make your own checkout so nobody's worktree is disturbed: `git worktree add --detach ../review-<card> origin/<story branch>`. Remove it when you are done.
3. Read the card, the pull request, and the full diff. Run the tests (`mvn -q test`, and `mvn verify -Ppostgres` when the story touches the database; `pnpm test`, `pnpm lint`, `pnpm build` for the frontend). Check that the tests would fail if the feature were broken.
4. Post the verdict as a comment on the card: `APPROVED` or `CHANGES REQUESTED` on its own line, then specific, actionable points, separating blocking from non-blocking. Only call something blocking if it breaks a consumer, the contract, a security property, the acceptance criteria, or these rules. On changes requested, the author moves the card back to DOING.
5. Contract stories also need the owner's approval after your `APPROVED`; the coordinator brings it to the owner.

### Pending reviews

Reviewers come from two places: the coordinator starts a dedicated reviewer, and any agent between cards looks for pending reviews (step 2 of the work loop). A review is pending when a card is in TO-REVIEW, has a "review requested" comment, and has no "Reviewing" claim or verdict yet. Review cards of other roles, never your own, and never the owner-only part of a contract story. Reviewing is read-only: it does not break the rule against editing another role's directories.

### Re-review after changes

If an approved deep-review pull request changes materially after the approval (an owner decision changes behavior, a contract it depends on changes, or the author makes more than the trivial fixes the reviewer asked for), it needs a re-review of the delta by an independent reviewer before it is merged. Post the new verdict on the card. The author does not merge until it says `APPROVED`.

## 7. Definition of Done

Code merged into the role branch; required tests written and passing; contract respected; no stale dummy data; review finished (light or deep); card in DONE and the issue closed, with links to the pull request and test results.

## 8. Do not

- Skip tests to move faster, or mark a card DONE with failing tests.
- Take more than one card at a time.
- Rewrite history on shared branches, or force-push.
- Change the design. If the design seems wrong, comment on the card and let the coordinator and owner decide.
- Install system packages, or add dependencies the design does not name without recording the reason on the card.

## 9. Bugs, stopping, and reporting

**Bug cards.** If you find a defect or a failing check in code you do not own, in a merge gate, or in shared tooling:

1. Do not fix it yourself outside your role.
2. Open an issue titled `BUG: <what is wrong>` with the owning role's label, the `bug` label, and `epic:N`. Say what is wrong, how to reproduce it (commands, run URLs), what you expected, and which card or gate exposed it. Add the `contract` label if the fix touches `openapi.yaml` or `backend/common`.
3. Put it on the board with a Status and Epic (Status TODO when it blocks work, otherwise BACKLOG). Comment on the originating card with the link.
4. The owning role takes it through the normal work loop. After a merge gate, the coordinator opens the bug cards for failures.

**Stop and report** (end your run with a short report: cards completed with pull request links and test results, anything awaiting review, and any problem for the owner) when any of these happens:

- a `review:deep` or `contract` card reaches TO-REVIEW and you have commented "review requested";
- you need an owner decision (an ambiguous API, data model, or user-visible behavior; a design problem; a missing tool; a denied permission);
- tests fail and you cannot fix them within the card's scope;
- no unblocked card remains for your role, and no review is pending for you.
