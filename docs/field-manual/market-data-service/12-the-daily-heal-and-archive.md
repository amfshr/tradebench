# Chapter 12 — The daily heal and archive

The stream is the collector's main job; this is its second. Every day, for every market, the
collector looks back at what it recorded, fills the 1-minute bars it missed from IG's REST history,
and writes the day off the box as two Parquet files. This chapter is how that works, what it
decides, and how to read what it did. Everything here is true of the code as built in E1-T6.

## 1. Two jobs, one runner

**The heal** fills missing 1-minute bars from IG's REST history. It cannot fill ticks: IG's REST
API has no tick history, so ticks are stream-only and the heal never claims otherwise. **The
archive** writes a market's day as Parquet into a bucket off the box: an offsite copy that
survives the server, and a backtest-ready file that needs no database to read.

Both run on one plain scheduled executor inside the collector's own process, the `capture-jobs`
thread (`jobs.DailyJobs`, `jobs.JobsRunner`). There is no Spring and no extra process (D6 amended
2026-10-15). Every attempt of every phase writes one `job_runs` row, clean or failed: the row's
absence is the alarm.

## 2. When a day is due

Nothing here runs at a configured time. The runner wakes every five minutes and asks the data a
question: which market-days have ended, settled, and not yet succeeded?

| Rule | Number | Why |
|---|---|---|
| A UTC day is due once it has ended and **settled** | 10 minutes after midnight UTC | the last minute's bar seals and IG's history catches up |
| A phase is due **until it succeeds** | — | a failure is retried, a success is never repeated |
| The archive waits for the heal to be **attempted**, not to succeed | — | a day stuck on a failing heal is still archived |
| The archive is due again whenever the heal **succeeds after it** | — | a heal that finally works gets its bars into a fresh archive |
| A failed phase is retried on a doubling backoff | 1 h, 2 h, 4 h … capped at 24 h | a weekend's exhausted allowance does not write a row every five minutes |
| Only the last seven complete days are considered | 7 days | bounds catch-up; the allowance's own guards (§4) bound what it can spend |

A collector that was down at midnight does the work when it comes back: catch-up is the same
question asked later. Nothing in the runner can stop the collector — a phase's failure is a failed
row (a native library that will not load included), a database failure ends the tick, and the next
wake tries again. A stop during a phase is not recorded as a failure, so the next start is not held
back by the backoff.

## 3. What "open" means — IG's own word

IG publishes no trading hours for the DAX epic (`GET /markets` returns `openingHours: null`,
captured 2026-10-16), so there is no hand-kept calendar. The heal reads "open" from what IG said,
as the stream recorded it (D25 amended 2026-10-16). Every minute of the day gets one of three
states (`heal.DaySpans`):

- **Open** — the collector was observing, and IG's deal flag said `DEAL` at some point in the
  minute. A missing bar here is a real gap.
- **Closed** — the collector was observing, and IG's flag said anything else (`CLOSED`, `SUSPEND`,
  `EDITS_ONLY` …) for the whole minute. Never a gap; never asked for.
- **Unknown** — the collector was not observing the whole minute, or IG had not yet said anything.
  The heal asks IG, whose candles answer whether the market was open.

**When was the collector observing?** Only while it was *streaming*. Within each run of the process
— from its boot (`service_start`) to its last sign of life before the next boot, the latest tick or
event row — the windows are the spans a recorded `connection_status` says streaming (D27's one rule).
A run starts not streaming, so the handshake after a boot is not observing; a stream outage inside
one run is not observing either, which matters at a reopen: if the stream drops under `CLOSED` at
21:10 and only streams again at 22:30, the reopen at 22:00 was not seen, so 22:00–22:29 is unknown
and asked of IG, not read as closed. Between runs the collector was down. An orderly stop writes
`service_stop`; a crash writes nothing, which is why the last sign of life ends the run. History
recorded before boots and statuses were written (before E1-T6) is taken as observed, as it was.

**Two guards keep the allowance safe.** Minutes before the collector's first-ever record are out of
scope: the platform never claimed to have captured them, and backfilling history is a different
job (backlog B9). And closed minutes are never asked for, so IG's own closures cost nothing.

Holidays need no list: IG never flags them `DEAL`.

## 4. The heal, step by step

`heal.Healer` runs once per market-day:

1. **Classify** every minute of the day (§3).
2. **Find the missing minutes**: open or unknown, with no bar from any source.
3. **Group them into runs** of contiguous minutes, at most 720 each.
4. **Ask IG** for each run with a dated history request
   (`IgRestClient.minuteCandlesBetween`). IG reads the window's `from` and `to` in the **account's
   local time**, not UTC (captured 2026-10-16: asked 08:00–08:10 on a UTC+1 account, served
   07:00–07:10Z), so the window is shifted by the account's offset, which IG reports at login.
   Every returned candle is checked against the UTC window asked for; one outside it means IG read
   another zone — the call fails loud rather than heal the wrong minutes. Both ends are inclusive,
   and the allowance is one point per candle returned.
5. **Write what IG has** under the REST source of the environment (`ig-rest-demo` or
   `ig-rest-live`), so a healed minute is always told from a streamed one (P8).
6. **Close each gap row** whose last minute falls in the day, with IG's answer.
7. **Record any gap the pump never saw** — one across a restart, which the pump cannot detect —
   already closed with its outcome. A run that reaches midnight is the exception: the pump writes a
   gap across midnight only when the next day's first bar seals, so the next day's heal closes that
   row, and recording half of it now would count its minutes twice.

**Before each run** the heal checks the allowance IG last reported — remembered across attempts until
IG says it resets, so a retry never spends as if the week were fresh — and keeps a reserve of 2,000
points it never spends, for manual heals and anything else sharing the key. If the next run would
go below the reserve, the heal stops loud: the bars it healed stay, the rest of the rows stay open,
and the runner retries later.

**The account's offset can go stale.** IG reports it at login, and a clock change (British Summer
Time ends on the last Sunday of October) makes the login's wrong for the rest of that session.
When IG serves a window at another offset, the request fails with the offset IG actually used, read
off the served candles' own local and UTC stamps; the heal asks once more at that offset and keeps
it. One wasted request per change, never a retry storm. A retry also takes the session's failure
path, so a dead token is replaced rather than reused.

**Retries** (`heal.RetryingCandleSource`, Resilience4j): three attempts, doubling from two seconds,
for transient failures only — an I/O error, or an IG error the classifier calls retryable. Never a
lockout, and never the week's historical allowance running out: IG's code for that classifies as
retryable by default, but waiting minutes cannot refill a weekly budget, so it fails at once.

## 5. The outcomes

Each gap row in `bar_gaps` ends with one `heal_outcome`:

| Outcome | Means | Your action |
|---|---|---|
| `healed` | IG filled some or all of the gap's minutes | none — the bars are in, under the REST source |
| `tickless_at_source` | IG had no candle for any of them: nobody traded | none — nothing of ours was lost |
| `closed_at_source` | every minute was inside a span IG itself flagged closed | none — it was never a gap (the pump records gaps across the overnight close) |
| *(still open)* | the heal has not run on it yet, or failed | look at the day's `job_runs` row for the reason |

A heal that fails writes no outcome: the row stays open for the retry, and the failure is in the
`job_runs` row's detail.

## 6. The archive

`archive.Archiver` turns one market's UTC day into two files (`archive.DayParquet` holds the
contract):

| File | Columns |
|---|---|
| `ticks.parquet` | `ts_utc` (UTC, microseconds), `bid`, `ask`, `source` |
| `bars.parquet` | `start_utc`, `bid_o`…`bid_c`, `ask_o`…`ask_c`, `ltv` (optional), `source` |

Prices are `DECIMAL(18,6)`: exact, and a price that needs more than six decimals fails the export
rather than be rounded. Every row carries its source, so a healed bar is visible in the file.
Files are Zstandard-compressed. They are written through an in-memory DuckDB, because the usual
Java Parquet library needs Hadoop at runtime; DuckDB also reads a folder of daily files as one
table, which is how a backtest will read them.

The files go into the bucket at `<user>/<source>/<epic>/<date>/ticks.parquet` (and `bars.parquet`),
uploaded with their SHA-256 so S3 and R2 verify the bytes they received. Then one row per file goes
into `archives` — only after the file landed, so a row always names an object that exists. A re-run
replaces both. A day with nothing recorded archives nothing, and its row says so.

**Switching it on:** the archive runs only when all five `TRADEBENCH_ARCHIVE_*` variables are set
(endpoint, region, bucket, access key, secret). With none, the daily job heals only and says so at
boot. With some, the collector refuses to start: half a bucket configuration is a mistake. The
bucket's provider is chosen at E1-T7; the code speaks the S3 protocol, so R2, AWS S3, Google Cloud
Storage, Backblaze and Hetzner all work.

## 7. Reading what it did

```sql
-- the day's runs: one row per phase per market-day attempt
SELECT job_name, covers_date, status, started_utc, detail
FROM job_runs WHERE covers_date >= current_date - 2 ORDER BY started_utc;

-- what the heal decided about each gap
SELECT gap_from_utc, gap_to_utc, missing_minutes, heal_outcome
FROM bar_gaps WHERE gap_from_utc >= current_date - 2 ORDER BY gap_from_utc;

-- what is off the box
SELECT kind, covers_date, object_key, bytes FROM archives ORDER BY covers_date DESC, kind;
```

A heal row's `detail` says how many minutes were expected and missing, how many runs were asked,
how many minutes IG filled, the points spent and the allowance left. An archive row's says how many
ticks and bars it wrote and their bytes.

Chapter 11 §8 is the evening check that reads these by hand until the operator console (E9) does.
