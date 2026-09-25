# Idea: Event-source & clock model — "one engine, many clocks"

> **Maturity: exploration — NOT decided.** A fleshed-out idea, not a spec. Graduates to `design/` + a
> `decisions/` entry once an approach earns conviction. Push back on any of it.
>
> **Revised (v3) after feedback.** Earlier drafts treated "fast-forward / skip fast, step slow" as an
> **automated-backtest** feature, complete with wake-predicates and a "never miss a trade" invariant.
> That was wrong. "Skip fast, step slow" is a **human-driven, visual** concern — it now lives in
> `visual-research-workbench.md`. A **headless backtest of a defined strategy just runs the whole
> range**; there is nothing to fast-forward. This doc is therefore slimmer: it owns the **shared
> replay foundation** (clock + event source + series store) that *both* consumers sit on top of.

---

## 1. What this is

A single foundation that lets the **same** model/indicator code run against three worlds — live,
headless replay, and interactive replay — without forking. Everything above it (indicator library, DSL,
backtester, visual workbench) sits on this.

| Mode | Data pacing | "Now" comes from | Who drives time |
|---|---|---|---|
| **Live** | wall-clock; events pushed by Lightstreamer | the system clock | the world (push) |
| **Headless replay** (backtester) | as fast as consumable; whole range, start→finish | the current event's timestamp | the run-loop (pull) |
| **Interactive replay** (workbench) | human seeks/steps/plays | the playhead | a person |

Two hard requirements:

1. **No code reads the wall clock directly** — every time-dependent behaviour takes its time from an
   **injected source**. True from commit #1, or retrofitting is a rewrite. (Proven: the prototype's
   watchdog is pure logic with injectable clocks — `../ig-broker-playbook.md` §3.4.)
2. **Same input + same code → same result, in every mode.** Parity is a property, not a hope (§7).

---

## 2. Two mandates that survive the reframe

### 2a. No reference model — everything is model-declared

The prototype ships one fixed state machine (`SCANNING → WAIT_RETRACE → ARMED → IN_POSITION`) and one
data appetite. **That is one model's shape; the runtime must not bake it in.** Other models will have
different states (or none), consume different inputs (1m/10m bars, ticks, multiple epics, external
series), and decide at different cadences. So the runtime provides *machinery* (clock, series store,
event dispatch, order surface) and a *contract*; the **model** supplies its inputs, warm-up, states, and
logic (§5). The runtime never assumes what a model does.

### 2b. The series store is always current wherever you land

Indicators are **stateful and recursive** (an EMA/ATR at bar *N* depends on every bar before it). So the
series store must be advanced for **every** data point at the model's declared resolution — you never
skip the data and stay correct. Whether you get there by **stepping** (one point at a time) or by
**seeking** (bulk-compute up to a target), the resulting series must be **identical** (§7). "Wherever you
land" means: the end of a headless run, or the playhead in the workbench.

> Richard's "batch-calculate everything to a certain point" is exactly **seek**: it bulk-computes the
> series so the view is correct there. It skips *rendering/evaluation work between here and there* — it
> never skips the *data* the indicators need.

---

## 3. The key inversion (live vs replay time)

> **In live mode the world drives time. In replay the timeline drives time.**

- **Live:** events arrive async; the clock ticks on its own; the loop is push-driven (+ periodic timers).
- **Replay:** the driver *sets the clock to each event's timestamp before dispatch*. Time advances only
  when events/timers advance it. A "wait 5s backoff" becomes "advance sim-time 5s" — no real sleeping.

If both feed the engine through the same interface, the engine can't tell which world it's in.

---

## 4. The two layers

Keep these cleanly separate:

```
  LAYER 2 — Strategy evaluation (optional, per model)   ← the arbitrary state machine; runs in the
            "given the current series, do I act?"           backtester and live. Not always present
                        ▲ reads (always-current)            (the workbench may run Layer 1 alone).
  LAYER 1 — Series store (bars + recursive indicators)  ← ALWAYS current at native resolution.
            step OR seek — identical result                 Shared by every consumer.
                        ▲ fed by
                  EventSource (ordered market events)
```

- **Layer 1 (series store)** — the dataframe-like object (bars + indicator columns), the prototype's
  in-memory data plane generalised per model. Shared by *all* consumers. Supports `step()` and `seek()`.
- **Layer 2 (strategy evaluation)** — the arbitrary per-model logic. Present in the backtester and live;
  the visual workbench can run Layer 1 alone (just charting indicators) or overlay a strategy's would-be
  signals for visualisation.

---

## 5. The three consumers of the foundation

All three sit on Layers 1–2 + the clock/event-source; each drives time differently.

1. **Live engine.** `LiveEventSource` (Lightstreamer callbacks → the playbook's queue discipline: bars
   unbounded/never-dropped, ticks bounded/shed-oldest, drain bars-then-ticks) → step, per event, real
   clock. Owns all the resilience machinery (§6 table); the engine never sees it.
2. **Headless backtester.** `run(model, range)` → replays the whole range start→finish, sim clock set per
   event, Layer 2 evaluated, produces a deterministic **decision trace + performance metrics**. No seek,
   no skip — it just runs. *This is where "fast-forward" was a category error.*
3. **Visual research workbench** (`visual-research-workbench.md`). A human scrubs a playhead: **`seek(t)`**
   bulk-computes Layer 1 to `t`; **`step()`** advances slowly; **play** runs at an adjustable speed.
   Indicators overlaid/tuned live. "Skip fast, step slow" is this tool's control surface — and "missing
   a trade on a jump" is a non-concern, because it's a viewer and you can scrub back.

The **only extra requirement** the workbench places on the foundation vs the backtester: the replay
source / series store must support **`seek(Instant)`** and **`step()`/`stepBack()`** in addition to
run-to-completion. No wake-predicate machinery — the human decides where to stop.

---

## 6. Core abstractions & per-mode behaviour (sketch — illustrative)

Event-time (exchange UTC stamp, the ordering key) vs processing-time (when the engine sees it).

```java
interface MarketEvent { Instant eventTime(); EventKind kind(); /* payload */ }

interface EventSource {
    MarketEvent next();               // live: blocks on pushed event/timer; replay: next in order
    boolean hasNext();
    void seek(Instant t);             // replay only: bulk-advance Layer 1 to t (workbench)
    void stepBack();                  // replay only: retreat one point (workbench)
}

interface Clock { Instant now(); long monotonicNanos(); }
interface TimeService extends Clock {
    void sleep(Duration d);                       // live: real; replay: advance sim-time
    Cancellable scheduleAt(Instant t, Runnable r);
}

interface ModelSpec {                 // §2a — the runtime assumes nothing about a model
    Set<InputSpec> inputs();          // which series, what resolution, which epics
    Map<InputSpec,Integer> warmup();  // look-back each input needs before it's valid
    ModelState onEvaluate(ModelState s, SeriesView series, OrderSurface orders); // arbitrary Layer 2
}
```

| Subsystem | Live | Headless replay | Interactive replay |
|---|---|---|---|
| Trade/stream **windows** | pure fn of event-time | *identical* | *identical* |
| **Staleness watchdog** | active | disabled (data complete/explicit) | disabled |
| **Reconnect/backoff** | active | n/a (simulator may inject synthetic disconnects) | n/a |
| **Timers / daily jobs** | real | scheduled on sim timeline | driven by the playhead |
| **"Now" reads** | system clock | current event time | playhead time |
| **Series (Layer 1)** | step, per event | step, per event | step or seek — identical |

Most subsystems become pure functions of injected time and stop caring which mode they're in; the
live-only ones (watchdog, reconnect) are isolated behind the `LiveEventSource`, not sprinkled through the
engine.

---

## 7. Determinism & parity (the foundation must be trustworthy)

The engine must be a deterministic function of (events, config, seed):

- **No wall-clock reads outside the live `TimeService`** (enforceable with an ArchUnit rule).
- **No unseeded randomness.**
- **Deterministic event ordering**, incl. a defined **tie-break** when a tick and a bar-close share a
  millisecond (must match how the live queue drain interleaves them).
- **`seek` ≡ `step`:** seeking to `t` then reading the series must equal stepping from the start to `t`.
  This is what lets the workbench be trusted and constrains the indicator library to expose incremental
  *and* bulk computation that agree.

**Parity harness (built alongside, not after):**
1. **Record once, replay two ways** — capture a live session (events + decision trace); replay through
   the headless backtester; assert a byte-identical trace. Divergence = a determinism leak.
2. **`seek` ≡ full replay** — assert the series at `seek(t)` equals the series after stepping to `t`.

---

## 8. Open questions (raw)

- **Tick/bar tie-break** at equal millisecond — canonical order matching the live drain.
- **Forming/partial bars.** Live CHART emits a forming candle until `CONS_END`; the lake stores completed
  bars. Does any consumer need forming bars (the workbench, to animate a bar building)? Affects storage.
- **Data-lake shape.** What makes "all bars for span X" *and* "all ticks in window [a,b]" both cheap?
  (Columnar, partitioned by day+epic?) `seek` and tick-rendering lean on these queries.
- **Warm-up prepend.** Bounded look-back per input (from `warmup()`) prepended before a span/seek target
  so indicators are valid there.
- **Latency modelling** in headless backtest (fixed vs zero) — matters for tick-triggered fills.
- **Simulator interleaving.** The IG simulator is both an `EventSource` (fills/order events) and a
  consumer (receives orders); emissions must interleave deterministically with market events.
- **Multi-timeframe consistency** — a model using 1m + 10m: `seek`/replay must keep the derived 10m
  exactly consistent with its 1m source (the prototype's 1m→10m chain, generalised).

---

## 9. Leaning (provisional)

- Adopt `Clock` + `TimeService` + `EventSource` platform-wide **from commit #1**; ban direct wall-clock
  reads; inject time and events everywhere.
- **Two layers:** Layer 1 (series store, always current, `step` ≡ `seek`) beneath Layer 2 (arbitrary
  per-model evaluation). Never let a model's shape leak into the runtime (§2a).
- **Three consumers, one foundation:** live (push, step), headless backtester (pull, run-to-completion),
  visual workbench (human-driven `seek`/`step`/play). "Skip fast, step slow" belongs to the workbench.
- The foundation exposes `seek(Instant)` + `step()`/`stepBack()` on top of run-to-completion; **no
  automated wake-predicate machinery** — the only skipper is a human.
- Build the **parity harness** (record-replay + `seek`≡`step`) alongside the abstractions.

---

### Related

- `visual-research-workbench.md` — the human-driven consumer that owns "skip fast, step slow" (`seek` +
  `step`). This doc owns the *foundation*; that one owns the *exploration* use case.
- `../ig-broker-playbook.md` — §2.4 (callback/queue discipline the live source inherits), §3.4
  (injectable-clock watchdog — proof the pattern works).
- `../README.md` — the maturation pipeline and design backlog.
- Future docs: **strategy DSL**, **indicator library** (must satisfy `seek` ≡ `step`), **IG simulator**,
  **data-lake shape**.
- Prototype: `docs/signal-engine/data-plane.md` (the series store this generalises),
  `docs/signal-engine/p1-strategy.md` (ONE model's shape — explicitly *not* the template).
