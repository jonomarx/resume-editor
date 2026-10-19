# First steps

Goal: go from three documents to agents working on the first wave of stories. Part A is yours (about an hour); Part B starts the agents. GitHub CLI flags change over time, so check `gh <command> --help` if a command fails.

## Part A: set up the project (you)

**1. Tools.** Install and verify: JDK 21+, Maven, Node with pnpm, Docker, Git, the GitHub CLI (`gh`), and Claude Code. Run `gh auth login`, then `gh auth refresh -s project` so `gh` can edit Projects.

**2. Repo.** Create a GitHub repository and clone it. Add these files and commit to `main`:

- `AGENTS.md` at the root
- `.gitignore` at the root (Maven, Node, secrets, `DATA_DIR`, IDE and LaTeX output)
- `CLAUDE.md` at the root containing one line: `@AGENTS.md` (so Claude Code loads the guide automatically)
- `docs/DESIGN.md` and `docs/FIRST_STEPS.md`

**3. Branches.**

```
git branch integration
git branch role/backend
git branch role/frontend
git branch role/jobs
git push --all origin
```

Protect `main` and `integration` (require a pull request and passing checks). Branch protection availability depends on your plan and repo visibility; if it is unavailable, the rule in `AGENTS.md` (agents never merge into them) is your safeguard.

**4. Labels.**

```
for l in role:backend role:frontend role:jobs role:integration review:light review:deep contract dummy-swap; do gh label create "$l"; done
for n in 0 1 2 3 4 5; do gh label create "epic:$n"; done
```

**5. Board.** The project is https://github.com/users/jonomarx/projects/1 (created already). In the GitHub UI, make sure it uses the Board layout. Rename the Status options to BACKLOG, TODO, DOING, TO-REVIEW, DONE, ARCHIVE, and add a single-select field named Epic (0 to 5). Turn on the built-in workflow that adds new issues to the project.

**6. Stories.** Create one issue per story in Appendix A of `docs/DESIGN.md`: title `US-###: <story>`, body with the story text, acceptance criteria, and the tests required, labels for role, epic, and review level (deep stories are listed there). Add the "Depends on" relations as blocked-by only for same-role prerequisites and `contract` stories (cross-role entries are "dummy until ready" and are only noted in the card body) (`gh issue edit <n> --add-blocked-by <m>`, or in the UI). Ask Claude to generate a script that creates all issues and relations from the appendix if you do not want to do this by hand.

**7. First wave.** Put the Epic 0 stories in TODO and everything else in BACKLOG. Tick off US-000 yourself (steps 1 to 6). The stories that can start immediately are **US-001, US-002, US-003, US-012**.

## Part B: start the agents

**1. One working directory per agent.** Use git worktrees so agents never share files:

```
git worktree add ../app-backend role/backend
git worktree add ../app-frontend role/frontend
git worktree add ../app-jobs role/jobs
```

**2. Launch.** Open a terminal in each directory and start Claude Code. Opening prompt for each, with the role filled in:

> You are the Backend agent. Read AGENTS.md and docs/DESIGN.md, then follow the work loop in AGENTS.md: take the top unblocked TODO card labeled role:backend and work it to TO-REVIEW.

**3. Review the contract first.** US-003 (OpenAPI v0) is a deep-review contract story. Have another agent review it, then approve it yourself; Frontend's later stories depend on it. As soon as it is approved, merge US-003 into `integration` yourself (contract stories skip the epic gate) so Frontend can pull it. Until then, Frontend works on unblocked cards such as US-002; US-007 stays blocked by US-003.

**4. Typical early sequence.** US-001, US-002, US-003, US-012 in parallel; then US-004, US-005, US-006, US-007, US-009; US-004 and US-005 build against dummy frontend output until US-002 is merged, and US-007 builds against a dummy auth API until US-006 is merged. Then the feature gate: merge the role branches into `integration` after your review. Then US-008 (Integration) runs against `integration`; merge it, and merge M0 to `main` once the e2e test passes.

**5. Merge gate.** When an epic's non-Integration stories are all DONE (feature gate): update the role branches from `integration`, review and merge the pull requests into `integration`, and run the Postgres profile. Then the Integration agent takes the epic's E2E story; when it passes and you have merged it, merge `integration` to `main`. Then move the epic's cards to ARCHIVE and the next epic's cards from BACKLOG to TODO.

## Checkpoints for you

- After the first few cards: read a pull request closely and a card's comments; tune `AGENTS.md` where agents guessed or went out of bounds.
- After M0: confirm CI runs the H2 tests and lists `DUMMY` markers.
- Before Epic 2: decide the LLM provider and budget, and list your first career pages (the two open questions in `docs/DESIGN.md`).
- Keep notes on friction. The first week is as much about tuning this process as building the app.
