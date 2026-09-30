# Chapter 3 — Buffers, queues and backpressure

What happens between the callback returning and the row existing — and what gets sacrificed
when the consumer can't keep up.

## The jargon

- **Queue** — first-in-first-out hand-off between a producer thread and a consumer thread.
- **Bounded / unbounded** — a bounded queue has a capacity and forces an overflow decision;
  an unbounded queue grows until memory does the deciding for you.
- **Backpressure** — the consumer's slowness propagating backwards to slow the producer.
  Sometimes a feature (a file copy *should* read no faster than it writes); here, a hazard.
- **Overflow policy** — what a full bounded queue does with the next offer:
  - **block the producer** — correct when the producer may wait. Ours may not (chapter 2).
  - **drop-newest** — cheap, but under a stall you keep an ever-*staler* window. Wrong for
    prices.
  - **shed-oldest** — evict the oldest to admit the newest. The buffer always holds the most
    recent window. Right for prices, *if the loss is counted*.

## The concept

Chapter 2 ended with "put it on a queue and return". But one queue would force one policy
onto data of very different worth, so the design starts from a criticality question: *what
does each stream cost to lose?*

- **A sealed 1m bar** is the backbone of the record — and it is **recoverable**: IG's REST
  API serves historical 1m candles, which is exactly what T6's end-of-day heal does. But
  heals spend a metered allowance (playbook §4.3), so bars are treated as must-persist:
  losing one in our own plumbing, having received it for free on the stream, is
  self-inflicted damage. At ~1/minute, "unbounded" is a rounding error of memory.
- **A tick** is **unrecoverable** — no REST endpoint replays the tick stream. If ticks
  outrun the consumer, something must give, and the only real choice is *which* ticks. For
  live prices the freshest matter most, and the tick record's precision role (D15: the
  precision path; own 1m aggregation as verification oracle) degrades gracefully with a
  bounded gap while it would degrade catastrophically with a stale-forever window. So:
  shed-oldest, **counted, never silent** — P10, surface indicators.
- **Backpressure must never reach the socket.** The producer is the LS thread; blocking it is
  stalling the session (chapter 2). IG must never see a slow consumer. Every policy above is
  chosen so that `offer` returns immediately, whatever state the consumer is in.

## Our code path

`market-data-service/src/main/java/dev/amfshr/tradebench/marketdata/ingest/Buffers.java` —
one class, three queues, four counters. It *implements* `StreamEvents`, so it plugs straight
in as the stream's consumer:

| Lane | Structure | Policy |
|---|---|---|
| bars | `LinkedBlockingQueue<Bar1m>` (unbounded) | never dropped; removed only after the store accepts (`peekBarNow`/`removeBarNow`) |
| ticks | `ArrayBlockingQueue<Tick>` (`DEFAULT_TICK_CAPACITY = 100_000`) | shed-oldest: `while (!offer) { poll → droppedTicks++ }` |
| state changes | `ConcurrentLinkedQueue<StateChange>` (unbounded) | never dropped; rare by construction; same `peek`/`remove` ack discipline as bars |

The shed-oldest loop is worth reading twice: `offer` fails only when full, so we `poll` one
victim (counting it) and retry. Between our poll and our offer *the pump may also have
polled* — that's why it's a loop, not an if, and why the count increments only when the
victim actually existed. No lock, no blocking, correct under the race.

Capacity arithmetic: at full DAX rate (~20 ticks/s) 100k ticks is **~83 minutes** of buffer
(playbook §2.4) — a consumer stall long enough to shed is already a T5 incident, not a blip.

**State changes and the first-flag rule** (`Buffers.onTick`): every tick carries `DLG_FLAG`
(market state — `DEAL`, `CLOSED`, `EDIT`, …), but storing it per tick would be noise, and
letting it cross into the domain `Tick` would couple price to state (the `StreamAdapter`
ruling: `DLG_FLAG` never crosses). Instead `lastDealFlag.put(epic, flag)` returns the
previous value, and only a *change* enqueues a `StateChange(epic, atUtc, dealFlag)`. On the
very first tick `previous` is `null` — which never equals the flag, so **the session's first
observed state is itself recorded as a change**. Deliberate: after every restart the record
answers "what state was the market in when capture began?" without needing a special case.

Ticks cross into the domain immediately (`StreamAdapter.toDomain`, on the LS thread — record
allocation is within the cheap-work budget), so everything past the queues speaks `core`
domain language, never IG shapes.

**Why bars offer `peek`/`remove` but ticks only `poll`:** the bar lane is part of chapter
4's ack-after-apply chain — a bar must leave the queue only *after* the store accepted it,
so the pump peeks, writes, then removes. Ticks are best-effort by declared policy; `poll` —
take it and own it — is the honest verb. The asymmetry in the API is the criticality split,
visible in the method names.

**Honest counters.** Four `AtomicLong`s (`tickCount`, `barCount`, `droppedTicks`,
`malformedUpdates`) plus the pump's `writtenCount` feed the heartbeat line
(`app/Main.summary`):

```
ticks=22415 bars=141 written=22556 dropped=0 malformed=0
```

`ticks=`/`bars=` count *arrivals* (callback-side); `written=` is **sink-side truth** — events
the store actually accepted. The gap between them is the pipeline's in-flight/lost window,
and `dropped=`/`malformed=` name the losses. A heartbeat that reported enqueues as if they
were persistence would be the plumbing grading its own homework.

## The scars

- **The counted-shed doctrine is a direct playbook port** (§2.4) — the queue design arrived
  in this repo as settled rules, reasoning baked in: an unbounded tick queue merely converts
  a consumer stall into an eventual OOM kill, and *uncounted* shedding is indistinguishable
  from a healthy quiet market. The counter is the difference between "degraded, and telling
  you" and "lying".
- **Bars-unbounded earns its keep at heal time**: T6's healer exists because bars are
  recoverable — but every self-inflicted bar loss becomes a REST heal charged against a
  metered weekly allowance (playbook §4.3). The unbounded queue is cheaper than the heal.
- **The first-flag rule** exists for the restart case: without it, a capture started into a
  `CLOSED` market would record no state at all until the market next changed — and the T5
  watchdog (chapter 8), which stands down on `CLOSED`/`SUSPEND`, would have nothing to stand
  down *on*.

*Previous: [Chapter 2 — Threads and the callback boundary](02-threads-and-the-callback-boundary.md) · [Field Manual index](README.md) · Next: [Chapter 4 — The pump and ack-after-apply](04-the-pump-and-ack-after-apply.md)*
