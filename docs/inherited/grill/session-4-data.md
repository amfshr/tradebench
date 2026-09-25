# Grill Session 4 — Data

> **What this is.** The answered record of the fourth product grill session with Alex
> (2026-09-24). Companions: sessions 1–3 in this folder. Context that pre-answered the big
> sourcing calls: `tmp/java/research/market-data-providers-2026-08.md` (IG = broker truth,
> Databento `XEUR.EOBI` = market truth, Dukascopy free 13-yr archive as a one-off).

**Status: CLOSED.**

---

## Q1 — Market breadth: INDEXES ONLY

"Probably just indexes — not crypto or anything else." DAX and NASDAQ are the named starting
pair. The data model is shaped for index markets (calendars, tick sizes, currencies) without
needing to accommodate equities/FX/crypto idiosyncrasies.

## Q2 — Atomic unit: TICKS, kept forever

- **Ticks are the baseline stored truth** — "everything else is derivable."
- **Also stream IG's 1-minute bars** — "that essentially allows us to aggregate our own bars for
  everything higher."
- **Clarified in Session 5:** BOTH IG streams run — tick data AND 1-minute bars. The 1m stream's
  primary job is **resilience**: "tick data streams can break and we need to have 1-mins streamed
  for extra resiliency." And on aggregation: "consistency of how the system creates the available
  timeframe data is important — once the maths is done and correct, that is a non-issue."
- ⭐ Emergent property (flagged in-session): storing broker 1m bars *alongside* tick-aggregated
  bars recreates the prototype's oracle pattern for free — IG's own bars continuously verify the
  platform's tick→bar aggregation. Carry this as a deliberate design feature, not an accident.
- **Retention: persist forever.** "We will battle the storage issue when it comes."

## Q3 — Source as a first-class dimension: YES

- Every series knows its source; the same market can hold parallel series from different
  sources; a backtest declares which source it ran against.
- **Uploads must attribute the real source** — "being able to attribute the source instead of
  just 'user uploaded' is what I want."
- Source set for now: **Databento + IG-provided** is enough. (Dukascopy possible later, see Q4.)

## Q4 — Historical depth & the admin-provisioned data asset

- Live IG streaming accrues value continuously — "the longer the platform is live streaming IG
  data the better." (→ another reason the standalone data-collection service exists and runs for
  years; also why the Python Air keeps capturing through the hiatus.)
- **Plan: buy Databento tick data as the platform's starting historical base.** Minimum bar:
  **~2 years of Databento tick data for DAX and NASDAQ is enough for the project not to be
  inhibited.** Appetite exists to buy more ("as much as" reasonable — the Session-1 £100–200
  one-off comfort zone, possibly revisited).
- ⭐ **New product concept — platform-provided datasets:** Databento data is **managed centrally
  by Alex as admin** and **made available to every user** (via key link / user id), for DAX and
  NASDAQ. This creates two data-ownership tiers: **platform-provided shared datasets**
  (admin-provisioned, available to all) vs **user-owned data** (their streams, their uploads).
- Maybe: Dukascopy 5 years as a free default dataset for every user — "I will think about this."
  Not committed.
