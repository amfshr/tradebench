# Idea: Visual research & indicator workbench

> **Maturity: exploration — NOT decided.** Raw idea, captured to mature. **Origin: Richard.**
>
> This is the home of the "skip fast / step slow" idea. It was originally tangled into the
> event-source doc as an automated-backtest feature; that was wrong. It belongs here, as the control
> surface of a **human-driven visual tool** — where "missing a trade during a jump" is a non-concern,
> because a person is looking at a chart and skipping deliberately.

---

## The user story

> **As a strategy researcher (Alex / Richard),** I want to load historical market data onto an
> interactive chart and move through time at will — **jumping quickly to regions I find interesting and
> stepping slowly, bar-by-bar (or tick-by-tick), through them** — while **overlaying and tuning
> indicators live**, so that I can investigate price behaviour by eye and **develop, calibrate, and
> form strategy ideas** before committing them to a formal, testable strategy.

Supporting stories:

- **Seek.** Jump the playhead to any time and have every indicator correct *there*, without replaying
  from the start by hand.
- **Step.** Advance one bar (or tick) at a time, forward and back, to watch a setup unfold.
- **Play / fast-forward.** Play through at an adjustable speed, or jump instantly, to scan a day/range.
- **Experiment with indicators.** Add / remove / re-parameterise indicators and see them recompute
  instantly at the current view.
- **(Optional) Overlay signals.** Draw a candidate strategy's would-be arm/entry/exit markers on the
  chart, purely for visualisation — to *see* what a rule would have done.
- **(Optional) Annotate.** Mark and note interesting moments to revisit.

---

## Why this is distinct from the headless backtester

They share a foundation (the replay engine + series store — see `event-source-and-clock-model.md`) but
are different tools for different jobs:

| | **Visual workbench** (this) | **Headless backtester** |
|---|---|---|
| Driver | a human scrubbing a playhead | an automated run-loop |
| Needs a defined strategy? | no — exploration comes first | yes — that's the input |
| Motion | seek fast, step slow, play | run start → finish |
| Fast-forward | the whole point | meaningless — just run the range |
| "Missing a trade on a jump"? | non-concern (it's a viewer; scrub back anytime) | matters (reproducibility) — but there's no jumping, so it can't happen |
| Output | insight, calibrated indicators, strategy ideas | a decision trace + performance metrics |

**The key correction that spawned this note:** for headless backtesting of a *defined* strategy with no
GUI, "skip fast / step slow" makes no sense — you just replay the whole thing. The concept only earns
its keep in the *interactive, visual* context. So it lives here, not in the backtester.

---

## The one hard constraint: shared indicator code

The workbench and the backtester (and, ultimately, the live engine) **must compute indicators with the
same code** — the shared indicator library. Otherwise you get the classic research trap: an indicator
"looks perfect" in the chart tool but behaves differently once a strategy uses it in a backtest or live.
The chart is where you *develop* an indicator by eye; the backtester is where you *test* the strategy
built on it; live is where it runs. One implementation underneath all three.

This makes the workbench a direct driver of the **indicator library** design (its API must support live
add/remove/re-parameterise + instant recompute at an arbitrary view) and a feeder of the **strategy
DSL** (ideas formed here become formal strategies).

---

## What it needs from the foundation

Just two capabilities on the replay engine / series store:

- **`seek(Instant t)`** — batch-compute the series/indicators up to `t` so the view is correct there.
  (This *is* Richard's "batch calculate everything to a certain point.")
- **`step()` / `stepBack()`** — advance/retreat one point, keeping the series correct.

Note there is **no wake-predicate / never-under-wake machinery** here — the human decides where to stop,
so none of that automated-correctness apparatus is needed. `seek` + `step` is the whole control surface.

---

## Open questions (raw)

- **Rendering stack.** Web front-end over a Java backend? Desktop (JavaFX)? A web charting lib driven by
  a Java data service? (Leaning web — easiest charting ecosystem — but undecided.)
- **How indicators plug in for live experimentation** — ties directly to the indicator library API
  (parameter surface, recompute cost, warm-up handling on seek).
- **Tick-level rendering performance** — a day of ticks is a lot of points; level-of-detail / downsample
  for the overview, full detail when stepped in.
- **Data source** — reads the same data lake the backtester uses.
- **Does it share the exact series-store code with the backtester?** (Should — same math, see above.)
- **Scope creep guard.** This is a *research* tool. Keep it from quietly becoming a live trading
  terminal; live monitoring is a separate concern.

---

### Related

- `event-source-and-clock-model.md` — the shared replay foundation (clock, event source, series store)
  this tool consumes via `seek` + `step`. That doc now owns the *foundation*; this one owns the
  *human-driven exploration* use case.
- Future: **indicator library** (the workbench is its primary development surface) and **strategy DSL**
  (ideas formed here graduate into formal strategies).
