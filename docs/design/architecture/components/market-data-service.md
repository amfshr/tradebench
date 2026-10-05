# market-data-service — the capture service

**What it is.** Tradebench's first deployed artifact (D2): a standalone service that streams
IG ticks + sealed 1m bars into Postgres, losslessly and self-reportingly (the daily heal +
Parquet archive is **T6-era, planned**). The composition root is plain `Main` today; Spring
enters at T6. *Built through E1-T5 and T9 — the resilience belt (PR #12, 2026-10-04) and the sink's
hold-and-retry (PR #13, 2026-10-05) are merged; the daily heal is T6.*

## Package map

- **`app`** (`Main`): the composition root — reads env, takes the single-instance lock
  *before any IG contact*, wires the graph (`bind → start → watch → threads`), runs the 60s
  heartbeat (publishes `capture_status`; exits 1 if the pump or the supervisor thread dies),
  starts the REST pacer at a conservative 10/min and lets discovery set the real budget after
  login.
- **`ingest`**: `StreamAdapter` (anti-corruption to `core` domain), `Buffers` (the two
  criticality-split queues — bars unbounded/sacred, ticks 100k shed-oldest/counted), `Pump`
  (single consumer, bars-first, peek→write→remove ack-after-apply).
- **`store`**: `CaptureStore` seam with `PostgresStore` (real path, market data only) +
  `JsonlStore` (evidence); `PostgresObservabilityStore` (`EventLog` / `GapStore` / `StatusStore`
  — service events, bar gaps, `capture_status`; a pooled connection per write);
  `SingleInstanceLock` (dedicated non-pooled session); Flyway migrations; the schema drift gate.
- **`supervise`** (T5): the pure resilience cores (`StalenessWatchdog`, `StuckSubstateEscalator`,
  `BackoffPolicy`, `ReconnectClassifier`, `WitnessQuarantine`, `Tuning`) returning values; the
  one impure `Supervisor` shell (sweep thread, recovery budget, give-up → `FEED_DEAD` + `exit(1)`);
  `IgStreamControl` (the real `StreamControl` — session + stream lifecycle, generation-gated
  callbacks) and `StreamObserver` (the single `bind()` back-edge); `MarketFreshness` /
  `MarketTelemetry` (pulled from `Buffers`); `BeltView` + `HealthProbe` (the heartbeat's
  `capture_status` rows); `PacerDiscovery`.
- **`coverage`** (T5): `GapDetector` — a watermark grid over sealed bars, fed on the pump
  thread; gaps persist to `bar_gaps` and announce as `BAR_GAP` events.

## Boundaries & data

- Single **writer** of the `market_data` database (D17) — the published read product; other
  services read it read-only, or consume events (D4) when the first live consumer exists.
- User + source dimensions on every row from V1; idempotency lives in the schema, not code.

## Config (env only, D16)

`TRADEBENCH_INSTANCE`, `TRADEBENCH_EPICS`, `TRADEBENCH_IG_ENV`, `TRADEBENCH_SINK` (jsonl|db),
`TRADEBENCH_DB_*`, IG creds via `IG_DEMO_*` / `IG_LIVE_*`. No machine-specific code defaults.

*Teaches the concepts: Field Manual
[chapter 2](../../../field-manual/foundations/02-threads-and-the-callback-boundary.md) onward (the
pipeline), [chapter 8 — the resilience belt](../../../field-manual/market-data-service/08-the-resilience-belt.md)
(the cores and the shell) and
[chapter 10 — the failure playbook](../../../field-manual/market-data-service/10-the-failure-playbook.md)
(the policy and the rulings).*
