# Market-data providers beyond IG — survey & the two-feed decision

**Status:** research, concluded. Landscape web-verified **2026-08-25**; decision made same day.
**Question asked:** the Java platform's first priority is data collection, and IG is temperamental —
what other brokers/market services could be integrated as parallel streams, and what data vendors
exist? (Original sketch: a `market-data-provider-integration-service` handling N third-party
integrations into Parquet.)
**Answer:** N collapses to **two**. IG = broker truth, Databento = market truth. The whole
second-CFD-broker category was surveyed and eliminated — see §2.

Companion docs: [`../data-platform-design.md`](../data-platform-design.md) (the collection-platform
design this decision feeds — its §4 "vendor deep history + IG forward capture" now has a named
vendor and a named non-answer) and `tmp/parked/validation-and-data-programme.md` in the prototype
repo (the July 2026 vendor sweep this updates — two of its §3 entries are stale, §4 below).

---

## 1. The decision — two feeds, two jobs

| feed | job | status |
|---|---|---|
| **IG capture** (Lightstreamer PRICE + CHART:1MINUTE) | **Broker truth.** The only possible record of IG's own bid/ask — spread, fill levels, what orders actually face. Cannot be bought later; every day compounds | running (Python prototype, daily) |
| **Databento `XEUR.EOBI`** (FDAX/FDXM) | **Market truth.** Exchange-grade dense Eurex ticks — the quality-tick source no CFD broker can be | monthly historical buys per the §6.1 cadence (retroactive, ~$5/GB); live stream ($199/mo Standard) deferred until the platform needs live exchange ticks rather than an accruing archive |

Plus one **one-off download, not an integration**: Dukascopy's free Germany 40 (`DEU.IDX/EUR`)
historical archive — **tick data from Jan 2013**, no account needed
(<https://www.dukascopy.com/swiss/english/marketwatch/historical/>,
<https://www.dukascopy-node.app/instrument/deuidxeur>). Derived CFD price with known ~3-pt
step artifacts from its fair-value re-marks (measured in the July three-way study), so it's
general-model-development material and a cross-check — never ground truth.

## 2. Why no second CFD broker

The eliminating argument: **if the goal is good-quality tick data, the entire CFD-broker category
fails it.** Every CFD broker streams its own derived quote (a synthetic price built off
futures/liquidity feeds — there is no exchange tape behind it), and every one conflates:

- **OANDA** documents it outright: `/pricing/stream` sends **at most 4 prices/sec per instrument**
  (end-of-250ms-window snapshot; windows not even aligned across connections — two subscribers to
  the same stream see *different* tick series).
- **IBKR**'s Eurex feed is ~250 ms conflated snapshots, not tick-by-tick.
- **Capital.com** has a real quote WebSocket (`marketData.subscribe`: bid/ofr + **top-of-book
  sizes**, 40-epic cap) but publishes nothing about conflation.
- **IG itself** is no exception — Lightstreamer MERGE mode conflates under load, and completeness
  is unverifiable because IG's quote exists only inside IG. IG stays not because its ticks are
  complete but because it is the **execution venue**: its stream is by definition the best
  available record of the prices we trade on.

So a second CFD broker adds an independent *opinion* on a synthetic quote — worth something, but
not the thing the effort was for. Two feeds with distinct jobs beat three similar synthetic quotes.

**Corollary for the lake:** provider is a **first-class dimension, forever**. Each provider's
stream is its own price process (different conflation, different re-marks); never merge streams
into one "the price" series, and compare providers at bar level, never tick-for-tick. Range-bar
models make this bite hardest — bar construction depends on tick density, so two feeds of "the
same market" produce different bar sequences.

## 3. The survey (all facts web-verified 2026-08-25)

### 3.1 Brokers with public APIs (UK-accessible)

| broker | API / DAX data | verdict |
|---|---|---|
| **CMC Markets** | **No retail API** in 2026 — "CMC Connect" is institutional FIX / white-label only; retail algo access = MT4 (FX only) | ❌ off the list |
| **OANDA** (v20) | Clean REST + chunked-HTTP streaming (NDJSON over a held GET — not WebSocket, same architecture class); `DE30_EUR`; candles endpoint S5→monthly, 5k/request; free, FCA UK, demo | ❌ eliminated by the documented 4/sec conflation ceiling — fine for bars/redundancy, useless for quality ticks. (Its honesty about conflation is the reference point for judging the others.) |
| **Capital.com** | REST + WebSocket quote stream *and* native OHLC subscriptions (minute floor, bid-priced); demo env works; free (<https://open-api.capital.com/>) | ❌ (soft) — conflation unmeasured; was held as a "measure it side-by-side vs IG" candidate, dropped when the goal was sharpened to tick quality |
| **IBKR** | TWS API (first-class Java) + real licensed Eurex L1 — "Eurex Retail Europe" fee currently waived w/ ≥$35/mo commissions; expired-futures history vanishes after 2 yrs | ❌ as a data feed (conflated snapshots); 📌 keep in mind as a future *execution venue* for real futures |
| **Saxo** (OpenAPI) | Real retail API, but non-FX market data must be explicitly enabled/arranged; 1,200 datapoints/candle-request | ❌ friction, derived CFD anyway |
| **Dukascopy** | JForex API is Java-native; Swiss entity for UK residents post-Brexit | ❌ as an integration; ✅ the free historical archive (§1) |
| Trading212 / FXCM / Darwinex / LMAX | T212: no market data in the API. FXCM: REST API dead (~2023), avoid. Darwinex: free FTP tick history for live clients, MT/FIX access — secondary. LMAX: firm limit-order-book CFD data + Java libs, professional-oriented — the only "real order book from a CFD venue" option if ever needed | ❌ / long-list |

### 3.2 Data vendors

| vendor | offer | verdict |
|---|---|---|
| **Databento** | **Eurex `XEUR.EOBI` live since 2025-11-05** — sourced from Eurex T7 EOBI at FR2, hardware timestamps; FDAX schemas incl. trades, TBBO, MBP-1/10, MBO, OHLCV-1s/1m; historical ~$5/GB usage-billed, **no license fees for non-pro personal use**; live = Standard $199/mo. History starts **2025-03-10** (<https://databento.com/datasets/XEUR.EOBI>, <https://databento.com/pricing>) | ✅ **the** market-truth source (already the §6.1 plan; live-stream upgrade now exists when wanted) |
| **Dukascopy** | §1 — free 13-yr Germany 40 tick archive | ✅ one-off download |
| **FirstRate Data** | DAX 40 index 1-min bars **2008→present**, <$100/yr updates (<https://firstratedata.com/i/index/DAX>) | 💡 best bars-per-pound if deep *bar* history is ever wanted; no bid/ask (fails the fill-model checklist) |
| **Tick Data LLC / PortaraCQG** | The only deep (pre-2013) exchange-grade FDAX tick history; quote-based, hundreds+ | 🗄️ stays in the drawer until a proven strategy justifies regime depth (unchanged from July) |
| **dxFeed** | Carries Eurex but firm-oriented, sales cycle, no published pricing | ❌ |
| Twelve Data / Finnhub / EODHD / Polygon("Massive") | No real DAX intraday: EODHD non-US 1m "not guaranteed"; Finnhub candles premium + GDAXI issues; Polygon rebranded **Massive** early 2026, launched futures but **CME only, no Eurex** | ❌ all |

## 4. Updates to the July sweep (`tmp/parked/validation-and-data-programme.md` §3)

Two entries are stale as of this survey:

1. **Databento**: Eurex went **fully live Nov 2025** including a real-time stream — the July table
   predates the live product. The monthly-historical cadence is unaffected and still right; the
   change is that a live exchange feed is now a $199/mo option rather than nonexistent.
2. **Dukascopy**: listed there only as a free *cross-check*; it is also a **13-year free tick
   archive** (Jan 2013→) for the Germany 40 CFD — material as a £0 backtest corpus for
   general model development.

Also new climate signal: Deutsche Börse is pushing the "Eurex Retail Europe" top-of-book product
cheap/free to retail (waived at IBKR, $2/mo on TradingView) — exchange-data licensing is moving
in the retail direction.

## 5. Consequences for the Java platform

- The imagined `market-data-provider-integration-service` needs **two adapters, not N**:
  the planned `ig-client` (phase-1 build plan) + a `databento-client` (DBN over raw TCP for
  live, batch API for historical; official clients are Python/C++/Rust — the raw protocol is
  simple enough to wrap in Java). Still worth a thin `MarketDataProvider` SPI so the resilience
  stack (watchdog, reconnect, gap ledger — playbook §2–§4) wraps *any* provider, but don't build
  adapter machinery for providers that were eliminated on the merits.
- **Capture raw, then normalise — keep both.** Land provider-native messages immutably, then
  normalise into the canonical Parquet layout; provider in the partition path. Instrument mapping
  (IG epic ↔ FDAX contract ↔ `deuidxeur`) gets a real home early.
- Isolation still matters with two: one provider's outage/API change must never stall the other.
- This slots directly into [`../data-platform-design.md`](../data-platform-design.md): its §4
  translation layer (futures mid + IG spread model, never the futures book spread) is exactly the
  bridge between the two feeds.

## 6. Automating the monthly buy — the Spring batch-pull job (designed 2026-08-25)

The $199/mo (~£145) Standard plan buys **latency, not data**: the month-end batch buy delivers
byte-identical exchange data one month later, and while the priority is collection, latency is
worth £0. But the pipeline should still be *programmatic* — no human clicking a portal every
month. Databento's historical side supports exactly this (verified 2026-08-25 against their
pricing page): **pay-as-you-go with card payment, explicitly "no subscription required"**, plus
portal-side **monthly spending limits and usage notifications**. So: a Spring `@Scheduled` job
orders last month's FDAX, the card is charged single-digit pounds (July calibration: 22-day
mbp-1 parent job ≈ 291 MB), and two spend caps guard it.

**The flow** — this *is* the prototype repo's §6.1 monthly cadence, automated:

```java
@Scheduled(cron = "0 0 7 3 * *")   // 07:00 on the 3rd — month-end data long since available
void monthlyFdaxPull() {
    var range = previousCalendarMonth();

    // 1. Preflight — never order blind
    var cost = databento.getCost("XEUR.EOBI", "FDAX.v.0", CONTINUOUS, MBP_1, range);
    if (cost.exceeds(monthlyCap)) { alert(cost); return; }        // our guard, on top of
                                                                  // the portal spend limit
    // 2. Order (async batch job)
    var job = databento.submitBatchJob("XEUR.EOBI", "FDAX.v.0", CONTINUOUS, MBP_1,
                                       range, encoding, ZSTD);

    // 3. Poll until done, download, verify manifest hashes
    var files = databento.awaitAndDownload(job, rawLakeDir(range)); // files stay ~30 days —
                                                                    // retries are cheap
    // 4. Normalise → Parquet, record in the coverage ledger with job id + billed cost
    ingest.normalise(files, range);
}
```

**Transport:** plain HTTPS against `hist.databento.com`, API key as HTTP Basic auth —
`java.net.http`, no SDK. Databento's official clients are Python/C++/Rust only, but this
batch/REST flow is the easy 90% of the future `databento-client` module; the harder live-TCP
protocol only ever gets written if the live-feed day comes.

**The one real design decision — the decode step.** Bit-exact truth is DBN (binary, int64
fixed-point prices; the July notes verified Python `to_parquet(price_type="fixed")` end-to-end).
Options for Java:
1. **CSV encoding with raw fixed-point prices** (lossless int64s, zstd) — trivially parsed,
   the clean Java-only path. **Start here; don't block on DBN.**
2. A small Java DBN reader later — the spec is open, records are fixed-size structs; a nice
   self-contained module.
3. Pragmatic bridge: keep the already-verified Python DBN→Parquet step until (2) exists.

**Guardrails (day one):**
- `get_cost` gate with an own-config cap (expect low single-digit £/month) + the **portal
  monthly spend limit** as the hard backstop — two independent layers.
- **Idempotency keyed by month** in the coverage ledger: a re-run must notice the month is
  already landed, not re-buy it.
- **Alert on silence**: a monthly job that quietly stops firing is the lid-sleep failure mode
  again — the coverage ledger must show the expected month *missing*, not show nothing.

## 7. Next actions

- [ ] Download the Dukascopy Germany 40 tick archive (2013→present; no account needed)
- [ ] Keep the Databento month-end cadence running manually (prototype repo §6.1; first gate end
      of Aug 2026) until the §6 job exists in the Java collector
- [ ] When the card goes on file: set the portal monthly spend limit + usage notifications
- [ ] Databento live (Standard) — decision deferred; revisit only if the platform ever consumes
      live exchange ticks for decisions
- [ ] Fold the §4 corrections into the parked doc's vendor table when it's next touched
