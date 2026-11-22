# The one-month review: storage, headroom, a second market, per-second data and the DAX's hours

**Status: CLOSED** (2026-11-22). Resolves backlog **B26**, the review of the first month of production data and of
the limits around it. Decisions **D39** (dumps leave the ticks to the Parquet archive) and **D40** (the data scope
after the first month: NASDAQ next, per-second derived from ticks). Spawns the follow-ups ticket's backup half, a
NASDAQ ticket, a reference page, and B24's evidence.

## Why now

The collector has run in production since 2026-10-18 and archived every trading day since 2026-10-19. B26 was
captured on 2026-10-30 to ask, once a month of data existed, whether R2's growth is sustainable, whether there is
room for a second market or per-second data, and what the DAX's hours on IG really are. The 2026-11-15 review
(B26's evidence) showed the bucket growing about 100 MiB a week, nearly all of it database dumps.

## Settled before the session

- **P8** — data collected forever, source-attributed. The archive of record is the Parquet in R2 (D25; the seed
  pack's data-platform design §5: "Parquet … = immutable published archive … write-once").
- **D34** — nightly `pg_dump` to R2, dailies and weeklies kept; Hetzner's whole-box backups are the fast restore.
- **D25 / D35** — "open" is a recorded dealing span (`DEAL`, `DEALNOEDIT`, `CLOSINGSONLY`), not a calendar: IG
  publishes no hours for the DAX epic (`openingHours: null`). A broker's own bar arrives, if ever, as a new `source_id`.
- **D36** — the Python prototype no longer draws on the shared IG key's allowance.

## The evidence (2026-11-22)

All read without touching production: R2 through the read-only remote, the app through Grafana, the archive and the
latest dump locally (`~/development/amfshr/tradebench-data/`).

**Storage.** R2 held 393 MiB: dumps 369 MiB (8 dailies, 5 weeklies), Parquet 24 MiB (31 days). Each dump is the
whole database; the weekly dumps grew 7.1 → 14.1 → 22.9 → 29.8 → 38.8 MB, about 8 MB a week, and some 13 copies are
kept — so R2 grew about 100 MiB a week, the weekly increase itself growing. Inside the latest dump (uncompressed):

| Table | Size | Rows |
|---|---:|---:|
| `ticks` | 254.4 MB | 4,493,331 |
| `bars_1m` | 3.4 MB | 35,564 |
| everything else (`service_events`, `job_runs`, `bar_gaps`, `archives`, status, reference) | ~0.13 MB | ~740 |

Ticks are 98.6% of a dump. The same ticks are already in the Parquet archive, at about 5.5 MB a week.

**Headroom.** The collector: CPU 0.13% on average, the heap at most 133 MB after GC of ~360 MB. Grafana Cloud's free
tier: 591 of 10,000 active series, ~0.2 GB of 50 GB logs a month. IG: the historical allowance untouched (10,000 of
10,000 this week; the heal spent 2 points in three weeks); 2 of the 40 Lightstreamer subscriptions a connection
allows (the seed pack's broker playbook §6); REST paced well inside the 30 a minute per account.

**The DAX on IG runs on New York time.** From the bars and ticks, every day since 2026-10-16:
- Sunday open **18:00 New York** — 22:02 UTC until 2026-11-01, 23:02 UTC after (the US clock change; the EU change on
  2026-10-25 moved nothing).
- Friday close **17:00 New York** — 20:59 / 21:00 UTC, then 21:59 UTC from 2026-11-06.
- Weekdays trade around the clock. The one thin hour is **17:00–18:00 New York** (21:00 UTC while the US is on summer
  time, 22:00 after): 256–472 ticks in it against ~9,000–12,000 in a session hour — the US futures' daily break. Most
  of the archive's missing bar-minutes fall in it.
- The busiest hours: 08:00 UTC (09:00 Frankfurt, the Xetra open) and the US open.

**Per second.** In the busiest hour 96% of seconds carry a tick (3.5 a second on average); overnight 50–65%. The most
ticks in one second: 11.

## The survey, condensed

**Backups and archives are different things.** A backup restores operational state — what is being written now, the
events, the gaps; an archive is the permanent record — write-once, compact, outside the database. The standard shape
(tiered storage; a lake under an operational database) keeps the bulky history only in the archive and backs up the
live state, so backups stay small and flat. Its known failure is a restore path nobody has run: the pattern is only
as good as a restore drill.

**Per-second data.** The seed pack's design (data-platform §2): "skip CHART:SECOND too — raw ticks are finer (and you
can derive 1s if ever needed)". The caveat: IG's price stream is conflated (MERGE mode — D36 saw it collapse rapid
updates differently per session), so a burst inside one second can lose its extreme; IG's own `CHART:…:SECOND`
candles would keep it. That matters only to a strategy that needs exact intra-second highs and lows.

## Rulings

All five as recommended — Alex, 2026-11-22: "i agree with all your recommendations".

- **R1 — Dumps leave the ticks out; the Parquet archive is the record for ticks.** The nightly dump runs with
  `--exclude-table-data=ticks`; a restore is the dump, then the ticks reloaded from Parquet (a small DuckDB script).
  Proven by a restore drill before it is relied on. Dumps fall from ~39 MB to ~1–2 MB; R2 then grows by about the
  Parquet's 5.5 MB a week per market. Retention stays as it is; fewer copies and harder compression (B26's options b
  and c) are no longer needed. *Why:* a dump should carry what only the database holds; the ticks are already kept,
  more compactly, where P8 keeps them forever. → D39.
- **R2 — The cost of R1 is accepted:** ticks from midnight to the 02:30 dump are in neither copy until the next
  night's archive — at most 2.5 overnight hours in a worst-case restore, against today's dumps, which already lose
  everything after 02:30. No extra export. *Why:* small, overnight, and recoverable as bars by the heal. → D39.
- **R3 — A second market: NASDAQ (`IX.D.NASDAQ.CASH.IP`), as its own ticket after the follow-ups.** Capacity is not
  the constraint; the design work is — the witness-quarantine path with two markets, and D25's "a quarantined market
  counts as not observing", built "when a second market arrives". *Why:* the PRD's named second market (§7, "DAX and
  NASDAQ first"); every limit above has an order of magnitude to spare. → D40.
- **R4 — Per-second data is derived from ticks on read;** no `CHART:SECOND` subscription and no extra storage. A
  `CHART:SECOND` stream is added only when a strategy needs exact intra-second extremes, as its own source (D25).
  *Why:* the ticks are the superset (P6); the conflation caveat is real but untested by any strategy yet. → D40.
- **R5 — The DAX's hours are recorded, not coded.** A reference page, `docs/reference/ig-dax-hours.md`; the stream
  stays on 24/5 and "open" stays IG's dealing flags (D25/D35). The thin hour becomes B24's evidence for an adaptive
  watchdog threshold.

## Correction

B26's 2026-11-15 evidence said the Sunday open moved 22:02 → 23:02 UTC "at the EU clock change (2026-10-25)". The
archive shows the move on 2026-11-01, the US change; 2026-10-25 still opened at 22:02. B26's backlog entry is
corrected to point here.

## Spawned work

- **The "Collector follow-ups after the first month" ticket** (proposed on the board): its backup item becomes R1 —
  the dump without ticks, the DuckDB reload script, and a restore drill — in place of "fewer copies". The rest stands:
  B29 (both halves, `v0.2.1`), B25 (the mirror script), B28 if the demo credentials are at hand.
- **A NASDAQ ticket** (R3), after the follow-ups.
- **`docs/reference/ig-dax-hours.md`** (R5), filed with this record.
- **B24** gains the thin-hour evidence.

## Illustrative (not committed)

```bash
# deploy/backup.sh — the dump, without the ticks the Parquet archive holds
docker compose -f compose.prod.yaml exec -T postgres pg_dump -U postgres -Fc --exclude-table-data=ticks market_data > "$dump"

# a restore's second step — ticks back from the mirror, through DuckDB's postgres extension
duckdb -c "INSTALL postgres; LOAD postgres; ATTACH '<dsn>' AS pg (TYPE postgres);
           INSERT INTO pg.ticks SELECT … FROM read_parquet('<mirror>/platform/*/*/*/ticks.parquet');"
```
