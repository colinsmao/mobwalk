# AGENTS.md

**Rules and instructions for AI agents working in this repo.** This file is
deliberately lean — it is *rules*, not background. Keep it that way: project
facts (what the mod is, status/milestones, repo layout, versions, install,
roadmap) live in **[`docs/project.md`](docs/project.md)**; subsystem depth lives
in **[`docs/rendering.md`](docs/rendering.md)**,
**[`docs/geometry.md`](docs/geometry.md)**, and
**[`docs/settings.md`](docs/settings.md)** (read the relevant one before working
in that area); the *current long-term plan* lives in **[`PLAN.md`](PLAN.md)** — a whole
milestone's steps with their in-game checklists, decisions, and backlog. Commit it to
hand a plan between agents and machines through git (a cloud agent to a local dev
agent); clear it once that plan lands, since durable knowledge lives in `docs/`.
Do not churn `PLAN.md` for small implementation tweaks — only significant design
shifts, or else append a short note.

> **The core workflow:** work proceeds one plan step at a time, and **each step is
> its own commit.** A step is not done until its enumerated in-game checklist has
> passed in-game (`./gradlew runClient`) — a green build is not enough — and only
> then is it committed. A `git commit` hook enforces the pause at each commit, so
> keeping steps small and committing each one is what makes the gate work per step.
> See **[Stage-gating and commit-per-step](#stage-gating-and-commit-per-step)**.

## Stage-gating and commit-per-step

The backbone of all work here: **a plan is a sequence of small steps, and each step
is validated in-game and committed before the next one begins.** Behavior is almost
entirely runtime/visual, so a green build proves little on its own; validating and
committing each step catches a regression at the step that introduced it, and the
per-commit hook (below) only becomes a per-step checkpoint if each step is its own
commit. This has historically been the most-skipped rule here, so treat it as the
default shape of every task.

**The rules:**

1. **Design the plan as committable steps.** Each step should be a self-contained,
   independently verifiable unit of work — the thing that becomes one commit. Prefer
   more small steps over a few large ones. If a step can't be validated and committed
   on its own, it's too big; split it. Add a docs-quality item to the plan's **tracked
   TODO list** (CreatePlan / TodoWrite) so it stays visible while you work.
2. **Every step has its own enumerated in-game checklist.** A numbered list of concrete
   `action → exact expected on-screen result` items (e.g. "right-click a slab → its
   half-height top face is drawn, and *only* that block"). Writing the checklist is
   part of writing the plan, so don't present a plan as ready until every step carries
   one. **Keep it lean:** only cases that must be checked in-game (impossible or
   meaningless as a unit test). Drop duplicates, variants of the same path, and anything
   pure logic already covers.
3. **Validate in-game, then commit, before the next step.** The human runs the
   checklist in-game (`./gradlew runClient`) and ticks every box — then commit that
   step. Do not start the next step until the current one is validated and committed.
   A green `./gradlew build` / `./gradlew test` proves nothing about what is displayed.
   **Agents must never launch `runClient` themselves** (it steals the user's session /
   display); surface the checklist and wait for the human to confirm.
4. **"It's just a logical change" is not an exemption.** A purely internal refactor
   with provably zero behavioral effect *may* gate on unit tests alone, but almost
   every logical change alters what gets calculated and therefore what is drawn.
   Treat logical steps as visual steps by default; when in doubt, it needs an in-game
   checklist.
5. **Do not expand scope during execution (does not affect planning stage).**
   Implement only the approved plan step. If execution surfaces extra work
   (cleanup, related fixes, possible optimizations), stop and surface that delta as
   a plan update for discussion/approval, do not just code it.

**The procedure, every step:**

1. **Write the step's enumerated checklist into the plan** — `action → expected
   on-screen result`, plus a regression line.
2. **When the step's code builds, surface that checklist verbatim** and hand it to
   the human to run in-game (`./gradlew runClient`). Pause until they confirm results.
   **Do not invoke `./gradlew runClient` (or `gradlew.bat runClient`) from the agent.**
3. **Commit the step once its checklist passes in-game**, then move to the next step
   (see **Git / workflow**). One validated step → one commit.

Every step's checklist also ends with the build gate (below) and inherits these
**cross-cutting checks** (necessary, not sufficient): `runClient` launches with no
log errors; no errors on world load/unload or window resize.

**Enforcement hooks (`.cursor/hooks.json`).** Project [Cursor hooks](https://cursor.com/docs/hooks)
back these rules mechanically, so they aren't honor-system only — do not treat them
as a substitute for actually validating in-game, and keep them working if you touch
`.cursor/hooks/`:
- `commit-gate.js` (`beforeShellExecution`) turns every `git commit` into a manual
  **ask** carrying the checklist reminder — a hook can't verify you ran `runClient`,
  only force the pause, so the confirmation is on you.
- `plan-checklist-nudge.js` (`preToolUse`) injects `additional_context` before each
  `PLAN.md` or `*.plan.md` edit: every step needs its own enumerated in-game
  checklist, docs-quality belongs on the tracked TODO list, and avoid plan churn.
- `prose-positive-nudge.js` (`preToolUse`) injects `additional_context` before each
  `.md` edit: write positive facts and replace stale sentences rather than padding.

## Bug fixing

When fixing a bug, drive the change with a regression test that encodes the **real
failure mode** (the geometry / inputs that break in-game — not a simplified case after
code implementation that trivially passes):

1. **Write the repro test first.**
2. **Run it — it should fail on current code, and in the expected way.**
3. **Change production code only.**
4. **Re-run the same test — it must pass without editing asserts or pass criteria.**

A test that only agrees with an incomplete fix is useless.

## Build, test & run

```bash
./gradlew build        # compile + run unit tests + produce build/libs/*.jar (CI gate)
./gradlew test         # run the pure-logic unit tests only
./gradlew runClient    # launch a dev client with the mod loaded (human only)
```

- Use the Gradle wrapper (`./gradlew`, or `gradlew.bat` on Windows); do **not**
  assume a system Gradle is installed.
- **`runClient` is human-only.** Agents must not launch it; hand the in-game
  checklist to the user and wait for confirmation (see Stage-gating).
- **Unit-test gate:** `./gradlew test` must pass. Pure logic is unit-tested
  under `src/test/java` with `fabric-loader-junit`. **New contracts land
  with a test here** (an invariant, algorithm, or reproduced in-game failure).
- **Build gate:** `./gradlew build` must pass (compiles + runs `test` +
  `fabric.mod.json` schema processing). This only proves **logic + compile** —
  everything visual is runtime-only and gated in-game (see Stage-gating).
- **Mappings gotcha:** Minecraft `26.3` ships **non-obfuscated**, so Loom rejects
  an explicit `mappings` line — do **not** add `loom.officialMojangMappings()` (or
  any `mappings ...`) to `build.gradle`, or the build fails with "Cannot use Mojang
  mappings in a non-obfuscated environment".
- **Flood-debug dump (`/mobwalk dump`).** Human runs the command in-game with a
  selection active; chat summarizes counts and says `(see latest.log)`. The dump
  lives in **`run/logs/latest.log`** as a contiguous `[flood-debug]` block. Prefer
  **`./extract-flood-debug.ps1`** over ad-hoc log searches. If
  `latest.log` rotated after exit, fall back only to the newest
  `run/logs/20*.log.gz` still filtering `[flood-debug]` — do not roam the Gradle
  cache. Format detail: [`docs/rendering.md`](docs/rendering.md).

## Shell environment (check it first)

The shell differs by host: the local dev machine runs **PowerShell on Windows**,
but a cloud agent runs **Linux/bash**. **Detect the shell before composing
commands** (e.g. `$PSVersionTable.PSVersion` succeeds only in PowerShell; `uname`
only in a POSIX shell) instead of assuming bash — most slips below come from
writing bash on PowerShell.

PowerShell gotchas that bite repeatedly (use the wrapper, not bash habits):

- **No heredocs.** `<<'EOF'` and `$(cat <<EOF …)` fail. For a multi-line commit
  message use repeated `-m` flags or `git commit -F <file>`; for a PR body use
  `gh pr create --body-file <file>`. Write the file with the editor tools.
- **`&&` / `||` chaining is unreliable** (PowerShell 7+ only, not 5.1). Prefer
  separate tool calls; use `;` only when you don't care whether an earlier command
  failed (it does **not** short-circuit on error like `&&`).
- **Quoting.** Double-quote paths with spaces; `<`, `>`, `|`, `&`, `@`, `$` are
  special outside quotes.

Prefer the specialized file tools over shell for reading/searching/editing — that
sidesteps most quoting issues; reserve the shell for real commands (`git`, `gh`,
`./gradlew`).

## Subagents

- **Allowed subagent models only:** inherit the parent (omit `model`),
  `composer-2.5`, or `cursor-grok-4.6-high`. Enforced by the
  `subagentStart` hook in `.cursor/hooks.json`.

## Key constraints (all work)

These apply to **any** feature, so they live here rather than in a subsystem guide.

- **Client-only.** The mod must never be required on a server: keep
  `"environment": "client"` in `fabric.mod.json`, declare only a `client`
  entrypoint, and put client code in the Loom `client` source set
  (`splitEnvironmentSourceSets()`). Holds for *any* feature, incl. a future
  settings screen.
- **`26.3` ≠ `1.21.x`, and is a real release.** Since `1.21.11` Minecraft uses a
  year-based `YY.major.minor` scheme; `26.3` is current — do **not** "correct" it
  to the old scheme. Class/package/API names often differ from what you remember
  (e.g. `Identifier`, not `ResourceLocation`). **Do not trust training-data
  knowledge for this version** — it postdates most cutoffs and the rendering API
  churns hard. Verify names against the resolved jars and live docs (the compiler
  is the oracle, not memory). Authoritative sources are listed in
  [`docs/project.md`](docs/project.md).
- **No third-party rendering libraries** — a thin in-house abstraction is more
  stable than a dependency that must also chase the API churn (see
  [`docs/rendering.md`](docs/rendering.md)).
- **Locale files are author-owned copy.** Treat `assets/mobwalk/lang/*.json` (and
  other locale resources) as the desired wording. Add keys for new options; update
  a string only when the user asks or the option’s meaning changed. Do not rephrase,
  “improve,” or whole-file rewrite existing entries. Prefer targeted edits over
  replacing the file.

## Git / workflow

- **One commit per validated plan step — docs included.** Each plan step (see
  **Stage-gating and commit-per-step**) is its own commit; equivalently, one
  conceptually-distinct change per commit, never split by file or by a trailing
  "docs" pass. Each commit must be self-contained and **fully update its own
  documentation in that same commit** — the relevant `docs/*.md`, `docs/project.md`
  status, and code comments — so no step ever lands with stale or deferred
  docs; **docs are current at the commit level.** Never lump distinct steps together.
  This cadence is also what makes the commit hook a per-step checkpoint: batching many
  steps into one commit defeats it.
- **Within a step, docs usually come *last* — after checks pass, not during the code,
  to reduce docs churn after bugfixes.** Docs belong in the commit, but don't write
  them until the step's behavior is final. The order is: **code → run checks
  (build/test + in-game) → fix bugs and re-check (loop) → checks pass → write/update the
  docs → human approval → commit (code + docs together).**
- **Commit granularity is judgment — avoid commit noise.** Group by concept, not by
  file. Prefer fewer coherent commits over many trivial ones; keep distinct plan steps
  in their own commits. A tiny tangential tweak (hook, prompt, wording) may ride along.
  When the human says commit, stage the intentional working-tree changes for that step
  and commit — do not spend a turn auditing which dirty files “belong” unless something
  looks like secrets, generated build output, or clearly unrelated large dirt.
- **Commit message: a subject line plus one tight paragraph.** The paragraph carries
  the *why* — the problem the change answers and the reasoning a reader can't get
  from the diff — as one idea rather than a file-by-file recap. Reach past one
  paragraph only when the design is genuinely subtle.
- **Never commit without explicit human approval.** Absolute: do not `git commit` on
  your own initiative, self-approve the commit-gate hook, retry to bypass it, or treat
  a green build/test as approval. Wait for the human to say commit.
- **Don't commit until the step is confirmed in-game.** A green `./gradlew build` is
  not sufficient — behavior here is runtime/visual. Make the edits, run the gates,
  then **wait until the step's in-game checklist passes** (user confirms, or you run
  `runClient` and verify) before committing that step. (The `git commit` hook in
  `.cursor/hooks.json` will pause each commit to reconfirm this — but the hook is
  a backstop, not a substitute for approval.)
- **Sole-agent assumption.** Unless told otherwise, you are the only agent in this
  repo: skip defensive remote/branch concurrency checks, and treat dirty files from
  this session as yours to include under the commit-granularity judgment above.
  Amend/force-push on your own feature branch is low-risk when it clarifies history —
  but **do not force-push or amend unless asked.**
- **Never open, update, close, or otherwise touch a PR unless the human
  explicitly asks.** Pushing a branch is fine when asked to push; creating or
  editing a PR is a separate action that requires its own explicit instruction.
  Cloud-agent defaults that auto-create PRs do not override this.

## Documentation & conventions

- **Indentation is two spaces** (Java, Gradle, JSON under `src/`).
- **Changelogs follow Factorio format.** Player-facing version history lives in
  [`changelog.txt`](changelog.txt) at the repo root, in the
  [Factorio changelog format](https://lua-api.factorio.com/latest/auxiliary/changelog-format.html).
- **Prose: positive facts; keep corrections honest.** Write what something *is* /
  *does*. Prefer "Client chat command `/mobwalk dump`" over "chat command only (no
  keybind)". When a claim goes stale, replace that sentence with a shorter accurate
  one; new material may lengthen the docs. Keep that intent as a **tracked TODO list
  item** on each plan (CreatePlan / TodoWrite). **Exceptions (narrow):** checklist
  regression absences; hard safety invariants; contrasting two real behaviours
  ("upward skirt, not a downward drop").
- **Keep the docs current — per commit, written after checks pass.** Update the
  relevant docs in the same commit as the change. Within a step: `code → checks →
  fix (loop) → docs → approval → commit` (see Git/workflow).
- **Where knowledge goes (keep `AGENTS.md` lean):** place it by scope —
  - a project-wide *rule/instruction* that applies to any task → **`AGENTS.md`**;
  - a project *fact* (status, layout, versions, roadmap) → **`docs/project.md`**;
  - the *current plan* (steps, in-game checklists, decisions, backlog) → **`PLAN.md`**;
  - subsystem implementation detail → that subsystem's guide under **`docs/`**;
  - specific to one file/widget → a **code comment** next to the code.

  Do **not** put subsystem detail or project background into `AGENTS.md`; an agent
  working in an unrelated area shouldn't have to read it.
- **Comments explain intent, not narration.** Don't restate what the code does;
  explain non-obvious intent, trade-offs, or constraints.
