# Task workflow

> AI roles, guardrails (G1–G8), and session protocols live in `.claude/ai-framework.md` — this
> file covers board mechanics only.

- **The board (`.claude/tasks/board.md`) is the single source of truth.** GitHub is for PRs
  only. Epics carry a mission and references; tickets carry a DoD. Close-out moves a summary
  line to Done history with its evidence (PR, report, ruling).
- **Before starting a ticket:** read its epic's references and the relevant PRD sections. If a
  ticket hides an unmade decision, surface it as `Decision: (TBD)` and stop — don't implement
  past it. Real decisions land in `docs/decisions.md` with the why.
- **Epics with active build work carry a plan file** at `.claude/tasks/epics/e<n>-<slug>.md`:
  per-ticket approach, build order, test plan, and the decisions to settle in-ticket. The
  board row stays the index + DoD; plans are living sketches, refined (not re-approved) at
  `/start-ticket` time.
- **AI review records live in `.claude/reviews/`** — dated `YYYY-MM-DD-<scope>.md` (e.g. the 2026-10-05
  PR #14 belt review and its testing-framework proposal), never in `docs/` (Alex, 2026-10-05). The board
  cites them as evidence; the tickets they spawn cite finding numbers rather than restating them.
- **Build increments small and self-contained** — Alex's time is bursty; every merged PR leaves
  the repo shippable and self-explaining (PRD §9, AI-workable NFR).
- **PR-only to `main`, CI green; docs-only changes may go direct.** Never commit secrets or
  strategy definitions (public repo).
- **Testing:** every new behavioural test is mutation-verified (apply the exact break it
  catches → red). Independent anchors over self-referential ones. Fail-closed paths get
  failure-mode tests. (Engineering playbook §2 — it exists because a green AI-written suite
  once survived 14 of 19 planted bugs.)
- **Ideation is not ticketed.** Ideas mature through docs (`docs/`, or a design session with
  Alex) and earn tickets only at the build-start boundary.
- **Session close-out, every session:** board statuses current, records/decisions filed, work
  committed or PR'd, next-session menu refreshed — and `docs/design/architecture/README.md` updated
  whenever the change altered the system's shape. Leave the camp clean — the next session may
  be months away.
