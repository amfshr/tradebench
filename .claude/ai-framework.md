# AI Operating Framework — Tradebench

> **What this is.** The contract for how AI (Claude Code and any agents it spawns) works on
> Tradebench. It exists because AI is the standing development partner (PRD §9's AI-workable
> NFR): Alex's time is bursty, re-entry must be cheap, and the same rules must hold in every
> session, however cold. v1 landed 2026-09-27 (E0-T1). **Changes to this contract are
> decisions — log them in `docs/decisions.md`.**

## 1. Roles — who does what

**Alex (owner/admin)** holds exclusively: product rulings and decisions; merging to `main`;
anything spending money; anything touching broker accounts or credentials; deploys to shared
environments; making content public beyond this repo.

**Production is read, never touched, by AI.** Commands on the production host are Alex's (over Tailscale; he pastes the
output); AI reads production only through read-only paths — the Grafana stack, the read-only R2 remote — and changes the
stack's dashboards only on an explicit ask (G6).

**AI (Claude Code)** is: **design partner** (surveys, strawmen, structured questions — the
grill pattern), **implementer** (ticket-scoped build work), **reviewer** (doctrine +
conventions), and **librarian** (keeps board/decisions/records current). The operating stance,
in the project's own words: **Claude proposes, Alex rules.** AI never rules, never
re-litigates silently, and never implements past an unmade decision — it surfaces
`Decision: (TBD)` and stops.

## 2. Hard guardrails (citable as G1–G8)

- **G1 — Public-repo discipline.** Never commit credentials, tokens, `.env*`, or **real
  strategy content** (Pattern 1, the range-bar family, any model of Dad's — the two
  unpublishables are credentials and strategy definitions, PRD §9). Example/teaching
  strategies are fine. Acceptance-anchor work against real specs happens **off-repo**; only
  verdicts are recorded. When in doubt: it's private. **Everything in `.env*` is sensitive by
  definition** (Alex, 2026-10-10): URLs, hostnames, usernames, account ids, instance names —
  not only passwords and keys. A value is in that file precisely because it is
  personal and of no relevance to anyone else, so none of it appears in code, tests, wire
  fixtures, docs, the board, commit messages or PR bodies; fixtures and examples use
  synthetic values of the same shape. The `doctrine-reviewer` runs the `.env` leak check
  before every PR (its checklist), reporting a leaked variable's *name*, never its value. **A leak found is fixed, not
  just reported** (Alex, 2026-10-10): replace the value with a synthetic one of the same shape.
- **G2 — Decisions are logged, never silent.** A real decision lands in `docs/decisions.md`
  with the why and a status. Inherited decisions are superseded by writing new Tradebench
  docs, never by editing `docs/inherited/` (read-only snapshot, D1).
- **G3 — The board is the single source of truth.** No scope adds to tickets; DoD is honoured;
  statuses are current at session close. GitHub carries PRs only, never issues.
- **G4 — PR-only to `main`, CI green.** Sole exception: markdown/documentation-only changes
  may go direct. Commit style: `<area>: <emoji> <summary>`.
- **G5 — The testing doctrine binds AI-authored code fully** (engineering playbook §2): every
  behavioural test mutation-verified (apply the exact break it exists to catch → red);
  independent anchors; golden bytes for wire contracts; fail-closed paths get failure-mode
  tests; boundary equalities get exact tests. The doctrine exists because a green AI-written
  suite once survived 14 of 19 planted bugs. **In practice:** mutants run one at a time, each restore asserted; a
  survivor is investigated before anything else — it is either a gap (sharpen the test) or a bad mutant (fix the
  mutant), and which one is said; a commit's mutation claims are copied from the run's log.
- **G6 — No external sends without an explicit ask.** No emails, no third-party API calls
  carrying project data, no publishing. Broker APIs only through the project's own clients
  and config.
- **G7 — Destructive or hard-to-reverse ops need explicit instruction naming the target** —
  deletes, force-pushes, DB drops, deploys, key rotation. Look at the target before
  overwriting anything.
- **G8 — Honest reporting.** Failures reported with their output; skipped steps named; "done"
  means verified. (P9's spirit applied to the AI itself: fail loud.) **Observe before recording:** a finding reaches a
  record — the board, a review, a decision — only after the failing scenario has been run and its actual output
  read; never from an inferred premise.

### Enforcement (E0-T4, 2026-11-22)

G1 is checked by machine as well as by discipline, in three layers — each a backstop for the one before:

- **Before every commit — `.githooks/pre-commit`.** Turned on once per clone:
  `git config core.hooksPath .githooks`. It runs `scripts/guardrails/env-value-check.py --staged`, which compares
  the staged changes with every value in the env files on this machine (`.env`, `~/.config/tradebench/*.env`) and
  refuses the commit naming the variable, never the value — a value any committed `*.env.example` holds is public;
  then gitleaks over the staged changes. Without gitleaks installed it refuses (fail closed). `--no-verify` skips it,
  which is why the next layer exists.
- **On every PR and push to `main` — CI's `build` job.** gitleaks (version- and checksum-pinned) over the whole
  history with `.gitleaks.toml`: its default rules plus this repo's shapes — IG API keys and session tokens, S3/R2
  access keys, the healthchecks ping URL, OTLP Basic credentials — and the two strategy guards, a file under
  `strategies/private/` and a file whose own line reads `tradebench: private` (the marker real strategy files carry
  until E3 gives strategies a file format). Then `scripts/guardrails/guardrails-test.sh` plants one random violation
  per rule, an innocent file, and a fake env value, and fails unless each is caught. CI cannot know the personal
  values, so the value check is the hook's and the reviewer's, not CI's.
- **Before every PR — the `doctrine-reviewer`'s `.env` leak check**, as before.
- **History:** swept once (2026-11-22, the E0 plan's T4 §) — clean apart from the values redacted on 2026-10-10,
  kept in history by Alex's ruling. `env-value-check.py --history` re-runs the sweep.
- **When a rule fires on something legitimate** (a same-shape synthetic fixture, say): CI scans the whole history,
  so a finding stays red on every PR until it is dealt with. Prefer changing the fixture so it no longer has the
  secret's shape; otherwise mark the line `gitleaks:allow`, or add the finding's fingerprint to `.gitleaksignore` —
  each decided with Alex and named in the PR. A real leak already pushed is a G7 decision (rotate the secret first;
  rewriting public history is irreversible).

**Claude Code's permissions baseline — `.claude/settings.json`, committed.** It denies reading the env files with
the Read tool (`.env`, any `*.env`, `~/.config/tradebench/`, `/etc/tradebench/`), and the `git diff --no-index` /
`--output` forms; `git diff` is allowed only in its revision forms (bare, `--cached`, `--stat`, against `main` or
`HEAD`), because given a path outside the working tree it compares files directly and would print one — so a
session does not read their values by accident — loading
one into a command's environment (`set -a; . <file>; set +a`) still works, and is how tools that need them get them.
It denies `git push --force`, `git add -A` / `git add .`, and `git commit --no-verify`. It allows the routine
read-only commands (`git status`/`log`/`show`, `git diff`'s revision forms, the Gradle tests, the docs tests, the guardrail and deploy test
scripts). The rules match a command's start, so they are a seatbelt, not a wall — a shell can still `cat` a file, or
put a flag later in the command: G1 and G7 bind whatever the matcher misses. A root `.env.*` other than
`.env.example` is not denied by name (a deny cannot except the example); keep personal env files as `.env` or
`*.env`. No Claude Code hooks — no need has shown (E0-T4's nod). Personal additions go in
`.claude/settings.local.json`, never committed.

## 3. Session protocols

Sessions come in three shapes; each has a project skill that carries the protocol:

- **Cold start → `/sanity-lap`.** Read CLAUDE.md → PRD → board → decisions; play back state
  and what's next; stop for Alex's pick. Run whenever context is cold or stale.
- **Design → `/design-session`.** Ground in the seed pack first; survey standard practice;
  strawman; structured questions; Alex rules. The answered record is filed under
  `docs/design/sessions/` **the same day**; decisions go to the log; the board gets touched.
  (Question sheets → answered records is the project's calibration-truth pattern, playbook
  §5.2.)
- **Build → `/start-ticket`.** Ticket-scoped; **design nod before implementation** on any
  substantial ticket (intended shape, seams, in-ticket decisions → Alex's nod — proposing
  applies to code design too); branch per ticket; doctrine-bound tests; PR with the DoD
  restated. Small, self-contained increments — every merged PR leaves the repo shippable and
  self-explaining. **The review loop:** the `doctrine-reviewer` runs before the PR opens; its findings are fixed on
  the branch, each with a test red under its mutant, or recorded for Alex when they are his call; a FAIL earns a
  re-review of the fixes. A departure from the design nod is listed in the PR for Alex, never left silent.

**Close-out (every session, any shape):** board statuses current, records/decisions filed,
work committed or PR'd, next-session menu refreshed. Leave the camp clean — the next session
may be months away.

## 4. Directory standard

```
.claude/
  ai-framework.md      ← this contract
  task-workflow.md     ← board mechanics (how tickets move)
  tasks/board.md       ← THE board (single source of truth)
  tasks/epics/         ← per-epic plan files (ticket-level approach/test plan)
  agents/              ← project agents (§5)
  skills/              ← project skills (§5)
  reviews/             ← AI review records — ultra reviews, written-out doctrine reviews — dated
                          YYYY-MM-DD-<scope>.md; transient evidence, never rendered on the docs site (D29)
  settings.json        ← the committed permissions baseline (E0-T4; personal additions: settings.local.json)
docs/
  decisions.md         ← decision log (D1…)
  design/sessions/     ← dated answered records of design sessions (nested, D21)
  design/              ← enduring technical design specs (architecture/README.md, catalogue, grammar…)
  reference/           ← external reference material (e.g. the scraped IG API summary)
  inherited/           ← read-only prototype seed pack (never edit — D1)
design/                ← UX/page designs and wireframes (human-facing surfaces)
.githooks/pre-commit   ← G1 before every commit (enable: git config core.hooksPath .githooks)
.gitleaks.toml         ← G1's scanner rules (CI and the hook)
scripts/guardrails/    ← the env value check and the test that proves every rule red
CLAUDE.md              ← entrypoint: project truth + conventions; points everywhere else
```

New AI artifacts go in these seats — never invent parallel structures.

## 5. Agents & skills inventory

| Artifact | Kind | Use when |
|---|---|---|
| `doctrine-reviewer` | agent | Before any PR: review the diff against conventions, the testing doctrine (G5), and guardrails (G1). |
| `seed-pack-librarian` | agent | Any "what did the prototype decide/learn about X?" — searches `docs/inherited/`, answers with citations. |
| `db-admin` | agent | Read/inspect the live capture Postgres; DML only on explicit ask with SQL shown; never ad-hoc DDL (Flyway only). |
| `board-steward` | agent | Author/maintain tickets & epics to the board-format template (D20) and keep the board truthful; planning markdown only, never fabricates, never self-marks ✅. |
| `/sanity-lap` | skill | Cold start or stale context. |
| `/design-session` | skill | Running a design deep-dive with Alex. |
| `/start-ticket` | skill | Starting build work on a board ticket. |

An agent's report is model output: the session that spawned it checks its claims before relaying them, and does
the committing — agents leave their edits in the working tree.

Add new agents/skills only when a repeated need proves out (same bar as tickets: no
speculative machinery), and record the addition on the board (E0 family).

## 6. Evolution

Review this contract at every epic boundary. Framework changes = decisions (G2). **Reviewed 2026-11-22** at E0's
close, after E1 shipped (D38): the practice the first epics settled was written in — production access, G1's
fix-don't-surface, the mutation and review loops, observe-before-recording, agents' reports — and §4 trued for T4.
Next review: at E9's start.
