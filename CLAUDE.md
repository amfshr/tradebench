# CLAUDE.md

Guidance for Claude Code when working in this repository.

## What Tradebench is

A multi-user web platform for the full algorithmic-trading pipeline — data collection →
charting/exploration → strategy DSL → backtesting (visual + jobs) → paper/demo/live bots with
risk management and analytics. **Backtest-first, live-last**; the product thesis is *compressing
the loop between idea and verdict*. Owner/admin: Alex (SWE); future users: his brother and Dad
(a returning strategy author whose models must slot in as data, never as platform code).

**Status: pre-build.** The requirements phase is complete; Epic 1 (the 24/7 collection service)
is planned on the board and is the active work.

## Read-first order

1. `docs/inherited/product-requirements.md` — **the PRD.** Product truth: vision, principles
   P1–P10, the platform surface, phasing, open questions. Cite principles by number.
2. `.claude/tasks/board.md` — the board: the single source of truth for epics/tickets. GitHub is
   for PRs only, never issues.
3. `docs/decisions.md` — this repo's decision log (why + status). Add to it when a real decision
   lands; never re-litigate silently.
4. For anything IG or engineering-doctrine: `docs/inherited/ig-broker-playbook.md` and
   `docs/inherited/prototype-engineering-playbook.md`. For why a prototype decision does/doesn't
   apply here: `docs/inherited/decisions-triage.md`.

`docs/inherited/` is a **read-only snapshot** of the Python prototype's seed pack (2026-09-25).
Do not edit it; supersede it in this repo's own docs. Links inside it that point outside the
pack refer to the private prototype repo (`FisherNE/ig-algorithmic-trader`) and won't resolve
here.

## Load-bearing principles (PRD §4 — the short form)

P1 backtest-first, live-last · P2 immutable strategy identity (any tweak = a new strategy;
results never conflated) · P3 no look-ahead, ever (compile-time rejection) · P4 internal trade
management (broker orders are clamped failsafes, never the mechanism) · P5 the DSL is the
product's ceiling (power > hand-holding) · P6 implement the superset, degrade implicitly to the
simple case · P7 decoupling is a means (deployable-artifact boundaries; the collection service
stands alone) · P8 data collected forever, source-attributed always · P9 fail closed, fail loud
(digest email absence = alarm) · P10 surface indicators, never gate the user.

## Engineering conventions

- **Java 21+, Spring Boot, Gradle multi-module** (services); **React/TypeScript** in
  `frontend/` with its own toolchain. PostgreSQL via Flyway migrations. Redis Streams for
  inter-service messaging (provisional — decisions.md D4). Docker; Linux targets only.
- **Injectable clocks everywhere**; distinguish monotonic/awake time from wall time. Business
  logic on in-memory state; the DB is downstream of decisions, never upstream.
- Cheap work only on transport callback threads; queue the rest. At-least-once consumers,
  idempotent by key, ack-after-apply.
- **User and data-source are first-class dimensions in every schema/API from day one** (the
  platform runs as `default-user` until login lands).
- **Testing doctrine** (engineering playbook §2): every new behavioural test is
  mutation-verified (apply the exact break it exists to catch → red); anchor expectations
  independently of the code under test; golden bytes for wire contracts; fail-closed paths get
  failure-mode tests; boundary equalities get exact tests.
- **Config discipline:** no code defaults for anything machine-specific; secrets in env files
  beside config, never committed; every CLI/job names its instance before acting.

## Ways of working

- Work is tracked on `.claude/tasks/board.md` (see `.claude/task-workflow.md`). Tickets carry a
  DoD; don't add scope; surface `Decision: (TBD)` items instead of implementing past them.
- **PR-only to `main`** with CI green. The one exception: documentation-only changes may go
  direct to main.
- This is a **public repository**. Never commit credentials, `.env*`, or **strategy
  definitions** (real strategy content lives only in deploy config/DB — the two things that must
  never leak). Example/teaching strategies are fine.
- Commit style: `<area>: <emoji> <summary>` (inherited habit — e.g. `docs: 📈 …`,
  `collection: 📡 …`).
