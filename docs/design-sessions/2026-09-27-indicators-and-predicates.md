# Design Session — Indicators & Predicates (2026-09-27)

> **What this is.** The answered record of the first design-phase deep-dive, spawned by grill
> S3-Q1 (Alex: "a longer session on what could potentially constitute predicates and
> indicators"). Present: Alex + Claude. Companions: the S3 grill record,
> `docs/inherited/ideas/indicator-library.md`, `docs/inherited/ideas/event-source-and-clock-model.md`.
>
> **Status: CLOSED.** Core language semantics ruled (R1–R8 below). Resolves PRD open **Q#6**
> (one DSL surface); advances **Q#5** (vocabulary) into a spawned artifact. Decisions D7–D9
> recorded in `docs/decisions.md`. All syntax in this record is **illustrative, not committed**
> — the grammar is designed at E3 build time against these semantics.

---

## 1. The survey — how the industry defines indicators (the lesson, condensed)

Four paradigms were examined; Alex's prior instinct (small custom DSL, relative bar indexing,
`[0]` = now counting backwards) turned out to be the dominant industry convention, not a naive
sketch.

- **A. Trader-facing series DSLs** — EasyLanguage (the 1980s granddaddy), MQL, thinkScript,
  AmiBroker AFL, and today's dominant **Pine Script**. Shared core: *every variable is a time
  series evaluated in an implicit current-bar context, with a bracket operator for history*
  (`close[1]`), and the engine runs the expression once per bar — the same text over history
  and live, which is exactly the "one engine, many clocks" shape. Worth stealing from Pine:
  recursive series definitions (`ema := α·close + (1−α)·ema[1]`), the series/scalar type
  distinction, automatic lookback inference. Pine's famous failure: `request.security()`
  multi-timeframe access enables **repainting** — backtests that can't reproduce what live saw.
- **B. Function libraries over arrays** (TA-Lib, pandas-ta, vectorbt) — inspiration for the
  engine's vectorised batch path; rejected as the user surface (strategies must be data, not
  code — D33/P5; no compiler to stop look-ahead).
- **C. Event-driven code frameworks** (NinjaTrader, QuantConnect/LEAN, backtrader) — strategy
  = host-language class; the prototype's named failure mode. Rejected as surface; their event
  loop/injectable-clock internals are what we build *under* the DSL.
- **D. Declarative dataflow** — compile user expressions to a **DAG of pure functions over
  streams**, every node declaring seed / step / batch. Buys, by construction: compile-time
  look-ahead rejection (index analysis), derived warm-up (max lookback along paths),
  **batch ≡ step** testable per node, and free composition (indicators feeding indicators =
  edges). This is precisely what `ideas/indicator-library.md` was groping toward.

**Synthesis adopted: a Pine-family surface (A) compiled onto a dataflow engine (D), with B's
vectorisation as the batch fill path and C's event loop as the runtime.**

## 2. Rulings

### R1 — One expression grammar, two declaration kinds — ✅ Alex

One language. `indicator` and `strategy` are declaration kinds sharing the same expression
grammar; a strategy transition guard is the same expression language as an indicator body.
Knowledge transfers, the compiler is one thing. **Resolves PRD open Q#6.**

### R2 — Declarative, not imperative — ✅ Alex

Pure expressions + `let`-style bindings + recursive series definitions. No statements, loops,
or mutation. This is what makes compile-time look-ahead rejection and batch ≡ step *provable*
rather than aspirational (Pine is semi-imperative and its scars show).

### R3 — A predicate is a boolean-typed indicator expression — ✅ endorsed in discussion

No second machinery. The type system produces `series<number>`, `series<bool>`,
`series<record>`; a "predicate" is any `series<bool>`. Fitted-shape predicates (S3-Q1's
linear/quadratic shape matching) decompose into *fit functions returning records* plus
ordinary comparisons:

```
rising_channel = linfit(close, 24).slope > 0 and linfit(close, 24).r2 > 0.85
```

### R4 — ⭐ `[0]` is the forming bar; determinism comes from the engine — ✅ Alex (the session's headline ruling)

Both calibrated users (Richard, brother) independently expected `[0]` = the bar in progress —
the Pine/MQL standard. The session's key analysis: **forming-bar access is not look-ahead.**
P3 bans *future* information; a forming bar is *partial present* information — causal, and
equivalent to information visible on a finer timeframe. Pine's "repainting" disease is not
caused by forming bars; it's caused by (a) evaluation-timing ambiguity and (b) a backtester
that replays only sealed bars and therefore cannot reproduce the samples live saw. The cure is
engine-level, not a language ban:

1. **Deterministic evaluation instants.** Expressions are evaluated only at defined points on
   the event clock: the strategy's home-timeframe bar seal (default), plus tick events in
   states that declare tick triggers (the ARMED pattern). The backtester replays *identical*
   instants from stored atoms.
2. **`[0]` = the newest bar of the referenced timeframe that has printed data as-of the
   evaluation instant.** For a 10m-home strategy referencing 1h: at the 10:40 seal, `@1h[0]`
   is the 10:00–11:00 bar *forming* (its close = latest price, its high = high so far); at the
   11:00 seal it is that same bar, now sealed (the 11:00 bar has no data yet); at 11:10 the
   forming 11:00–12:00 bar takes `[0]` and the sealed 10:00–11:00 bar becomes `[1]`.
   Degenerate case: when referenced TF = home TF at seal evaluation, `[0]` is always the
   just-completed bar — simple strategies never meet the subtlety (P6 in miniature).
3. **Forming views are always derived from our own finer atoms** (ticks → bars; PRD §7's
   capture-the-finest-atom doctrine) by the single shared aggregation implementation — never
   read from the broker's forming candle (broker bars remain the verification oracle, D36
   carry). Live and backtest therefore read byte-identical views by construction.
4. **`closed(x@1h)[n]`** — sugar for "n-th most recent *sealed* bar", for stability regardless
   of position within the hour (plain indexing would need `[0]`-or-`[1]` depending on the
   clock — clunky).
5. **Volatility is surfaced, never gated (P10).** The compiler statically marks expressions
   that touch forming data ("volatile") vs sealed-only ("stable"); the workbench badges them.
   An accidental `sma(close@1h, 20)` including a 3-minute-old partial bar is *visible*, never
   blocked.
6. **Fail loud on missing resolution (P9).** Volatile references in a backtest require
   finer-resolution atoms to reconstruct; if the declared data source lacks them for the
   period, that is a named, fail-closed coverage error — never a silent fallback to
   sealed-only semantics.
7. **P3 stands uncompromised**: negative indexes and any future reference remain compile-time
   errors; there is no Pine-style lookahead flag to abuse.

### R5 — Tick awareness lives in the strategy layer — ✅ Alex (matches prototype record)

The indicator layer computes over bars (sealed + forming views). Tick-level evaluation belongs
to strategy states that declare tick triggers — the prototype's `ARMED` price-tracing pattern
(it consumed ticks *only when armed*; that state-dependent data appetite is also the
variable-resolution backtest fast-forward gate). Indicators compute **levels** from bar data;
the engine watches live ticks against those levels — which is also P4's
internal-trade-management shape (D42/D46).

### R6 — Constant offsets in v0 — provisional

`x[n]` takes a compile-time constant (literal or declared parameter) so index analysis is
airtight. Dynamic indexes are deferred until a real strategy demands them.

### R7 — Compiler shape: EBNF → AST → visitor passes → dataflow DAG — ✅ Alex

Grammar defined in EBNF; parsed (ANTLR4, **provisional**) into **our own AST of dumb node
types** (parser-swappable — ANTLR's parse tree never leaks past the front end); compiler
passes as **visitors**: type check → index/look-ahead analysis → warm-up derivation →
volatility marking → lowering to the dataflow DAG whose nodes declare seed/step/batch with the
batch ≡ step byte-identity invariant tested per node family. D33's bar: **teaching-quality
error messages are a requirement**, not a nicety (ANTLR defaults don't meet it; customise or
hand-roll later).

### R8 — Typing v0: light but structured — ✅ Alex ("good initial broad set, not over the top")

v0 types: `number`, `bool`, `record`, `duration`, `time-of-day` (plus series-of each).
Unit-awareness (price vs points vs percent) is parked as a question in the vocabulary
catalogue, where it can be judged against real primitives.

## 3. Illustrative sketches (semantics anchors, not committed syntax)

```
indicator ema(src: series, len: int) -> series {
  alpha = 2 / (len + 1)
  out := alpha * src + (1 - alpha) * out[1]    # ':=' marks recursion on own past
  seed out = sma(src, len)                     # explicit bootstrap; warm-up derived
}

indicator regime_up() -> series<bool> @10m {
  out = ema(close@1h, 50) > closed(ema(close@1h, 50))[0]   # forming vs last-sealed, explicit
}

strategy golden_cross @10m {                   # undeclared machine ⇒ FLAT ⇄ IN_POSITION (P6)
  enter long when crosses_above(ema(close, 21), ema(close, 55))
  exit       when crosses_below(close, ema(close, 21))
  stop  initial = low[1] - 5
  trail by = atr(14) * 2
}
```

Series coordinates carry `(market, source, barspec)`; barspec is `time(10m)` **or `range(N)`**
— range bars are a first-class bar type with a price-distance sealing rule, which is exactly
why sealed/forming semantics (R4) are the foundation rather than clock alignment.

## 4. Spawned work

1. **Vocabulary catalogue v0** (Claude proposes, Alex rules) — every primitive organised by
   compute family (recursive / rolling-window / session-anchored / composite /
   whole-window-refit) with signature, semantics, warm-up, volatility class. Candidate
   families from the session: arithmetic/comparison/boolean; bar geometry; rolling
   (`sma`/`ema`/`highest`/`lowest`/`stdev`/`atr`/`rsi`); edges & persistence
   (`crosses_above/below`, `holds`, `bars_since`, `count`); session/anchored
   (`session_open`, `vwap`, `time_in`); level/retrace geometry (needs Pattern 1); fits
   (`linfit`, `quadfit` → records). Carries the unit-awareness typing question. **This
   catalogue is the spec E3's DSL v0 implements; it advances PRD open Q#5.**
2. **Acceptance-anchor check** — walk Pattern 1 (Dad's answered sheet) and the range-bar
   consolidated spec against these semantics. Both live in the private prototype repo and are
   **real strategy content — never committed to this public repo** (PRD §9). The exercise runs
   off-repo; only the verdict is recorded here.

## 5. Question-register effects

| PRD open Q | Effect |
|---|---|
| #5 (indicator/predicate vocabulary) | Advanced — vocabulary catalogue v0 spawned (§4.1) |
| #6 (one DSL surface or two) | **Resolved: one grammar, two declaration kinds (R1)** |
