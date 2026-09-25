# Grill Session 3 — Strategies & the DSL

> **What this is.** The answered record of the third product grill session with Alex (2026-09-24).
> Companions: [session-1-vision.md](./session-1-vision.md),
> [session-2-product-surface.md](./session-2-product-surface.md).

**Status: CLOSED** — Q3 resolved by an in-session lesson + Alex's confirmation (see below); one
dedicated follow-up session spawned (indicators & predicates, design phase).

---

## Q1 — Acceptance anchors: YES, plus the extension requirement

- **Accepted:** "the DSL can fully express Pattern 1" is the strategy language's acceptance test,
  with the range-bar model as the second, harder anchor.
- **But the language must extend well beyond them.** Alex's sketch of the layering:
  - **Indicators are mathematically computable functions over relative indexes of chart data**
    (i.e. expressions over series with relative offsets/windows).
  - **Conditions/predicates are defined using the indicators** — including shape/model matching:
    e.g. **linear or quadratic curves that match/model a shape** in the data.
  - The **state machine defined in the strategy** sits on top; the two-framework split
    (indicator layer + strategy layer) is endorsed.
- ⭐ **Spawned session:** Alex wants "a longer session on what could potentially constitute
  predicates and indicators." Scheduled as the first *design-phase* deep-dive (feeds
  `ideas/indicator-library.md` v2 + a new strategy-DSL design doc) — after the PRD, since the
  product requirement is already captured.

## Q2 — Additional primitives

Maybe **volume-based indicators**; otherwise genuinely unsure — the confirmed inventory stands:
multi-timeframe series, derived-indicator chains, session/time windows, retrace/level geometry,
armed lifecycle, tick-level triggers, range bars (+ shape/curve-fit predicates from Q1).

## Q3 — State machine vs signal expressions

Alex: really unsure, lacks domain experience; state machine "made sense for sure," signal
expressions "could be useful" — **explicitly requested a lesson and guidance**. Lesson delivered
in-session (2026-09-24); recommendation on the table: **hybrid** — declarative state-machine
strategy layer, every transition guard is a signal expression over indicators, simple strategies
get a default FLAT ⇄ IN_POSITION machine so a crossover stays tiny. (The prototype quietly proved
this shape: fixed machine + expression conditions, D33+D38 — the platform generalises the machine
to user-defined.)

**✅ CONFIRMED by Alex (2026-09-24), with the design principle stated in his own words:**

> "Yes both — let the state machine degrade and represent simple expression patterns, that is the
> correct way forward. **Implement the superset and let lack of declaration degrade implicitly
> into the simple cases.**"

The state machine is the superset; an undeclared machine implicitly degrades to the simple
FLAT ⇄ IN_POSITION case, so flat signal-expression strategies stay tiny.

## Q4 — Who the DSL is for: POWER wins

> "The DSL has to be powerful and they can learn it. The idea is that strategies are not Java
> code — the strategies are as good as the DSL allows to express."

- The DSL's expressiveness is the **ceiling of the product** — never dumb it down for
  learnability; learnability is delivered by the teaching wizard + the DSL copilot instead.
- Dad and brother are treated as capable of learning it (self-serve authoring is the goal).

## Q5 — ⭐⭐ The immutability doctrine (results integrity)

> "The bot, when created, makes local copies of the full strategy that is **not mutable** — it
> will never be impacted by a changing indicator dependency. If the user wishes to change a
> strategy, they shut down [the bot] and make another… **one thing we never do is conflate one
> strategy's results with another — even if it's a small tweak — or there is no way to know how
> one strategy performed over another.**"

- Bot creation = frozen, self-contained copy of strategy + all indicator dependencies.
- Any tweak, however small, = a **new strategy identity** with its own results history.
- Backtesting before going live is **the user's choice** (the platform may surface backtest-
  coverage indicators on a bot, but never gates on it).
- PRD status: named principle. Analytics ("results scoped per strategy that has traded demo or
  live", Session 2 J6) depends on it.

## Q6 — Look-ahead

> "No look-ahead ever — that would simply be a bug, and we code to stop that."

Compile-time look-ahead rejection carried from the prototype as a non-negotiable platform
invariant. It is what makes every backtest verdict trustworthy.

---

## Open at session close

- Spawned: dedicated indicators & predicates deep-dive (design phase, post-PRD — Alex requested
  "a longer session on what could potentially constitute predicates and indicators").
