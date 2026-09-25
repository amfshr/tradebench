# Java Trading Platform — Design Workspace

> **What this folder is.** The design workspace for a ground-up **Java** trading platform — the
> long-term successor to this Python repo. This repo (`ig-algorithmic-trader`) was the **fast
> prototype**: it proved the concepts, hit the walls, and banked the lessons. The Java platform is
> **Alex's long-term investment** — a general-purpose, "all-can-do" trading platform built properly,
> with the prototype as its evidence base.
>
> **Reframed 2026-09-24 (the pivot):** Dad is taking a ~3–6 month break from trading entirely, so
> the Python system is **shelved** — it runs on only as a **data-capture appliance** (market-data
> streaming daily on the Air; engine + OMS disabled) and as the lessons archive/reference
> implementation. The "handed to Dad" plan is parked with the hiatus; his return is a *designed-for
> event* in the platform (strategies slot in as data), not a dependency. The Java platform is now
> **the main line** of Alex's limited time — built to Alex's requirements, unobstructed.
>
> **The requirements phase is DONE (2026-09-24):** five grill sessions ([`grill/`](./grill/))
> produced the **PRD** ([`product-requirements.md`](./product-requirements.md)) plus the
> engineering playbook, the decisions triage, and the seed pack — see the document index. Next up:
> the design-phase deep-dives (first: indicator/predicate vocabulary), then repo spin-up per
> [`seed-pack.md`](./seed-pack.md).

---

## Why Java, why now

The prototype answered the questions that only a live system can answer: how IG really behaves, what
resilience actually costs, where the strategy edge lives, what the dealing rules do under your feet
(see `ig-broker-playbook.md`). It was the right tool for *learning fast*.

The platform is a different job. It's a durable codebase Alex will live in for years, greenfield, in
the language Alex is most fluent in (Java/Spring). The Python live core stays alive for Dad; the Java
platform is not a port — it's a **rebuild that inherits the lessons**, not the code.

The one-line framing: **prototype = "does this work?"; platform = "build the thing I'll use and extend
for a decade."**

---

## Scope — the "all-can-do" platform

The vision is a single, coherent platform that covers the whole lifecycle, not a stack of disconnected
scripts. The pillars, roughly in dependency order:

1. **Market-data ingestion & streaming** — live IG (Lightstreamer) plus other sources; the resilience
   patterns from the prototype baked in from day one (reconnect stack, staleness watchdog, gap
   backfill, windows). See `ig-broker-playbook.md` §2–§4.
2. **Persistence & data lake** — the system of record + a queryable store for research/backtesting.
   Live data is downstream of decisions; historical data is a first-class asset.
3. **Indicator library** — a reusable, composable, well-tested catalogue of indicators (the prototype
   reimplemented a fixed derived/indicator chain in memory; here it becomes a general library).
4. **Strategy DSL** — a generalised strategy-definition language: declarative, look-ahead-safe,
   compiled/validated at load time. The prototype's TOML expression language (with load-time
   look-ahead rejection) is the seed idea; the platform generalises it.
5. **Money management & risk** — position sizing, exposure limits, the **constraint-failsafe layer**
   (watch the true level, rest a clamped second-best — playbook §5.5) as a reusable runtime primitive.
6. **Execution / live trading (OMS)** — order lifecycle, broker abstraction, fail-closed gates,
   reconciliation, flatten belt (playbook §5, §7).
7. **Broker & market simulator ("IG simulator")** — a faithful fake IG (REST + stream) that models
   the behaviours that actually bite: rejections-inside-200, dealing-rule minimums and their drift,
   slippage/fills, market states. Lets the whole platform be tested without touching IG.
8. **Backtesting engine** — the **same** strategy/indicator/risk code run over historical data, with
   **fast-forward / batch replay** (see the central design challenge below).
9. **Analysis & performance platform** — trade/position analytics, performance metrics, reporting,
   parameter studies/optimisation.
10. **Data acquisition** — sourcing quality historical data (e.g. a Databento tick-year for a real
    fill model), IG stream as a live-verification oracle. (Ties to the parked
    `tmp/parked/validation-and-data-programme.md` in this repo and the reading-programme notes.)

The unifying rule inherited from the prototype: **business logic lives in code on in-memory state; the
database is downstream of decisions, never upstream** (playbook golden rule #4). It's what makes the
same engine runnable live *and* in backtest.

---

## The central design challenge: one engine, many clocks

This is the alignment problem to solve *before* the module boundaries harden, because it shapes all of
them. The requirement:

- **Live mode:** events (ticks, bars) arrive from Lightstreamer in wall-clock time.
- **Backtest mode:** events are pulled from the data store and replayed **as fast as the engine can
  consume them** — with the ability to **fast-forward / jump in batches** over the boring stretches,
  and drop to fine resolution when something interesting is happening.

The design principle that makes this tractable — and it falls out of the prototype's own structure:

> **The engine's data appetite is state-dependent, and that's exactly what enables variable-resolution
> replay.** When no strategy is armed, the engine only needs *bars* (coarse — you can batch-jump whole
> spans). When a strategy is **armed near a trigger**, it needs *ticks* (fine — replay tick-by-tick).
> The prototype already only consumes ticks when `ARMED`; in backtest that same gate becomes the
> fast-forward gate.

So the platform should be built around an abstract **time source / event source** that the engine
consumes, with concrete implementations for live, tick-replay, and fast-forward-batch. Everything
time-dependent (clocks for the watchdog, windows, backoff, staleness) must take an **injectable clock**
— the prototype already proved this pattern works (its watchdog is pure logic with injectable clocks,
fully unit-testable; playbook §3.4). Adopt it platform-wide from the first commit.

Open sub-questions to work out (see the design backlog below): how coarse can a batch jump be without
missing a state transition? how does the fast-forward decide when to slow down? how do live and
backtest share one deterministic ordering of ticks vs bars? how does the simulator's fill model plug
into the same event stream?

---

## Principles carried over from the prototype

Distilled from `ig-broker-playbook.md` and this repo's decision log. These are the non-negotiables to
carry into the Java design:

- **Read broker constraints live; never hardcode.** Dealing rules drift and differ demo↔live
  (playbook §5.1–§5.2).
- **A connected socket is not a live feed.** Independent freshness watchdog + stuck-substate escalator
  (playbook §3.3–§3.4).
- **Do cheap work on the broker's callback thread; queue everything else.** (playbook §2.4)
- **Fail closed.** No stake → no trade; exposure-growing verbs gated; kill-switch verbs always work
  (playbook §5.6).
- **Design for swappability where it pays.** Broker behind an interface, persistence behind a port —
  so the simulator and alternative brokers/data sources drop in without touching business logic.
- **Injectable clocks and event sources everywhere** — the foundation of both testability and
  backtesting.
- **Reliability is observable.** Structured events + a quiet-is-healthy warnings stream + a heartbeat
  (playbook §3.7).

---

## How this workspace grows

### Ways of working — ideation now, tickets later

This is an **ideation period**, deliberately. We are *not* imposing tickets/tasks yet. A ticket asserts
"committed, scoped work with a definition of done" — but there's no work yet, only thinking, and forcing
thinking into tickets creates pressure to *close* ideas before their design has earned its shape.

Instead, ideas flow through a **maturation pipeline**, graduating only as they earn conviction:

```
ideas/      raw capture — a paragraph is fine; no commitment, no DoD, half-formed is welcome
   ↓ (when an idea earns investigation)
research/   investigate one question properly (data sources, fill models, external reading)
design/     flesh out a design that's earned conviction — architecture, module designs
   ↓ (when we commit to an approach)
decisions/  the choice + the WHY + status (Accepted / Provisional / Open) — mirrors docs/decisions.md
```

The **design backlog** below is the informal front of this pipeline — a running list of open questions,
*not* a task list. Keep capturing; don't formalize.

**Tickets earn their place at the build-start boundary** — the moment we decide "I'm cutting Java module
X now" — because that's when execution exists and its state is worth tracking. Until then, tickets are
overhead that fakes progress.

### Folder categories

Keep each file focused on one thing; link between them. Create subfolders as categories fill out (flat
is fine until then):

- **`ideas/`** — raw, un-fleshed ideas worth noting. Low friction, low commitment. Mark each with a
  maturity line so it's clear what's decided vs exploratory.
- **`lessons/`** — distilled lessons from the prototype (currently `ig-broker-playbook.md`, kept at the
  workspace root for prominence).
- **`research/`** — data sourcing, fill models, optimisation approaches, external reading.
- **`design/`** — architecture & module designs that have earned conviction (graduated from `ideas/`).
- **`decisions/`** — the platform's decision log (the *why* + status of each committed choice).

**Document index** (update as files land):

| Doc | Category | Status | Summary |
|---|---|---|---|
| [`product-requirements.md`](./product-requirements.md) | **PRD** | ✅ v1 (2026-09-24) | **THE product truth** — vision, users, principles P1–P10, platform surface, phasing, non-goals, open questions. Everything (epics, tickets, architecture) derives from it. |
| [`grill/`](./grill/) (sessions 1–5) | calibration records | ✅ all closed | The answered grill-session records the PRD derives from. Where PRD and record disagree, the record wins. |
| [`prototype-engineering-playbook.md`](./prototype-engineering-playbook.md) | lessons | ✅ complete | The non-broker lessons: architecture/testing/config/ops/process doctrine, each with the scar that taught it. Companion to the broker playbook. |
| [`decisions-triage.md`](./decisions-triage.md) | lessons | ✅ complete | All 45 prototype decisions triaged carry / carry-as-lesson / re-decide / retire for the platform. |
| [`seed-pack.md`](./seed-pack.md) | handoff | ✅ ready | What to copy into the new repo and the reading order — the one-action handoff. |
| [`ig-broker-playbook.md`](./ig-broker-playbook.md) | lessons | ✅ complete | Everything learned about the IG broker — session/account gotchas, streaming resilience, dealing-rule constraints. The single most load-bearing lessons doc. |
| [`ideas/event-source-and-clock-model.md`](./ideas/event-source-and-clock-model.md) | ideas | 🌱 exploration (v3) | The shared replay foundation — clock + event source + always-current series store — that live, headless-backtest, and the visual workbench all sit on. One engine, many clocks; no reference model. Load-bearing. |
| [`ideas/visual-research-workbench.md`](./ideas/visual-research-workbench.md) | ideas | 🌱 exploration | Richard's idea: a human-driven visual chart for exploring data + experimenting with indicators — seek fast, step slow. Owns "skip fast, step slow" (a GUI concern, *not* an automated-backtest feature). Drives the indicator-library API. |
| [`visual-workbench-for-richard.md`](./visual-workbench-for-richard.md) | review | 📨 awaiting Richard | Plain-language, self-contained alignment doc to send Richard — summarises our understanding of his visual-tool idea + questions on purpose, modes, and (esp.) timeframe behaviour when jumping across strategies on different timeframes. |
| [`ideas/indicator-library.md`](./ideas/indicator-library.md) | ideas | 🌱 exploration | A structured indicator class/family hierarchy — each declares seed/init, warm-up, recursive step, and (where possible) vectorised batch compute; guarantees bulk≡step. The shared math under workbench + backtester + live; makes "add an indicator → fill its column from the start" reliable. |
| [`research/market-data-providers-2026-08.md`](./research/market-data-providers-2026-08.md) | research | ✅ concluded | Provider/vendor landscape survey (web-verified 2026-08-25) + **the two-feed decision**: IG = broker truth, Databento `XEUR.EOBI` = market truth; the second-CFD-broker category eliminated on tick quality (all conflate, all synthetic); Dukascopy's free 13-yr Germany 40 tick archive flagged as a one-off download. Includes the §6 design for the automated monthly Databento batch pull (Spring `@Scheduled` + `get_cost` preflight + two-layer spend caps — programmatic pipeline for £s/month, no live plan). |
| [`design/phase1-market-data-build-plan.md`](./design/phase1-market-data-build-plan.md) | design | ✅ ready to build | **Phase 1 build plan** — own-built `ig-client` + `market-data-service` as a Gradle multi-module repo (full module map incl. reserved seats), playbook porting order, the AWS deployment analysis (free-tier answer: ~6 months on new-account credits, then ~£4–10/mo; recommend Lightsail + docker compose, 24/7), and the weekend build sequence ending in a Python-vs-Java shadow-diff week. |
| _(next)_ | ideas/design | 🔲 | Strategy DSL — grammar, semantics, look-ahead safety, compilation. |
| _(next)_ | ideas/design | 🔲 | IG simulator — fake REST+stream fidelity (rejections-in-200, dealing-rule drift, fills). |

---

## Design backlog (things to work out over the coming weeks)

Loose, unprioritised — the raw material for future docs and decisions:

- **Event-source/clock abstraction** — the interface the engine consumes; live vs replay vs
  fast-forward implementations; deterministic tick/bar ordering.
- **Fast-forward policy** — how the backtester decides when to batch-jump vs step tick-by-tick;
  guaranteeing no state transition is skipped across a jump.
- **Strategy DSL** — how far to generalise the prototype's TOML expression language; grammar, type
  system, look-ahead rejection, compilation target (interpret vs codegen), hot-reload.
- **Indicator library** — composition model, warm-up semantics, exactness (Decimal vs double), how
  indicators declare their look-back so the DSL/backtester can bound warm-up.
- **Simulator fidelity** — which IG behaviours the fake models (rejections-in-200, dealing-rule
  minimums + drift, slippage, market states, opposing-position refusal); how the fill model is fed by
  a real tick dataset.
- **Backtest ⇄ live parity** — proving the same strategy produces the same decisions on the same data
  in both modes (the prototype used an oracle-diff against SQL triggers; find the platform's analogue).
- **Money management / risk engine** — sizing rules, portfolio-level exposure, the constraint-failsafe
  primitive as a first-class DSL/runtime feature.
- **Performance & analytics** — the metric set, storage, and how research/optimisation results feed
  back into strategy selection.
- **Tech stack** — Spring Boot? plain Java + a light DI? Gradle multi-module layout; Postgres +
  something for the tick lake; the Lightstreamer Java SDK (playbook §9); testing approach.
- **Data acquisition** — Databento (or similar) tick-year for the fill model; IG stream as the
  live-verification oracle; storage format for the lake.

---

## Relationship to the Python prototype

- **Kept alive (Python):** the data-capture appliance only — market-data streaming daily on the
  Air through Dad's hiatus (every day accrues historical IG data for the platform, and it's the
  ready-made shadow-diff oracle for the Java collection service). Engine + OMS disabled. Not
  being ported; nothing new gets built in it.
- **Inherited (into Java):** the lessons, the architecture principles, the hard-won IG knowledge, the
  strategy/indicator/risk *ideas* — not the code. Formalised in the PRD + the two playbooks + the
  decisions triage (see the document index).
- **New (Java only):** the general platform surface — DSL, indicator library, backtesting with
  fast-forward, the simulator, the analysis/performance layer, the multi-user web platform.

When in doubt about *how IG really behaves* or *why a resilience pattern exists*, the answer is in
`ig-broker-playbook.md`, which points back to the exact prototype source files.
