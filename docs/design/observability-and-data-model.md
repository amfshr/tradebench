# Observability & data model — the collection service's captured truth and its read model

> **Ruled 2026-09-30 (E9-T1 design session; rulings R1–R7 below, Alex).** The enduring spec for
> what the 24/7 collection service *records* about itself and its data, and the read model the
> Operator Console (E9) serves over it. Supersedes the first-pass `service_events` (V1). Decision
> **D25**; console architecture **D24**; data-product topology **D17**; dataTime doctrine D38.
>
> **Who builds what:** the tables here are written by **E1** (the collector: `service_events`
> v2, `bar_gaps`, `capture_status`) and **E1-T6** (the EOD job: `archives`, `job_runs`,
> `heal_outcome`). **E9** reads them (read-only creds, D17) and serves the console. **E1-T5
> slice B builds to the `service_events` v2 + `bar_gaps` + `capture_status` shapes here** — this
> spec is its input, so the collector writes the right rows the first time.

## Rulings (R1–R7, Alex, 2026-09-30)

- **R1 — `service_events` v2** carries severity + user/source/instrument dimensions + an
  occurrence/recorded time split + `jsonb` detail; `category` is a stored column;
  `correlation_id` is included (nullable); `event_type` is free text governed by a Java enum
  (no DB enum — adding a type must not need a migration).
- **R2 — `capture_status`** is a per-heartbeat UPSERT table: the console's near-live health
  source, read from the data product (a stale `updated_at_utc` *is* the box-down signal). Not
  dependent on Prometheus for "is it alive."
- **R3 — the Grafana/DIY split:** the SPA-over-DB owns current status + domain views (health,
  coverage, gaps, catalogue, downloads, event log) and ships in v1; Prometheus/Grafana owns
  time-series *trends + alerting* and is **additive** (a later ticket, a container behind the
  same tunnel); a healthchecks.io dead-man's-switch gives the serious-tier "box down" alarm in
  v1 without Prometheus.
- **R4 — scheduling (three clocks):** the per-market **stream window** is config (venue-local,
  subscribe/unsubscribe only — host is 24/7, D37) and defaults **generous** (not a tight
  06:00–17:00); the **`DLG_FLAG` watchdog** (T5) governs whether silence alarms; the **expected
  trading calendar** — not the window — is the gap-truth.
- **R5 — the expected calendar** is a small config-encoded `market_calendar` (XETRA sessions +
  German holidays) now; a library (exchange-calendars-style) only when markets multiply.
- **R6 — `archives`** is a manifest table (E1-T6 writes a row per Parquet/`pg_dump`); the
  console lists it and pre-signs `object_key` (no live bucket-listing).
- **R7 — timeframe generalisation** (Alex's forward question): higher timeframes are *derived
  on read* from healed 1m (D15/D9), never separately captured — so the monitoring schema stays
  **1m + tick scoped**; `source_id` is the escape hatch if a broker-TF oracle is ever wanted.
  See "Timeframe generalisation" below.

## Schema (V2 migration — supersedes/extends V1)

```sql
-- service_events v2: the audit log. Dimensions + severity + occurrence/recorded split.
service_events (
  id, instance,
  user_id       NOT NULL → users,        -- default-user for now
  source_id     → sources,               -- nullable: not all events are source-scoped
  instrument_id → instruments,           -- nullable: global events have no market
  category      NOT NULL,   -- lifecycle|resilience|data_liveness|data_quality|heal|error|rate_budget
  event_type    NOT NULL,   -- documented vocabulary, owned by a Java enum → text
  severity      NOT NULL,   -- info|warn|error
  event_time_utc  NOT NULL,               -- when it occurred (D38 occurrence/data time)
  recorded_at_utc NOT NULL DEFAULT now(),
  correlation_id,                          -- nullable: threads a reconnect storm together
  detail jsonb )                           -- typed per event_type in code
  -- idx: (event_time_utc DESC); (severity, event_time_utc DESC); (instrument_id, event_time_utc DESC)

-- bar_gaps: GapDetector.Gap persisted (E1-T5 slice B); healed_at for E1-T6.
bar_gaps (
  id, user_id NOT NULL, source_id NOT NULL, instrument_id NOT NULL,
  gap_from_utc NOT NULL, gap_to_utc NOT NULL, missing_minutes NOT NULL,
  detected_at_utc NOT NULL DEFAULT now(),
  healed_at_utc,                           -- null = open
  heal_outcome )                           -- null|healed|tickless_at_source|failed (E1-T6)
  -- UNIQUE(user,source,instrument,gap_from,gap_to); partial idx WHERE healed_at_utc IS NULL

-- capture_status: per-heartbeat UPSERT — the console's near-live health source.
capture_status (
  instance, instrument_id NOT NULL → instruments,
  updated_at_utc NOT NULL,                 -- stale => box down (dead-man's read)
  stream_state NOT NULL,                   -- CONNECTED_STREAMING|RECONNECTING|WINDOW_CLOSED|QUARANTINED
  market_state,                            -- last DLG_FLAG
  last_tick_at_utc, last_bar_at_utc,
  ticks_total, bars_total, dropped_ticks, malformed, reconnects_total, db_pending,
  PRIMARY KEY(instance, instrument_id) )

-- archives: manifest written by E1-T6; the console pre-signs object_key.
archives (
  id, kind,                                -- parquet_day | pg_dump
  instrument_id → instruments,             -- null for pg_dump
  covers_date,                             -- the day (parquet) or null
  object_key NOT NULL, bytes, sha256,
  created_at_utc NOT NULL DEFAULT now() )
```

`job_runs` (V1) is unchanged — the EOD job's audit row; its absence is the alarm (D6).

### The `event_type` vocabulary (the Java enum → text)

| category | event_types |
|---|---|
| lifecycle | `service_start` · `service_stop` · `config_loaded` · `window_open` · `window_close` |
| resilience | `reconnect` · `stuck_substate_escalated` · `session_refreshed` · `transport_downgraded` |
| data_liveness | `watchdog_stale` · `watchdog_recovered` · `market_quarantined` · `market_released` · `subscription_rejected` · `market_state_change` · `host_suspend` · `feed_dead` |
| data_quality | `bar_gap` · `bucket_void` · `oracle_mismatch` · `malformed_update` |
| heal | `daily_heal` · `backfill` · `parquet_archived` · `pg_dump` · `digest_sent` · `digest_suppressed` |
| error | `ig_api_error` · `db_error` · `sink_failure` |
| rate_budget | `pacer_discovered` · `allowance_low` · `allowance_exhausted` |

The monotonic-vs-wall discriminator is a watchdog *input*, not a column: it produces the
*classification* (`host_suspend` vs `feed_dead`), which is the recorded `event_type`.

## Metrics (Micrometer) + the Grafana/DIY split (R3)

Tags on every metric: `instance`, `market` (epic). **Counters:** `capture.ticks.total`,
`capture.bars.total`, `capture.ticks.dropped.total`, `capture.updates.malformed.total`,
`capture.events.written.total`, `capture.publish.failures.total`, `capture.reconnects.total`,
`capture.gaps.detected.total`. **Gauges:** `capture.tick.age.seconds`, `capture.bar.age.seconds`,
`capture.queue.pending`, `capture.pacer.budget.remaining`. **JVM:** heap/GC/threads/uptime
(Actuator built-ins). **Timers:** `capture.write.latency`, later `capture.heal.duration`.

- **DB (SPA, v1):** current status (`capture_status`), coverage map, gaps, catalogue, event
  log, downloads.
- **Prometheus/Grafana (additive, later):** time-series trends + alerting rules.
- **healthchecks.io (v1):** heartbeat absence-alarm — the serious-tier "box down" without
  Prometheus.

## Scheduling — the three clocks (R4)

| Clock | Governs | Where |
|---|---|---|
| **Stream window** (per-market config, venue-local) | subscribe/unsubscribe — not process lifecycle (host 24/7, D37) | collector config; DAX default **generous** (~06:00–22:00 venue-local, warm-up + post-close) |
| **`DLG_FLAG` watchdog** (from stream) | whether silence *alarms*; self-suppresses weekends/holidays (D25/D25-triage) | T5 `StalenessWatchdog` |
| **Expected calendar** (`market_calendar`: XETRA sessions + German holidays) | classifies **gap vs scheduled-closure** for coverage | config table (R5) |

A tight 06:00–17:00 window on an always-on box buys ~nothing (a closed DAX just sends
`DLG_FLAG=CLOSED`, no ticks) while risking clipped data — so the window is generous and the
**calendar**, not the window, defines a real gap.

## Read-model SQL (per console view)

- **Health:** `capture_status` for the instance + `now() - updated_at_utc` freshness + open-gap
  counts per market. Stale = `now() − updated_at_utc` > 2–3 × the collector's heartbeat
  (`Main.HEARTBEAT`, 60s) → **box down**; a fresh row with a stale `last_tick_at_utc` while
  `market_state` is open, or `stream_state ≠ connected_streaming`, → **feed dead** (ruled
  2026-10-03). `window_closed` is not produced until R4's stream windows exist.
- **Coverage map:** per market×day — expected session-minutes (`market_calendar`) `LEFT JOIN`
  present bar-minutes (`bars_1m`) → `complete | partial(gaps) | closed`.
- **Gaps:** `bar_gaps WHERE healed_at_utc IS NULL` (open) + healed history with `heal_outcome`.
- **Catalogue:** `min/max(ts_utc), count GROUP BY instrument, source` over ticks/bars.
- **Event log:** `service_events WHERE severity IN (…) [AND instrument_id=…] ORDER BY
  event_time_utc DESC LIMIT …` — the errors/warns filter.
- **Data-quality:** `oracle_mismatch` rate (own tick→1m vs IG 1m, D15) + `malformed_update`
  rate over time.
- **Downloads:** `archives` rows → pre-signed `object_key`.

## Timeframe generalisation (R7 — the "what if I want 5m/10m later" answer)

IG offers CHART `SECOND|1MINUTE|5MINUTE|HOUR` — **no native 10m**. Doctrine (D15/D9): capture
the finest atoms (ticks + broker sealed 1m) and **derive** every higher timeframe by one shared
aggregation, computed on read — cheaper, complete (only 1m is healable), and required for T8's
oracle byte-match. A separately-streamed broker 5m is a *different* number that can diverge, so
it is only ever an extra verification oracle or a deliberately-labelled alt-source, never the
canonical series.

**Because higher TFs are derived on read, there is no independent 5m/10m capture to monitor** —
a 10m "gap" *is* a 1m gap. So this schema stays 1m + tick scoped; adding a `timeframe` column
now would be speculative. The generalisation path if a broker-TF oracle is ever wanted: it
arrives as a new **`source_id`**, at which point `bar_gaps`/`capture_status`/coverage gain a
`timeframe` dimension and the watchdog gains **per-timeframe silence thresholds** (a 5m stream
is "silent" after ~6 min, not the 1m stream's 210s) and a bar-interval gap-grid. Lighter load ≠
less monitoring — just different expectations. The seams (generic `SubscriptionSpec`, PRD §5.1's
multi-timeframe stream-job config, the `source_id` dimension) already exist; the doctrine simply
means you rarely reach for them.
