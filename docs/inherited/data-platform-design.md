# Data-collection platform — design notes (PARKED / forward-looking)

**Status:** parked design material for a FUTURE greenfield project (Alex's
Java/Spring "platform-level" build). No coupling to the live trading core.
Captured 2026-08-09 from a design discussion. This is the *what & why*, to
read before writing the Java streamer — not a committed plan.

**Goal (Alex's words):** a long-running server that automates fetching +
persisting IG data across many timeframes, self-healing around IG
downtime, so that in 2–3 years there's a trustworthy multi-timeframe
dataset (ticks + 1/3/5/10/15/30/60m) for backtesting strategies that use
several timeframes — written to Parquet (and/or a DB), with a reporting
web UI showing what's complete vs missing.

---

## 1. Core principle — capture the finest reliable atom, derive the rest

**One source of truth per instrument, at the finest granularity you can
reliably capture; derive every coarser timeframe from it with ONE
deterministic rule.** The instant you keep two independent versions of
"the 5-minute bar" (streamed from IG *and* aggregated from 1m), they
disagree at the edges (boundary handling, IG's internal consolidation vs
yours, a dropped update on one stream) and you burn time reconciling. So:
stream the atom, derive the rest. (Alex's instinct — "stream 1min,
consolidate the rest myself" — is correct.)

## 2. What to stream from IG — TWO subscriptions per instrument

- **PRICE** → raw ticks (the finest atom; for fill-model realism).
- **CHART:1MINUTE** → the sealed 1-minute bar (accept only `CONS_END=1`;
  discard the continuous in-progress updates — see the current system's
  `parsing.parse_chart_fields`).

**Derive** 3m / 5m / 10m / 15m / 30m / 60m from the sealed 1-minute bars
(all divide cleanly on wall-clock boundaries → trivial, deterministic).

**Do NOT stream CHART:5MINUTE / HOUR as archive sources** — no
data-integrity gain (same underlying feed), more subscription + reconnect
surface, and a second source-of-truth to reconcile. **Skip CHART:SECOND**
too — raw ticks are finer (and you can derive 1s if ever needed).
*Exception:* for LIVE trading a strategy may want IG's OWN native bar for
the exact frame it trades (no derivation lag) — stream that one frame for
that purpose, but keep the ARCHIVE derived-from-1min so it stays
internally consistent.

## 3. REST's role — HEALING the 1-minute record, not parallel capture

- Live 1-minute stream = primary capture.
- **In-service** gap detection → immediate *surgical* REST backfill of the
  exact missing minutes.
- **End-of-day** completeness heal → fetch anything still missing.
- Re-derive all higher frames from the healed 1-minute record.
- *Optional QA:* an EOD REST fetch of IG's NATIVE 5m/HOUR bars as a
  cross-check to validate the derivation (a proxy-agreement gate — the
  same idea as the vendor-vs-IG comparison in the 🚰 work). Not a source.

**Hard limit:** IG's historical price endpoint charges every returned bar
against a weekly allowance (~10k points/week on demo — verify on live).
So REST is for *surgical recent healing only* — you CANNOT bulk-fetch
years of 1-minute history from IG.

## 4. IG is the forward-accruing GOLD standard, not the deep-history source

IG will not give you 2–3 years cheaply. What it gives is a gold-standard
record of *the exact instrument you trade*, accruing 1 day/day from the
moment the collector runs. For deep history NOW you need a **vendor** —
Databento FDAX futures (basis −86…−93 pts, corr ≥0.995 per the 🚰 study)
or Dukascopy free ticks (~2012+) — plus the **translation layer** (futures
mid + IG spread model, never the futures book spread). Backtest corpus =
**vendor deep history (translated) + IG forward capture (the ground truth
the translation calibrates against).** Start the IG collector NOW so that
in 2–3 years you also hold years of native IG data.

## 5. Storage — DB as the mutable heal-store, Parquet as the sealed archive

Give each a job (don't pick one):
- **DB (Postgres / TimescaleDB) or an append-log = write + heal store.**
  Healing = upserting a late minute; Parquet is bad at in-place upserts
  (rewrites the day file). Do the mutable work in the DB.
- **Parquet, partitioned `instrument/timeframe/date` = immutable published
  archive.** Once a day is complete, export + never touch. Columnar,
  compressed, language-neutral (Java/Arrow + Python/pandas both read it) →
  fast backtest scans across years. Timeframe as a partition key → a
  strategy loads exactly the frames it needs.

Flow: **stream → DB (append + heal) → seal complete days → Parquet.**
Treat Parquet as write-once.

## 6. Self-healing + the COVERAGE LEDGER (the reporting web server's data)

The thing that makes "trust it in 3 years" real: a **completeness ledger**
— per `(instrument, timeframe, day)`: expected bars / present / healed /
known-empty. That drives the reporting UI's coverage map:
🟩 complete · 🟨 healed-from-REST · 🟥 missing-unrecoverable · ⬛ closed/maintenance.
(The current system has raw materials — gap events, `daily_heal` rows —
but no unified map. Build the map as a first-class thing.)

Two caveats:
- **Ticks are unhealable** (no IG historical tick API). Mark tick gaps as
  exclusion windows — "mark, don't mend"; the 1m record heals, ticks don't.
- **Known-empty ≠ missing.** IG has a daily maintenance/rollover window +
  Sunday downtime; DFB/index instruments trade ~24h but overnight is thin
  (wide spreads, sparse ticks). Encode the EXPECTED trading calendar
  (05:00–22:00 Mon–Fri + closures) so genuine gaps stand out from
  scheduled closure — otherwise the map is all red at 3am for nothing.

## 7. The Java greenfield — and PORT the lessons, don't rediscover them

This platform is decoupled from the trading logic (pure ingestion +
persistence + healing + reporting) → ideal greenfield Java/Spring (a
long-running server + subscription manager + heal scheduler + coverage
web UI is textbook Spring Boot), consistent with keeping the proven Python
live core and building the platform layer in the stronger language.

**Read the current Python capture first.** It already solved the hard
parts — worth a day before writing the Java streamer:
- `CONS_END` sealing (only completed 1m bars enter the record).
- Session/reconnect resilience + the stuck-stream failure modes
  (`is_dead` only fires on bare DISCONNECTED / server error; WILL-RETRY
  and TRYING-RECOVERY hangs are unreachable — the 📶 escalation-ladder
  ticket).
- D21 in-service backfill + D25 bar-silent watchdog + the 18:15 daily heal.
- The tick-unhealability reality.
- Per-field-mid consolidation matching the D4 SQL oracle
  (mid of opens, mid of highs, … — NOT the true mid high/low).
- The `ig_client` wrapper knowledge (the 📡 ticket) = the reusable
  IG-integration core; the Java platform wants the same abstraction.

## 8. Open decisions (settle when the project starts)

- Session envelope: 05:00–22:00 vs true ~24h capture (overnight thin).
- Volume: IG CFD LTV/TTV is limited/synthetic for indices — capture but
  don't rely on it; real volume comes from the exchange/futures feed.
- DB engine: plain Postgres vs TimescaleDB hypertables (Alex already flags
  Timescale as adoptable when analytics queries hurt — it's a Postgres
  extension, not a language argument).
- Instrument set + which frames to materialise vs derive-on-read.
- Reconcile with E5's 🚰 pipeline (vendor DBN→Parquet) + E2's 📡 IG-wrapper
  package — this platform is where they converge.
