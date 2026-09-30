# core — the shared domain

**What it is.** The Gradle module every service depends on and nothing depends back on: the
value objects and SPIs that define what Tradebench *means* by a market event and by time.
No I/O, no framework, no vendor.

## Responsibilities

- **Domain types** (`core.domain`): `MarketEvent` (sealed, permits `Tick` | `Bar1m`),
  `Tick`, `Bar1m`, `OhlcPrices`. Prices are `BigDecimal` at exact wire scale (D36); a
  `Bar1m`'s `mid()` is the per-field 1m mid that higher timeframes aggregate from (D15).
- **Time SPI** (`core.time`): `Clock` — two methods, deliberately split: `wallInstant()`
  for stamps and `monotonicNanos()` for spans. `SystemClock` is the only place the platform
  reads a real clock; everything else takes readings as arguments (see the Field Manual on
  [clocks](../../../field-manual/07-clocks-wall-monotonic-and-sleep.md)).

## Boundaries

- Depended on by `ig-client` and `market-data-service`; depends on nothing in-repo.
- The event-stream SPI (one ordered mixed feed) is **parked to E3** — `MarketEvent` and the
  domain types stand; the engine feeder is designed at the E3 engine session.

## Conventions

JSpecify `@NullMarked` from birth (D14); Java 25 (D12). No validating constructors on wire
DTOs — judging market data is a consumer concern (tech-notes).

*Teaches the concepts: Field Manual [ch. 7 — Clocks](../../../field-manual/07-clocks-wall-monotonic-and-sleep.md).*
