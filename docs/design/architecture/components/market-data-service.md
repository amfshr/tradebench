# market-data-service — the capture service

**What it is.** Tradebench's first deployed artifact (D2): a standalone service that streams
IG ticks + sealed 1m bars into Postgres, losslessly and self-reportingly (the daily heal +
Parquet archive is **T6-era, planned**). The composition root is plain `Main` today; Spring
enters at T6. *Built through E1-T4; the resilience/coverage packages below are marked where
they are still in progress on `e1-t5-resilience`.*

## Package map

- **`app`** (`Main`): the composition root — reads env, takes the single-instance lock
  *before any IG contact*, wires the graph, runs the heartbeat, exits(1) on pump death.
- **`ingest`**: `StreamAdapter` (anti-corruption to `core` domain), `Buffers` (the two
  criticality-split queues — bars unbounded/sacred, ticks 100k shed-oldest/counted), `Pump`
  (single consumer, bars-first, peek→write→remove ack-after-apply).
- **`store`**: `CaptureStore` seam with `PostgresStore` (real path) + `JsonlStore` (evidence);
  `SingleInstanceLock` (dedicated non-pooled session); Flyway migrations; the schema drift gate.
- **`supervise`** (T5, **planned** — on `e1-t5-resilience`, not yet merged): pure resilience
  cores (`StalenessWatchdog`, `StuckSubstateEscalator`, `BackoffPolicy`, `ReconnectClassifier`,
  `WitnessQuarantine`, `Tuning`) returning values; one impure `Supervisor` shell executes them.
- **`coverage`** (T5, **planned**): `GapDetector` — a watermark grid over sealed bars.

## Boundaries & data

- Single **writer** of the `market_data` database (D17) — the published read product; other
  services read it read-only, or consume events (D4) when the first live consumer exists.
- User + source dimensions on every row from V1; idempotency lives in the schema, not code.

## Config (env only, D16)

`TRADEBENCH_INSTANCE`, `TRADEBENCH_EPICS`, `TRADEBENCH_IG_ENV`, `TRADEBENCH_SINK` (jsonl|db),
`TRADEBENCH_DB_*`, IG creds via `IG_DEMO_*` / `IG_LIVE_*`. No machine-specific code defaults.

*Teaches the concepts: Field Manual chapters
[2–6](../../../field-manual/02-threads-and-the-callback-boundary.md) (pipeline) and
[8 — the resilience belt](../../../field-manual/08-the-resilience-belt.md).*
