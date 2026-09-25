# Idea: Indicator library — a structured class/family hierarchy

> **Maturity: exploration — NOT decided.** A stub to grow. Seeded by Alex's intent to give every
> indicator a **clear, common shape** so the platform always knows how to compute, backfill, and update
> it — especially when indicators are added/changed live on the visual workbench.

---

## Why this is a key capability

Three consumers must compute indicators with the **same math**: the visual workbench (where you develop
them by eye), the headless backtester (where strategies using them get tested), and the live engine
(where they run). If the math differs anywhere, you get "looked great on the chart, behaved differently
in the test/live" — the classic research trap. **One implementation underneath all three.**

The sharp operation that forces structure: on the workbench you can **add or re-parameterise an
indicator mid-session**. When you do, its whole line has to be **filled from the start of the day (or its
warm-up window) up to the playhead** — "play catch-up." An indicator can only do that reliably if it
declares *how* it computes. Hence a structured contract, not ad-hoc formulas.

## The proposed shape (Alex's idea)

Every indicator is a class in a clear family hierarchy, declaring:

- **Init / seed** — its starting value(s) and how it bootstraps.
- **Warm-up / look-back** — how many bars of history it needs before its output is valid (so the tool
  knows how far back to reach when filling a column, and the backtester knows how much to prepend).
- **Recursive step** — how it produces the next value from the previous state + the new bar. The
  incremental update used when *stepping* one bar at a time.
- **Batch / vectorised compute** *(where possible)* — how to compute a whole stretch at once, fast. Used
  to fill a column on add, or to seek.
- **Inputs / composition** — what it consumes: raw bars, or *other indicators* (an EMA of an RSI, a
  MACD signal line). Indicators feeding indicators → a dependency graph, computed in order.

## The invariant that ties it to the foundation

**Batch ≡ step** (a.k.a. `seek ≡ step` in `event-source-and-clock-model.md` §7): filling a column in
bulk must produce **byte-identical** values to stepping one bar at a time. This is what lets the
workbench's fast "fill from start" and the backtester's step replay be trusted as the same thing. It's a
hard test the library must pass.

## Families to think about (rough)

- **Purely recursive** (cheap incremental step): EMA, running sums, ATR-style smoothers. Batch = fold.
- **Rolling-window** (need last N bars): SMA, rolling highest-high / lowest-low, rolling std. Step is
  cheap with a ring buffer; batch is a windowed pass.
- **Session/anchored** (reset at session open): VWAP, session range. Warm-up = "since session start."
- **Composite** (functions of other indicators): MACD, signal lines, indicator-of-indicator.
- **Whole-window / re-fit** (no clean recursion): anything needing a recompute over the full range on
  each update — the expensive case; flag these so the tool budgets for them.

The point of naming families is that **each family has a known strategy for init / step / batch /
warm-up**, so adding a new indicator is "pick the family, fill in the specifics" — and the workbench and
backtester automatically know how to handle it.

## Open questions

- Which **real indicators Richard uses** break the simple recursive shape? (Being asked now —
  `../visual-workbench-for-richard.md` Q15.) Concrete examples decide which families we need.
- **Composition model** — how indicators declare dependencies on other indicators; ordering; caching.
- **Numeric exactness** — Decimal (as the prototype's derived chain uses) vs double; where exactness
  matters for parity.
- **Parameter surface** — how parameters are declared so the workbench can offer live re-tuning.
- **Warm-up declaration** — static (fixed N) vs dynamic (data-dependent) look-back.
- **Multi-timeframe** — an indicator on 10m bars derived from 1m: how the library and the series store
  keep the two consistent (ties to the timeframe questions in the Richard doc, section C).
- **API ergonomics** — what defining a new indicator looks like in code (this is the thing Alex will
  write most often; it should be pleasant).

---

### Related

- `visual-workbench-for-richard.md` §D — the questions to Richard that this idea drives (init/step/batch,
  indicators-of-indicators, whole-window cases).
- `event-source-and-clock-model.md` — the series store this fills; the `seek ≡ step` invariant this must
  satisfy.
- `visual-research-workbench.md` — the primary development surface for indicators (live add/tune).
- Prototype: `docs/signal-engine/data-plane.md` — the fixed Decimal derived/indicator chain this
  generalises into a library.
