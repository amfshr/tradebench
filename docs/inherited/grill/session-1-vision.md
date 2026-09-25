# Grill Session 1 — Vision & Ambition

> **What this is.** The answered record of the first product grill session with Alex (2026-09-24) —
> part of the session series that feeds the platform PRD. The answers here are the calibration
> record; the generated docs (PRD etc.) derive from them, not the other way round.

**Status: CLOSED — all follow-up probes answered (see end of file).**

---

## Q1 — The one-liner: "In three years, this platform is ______"

A full-featured, reliable, multi-user trading platform covering the whole pipeline:

- **Backtesting of models** that may use **multiple timeframes**, predominantly overlaid with
  **custom indicators**.
- Users **log in** and **define custom indicators in a DSL**.
- Backtests run **visually** (rich charting: multiple panels per timeframe, indicator overlays,
  tick-through and fast-forward) **or as jobs** (no charts, results only).
- Proven models can be **promoted to automated trading bots** applying that strategy, with
  results views/pages.
- **Data collection is per-user**: each user uploads their own broker credentials and selects
  which markets/timeframes to stream, within the constraints of their API key and hardware.
- Envisaged surface: **multiple services and sets of web apps** — data management (upload data,
  see available data, set stream jobs), a backtest runner with charting, trading-bot jobs +
  configuration pages, risk management and results pages.
- **Each part decoupled as reasonably as possible.** "A fully fledged microservice-architectured,
  full-featured platform."
- **Robust, doesn't crash, reliable and informative about poor performance** — e.g. email
  updates/notifications if an API key is being rejected. "Something that can be deployed and just
  works, and requires minimal maintenance."

Alex's own calibration: *"in three years I reckon that could be achievable, to some extent."*

## Q2 — Identity ranking

**(a) money → (b) craft → (d) platform for others → (c) research instrument.**

> "a) because money is the ultimate point"

Note the nuance: research-*instrument* ranks last as an identity, but backtesting ranks FIRST as a
capability (see Q3) — backtesting serves the money goal, it is not market-curiosity for its own
sake.

**Clarification (Alex, 2026-09-25):** the last-place ranking pigeonholed the word "research."
What ranks last is *general/external* research — literature, wider market sentiment, studying
trading as a subject — which stays **outside this project** (Alex may do it elsewhere).
**Research into the strategies the user builds** — running them over data, exploring indicators,
extracting verdicts — is technically research too, and that is **core product, living inside
(a) money**: it is what backtest-first means. Read the ranking accordingly — (c)'s position
never deprioritises the workbench/backtest loop.

## Q3 — Success without live trading?

Slight disappointment *if it takes 18 months to get there*, but **not** a failure — and the
priority order is emphatic:

> "a strategy should only be taken live once it's proven as much as possible to work and make
> money … I would prioritise a fully functional backtesting/charting platform that told me my
> strategy makes no money over a live trade, without hesitation."

> "as much as Dad says his model may work, I want to be able to **run it over 5 years of data in
> 5 minutes** and say no actually it doesn't, before caring about a live trade."

Context: the Python prototype's strategy lost money; the lesson landed hard.

## Q4 — The dead version (anti-requirements)

- **It becomes a mess to work on** — the way the Python project did. Root cause named precisely:
  *"it ties me down to a specific strategy … Dad changed his requirements, and it gets all messy."*
  The platform must never couple its core to one person's strategy.
- **Unreliable and buggy.**
- Wants: clean, maintainable, well-decoupled code — *"to not have to worry about specific
  components, like going back to fix or change or strengthen the market data service."*
- Fairness note from Alex: the Sep 2026 silent outage was the MacBook Air deploy's fault, not the
  app's — the real platform deploys to a NUC or cloud service.

## Q5 — Enjoyment autopsy (from the Python prototype)

**Enjoyed:** designing/doing the decoupled microservice architecture with Redis messages;
abstracting the IG client component.

**Drained:** fitting Windows requirements (platform drudgery with no product value); and — the
strongest signal of the whole session —

> "seeing the strategy run all day with very limited noise/results, just armed / invalidated a
> handful of times a day … that is the worst thing ever … it was hell coming home from work going
> 'what's the results' and having a handful of logs/db inserts to look at."

→ **Product thesis: compress the loop between idea and verdict.** Live trading is a massive time
drain for feedback; the platform must deliver a day's (or 5 years') verdict in minutes.

## Q6 — Money, both directions

- **In:** no expectation initially; build up to **reliable beer money, £50–200/month** — that
  would feel like "wow, that's a success." Long-term depends on performance.
- **Out:** initially few restrictions; **happy to spend £100–200 one-off** on a solid Databento
  dataset to build the project out; happy to pay for feeds/historical data and hosted compute
  "once I have a serious project"; it all comes down to budgeting.
- (Probe pending: indefinite steady-state monthly ceiling.)

## Q7 — Time envelope

Mixed and bursty: small workday bits + longer weekend sessions, with **dry weeks or even
months**. Consequences Alex draws himself:

- Changes must be **self-contained**; agile dev with clearly self-contained features and
  **well-scoped epics**.
- The project must **work well with AI** as a development partner.
- Components/services must be **buildable and deployable separately**.

## Q8/Q9 — Audience & name

- Users: **Alex, his brother, and Dad.**
- **Public repo** (branch protection + portfolio piece). Only things that must never leak:
  **strategy definitions and credentials**. No deploy will be public.
- **No name yet.** Two naming rules: it should **not** be linked to IG (the provider may change —
  provider identity must be abstracted from the product identity), and the domain will likely be
  **amfshr.dev** (owned).

---

## Follow-up probes — ANSWERED (2026-09-24)

1. **Multi-user: designed-in concept, deferred surface.** The user concept runs through the
   design from day one (schemas, APIs), but operationally the platform runs as a `default-user`.
   When Alex wants to offer it to Dad/brother, it gets gated with a login page, plus edge gating
   (Cloudflare or similar) in front of a cloud deploy — no ultra-tight lockdown needed since no
   deploy is public. Deployment trajectory: this kind of project "doesn't realistically sit on a
   personal computer" — possibly a local home server first, cloud in ~1–2 years once the project
   materialises. Main driver: **Dad/brother must never have to worry about deploy hardware.**
   (Brother's concrete persona/role: still open — pick up in a later session.)

2. **Microservices = means, not end.** "Microservice just for the sake is not what I want —
   having the correct separation of deployable artifacts is what I want." Happy with a
   well-modularised monolith where it fits; the real system-design drivers decide. Two standing
   convictions: (a) **message brokers as communication between systems** feels right; (b) a
   **separate streaming/data-collection service that's deployed and just works** without anything
   else is independently valuable — "I want to collect data for years to come."

3. **Speed: tiers, not pinned numbers.** Alex explicitly declines to pin latency targets (too
   unsure what strategies/time-series will look like). The working model: the charting dashboard
   is an **exploratory tool for developing ideas/indicators**; a strategy run over **1–2 weeks to
   a month of data should be quick**; **full-dataset runs as overnight jobs are fine**. If a pure
   backtester ever needs raw speed, open to a C/C++ compute kernel. Revisit NFRs once strategy
   shapes are known. (The Q3 "5 years in 5 minutes" line was aspiration, not a requirement.)

4. **Private bits:** gitignored config files in the repo + the database where applicable.
   Possibly encrypted remote storage later; realistically gitignored files.

5. **Steady-state spend:** **~£20/month** for cloud deployment; effectively nothing recurring on
   data after upfront historical fetches.
