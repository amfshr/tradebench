# Tradebench documentation map

One page to find everything. The tree has four registers — product (what and why for
humans), teaching (the book), design truth (specs, sessions, decisions), and inherited
evidence (the prototype's read-only seed pack) — plus the working layer in `.claude/`.

## Start here

| You want… | Go to |
|---|---|
| What Tradebench is and how it works, in prose | [`product/overview.md`](product/overview.md) |
| To *understand* a mechanism (sockets, threads, queues, locks, clocks, resilience, busses) | [The book](book/README.md) |
| The system's shape — modules, seams, data flows | [`design/architecture.md`](design/architecture.md) |
| Why a decision was made (and its status) | [`decisions.md`](decisions.md) |
| What's being built right now | [`.claude/tasks/board.md`](../.claude/tasks/board.md) |

## The registers

- **[`product/`](product/overview.md)** — technical product docs. Today: the overview. A
  named seat waits for the strategy-language reference when E3 makes the DSL real.
- **[`book/`](book/README.md)** — the learning pages: jargon → first principles → our exact
  code paths → the scar that taught it. Nine chapters following the data from IG's socket
  to Postgres and out again.
- **[`design/`](design/README.md)** — enduring technical specs (*what is*):
  [`architecture.md`](design/architecture.md) (living C4 — modules, seams, key behaviours,
  trajectory, fan-out plan), [`tech-notes.md`](design/tech-notes.md) (stack, Java feature
  policy, null-safety, naming and SQL conventions),
  [`secrets-and-config.md`](design/secrets-and-config.md) (the D16 env/credentials design).
- **[`design-sessions/`](design-sessions/2026-09-27-indicators-and-predicates.md)** — dated
  *answered records* of how designs got decided (*why*, frozen; they don't evolve).
- **[`decisions.md`](decisions.md)** — this repo's decision log, D1 onward: each with
  context, ruling, and status. Never re-litigated silently.
- **[`reference/`](reference/ig-api-reference-summary.md)** — external-fact digests: the IG
  API reference summary, the `trading-ig` open-source comparison sweep.
- **[`inherited/`](inherited/README.md)** — the Python prototype's seed pack
  (**read-only snapshot, 2026-09-25**): the [PRD](inherited/product-requirements.md), the
  [IG broker playbook](inherited/ig-broker-playbook.md) (hard-won broker behaviour), the
  [engineering playbook](inherited/prototype-engineering-playbook.md) (doctrine incl. the
  testing rules), the five grill records, matured ideas, and the
  [decisions triage](inherited/decisions-triage.md) mapping 45 prototype decisions to this
  repo. Superseded *here*, never edited *there*.

## Adjacent to `docs/`

- **[`.claude/tasks/board.md`](../.claude/tasks/board.md)** — the board: single source of
  truth for epics and tickets (GitHub is PRs only). Per-ticket plans live in
  [`.claude/tasks/epics/`](../.claude/tasks/epics/e1-collection-service.md).
- **[`.claude/ai-framework.md`](../.claude/ai-framework.md)** — the AI operating contract:
  roles, guardrails G1–G8, session protocols, agents and skills.
- **[`ops/queries/`](../ops/queries/README.md)** — operator SQL for the live capture
  database (health, recency, gap checks).
- **`/design`** (repo root, when it lands) — UX/page designs and wireframes.
