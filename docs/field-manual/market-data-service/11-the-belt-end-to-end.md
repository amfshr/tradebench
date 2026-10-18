# Chapter 11 — The resilience belt, end to end: how it actually works

Chapter 08 is the belt's reference — every core, every scar, every ticket number. Chapter 10 is
the playbook — a failure, what we do, what it costs. This chapter is the walk-through: what the
running service is doing from one second to the next, told once, in order, with the numbers. Read
it before the first deploy, and keep its last section open in the evenings until the operator
console (E9) takes the watching over. Everything here is true of the code as merged on 2026-10-11
(E1-T13, PR #25); where a detail has its own chapter, the chapter is named.

## 1. What the belt is for

The stream lies by omission. A Lightstreamer socket can stay open while the server stops
talking (the 2h56m scar); the SDK can give up reconnecting without saying so; one subscribed item
can die while its neighbours stream on; the host can be suspended; the database can restart under
us. None of those raises an exception. So the belt watches the one thing that cannot lie —
**whether data is arriving** — and when it stops, acts with the smallest remedy that could help,
climbs to larger ones if that does not, and when nothing helps, stops **loud** (P9): a written
reason, an exit code, and a process supervisor that restarts us.

Three ideas carry the whole design:

1. **One streaming rule.** A connection is "up" only when its status is `CONNECTED:*` other than
   the `STREAM-SENSING` handshake (`ReconnectClassifier.isStreaming`, D27 (3)). Every core uses
   that one definition: the watchdog stands down while the connection is not streaming, the
   classifier closes an outage when it is, the escalator's stopwatch runs until it is.
2. **Decisions on one thread, in memory, on injectable clocks.** Nothing is decided where the
   SDK calls us. Everything the stream says becomes an *observation* in a queue, and one thread —
   the supervisor's **sweep**, once a second — drains it and decides. The database is downstream of
   every decision, never upstream.
3. **Bounded everything.** Every wait has a number, every ladder has a cap, every outage has a
   budget. A remedy that keeps failing ends in `FEED_DEAD`, never in a quiet loop.

## 2. The cast

Six threads of ours (foundations chapter 02) and the SDK's pool:

| Thread | What it does for the belt |
|---|---|
| LS SDK threads | deliver every tick, candle, status change and subscription outcome. Our callbacks do microseconds of work: a tick is parsed and queued (`Buffers`), a status or subscription outcome is stamped with its connection's *generation* and queued for the sweep. Nothing is decided here. |
| `capture-supervisor` | the **sweep**, every second (`CaptureAssembly.SWEEP_INTERVAL`): drains the observations, runs every detector, applies remedies, writes the reason rows. The whole belt runs here. |
| `capture-pump` | drains ticks and bars into the store (chapter 04). Not part of the belt, but the watchdog reads its last-arrival clocks, and the pump's own hold-and-retry (D28) is the database side of the same philosophy. |
| `capture-events` | writes the sweep's event rows to Postgres from a bounded queue, so the sweep never waits on the database (D29 (3)). |
| `main` | the **heartbeat**, every 60s: checks the pump and the supervisor are alive, re-verifies the single-instance lock, publishes `capture_status`, prints the summary line. |
| `capture-shutdown` / `capture-exit` | the orderly close on a stop; the one-shot `exit(1)` on a fatal. |

The **generation gate** sits between the SDK and the queue: every connection `IgStreamControl`
opens gets a number, and once a connection is superseded by a rebuild nothing it says reaches the
sweep. A torn-down connection's farewell cannot open a phantom outage; a dead item's late
rejection cannot strike the new session (E1-T10 #11).

## 3. A sweep, step by step

`Supervisor.sweep()` runs the same eight steps every second, in this order, each for a reason.

1. **Drain the observations.** For each queued item, oldest first:
   - a **status change** is written raw as a `connection_status` row (the record a future replay
     is cut from, E1-T12), then fed to the reconnect classifier (a `reconnect` row when streaming
     resumes after a drop, a `transport_downgraded` row when the SDK falls back to polling), to
     the stuck-substate escalator, and to the watchdog as "streaming" or "not streaming". A bare
     `DISCONNECTED` — the client has given up for good — **latches a death**.
   - a **server error** writes an `ig_api_error` row and latches the same death (one death, one
     rebuild, however many callbacks announce it).
   - a **subscription confirmed** tells the witness core that market's leg is confirmed.
   - a **subscription rejected** is judged by the witness core at once (§5.4): retry the pair,
     quarantine the market, rebuild the session, or wait for a witness.
   If anything in the drain made the belt give up, the sweep ends here — nothing after a give-up
   may act (#13).
2. **Render held verdicts.** A rejection whose verdict was waiting for a witness is rendered the
   moment that witness confirms or its 30s window lapses. The first `Rebuild` rendered resets every
   held verdict, so the loop stops at the first it cannot find (two markets dying together are
   one rebuild, not a crash — E1-T13).
3. **The budget.** If this outage's first rebuild was 10 minutes ago (`giveUpAfter`) and streaming
   has not resumed, give up — `FEED_DEAD{reason: budget}`. Checked here, after the drain (a queued
   resume resets it first) and before any detector, so it never depends on how often one re-fires.
4. **The death latch.** A latched death asks for a rebuild. If the pacing floor refuses it this
   second, it asks again next second — a death never waits for a detector.
5. **The latched verdict.** A `Rebuild` verdict the pacing refused earlier is re-asked the same
   way (the cores consume a verdict as they render it, so nothing else would — E1-T13).
6. **The stream-down re-ask.** A rebuild whose connect *failed* left no connection to report
   anything, so the sweep keeps asking, paced, until one connects.
7. **The escalator.** If the connection has not streamed for its threshold (§5.2), rebuild.
8. **Staleness.** The watchdog evaluates every market's clocks (§5.1) and returns remedies —
   a surgical resubscribe for one market, or a session rebuild.

Any rebuild that actually runs clears *every* latch (steps 4, 5, 6): whatever it was asked for,
it supersedes the connection the others described. Nothing in the sweep waits on the database:
event writes go onto the bounded queue the writer thread drains.

## 4. The remedies and their pacing

There are three remedies, and the belt always reaches for the smallest that could work.

**Resubscribe one market** (`stream.resubscribe(epic)`): drop that market's PRICE+CHART pair and
subscribe it afresh on the same connection. Free — no login, no reconnect — so it is the
watchdog's first and second answer to a silent market, and the witness core's answer to a
rejected leg (a "retry"). It opens a fresh 30s confirm window for the pair.

**Rebuild the session** (`stream.rebuild()`): close the stream, re-establish the IG session,
reconnect, resubscribe every market that is not quarantined. This is a **login**, and logins are
what IG throttles, so rebuilds are paced three ways:

| Pacing | Number | Why |
|---|---|---|
| the login gate | ≥ 61s between logins (`LoginRateGate`) | IG's own rule; a rebuild inside the gate blocks until it opens, so a "rebuild now" can take a minute |
| the backoff | 1s · 2^(n−1), jitter ±50%, **5s floor**, **60s cap** — in practice 5, 5, 5, 8, 16, 32, 60, 60 … | spaces the ladder so a re-login storm never trips the throttle; the floor clears HikariCP's alive-bypass window too |
| the ceiling | **10** rebuilds that *connected* and never streamed → `FEED_DEAD{reason: ceiling}` | a server that accepts logins and streams nothing is dead; a rebuild that could not even connect does not count — that outage ends by the budget, the clock |

A rebuild's reason row (`watchdog_stale`, `subscription_rejected`, `connection_dead`,
`stuck_substate_escalated`) is written only when the rebuild actually proceeds, so the record is a
faithful list of rebuilds that happened. A rebuild "paced out" by the floor is not lost: its latch
asks again next second (§3). The moment the new connection streams, the ladder resets —
`consecutiveFailures` to zero, the budget clock stopped — and a `reconnect` row closes the outage
with its wall-clock and awake-clock lengths and whether the server replayed what we missed.

**Give up** (`FEED_DEAD`): one row with its reason — `budget`, `ceiling`, `fatal_config` (IG
rejected the credentials or account: never climb a ladder against a lockout), or `fatal` (an
exception on the sweep thread) — then the sweep stops and `Main` exits 1 from its own thread. The
process supervisor restarts us; the restart counter and the row are the evidence. The belt does
not try forever by design: "a deliberate stop with a written record, not a crash loop".

## 5. The detectors, each with its clock

### 5.1 The staleness watchdog — is the data arriving?

The watchdog never looks at the connection's status. It looks at **when each market's last tick
and last sealed candle arrived** (monotonic clock, fed by the pump's `Buffers` only when the value
advances), and at the market's **deal flag** (`DLG_FLAG`: `DEAL` means open; `CLOSED`, `SUSPEND`,
`EDIT`, `AUCTION` … mean the market itself is quiet).

| Signal | Rule | Default |
|---|---|---|
| **tick silence** | no tick for `tickSilent` while the market's flag says open | 90s — in-window DAX runs ~90 ticks a minute, so 90 seconds of nothing is never the market |
| **bar silence** | ticks flowing but no sealed 1m candle for `barSilentWhileTicksFlow` | 210s — a dead CHART leg while the PRICE leg lives (three candles missed) |
| **stand-down** | the market's last flag is not dealing (`DealFlag`: CLOSED, CALL, EDIT, SUSPEND, the auctions — E1-T15): no verdict, however quiet | bounded by one *teaching* resubscribe per 12h (`standDownTeach`), whose snapshot re-teaches the flag, so a dead item cannot hide behind a weekend (D29 (1)) |
| **not streaming** | the connection is not streaming (§1): no verdict at all | the silence is the connection's — the escalator's and the death latch's domain |

A market that goes silent opens an **episode**, and the episode climbs a ladder:

```
silence from T      T+90   RESUBSCRIBE  (grace 60s, doubled → 120)
                    T+210  RESUBSCRIBE  (→ 240)       the two surgical tries (watchdogMaxResubscribes)
                    T+450  REBUILD      (→ 480)       then the session
                    T+930  REBUILD      (→ 960)  …    doubling to the 30-minute cap (watchdogGraceCap)
```

Each rung writes a `watchdog_stale` row naming the market, the action and the signal. The episode
**survives the rebuild it earned** (E1-T13): when the new session streams and the market is still
dead, it waits its doubled grace instead of starting again at 90s — one dead item costs a login
every 7.5 minutes, then 15, then 30, not every 7.5 forever. The episode **closes on data of the
kind that convicted it**: a tick heals tick silence, a candle heals bar silence; the other kind
does not, because a half-dead pair is exactly what the signal exists to catch. A healing market
restarts its CHART clock with its ticks (no candle was owed while it was gone), and a market
leaving its stand-down starts its CHART clock at the opening.

**Two or more markets stale in the same round is never market noise.** One `REBUILD` for the
session, not N surgeries — however far apart they crossed their thresholds. A lone stale market
whose neighbour is one sweep from its own threshold waits that sweep, so two feeds dying 584ms
apart earn one verdict (E1-T10 #33). The session verdict has its own ladder, **time-decayed**:
the gaps run 120 → 240 → 480 → 600s (`sessionGraceCap`, 10 minutes) and nothing the server
sends resets it — not a connection that reports streaming, not a tick, not a candle. The rung
stands until the grace *and* a whole cap have passed with no verdict, then it is forgotten
(E1-T13 ruling 1). So a session that answers every login and sends nothing costs a login every
10 minutes at the limit, and a thin-but-open pair converges to one rebuild per cap instead of one
per joint silence. The 2026-08-04 scar replayed through this ladder costs rebuilds at 149, 269,
509, 989 and 1589s instead of one every 90s for 2h56m.

**Clock anomalies.** Each round the watchdog compares the wall clock's advance with the monotonic
clock's. A wall jump of more than 5s against monotonic (`hostSleepSkew`) means the host was
suspended; a monotonic jump of more than 10s between rounds (`processFreezeJump`) means the process
itself was frozen. Either way the round is skipped and every stopwatch is re-baselined (a fresh
90s window after the anomaly) — the market was not silent, the machine was (foundations chapter
07). No row is written for the anomaly itself; the `reconnect` row that closes the drop the sleep
caused carries `hostSlept=true`, which is how a suspend is told apart from an outage in the record.

### 5.2 The stuck-substate escalator — is the connection going anywhere?

When the SDK drops the connection it reports `DISCONNECTED:WILL-RETRY` (it will try again; the
server has abandoned any replay) or `DISCONNECTED:TRYING-RECOVERY` (a lossless replay may still
complete). Then it reports `CONNECTING`, `CONNECTED:STREAM-SENSING`, and finally
`CONNECTED:WS-STREAMING` when data flows again. Or it does not: the 2h56m scar sat in
`WILL-RETRY` for hours, and a stale endpoint cycles `WILL-RETRY → CONNECTING → WILL-RETRY` every
few seconds forever.

The escalator measures **time not streaming**: its stopwatch starts at the first status that is
not streaming and runs across every non-streaming status until one streams (E1-T13 ruling 2). The
threshold follows the retry substate most recently seen — **120s** after `WILL-RETRY` (every waited
second is pure loss), **300s** after `TRYING-RECOVERY` (waiting may win data back), and 120s for a
connection that never reached either (a handshake that hangs at boot). Past the threshold:
rebuild, `stuck_substate_escalated` row, paced by the backoff. A bare `DISCONNECTED` clears the
stopwatch — the death latch owns that path.

### 5.3 The death latch — the client gave up

A bare `DISCONNECTED` (no substate) means the client will never try again; IG's refusal follows it
as a server error. Nothing else would ever escalate it (no substate to time, and the watchdog
stands down on closed markets), so the Supervisor **rebuilds at once**, paced by the backoff and
the login gate, and keeps asking every second until the rebuild runs. One `connection_dead` row,
dated from the first announcement.

### 5.4 The witness core — is it the market or the session?

A subscription can be rejected: a mistyped epic, a market the account cannot see, a session-wide
fault. The question is blast radius — isolate one market, or rebuild everything? The core counts
**strikes per attempt** (a pair's two legs are one strike; a leg of a pair already replaced is
none, so a flapping error code cannot reset the count):

```
strike 1  → RETRY   re-ask the pair surgically, fresh confirm window
strike 2  → RETRY
strike 3  → the verdict:
            another market's pair fully confirmed  → QUARANTINE this market   (market-shaped)
            no other market confirmed, none pending → REBUILD the session     (session-shaped)
            a would-be witness inside its 30s window → WAIT, render when it confirms or lapses
```

Quarantine (`market_quarantined`) unsubscribes the pair, stops watching the market and ignores its
later rejections; **exit is restart-only** — a configuration fault does not heal itself. A refused
unsubscribe would double-deliver every update, so it always rebuilds. With one market there can be
no witness, so the last market always rebuilds — it dies loud, never quarantined into a silently
idle service. A rebuild resets the strikes (the new session is judged afresh); quarantine persists.

### 5.5 The reconnect classifier — what did the drop cost?

Not a detector but the historian. It watches the statuses for the shape of an outage: from the
first `DISCONNECTED:*` to the first streaming status, on both clocks. The `reconnect` row carries
`replayed` (no `WILL-RETRY` was seen, so the server replayed what we missed — nothing to heal),
`wallOutageMs`, `awakeOutageMs` and `hostSlept`. A transport downgrade (websocket to HTTP or
polling) is `transport_downgraded`. The heartbeat's `reconnects_total` counts the real resumes;
the boot's first streaming status is not one.

## 6. The database side, briefly

The belt decides in memory; the database records. Three write tiers (D27, D28, chapter 10):

- **Tier 1, the product** — ticks and bars. A retryable failure (connection, operator
  intervention, resources — the blip taxonomy) **holds**: bars stay queued, the tick batch is kept,
  the pump backs off 5s → 60s and asks the store to recover until it does, with **no time budget**
  (a restart would drop what the hold protects). Anything not known-transient stops the pump and the
  process exits 1 at once. One `sink_failure` row per episode, written when the database is back.
- **Tier 2, observability** — events, gaps, status. Best effort, counted, continue.
- **Tier 3, decisions** — none. The database is never read to decide.

The **single-instance lock** (foundations chapter 06) rides its own session, and a Postgres restart
the sink holds through ends that session. So the heartbeat re-verifies it: a live session still
holds the lock; a dead one is replaced by a fresh session that takes it again (`advisory lock
re-acquired`); a refusal means another instance took it meanwhile and we exit loud; while the
database is unreachable the lock is *unverifiable* — reported each minute and asked again, never an
exit, because nobody can take a lock from a database that is down (E1-T13, D28).

## 7. A day in the life

**Boot.** `Main` reads the environment, migrates the schema, takes the lock, composes the pipeline,
and only then logs into IG and connects. The SDK reports `CONNECTING → CONNECTED:STREAM-SENSING →
CONNECTED:WS-STREAMING` within a few seconds; the escalator's stopwatch starts at the first and
clears at the last. Both legs of every market subscribe and confirm; each market's first PRICE
snapshot teaches its deal flag. From here the watchdog's clocks run, and the console shows a
`belt CONNECTION_STATUS {"status":"CONNECTED:WS-STREAMING"}` line for each transition.

**An ordinary blip (most of what you will see).** The socket drops: `DISCONNECTED:WILL-RETRY`,
then `CONNECTING`, then streaming again eight seconds later. The watchdog stood down for those eight
seconds; the escalator's stopwatch started and cleared; the classifier writes one `reconnect` row
with `replayed=false, wallOutageMs≈8000` (a `WILL-RETRY` was seen, so the server did not replay —
those eight seconds of ticks are gone, the bars will be healed by T6). No remedy ran. The heartbeat's
`reconnects_total` goes up by one.

**Silent while connected — the scar.** The server stops sending at `T` but the socket stays up
and the flag says `DEAL`. `T+90`: `watchdog_stale {action: resubscribe, signal: TICK_SILENT}` for
DAX, the pair re-asked for. Nothing. `T+210`: the second surgery. Nothing. `T+450`: the market's
own `REBUILD` — a login (gated), a new connection, every market resubscribed; the rebuilt session
streams, and DAX's episode is kept with its 480s grace. If DAX is still dead at `T+930` the next
rebuild runs; the rungs stretch to 30 minutes. With two markets both silent, the session verdict
fires instead and its own ladder runs 120, 240, 480, 600 … With the 2h56m scar replayed, that is
about eighteen logins over the whole outage instead of a hundred and seventeen — and the belt never
gives up a feed that may still return, because streaming keeps resuming and resetting the budget.

**One dead item, live neighbours.** NASDAQ confirms both legs; DAX is rejected three attempts
running. Verdict: quarantine DAX (`market_quarantined`), unsubscribe its pair, stream on.
`capture_status` shows DAX `quarantined`; it stays so until the next restart.

**Every market rejected (a configuration fault).** Each market's verdict waits on the other's
window; both lapse together: one `subscription_rejected` row and one rebuild. The new session
rejects them again, and the next verdict follows 32s on, then the next — one login per verdict,
spaced by the gate. Whether the belt ever gives up depends on what the SDK says about a session
with no live subscription: if it reports the connection as *not* streaming (as the harness models
it), ten such rebuilds meet the ceiling — `FEED_DEAD{reason: ceiling}`, exit 1; if it reports
`WS-STREAMING` for the bare session, every rebuild resets the ladder and the belt re-logs in about
once a minute until the configuration is fixed — loud in the record (a `subscription_rejected` row
per rebuild, the markets never `connected_streaming` in `capture_status`), but no exit. The record
has not captured a real one yet; the first will settle it, and a "streams but confirms nothing for
N minutes" give-up is the gap to close if it goes the second way (backlog). Either way: fix the
configuration, restart.

**The client gives up.** A bare `DISCONNECTED`: `connection_status`, `connection_dead`, a rebuild
as soon as the login gate allows. The new connection streams: `reconnect`, ladder reset.

**IG unreachable for good.** Every rebuild fails to connect; the sweep re-asks paced 5, 5, 5, 8,
16, 32, 60 … seconds; none counts toward the ceiling; ten minutes after the first,
`FEED_DEAD{reason: budget}`, exit 1, restart, and the next process boots into the same wall and
exits again — a slow, visible loop, one login a minute at most, until IG returns.

**A database restart.** The sink's write fails with a connection-class error: the pump **holds** —
`sink unavailable — holding N queued writes` on the console, then `sink retry 1 in 5s`,
`sinkFailures=1` on the heartbeat,
`db_pending` climbing in `capture_status` (which itself goes stale, since it cannot be written
either). The stream keeps flowing into memory. Postgres comes back; the next backoff tick recovers
the connection and lands the held batch; one `sink_failure` row with the outage's length. Within a
minute the heartbeat finds the lock's session dead and takes the lock again on a fresh session.
Nothing was lost.

**The weekend.** Friday night the flag reads `CLOSED`; the watchdog stands down on both markets
through the night and the weekend. Twelve hours after the flag landed, and every twelve hours
after that, one teaching resubscribe per market re-learns the flag (still `CLOSED`); Monday's first
`DEAL` snapshot ends the stand-down, and the CHART clock starts at the opening.

**A suspended host.** The machine sleeps; on waking the wall clock has jumped hours against the
monotonic clock. The round is skipped and every stopwatch re-baselined; the SDK reconnects on its
own and the classifier's `reconnect` row carries `hostSlept=true` — annotated, not treated as an
outage. (Servers are not suspended; this is the laptop case, kept because the mechanism also
catches a frozen process.)

## 8. The evening check (until E9 watches for you)

*On the deployed box (E1-T7), two more looks join these five: the healthchecks.io check, green on the
heartbeat's ping, and a glance at Grafana Cloud. The runbook (`docs/operations/runbook.md` §2) has the
commands for the box.*

Until the operator console sends the digest and the alerts (D30), there is no email: the service
is checked by hand each evening. Five looks, in order — the whole thing takes two minutes.

**1. Is it alive?** The last heartbeat line in the log, printed every 60s:

```
ticks=22415 bars=141 written=22556 dropped=0 malformed=0 sinkFailures=0 obsFailures=0 eventsQueued=0 eventWriteFailures=0 statusFailures=0
```

| Field | Healthy | Worry when |
|---|---|---|
| `ticks=` / `bars=` | climbing through the session | flat while the market is open (then look at `capture_status`) |
| `written=` | ≈ ticks + bars | far behind them — the sink is holding (see `sinkFailures`) |
| `dropped=` | 0 | > 0: the tick queue overflowed (100 000) during a hold — the hold went lossy |
| `malformed=` | 0 | > 0: updates we could not parse — a wire-shape change at IG |
| `sinkFailures=` | 0 | counts sink episodes held through today; non-zero is a database blip to read about in `service_events` |
| `eventWriteFailures=` / `obsFailures=` / `statusFailures=` | 0 | observability writes refused — the record has holes; the database was down or slow |

If the line has stopped, the process is gone: look for `FEED_DEAD` and `FATAL:` lines above the
end of the log, and the restart counter of the process supervisor.

**2. Is every market streaming?** One row per market, refreshed each heartbeat:

```sql
SELECT instrument_id, stream_state, market_state, updated_at_utc, last_tick_at_utc,
       last_bar_at_utc, reconnects_total, dropped_ticks, db_pending
FROM capture_status WHERE instance = '<your instance>';
```

`updated_at_utc` within the last two minutes, or the box is down (the dead-man rule of
`docs/design/observability-and-data-model.md`). `stream_state` `connected_streaming` for every
market; `reconnecting` means an outage is in progress (or the connection is not streaming),
`quarantined` a market the belt isolated (restart to re-admit it once the cause is fixed);
`window_closed` is reserved for the future stream windows and is not produced today.
`last_tick_at_utc` recent while `market_state` is `DEAL`; a stale one with `DEAL` and
`CONNECTED_STREAMING` is exactly the silence the watchdog is about to act on. `db_pending` is the
held batch during a database outage — it should read 0 in the evening.

**3. What did the belt do today?** Every warning and error, in order:

```sql
SELECT event_time_utc, event_type, severity, instrument_id, detail
FROM service_events
WHERE event_time_utc > now() - interval '1 day' AND severity IN ('warn', 'error')
ORDER BY event_time_utc;
```

A quiet day has a handful of `reconnect` rows at most. Read any `watchdog_stale`,
`stuck_substate_escalated`, `connection_dead`, `subscription_rejected`, `market_quarantined` or
`sink_failure` as a story: the row before it is the cause, the `reconnect`
after it is the recovery. A `feed_dead` row is the belt having given up — its `reason` says why,
and the process will have exited and been restarted.

**4. What is missing?** Open bar gaps, which T6's heal will fill nightly once it lands:

```sql
SELECT instrument_id, gap_from_utc, gap_to_utc, missing_minutes, heal_outcome
FROM bar_gaps WHERE healed_at_utc IS NULL ORDER BY gap_from_utc;
```

A gap matches an outage you can see in step 3. A gap with no outage beside it is the finding to
write down.

**5. Did the jobs run?** One `job_runs` row per phase per market-day, clean or not — the heal and
then the archive, from ten past midnight UTC:

```sql
SELECT job_name, covers_date, status, started_utc, detail
FROM job_runs WHERE covers_date >= current_date - 2 ORDER BY started_utc;
```

Yesterday should show a `heal` row and, if the bucket is configured, an `archive` row, both
`succeeded`. A **missing** row is the alarm — the job did not even start. A `failed` row says why in
its `detail` and is retried on its own, an hour later and then doubling. The gap outcomes from step 4
now read `healed`, `tickless_at_source` or `closed_at_source`; chapter 12 explains each.

When something does go wrong, the outage becomes a fixture: `scripts/export-replay.sh` cuts the
day's rows into a replay, and the belt is run against it in acceptance mode (chapter 10, "The
replay library"). That is how every scar in this chapter was learned, and it is how the next one
will be.

## 9. The numbers, on one page

| Knob | Value | Meaning |
|---|---|---|
| `tickSilent` | 90s | no tick while the flag says open → the market is silent |
| `barSilentWhileTicksFlow` | 210s | ticks but no sealed candle → the CHART leg is dead |
| `watchdogGraceBase` / `watchdogGraceCap` | 60s / 30 min | a market's ladder: doubled before each wait (90, 210, 450, 930 …), capped |
| `watchdogMaxResubscribes` | 2 | surgical tries before the market's ladder asks for the session |
| `sessionGraceCap` | 10 min | the session ladder's cap (120, 240, 480, 600 …) and the quiet after which it is forgotten (grace + cap) |
| `standDownTeach` | 12h | one teaching resubscribe per period on a market whose flag is not dealing |
| `willRetryRebuild` / `tryingRecoveryRebuild` | 120s / 300s | time not streaming before the escalator rebuilds |
| `subscriptionStrikes` / `subscribeConfirmWindow` | 3 / 30s | strikes per attempt before a verdict; how long a would-be witness may stay unconfirmed |
| `backoffBase` / `rebuildFloor` / `backoffCap` | 1s / 5s / 60s | rebuild pacing — doubled, jittered ±50%, floored, capped |
| `maxConsecutiveFailures` | 10 | connected rebuilds that never streamed → ceiling |
| `giveUpAfter` | 10 min | from an outage's first rebuild without streaming → budget |
| `hostSleepSkew` / `processFreezeJump` | 5s / 10s | the clock-anomaly discriminators |
| `LoginRateGate.MIN_INTERVAL` | 61s | IG's spacing between logins; a rebuild inside it waits |
| `CaptureAssembly.SWEEP_INTERVAL` / `HEARTBEAT` | 1s / 60s | the sweep; the heartbeat, `capture_status` and the lock check |
| `Database.CONNECTION_TIMEOUT` / `SOCKET_TIMEOUT` | 5s / 30s | a pooled or raw connect; a read on any connection |

Every number lives in `Tuning.playbook()` or the named constant, and every one has its reason in
chapter 10's "The rulings behind it".
