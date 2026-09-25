# Product Requirements — Tradebench

> **Provenance.** Synthesised 2026-09-24 from the five answered grill sessions in
> [`grill/`](./grill/) (the calibration records — where this document and a session record
> disagree, the session record wins and this document has a bug), plus the standing workspace
> docs (`ig-broker-playbook.md`, `research/`, `ideas/`). This is **day-one input for the new
> repository**: the product truth that epics, tickets, and system architecture derive from.
>
> **Status of each requirement** is marked: ✅ decided (Alex's ruling on record) · ⚖️ recommended
> (Claude's recommendation, pending Alex's nod) · 🔲 open (genuinely undecided — listed in §13).

---

## 1. Vision

**In three years: a full-featured, reliable, multi-user web platform covering the whole
algorithmic-trading pipeline** — fetch and store market data; explore it visually with custom
indicators; define strategies in a DSL; backtest them visually or as jobs; and promote proven
models to automated trading bots with risk management and results analytics. (S1-Q1; Alex's own
calibration: "achievable, to some extent.")

**The product thesis — compress the loop between idea and verdict.** The formative scar: the
prototype's strategy ran live all day and produced "a handful of logs/db inserts to look at…
the worst thing ever." Live trading is a massive time drain as a feedback mechanism. The
platform's core job is to deliver verdicts fast: *"run it over 5 years of data and say no,
actually it doesn't work — before caring about a live trade."* (S1-Q3/Q5.)

**Identity ranking** (decides every future prioritisation argument, S1-Q2):
**money → craft → platform-for-others → research-instrument.** Money is the ultimate point;
the way to money is a proven strategy; the way to a proven strategy is backtesting — so the
backtest/charting pipeline outranks live execution in build order, without hesitation.
*Clarified 2026-09-25:* "research-instrument" ranks last only as **general/external market
research** (literature, sentiment, trading-as-a-subject — out of scope, §12); **research into
the user's own strategies is not that category — it is the core loop and lives inside
"money"** (it is what backtest-first means). The ranking never deprioritises the
workbench/backtest pipeline.

**Provider-agnostic identity** ✅ — the product is not named after, or coupled to, any broker.
IG is the first integration, not the identity. **Name: Tradebench** ✅ (chosen 2026-09-25 — the
bench you work at; collision check done, nearest neighbours are sleepy). Home:
`github.com/amfshr/tradebench` · `tradebench.amfshr.dev`.

## 2. Users & tenancy

- **Personas:** Alex (builder, admin, primary user); Dad (SQL-native strategy author, returning
  from a ~3–6 month trading hiatus — his strategies must "slot in" with zero platform changes);
  Alex's brother (role still firming up — treated as a capable DSL learner). All three are
  trusted; no deploy is ever public. (S1-Q8, S1-probes.)
- **✅ User is a designed-in concept, not a v1 feature.** Every schema and API carries the user
  dimension from day one; the platform *operates* as a `default-user` until a login page is
  added, with edge gating (Cloudflare or similar) in front of cloud deploys. No heavyweight
  auth/tenancy build in v1. (S1-probe-1.)
- **✅ Admin role exists from the start** — it owns platform-provided datasets (§7) and
  deployment concerns, so Dad and brother "never have to worry about deploy hardware."
- **Per-user resources:** broker credentials, market/timeframe stream selections (within their
  API key's and the hardware's constraints), uploaded data, indicators/strategies (private by
  default, shareable by choice), bots, results. (S1-Q1, S2-J2.)

## 3. Success & failure criteria

- **Success (financial):** build up to reliable "beer money" — **£50–200/month** would feel like
  a real success. No expectation initially. (S1-Q6.)
- **Success (product):** a strategy idea can be taken from "written in the DSL" to "verdict over
  years of data" without leaving the platform — and a proven one to a running bot without
  writing platform code.
- **The dead version (anti-requirements, S1-Q4):** it becomes a mess to work on; it is
  unreliable/buggy; it ties the platform to one person's strategy ("Dad changed his requirements
  and it gets all messy" — the prototype's actual failure mode). The design must actively defend
  against all three.
- **Run-cost envelope:** ~**£20/month** steady-state (cloud); **£100–200 one-off** purchases for
  historical data are comfortably in-scope; effectively zero recurring data spend after upfront
  fetches. (S1-probe-5, S4-Q4.)

## 4. Product principles (named, numbered, citable)

- **P1 — Backtest-first, live-last.** The pipeline's build order and the product's centre of
  gravity: data → exploration → backtest → paper → demo → live. A strategy is only worth live
  money once proven as far as possible. (S1-Q3.)
- **P2 — Immutable strategy identity.** A bot freezes a complete, immutable local copy of its
  strategy and all indicator dependencies. Any change, however small, is a *new* strategy with
  its own results history. **Results are never conflated across variants** — "or there is no way
  to know how one strategy performed over another." Changing a bot = shut it down, create
  another. (S3-Q5.)
- **P3 — No look-ahead, ever.** Compile-time rejection of future-data references in the DSL.
  "That would simply be a bug, and we code to stop that." This is what makes every backtest
  verdict trustworthy. (S3-Q6.)
- **P4 — Internal trade management.** The platform manages entries, stops, trailing, breakeven
  and targets itself, on its own view of the market — it never relies on provider order
  mechanics as the primary mechanism (prototype scar: trades rejected by IG dealing-rule
  minimums). Broker-resting orders are the *failsafe backstop*, clamped to what the broker will
  accept, per the constraint-failsafe pattern (playbook §5.5). Later phases may expose provider
  order types as options. (S2-J3.)
- **P5 — The DSL is the ceiling.** "Strategies are not Java code — strategies are as good as the
  DSL allows to express." Power wins over hand-holding; learnability is delivered by teaching
  content and the copilot (§6), never by weakening the language. (S3-Q4.)
- **P6 — Superset that degrades.** "Implement the superset and let lack of declaration degrade
  implicitly into the simple cases." Stated for the DSL (state machine ⊃ simple expression
  strategies) and adopted as a platform-wide design principle. (S3-Q3.)
- **P7 — Decoupling is a means.** Correct separation of *deployable artifacts*, message-broker
  boundaries between systems, and one non-negotiable standalone service (data collection);
  everywhere else, a well-modularised deployable is fine. "Microservice just for the sake is not
  what I want." (S1-probe-2.)
- **P8 — Data is collected forever and attributed always.** Ticks are the baseline truth, kept
  indefinitely; every series knows its true source; the collection service outlives everything
  else. (S4.)
- **P9 — Fail closed, fail loud.** No configuration → no trade. Kill switches always work. The
  platform is "informative of poor performance": serious failures (e.g. broker API key rejected)
  reach the operator by email/push without being asked; a broken deploy must surface within a
  day, not a week (prototype scar: the silent Sep-2026 outage). (S1-Q1, S2-J8.)
- **P10 — Surface, never gate.** Backtest coverage, demo history, data quality are *surfaced* as
  indicators on a bot/strategy; the user's choice is never blocked by them (except hard safety
  invariants, §8). (S3-Q5.)

## 5. The platform surface

Sub-platforms (likely subdomains of one web product; S2-J1). Each bullet block is raw material
for one epic family.

### 5.1 Market Data Manager
- Onboarding wizard (one-off): broker credentials → stream-job setup (markets, timeframes,
  schedules/cron, dates) → config. ✅
- Data catalogue: what's stored per user and platform-wide — markets × timeframes × sources ×
  date ranges, **with gaps visible**. ✅
- Upload/installer tool: user-provided historical data admitted against a defined schema;
  format options as appropriate (Parquet, DB files, a stream connection). Uploads carry **true
  source attribution**, not just "user-uploaded". ✅
- Post-upload analysis dashboard: summary/quality view of freshly admitted data. ✅
- Gap detection + optional broker-dependent gap-fetcher service. ✅
- **Live chart view**: streaming the user's selected market live — candlesticks **and range
  bars**. ("Very very nice indeed.") ✅
- Export: all of a user's data and strategies exportable whenever. ✅

### 5.2 Charts & Backtest Workbench
- **Exploration mode** ✅: scrub/tick through history (backwards too), multi-panel layout with a
  panel per timeframe, indicator overlays, visible trigger moments (indicator breaks / hits a
  value).
- **Strategy replay mode** ✅: the full lifecycle replayed on the chart — entries, exits, stop
  moves, state annotations ("how a live trade would have looked"). Forward-only is acceptable
  here.
- **Job mode** ✅: headless backtests — quick runs over ~weeks of data feel fast; full-history
  runs are overnight jobs. No pinned latency NFRs yet (revisit when strategy shapes are known;
  a native compute kernel is an accepted future option). (S1-probe-3.)
- **Results pages** ✅: P&L, equity curve, **Monte Carlo analysis of the trade results**, trade
  list; domain guidance welcome ("the more the merrier"). **Trade → replay jump**: click a trade,
  load its data, watch the chart replay of that moment. (S2-J4.)

### 5.3 Strategy Studio
- In-browser **DSL code editor** with load-time validation (incl. P3 look-ahead rejection). ✅
- Library semantics: saved under the user's namespace; optionally published to other platform
  users; cloneable, versioned, exportable. ✅ Strategy↔indicator references are **pinned to
  versions** (P2 corollary). ⚖️
- **The DSL copilot**: a built-in, DSL-fluent AI assistant that writes/edits indicator and
  strategy definitions on prompt. ✅ (Later phase; also the natural "explain why it didn't
  trigger" assistant. 🔲 scope.)
- **Teaching content**: platform intro wizard (what each sub-platform is for) + DSL lessons with
  default/example strategies. ✅

### 5.4 Bots & Risk
- Create a bot by referencing a strategy by name → the platform freezes the immutable copy (P2),
  user picks broker account, market, mode (paper/demo/live), fixed stake. ✅
- **Risk pages**: per-bot local limits with a kill switch + a global kill switch. ✅ (Domain
  detail 🔲 — to be designed with guidance.)
- **Hard invariant** ✅: no two live registered bots may be configured on the same broker+market
  combination.
- **Notifications**: push on trade entry/exit and bot state changes; email for serious failures
  (API key rejected, stream dead, reconcile mismatch). ✅

### 5.5 Analytics
- Results dashboard scoped **per strategy identity** across demo and live history: daily /
  weekly / monthly views. ✅ (Enabled by P2 — identities are never conflated.)

## 6. The engine (domain core)

- **Two-layer DSL** ✅ (S3-Q1/Q3):
  - **Indicator layer** — mathematically computable functions over relative-indexed series
    data, including fitted-shape predicates (e.g. linear/quadratic curve models matched against
    the data). Volume-based indicators plausible. The full predicate/indicator vocabulary is the
    subject of a dedicated design deep-dive (already scheduled).
  - **Strategy layer** — a declarative **state machine whose transition guards are indicator
    expressions**, with declarative position management (initial stop, trail, breakeven, target)
    on the in-position state. Undeclared machines degrade implicitly to FLAT ⇄ IN_POSITION so a
    crossover strategy stays ~5 lines (P6).
  - **Acceptance anchors** ✅: the DSL must fully express **Pattern 1** (Dad's calibrated
    10-minute model) and, harder, **the range-bar model family** — while remaining general well
    beyond them.
- **One engine, many clocks** (carried from the workspace's central design challenge): the same
  strategy/indicator/risk code runs live, in visual replay, and in batch backtest, behind an
  abstract event-source/clock. Injectable clocks everywhere.
- **Execution ladder** ✅ (S5-Q2): backtest → paper bot (live stream, simulated fills) → IG demo
  bot → IG live bot. Every rung available, none mandatory (P10).
- **Broker port** ✅ (S5-Q1): broker behind an interface; **IG client = first adapter; the
  broker simulator = the mandatory second adapter**, doubling as the backtest/paper fill engine
  (modelling the behaviours that bite: rejections-inside-200, dealing-rule minimums and drift,
  slippage, market states — playbook §5, §7).

## 7. Data platform

- **Scope** ✅: **indexes only** (DAX and NASDAQ first). No equities/FX/crypto in the data
  model's v1 ambitions. (S4-Q1.)
- **Ticks are the stored baseline** ✅ — everything else is derivable. **IG 1-minute bars are
  streamed in parallel** for two jobs: *resilience* (tick streams can break) and *verification*
  (broker bars continuously check own tick→bar aggregation — the prototype's oracle pattern,
  kept deliberately). Consistency of derived-timeframe construction is a correctness matter:
  once the maths is right it's a non-issue. (S4-Q2 + S5 clarification.)
- **Retention** ✅: forever. "We will battle the storage issue when it comes."
- **Source is a first-class dimension** ✅: every series knows its origin (IG stream, Databento,
  user upload with true attribution); one market can hold parallel series from different
  sources; **a backtest declares which source it ran against**. Source set for now: IG +
  Databento. (S4-Q3.)
- **Platform-provided datasets** ✅ (new concept, S4-Q4): admin (Alex) centrally purchases and
  manages Databento tick data — **minimum ~2 years for DAX + NASDAQ** ("enough for the project
  not to be inhibited") — made available to every user. Two ownership tiers: platform-provided
  shared data vs user-owned data. Dukascopy 5-year free default per user: 🔲 Alex thinking.
- **The standalone collection service** ✅ (P7/P8): always-on, independently deployed,
  "deployed and just works," accruing value for years — the longer it streams, the better.
- **Build-and-deploy-FIRST ruling** ✅ (Alex, 2026-09-25): the collection service — IG client +
  provider port + the Spring Boot streaming app — is the platform's **first built and
  cloud-deployed artifact, running 24/7 before anything else exists**. Data is the only asset
  the calendar destroys; every other feature waits better than data does. (The Python appliance
  keeps streaming in parallel = the ready-made shadow-diff oracle for this service's first
  weeks.)
- **Daily completeness-and-archive cycle** ✅ (2026-09-25): a scheduled in-service job (plain
  scheduled task with audited job-run rows — deliberately *not* Spring Batch) that, end of each
  trading day: (a) **heals 1m-bar gaps via broker REST**, window-clipped and paced within
  provider allowances (**ticks are stream-only — provably unrecoverable from IG REST**, so tick
  completeness is owned by dual-stream resilience, never by healing); (b) writes the day's
  capture as **Parquet to object storage** (offsite copy, backtest-ready from day one);
  (c) emails a **capture digest** — counts, gaps, heal outcomes, archive location. The digest
  doubles as the P9 proof-of-life: **the absence of the daily email is itself the alarm.**

## 8. Execution & risk requirements

- Internal trade management per **P4**; broker-resting failsafe orders clamped to dealing rules;
  the true level watched by the engine (constraint-failsafe, playbook §5.5).
- Fail-closed gates (P9): no stake → no trade; exposure-growing verbs gated; kill-switch verbs
  always work. Reconciliation against the broker on startup; unknown deals = alarm + stand
  aside. (Playbook §5–§7 carries wholesale.)
- **Sizing v1 = fixed stake per bot.** ✅ Money-management/sizing models arrive **only after**
  backtesting and charting exploration are fully fledged. (S5-Q3.)
- Hard-prevent duplicate live bots per broker+market (S5-Q4); per-bot + global kill switches
  (S2-J7).

## 9. Non-functional requirements

- **Reliability**: "robust, doesn't crash, can be deployed and just works, requires minimal
  maintenance." The bar is a system Alex *doesn't have to think about* between sessions. (S1-Q1.)
- **Observability**: quiet-is-healthy; structured events; heartbeats; freshness watchdogs ("a
  connected socket is not a live feed"); tiered notifications (email = serious, dashboard =
  informational). A dead deploy must self-report within a day. (P9; playbook §3.)
- **Performance**: the §5.2 tiers — interactive exploration feels immediate; weeks-of-data runs
  feel quick; full-history runs may be overnight jobs. No numeric pins yet.
- **Cost**: §3 envelope governs infrastructure choices (this is a real constraint on messaging
  and hosting decisions).
- **Security posture**: no public deploys; edge-gated when cloud-hosted; per-user broker
  credentials stored responsibly; **strategy definitions and credentials never leak** (the only
  two things that must not — the code itself is public, §10).
- **AI-workable codebase** ✅ (S1-Q7): small self-contained modules, per-module docs, conventions
  files, agent-editable planning artifacts — the project must work well with AI as the standing
  development partner, because Alex's time is bursty (dry weeks/months) and re-entry must be
  cheap.

## 10. Technology directions

Decided (✅), recommended (⚖️), open (🔲):

- ✅ **Backend: Java 21+, Spring Boot.** The language/ecosystem Alex is fluent and *happy* in —
  enjoyment is a stated requirement, not a nicety.
- ✅ **Frontend: React/TypeScript SPA** — "if I want a frontend platform, this is what is
  required." Architectural instinct to design toward: **backend does most if not all of the
  work and streams data to the frontend for visuals** (thin visual client). Open-source charting
  library (e.g. TradingView Lightweight Charts) rather than hand-rolled; range bars need custom
  handling regardless. (S5-Q5.)
- ✅ **One monorepo including the frontend** (confirmed by Alex 2026-09-25): single-checkout
  context for AI agents, atomic cross-boundary PRs, one portfolio repo; frontend keeps its own
  toolchain in its own directory. Repo: `github.com/amfshr/tradebench` (public, Alex's own
  account). (S5-Q6.)
- ⚖️ **Gradle multi-module** for the Java side (provisional carry from the phase-1 doc — confirm
  at design).
- 🔲 **Messaging: Redis Streams vs Kafka.** Alex is Kafka-curious and open either way. Working
  default: Redis Streams for v1 (proven in the prototype; fits the £20/mo + minimal-maintenance
  NFRs), with well-schema'd event contracts so migrating later is mechanical. Decide at design.
- ✅ **PostgreSQL** as the system of record (both operators know it; prototype-proven).
  🔲 Tick-lake storage format (Postgres partitions vs Parquet files vs hybrid) — design phase.
- ✅ **Environments: staging + prod from the get-go**, separate DBs, promote by release-tag
  build-and-deploy. **Docker; Kubernetes only if the service count demands it.** Linux
  containers only — **no Windows deployment target, ever** (the prototype's Windows-fitting
  drudgery is explicitly designed out). (S5-Q7, S1-Q5.)
- ✅ **Repo & flow**: public repo (portfolio piece; branch protection), PR-only to main —
  direct-to-main permitted solely for zero-code (documentation-only) changes; CI from the first
  commit. (S1-Q8, S5-Q8.)
- ⚖️ **Planning artifacts**: markdown board in-repo as the canonical *write model* (agents edit
  it, git versions it) + a custom locally-served web app as the human *read model* rendering the
  same markdown — and that viewer app is the recommended first frontend warm-up project. GH
  Issues linked only if outside collaborators arrive. (S5-Q8.)

## 11. Phasing — the thin spine, then the flesh

Scope anxiety is answered by phasing, not by shrinking the vision (S2 close-out). **Phase 1 is a
thin end-to-end spine**: every pillar touched shallowly, deployable, real. Later phases thicken
one area at a time as self-contained epics — matching bursty time.

1. **The spine.** Data-collection service **built and cloud-deployed FIRST, streaming 24/7**
   (IG ticks + 1m, DAX; store forever; gaps visible; the daily heal → Parquet-archive → digest
   cycle, §7) → basic web chart over stored data → indicator DSL v0 (a handful of primitives,
   look-ahead rejection from day one) → backtest job runner v0 (default FLAT⇄IN_POSITION
   machine) → a results page (P&L, equity curve, trade list). *Success: one real verdict on one
   real strategy over real stored data — with capture already weeks deep by then.*
2. **The workbench.** Exploration mode proper (multi-panel, multi-timeframe, scrub, triggers),
   indicator library semantics (namespace, versions, clone/export), Databento ingest + the
   platform-dataset tier, upload tool + source attribution.
3. **The strategy platform.** Full state-machine DSL (Pattern 1 compiles = acceptance), strategy
   replay mode, results depth (Monte Carlo, trade→replay jump), overnight job orchestration.
4. **Execution.** Broker port + simulator adapter matured, paper bots, IG demo bots, risk pages
   + kill switches + notifications, reconciliation. Live bots last, behind the D46-style
   failsafe layer.
5. **The platform polish.** Login + edge gating (multi-user goes real), sharing, wizards +
   DSL lessons, the copilot, money management, NASDAQ second market, live chart streaming view
   (may justifiably pull earlier — it's high-joy).

Ordering within/between phases 2–5 flexes; the spine does not. (Phase numbering here is
indicative; epics get cut from §5 when the new repo's board is born.)

## 12. Non-goals & explicit exclusions

- ❌ Public deploys; productising for strangers; anything requiring hardened multi-tenant
  security beyond edge gating + login.
- ❌ Crypto, FX, single-name equities (indexes only).
- ❌ General market-research tooling — news, sentiment, literature, studying trading as a
  subject. The platform researches *the user's strategies against data*, nothing broader
  (S1-Q2 clarification, 2026-09-25).
- ❌ Windows as a deployment target; native-installer distribution of any kind.
- ❌ Provider-managed trade mechanics as the primary execution path (P4 — internal first;
  provider order types optional later).
- ❌ Porting Python code. The prototype is evidence, not source material. Its live core keeps
  running only as a data-capture appliance during the hiatus.
- ❌ Strategy-specific logic in the platform core, for anyone's strategy, ever (the prototype's
  named failure mode).
- ❌ Mandatory quality gates on the user's path to live (P10) — with the sole exception of hard
  safety invariants (§8).

## 13. Open questions register

| # | Question | Owner | When |
|---|----------|-------|------|
| 1 | ~~Product name~~ **RESOLVED 2026-09-25: Tradebench** (`github.com/amfshr/tradebench`) | Alex | ✅ done |
| 2 | ~~Monorepo-incl-frontend~~ **RESOLVED 2026-09-25: confirmed** | Alex | ✅ done |
| 3 | Redis Streams vs Kafka | design phase | phase 1 design |
| 4 | Gradle confirm; tick-lake storage format | design phase | phase 1 design |
| 5 | Indicator/predicate vocabulary (the dedicated deep-dive Alex requested) | Alex + Claude | first design-phase session |
| 6 | One DSL surface or two (indicator vs strategy syntax) | design phase | with #5 |
| 7 | Dukascopy 5-yr free default dataset per user | Alex | phase 2 |
| 8 | Brother's concrete persona/role | Alex | when he's ready |
| 9 | Analytics metric set + risk-page detail (domain guidance wanted) | Claude proposes | phase 3/4 |
| 10 | Board web-app shape — confirm ⚖️ | Alex | new repo, early |
| 11 | Copilot scope (author-only vs also explain/debug) | Alex | phase 5 |
