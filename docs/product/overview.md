# Tradebench — what it is and how it works

> The technical product document: the vision, the principles, the surface, and the state of
> the build — in prose. The authoritative requirements live in the PRD
> ([`docs/inherited/product-requirements.md`](../inherited/product-requirements.md)); this
> page is the readable front door and stays current as the build moves. For how any given
> piece *works inside*, see [the Field Manual](../field-manual/README.md); for system shape, see
> [`docs/design/architecture/README.md`](../design/architecture/README.md).

## The one-paragraph version

Tradebench is a multi-user web platform for the whole algorithmic-trading pipeline: collect
market data continuously and keep it forever; explore it on charts with custom indicators;
define strategies in a purpose-built language; backtest them visually or as overnight jobs;
and promote proven models to automated bots — paper, demo, then live — with risk management
and results analytics. It is being built by Alex for himself, his brother, and his Dad, on a
public repo at [`github.com/amfshr/tradebench`](https://github.com/amfshr/tradebench).

## The thesis: compress the loop between idea and verdict

The formative scar (PRD §1): the Python prototype's strategy ran live all day and produced
"a handful of logs to look at — the worst thing ever." Live trading is a terrible feedback
mechanism; it delivers a verdict at the speed of the market. Tradebench's core job is to
deliver verdicts fast — *run the idea over five years of stored data and say "no, actually
it doesn't work" before anyone cares about a live trade*.

That thesis fixes the build order permanently: **backtest-first, live-last**. The identity
ranking behind every prioritisation call is *money → craft → platform-for-others →
research-instrument* — and the way to money is a proven strategy, which is why the
data-and-backtest pipeline outranks live execution without hesitation.

## Who it's for

Three trusted users; no public deploy, ever (PRD §2, §12):

- **Alex** — builder, admin, primary user. The admin role owns platform datasets and deploys,
  so nobody else "has to worry about deploy hardware".
- **Dad** — a SQL-native strategy author returning from a trading hiatus. His models must
  *slot in as data* — strategy definitions, never platform code. The prototype died precisely
  by absorbing one person's strategy into its core; that is this product's named failure mode.
- **Alex's brother** — a capable DSL learner; role still firming up.

**User is a designed-in concept, not a v1 feature**: every schema and API has carried the
user dimension from the first migration, while the platform *operates* as `default-user`
until login lands (phase 5). The same is true of data-source attribution.

## The ten principles, in prose

These are numbered and citable (PRD §4); code review and design sessions refer to them as
P1…P10.

1. **Backtest-first, live-last** — the verdict loop is the product; live execution is its
   graduation ceremony.
2. **Immutable strategy identity** — any tweak makes a *new* strategy; results are never
   conflated across identities. This is what makes the analytics honest.
3. **No look-ahead, ever** — a strategy that peeks at the future is rejected at compile time,
   not discovered in production.
4. **Internal trade management** — the platform manages its own trades; broker-side orders
   exist only as clamped failsafes.
5. **The DSL is the ceiling** — the language's power bounds the product's power; power beats
   hand-holding.
6. **Implement the superset** — build the general mechanism; the simple case falls out as a
   degenerate configuration (the current env-configured capture runner *is* the degenerate
   single-stream-job case of the future jobs product).
7. **Decoupling is a means** — deployable-artifact boundaries where they pay (the collection
   service stands alone); no microservice cosplay.
8. **Data collected forever, source-attributed always.**
9. **Fail closed, fail loud** — a missing daily digest email *is* the alarm; silence must
   mean health, which means noise is never spent casually.
10. **Surface indicators, never gate the user** — the platform warns; the user decides. The
    only exceptions are hard safety invariants (e.g. two live bots may never share a
    broker+market).

## The platform surface

Five sub-platforms of one web product (PRD §5) — each is raw material for an epic family:

| Sub-platform | What it does |
|---|---|
| **Market Data Manager** | Stream-job setup (provider, account, market, timeframes, windows), the data catalogue with gaps visible, uploads with true source attribution, gap detection + heal, live chart view, full export. |
| **Charts & Backtest Workbench** | Exploration mode (scrub through history, multi-panel, multi-timeframe, visible triggers), strategy replay mode, headless backtest jobs, results pages (P&L, equity curve, Monte Carlo, trade → replay jump). |
| **Strategy Studio** | The DSL editor with load-time validation (including P3 look-ahead rejection), the versioned indicator/strategy library, teaching content, and later the DSL copilot. |
| **Bots & Risk** | Strategy → frozen immutable bot on an account/market/mode with a fixed stake; per-bot and global kill switches; push/email notifications. |
| **Analytics** | Results dashboards per strategy identity (P2) across demo and live — daily/weekly/monthly. |

## The strategy language

The product's centre of gravity is a custom DSL — think *our own Pine Script, built to our
rules* — in which indicators and strategies are data, never platform code. Its core
semantics are already ruled (decisions D7–D9, design session
[2026-09-27](../design/sessions/2026-09-27-indicators-and-predicates.md)): one expression
grammar for indicators and predicates (a predicate is just a boolean-typed indicator);
declarative dataflow compiled EBNF → AST → visitors → a DAG; relative bar indexing where
`[0]` is the *forming* bar and history counts backwards; look-ahead rejected at compile
time; determinism guaranteed by an event clock (backtests advance on data events, never
wall time).

**A seat is reserved here**: when E3 makes the language real, its reference documentation —
the grammar, the vocabulary catalogue, worked examples — becomes a sibling page of this one
(`docs/product/strategy-language.md`). Teaching examples are fine in public; real strategy
content never enters this repo.

## How it works today (the build state)

Phase 1 is a **thin end-to-end spine** (PRD §11): every pillar touched shallowly, deployable,
real — and its first vertebra is deliberately the data-collection service, cloud-deployed and
streaming 24/7, so that capture is already weeks deep by the time the first backtest runs.

What exists now (Epic 1, tickets T1–T5 merged):

- **`ig-client`** — a deliberately dumb IG broker library: session management with correct
  login pacing, typed REST (markets, prices, allowances) with exact decimal handling, and
  Lightstreamer streaming behind a vendor-neutral seam. It decides nothing; the service does.
- **`market-data-service`** — the capture pipeline: dual subscriptions per market (ticks for
  precision + broker-sealed 1-minute bars as the healable backbone), criticality-split
  queues, a single pump writing through an ack-after-apply chain into Postgres, under a
  single-instance advisory lock. Real DAX data flows through it today.
- **The database as a product** — `market_data` is not private state; it is the platform's
  published read surface (D17), with user and source stamped on every row and its schema
  pinned by a CI drift gate.
- **The resilience belt** (T5) — a staleness watchdog that trusts data freshness over
  connection status, reconnect classification (did we lose data or not?), backoff with a floor
  and a ten-minute recovery budget, quarantine judgment for multi-market blast radius, gap
  detection whose test fixture is a real captured outage, and a heartbeat that publishes each
  market's capture status for the console.
- **A sink that holds** (T9) — a database blip no longer costs data: bars stay queued, the tick
  batch is held in the store, the pump backs off and reconnects, and capture resumes with nothing
  lost; only a failure that is not weather stops the process. Proven with a 152-second outage
  drill on the dev database.

Next along the spine: the daily heal → Parquet archive → digest email cycle (T6), cloud
deploy (T7), and a week-long shadow-diff against the Python prototype as the epic's
acceptance (T8). Then: a basic chart over stored data, DSL v0, backtest runner v0, and one
real verdict on one real strategy.

## What Tradebench is not

No public deploys or productising for strangers; indexes only (no crypto, FX, or single-name
equities); no general market-research tooling; no porting of Python code (the prototype is
evidence, not source material); and **no strategy-specific logic in the platform core, for
anyone's strategy, ever** (PRD §12).

## Where to go next

- **How each piece works, explained properly** — [the Field Manual](../field-manual/README.md).
- **System shape and component boundaries** — [`docs/design/architecture/README.md`](../design/architecture/README.md).
- **Why things are the way they are** — [`docs/decisions.md`](../decisions.md).
- **What's being built right now** — [`.claude/tasks/board.md`](../../.claude/tasks/board.md).
