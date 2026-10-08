# Chapter 7 — Clocks: wall time, monotonic time, and what sleep does to both

Part of the Tradebench Field Manual. This chapter explains why the platform carries **two**
clocks everywhere, why business logic is forbidden from reading either one directly, and
how comparing the two detects host sleep and process freezes — the trick the staleness
watchdog's correctness rests on.

## The jargon

- **Wall time** — "what time is it?" A date-and-time reading, e.g. `2026-09-28T17:02:00Z`.
  In Java: `System.currentTimeMillis()` / `Instant.now()`.
- **Monotonic time** — "how long has it been?" A counter that only moves forward, with no
  meaning as a date. In Java: `System.nanoTime()`.
- **NTP (Network Time Protocol)** — the daemon that keeps your machine's wall clock
  agreeing with the world, by *slewing* (speeding/slowing it gently) or *stepping*
  (yanking it, forwards or backwards).
- **Leap smearing** — spreading a leap second across many hours of slightly-long seconds
  instead of inserting `23:59:60`. Another way wall seconds are not all the same length.
- **Awake time** — what monotonic clocks actually count on most platforms (macOS and
  Linux `CLOCK_MONOTONIC` included): nanoseconds *while the machine is awake*. Close the
  lid and the counter pauses with it.

## The concept

Wall time can jump **both ways**. NTP steps it, an admin sets it, daylight-saving shifts
the local rendering, a VM migration teleports it. Any elapsed-time computation done by
subtracting two wall readings — `deadline = now + 90s`, `stale = now - last > 90s` — is a
latent bug: a backwards step makes timeouts immortal; a forwards step fires them all at
once.

Monotonic time never goes backwards, which makes it the *only* legitimate input to
elapsed-time maths. Its price is that it means nothing as a date and — the subtle part —
it **pauses while the host is suspended** — a paused or live-migrated VM, a hibernated
instance, a frozen process. Sixteen minutes of suspension is sixteen minutes of wall time and
roughly *zero* monotonic time.

That asymmetry is not a nuisance. It is a **sensor**. Sample both clocks on a fixed
cadence and compare the deltas between consecutive rounds:

```
                 wall delta      monotonic delta     verdict
normal round        ~1s               ~1s            healthy
host slept          16min             ~1s            wall ran ahead → the HOST slept
process froze       ~11s              ~11s           both jumped → the PROCESS stalled
                                                     (GC pause, debugger, cgroup throttle,
                                                      an overloaded box)
```

Wall ahead of monotonic by more than a threshold ⇒ the machine was suspended (monotonic
paused, wall didn't). Monotonic itself jumping ⇒ the process wasn't scheduled — a long GC
pause, a breakpoint, CPU starvation. Two clocks, one subtraction, and you can tell three
states of the world apart. No OS callbacks, no platform-specific sleep notifications.

Why care? Because a supervisor measuring *silence* must never confuse "the feed died"
with "I wasn't awake to listen". Fire a resubscribe the instant the host resumes and you are
remedying an outage that never happened — possibly into a session that is mid-recovery.

## Our code path

The SPI is two methods, deliberately not one
(`core/src/main/java/dev/amfshr/tradebench/core/time/Clock.java`):

```java
Instant wallInstant();    // timestamps and reporting — never elapsed-time maths
long monotonicNanos();    // elapsed time, staleness, pacing — meaningless as a date
```

`SystemClock` is *the only place the platform reads real clocks*. Everything else takes
the reading as an argument or holds a `LongSupplier` seam — `RequestPacer` and
`LoginRateGate` take `clock::monotonicNanos`; `Pump` sleeps through an injectable
`Sleeper` (`ig-client/.../time/Sleeper.java`, `Sleeper.SYSTEM` in production); the
resilience cores take plain `long monotonicNanos` / `long wallMillis` parameters.

The discriminator lives in `StalenessWatchdog.clockAnomaly(...)`
(`market-data-service/.../supervise/StalenessWatchdog.java`), fed both clocks each ~1s
round by the future Supervisor:

```java
boolean hostSlept = wallDeltaMs - monoDeltaMs > tuning.hostSleepSkew().toMillis(); // >5s
boolean froze     = monoDeltaMs > tuning.processFreezeJump().toMillis();           // >10s
```

On either anomaly, `evaluate(...)` calls `rebaseline(...)` — every market's
`lastTick`/`lastBar` stopwatch is reset to *now* — and the round is skipped entirely.
Silence is measured from the wake, not from before the sleep: the watchdog can never fire
*into* a recovery (§3.4 of the playbook, carried whole). The thresholds live in
`Tuning.playbook()` with the rest of the §8 numbers.

Because everything is injected, the tests drive time as plain longs.
`StalenessWatchdogTest.hostSleepSkipsTheRoundAndRebaselines` replays a 16-minute suspend as a
wall clock 960 seconds ahead of monotonic; `processFreezeSkipsTheRoundAndRebaselines`
jumps monotonic by 11s. Both assert the skipped round *and* that a full fresh 90s window
after the anomaly does fire. The whole suite — 13 outage-shaped scenarios — runs in
milliseconds, deterministically, with no `Thread.sleep` anywhere. That is the entire
payoff of the injectable-clock doctrine: timing logic tested at the speed of arithmetic.

## The scars

> **Scar** — **"0.4s offline"** · a 16-minute gap, mis-measured
>
> The prototype — then a server running on a laptop — logged a sixteen-minute suspend as
> **"0.4s offline"**: it measured the gap on the monotonic clock, which had slept along with the
> host. The lesson is about any suspended host; a deployed service meets it as a paused VM or
> a hibernated instance, never as a lid. Awake-outage:
> 0.4s. Wall-outage: ~16 minutes of missing market data.
>
> **Lesson.** Wall time for stamps, monotonic for spans, and the *pair* for anomaly detection.
> `ReconnectClassifier` now reports `wallOutage`, `awakeOutage`, and `hostSleptDuring` side by side.

The complementary hazard is hypothetical only because the doctrine forbids it: a watchdog
measuring staleness against wall time declares every market dead the moment the host
resumes — one wake-up away from a remedy storm. Hence the standing convention (tech-notes;
playbook §3.3–§3.4):
**wall time for stamps, monotonic for spans, and the pair for anomaly detection.**

One forward pointer: the same discipline is why the DSL can promise determinism. Under D9,
backtest and live evaluation advance on **data events** — the event clock — never on wall
time; a strategy that says "90 seconds" means 90 seconds of *market data time*, replayable
bit-for-bit. The capture side's clock hygiene is what keeps that promise honest.