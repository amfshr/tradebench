# Shadow-diff: Tradebench against the Python prototype (E1-T8)

> **The trust gate, run 2026-10-30.** Nine trading days — 2026-10-19 to 23 and 26 to 29 — in which the
> production collector (on the Hetzner box since 2026-10-18) and the Python prototype (on the Mac, streaming
> 06:00–18:00 UK time) captured the same DAX feed from IG demo. Every delta below is explained, not only
> counted. **Verdict: Tradebench captured everything the prototype did, and more; the prototype's differences
> are its host's sleep, one silent stall of its own, and its REST backfill.** Market data only (G1): the
> prototype's strategy and order tables were never read.

## How it was run

- **Tradebench's side** — the R2 Parquet archive (`platform/ig-stream-demo/IX.D.DAX.DAILY.IP/<day>/`), copied
  with a read-only token and read with DuckDB. No access to the box's database was needed.
- **The prototype's side** — its `igtrader.dax_ticks`, `dax_bars_1m` and `dax_bars_10m` (mid OHLC only),
  exported from its local Postgres.
- **The window** — each day's comparison runs over the prototype's own first-to-last tick, so Tradebench's
  overnight capture (which the prototype never attempted) is not counted against it.
- **Matching** — ticks on (timestamp to the millisecond, bid, ask). Bars on the minute, Tradebench's bid/ask
  OHLC turned into mid prices — `(bid_x + ask_x) / 2` per field, the prototype's own formula. The prototype
  stores one decimal place, so a difference of 0.05 is rounding, not a disagreement.

## Ticks

| Day | Prototype window (UTC) | Prototype ticks | Tradebench ticks in that window | Exact matches | Share of the prototype's |
|---|---|---:|---:|---:|---:|
| Mon 19 | 05:13–17:00 | 108,887 | 108,886 | 108,885 | 99.998% |
| Tue 20 | 05:05–16:59 | 126,785 | 126,785 | 126,784 | 99.999% |
| Wed 21 | 05:08–16:59 | 70,759 | 113,478 | 70,756 | 99.996% |
| Thu 22 | 05:01–16:59 | 77,701 | 120,306 | 77,701 | 100% |
| Fri 23 | 05:16–16:44 | 641 | 120,995 | 641 | 100% |
| Mon 26 | 06:14–18:00 | 67,747 | 99,935 | 67,747 | 100% |
| Tue 27 | 06:15–17:59 | 37,918 | 108,197 | 37,918 | 100% |
| Wed 28 | 06:03–17:59 | 65,076 | 104,306 | 65,076 | 100% |
| Thu 29 | 06:13–17:59 | 90,363 | 118,155 | 90,363 | 100% |
| **Total** | | **645,877** | **1,021,043** | **645,871** | **99.999%** |

On the two days the Mac stayed awake (19 and 20), the two systems captured the same stream to within one
tick a day.

### The 6 prototype ticks Tradebench lacks

- **2 — Tradebench's only outage in the window.** At 2026-10-21 10:20:26Z IG refused the collector's expired
  session ("User/password check failed"); the belt rebuilt the connection in 644 ms. The prototype's own
  session was unaffected and caught two ticks in that gap.
- **4 — single ticks inside busy seconds** (19 Oct 14:25:27, 15:25:40; 20 Oct 09:26:49; 21 Oct 11:02:35), with
  no reconnect near them. Each time Tradebench holds a different price tens of milliseconds later. This is
  consistent with Lightstreamer's MERGE subscription mode, where the server may collapse rapid updates
  differently for each session — and it happens the other way too (below). Not proven beyond that pattern.

### The 375,172 Tradebench ticks the prototype lacks

| Where | Ticks |
|---|---:|
| Inside the prototype's 89 silences longer than three minutes (2,279 minutes in all) | 373,096 |
| Inside its 73 shorter silences, ten seconds to three minutes | 2,058 |
| Single ticks in busy seconds — the MERGE pattern in reverse (e.g. 19 Oct 10:08:33, 20 Oct 07:26:54) | 18 |

**The silences are the Mac asleep.** They follow a fixed rhythm — ~30–35 minutes of silence, a few seconds of
ticks, silence again — the shape of a sleeping Mac waking briefly in the background. The Mac's power log
(`pmset -g log`) reaches back to 2026-10-23 20:47Z, and covers 34 of the silences: **33 were at least 90% Mac
sleep**; the earlier 55 (21–23 Oct) predate the log's retention but follow the same rhythm.

**The one exception is the prototype's own failure.** On 2026-10-27 the prototype logged a `reconnect` at
07:21:21Z, during a dark wake, and then received nothing for **5 h 43 min**. The Mac woke fully at 10:28Z and
stayed awake; the prototype stayed silent — logging no warning — for another **2 h 36 min**, until 13:04:41Z.
Tradebench captured 63,355 ticks in that window. This is the prototype's *silent while connected* failure, the
2026-08-04 scar Tradebench's resilience belt was built to catch (Field Manual chapter 10).

## One-minute bars

Every one of the prototype's 7,003 bars in the window has a Tradebench bar for the same minute; the prototype
has no minute Tradebench lacks.

| How the prototype got the bar | Bars | Identical after rounding | Differ | Largest difference |
|---|---:|---:|---:|---:|
| Streamed live | 4,170 | 4,170 | 0 | — |
| Backfilled from IG's REST history (while the Mac slept) | 2,833 | 2,674 | 159 | 4.2 points |

(Six bars first classed as "streamed" fall in minutes where the prototype caught only 3–31 ticks in a few
seconds of a dark wake, against Tradebench's 173–241: it was asleep for most of the minute and its bar came
from the backfill. They are counted with the backfilled bars here.)

**Every minute the prototype genuinely streamed matches Tradebench exactly.** The 159 differences are all
backfilled minutes, clustered at the most volatile minutes — the 07:00 and 08:00 UK opens. IG's REST history
and its live CHART stream occasionally disagree about a fast minute. That is also why Tradebench's own heal
writes the bars it refills under a separate REST source rather than the stream's (P8, source-attributed
always): the two are not interchangeable.

## Ten-minute bars

Rebuilt from Tradebench's one-minute bars (first open, highest high, lowest low, last close): **662 of 683
identical after rounding.** All 21 that differ contain one of the backfilled one-minute bars above — no
second cause.

## What this settles

- **Coverage.** Tradebench holds every tick the prototype holds, bar six it could not have (its one 644 ms
  rebuild, and four MERGE-collapsed updates), plus 375,172 the prototype missed.
- **Correctness.** Where both streamed the same minute, ticks and bars are identical.
- **Robustness.** In the same nine days the prototype lost data to its host sleeping and to a silent stall
  it never reported; Tradebench ran twelve days unattended without a restart (E1-T7).
- **For the record.** The prototype's REST backfill drew on the IG account's historical allowance — the drop
  the collector's heal saw in `allowanceRemaining` while spending nothing itself (E1-T16's notes).

## Reproducing it

The working files live outside the repository, in `~/development/amfshr/tradebench-data/` (the archive mirror
and the prototype's exports), and the queries in `compare.sql` beside them. The prototype is retired after this
report, so the exports are the last copy of its side.
