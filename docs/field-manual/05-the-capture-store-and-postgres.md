# Chapter 5 — The capture store and Postgres

The pump ends every path at one interface: `CaptureStore`. This chapter is about that
seam, the two implementations behind it, and how the database — not the Java — carries
the other half of the at-least-once bargain from chapter 4.

## The jargon

- **Seam** — an interface placed exactly where you want to be able to swap implementations
  without the caller noticing. Role-named (what it *is for*), while implementations are
  named for what they *are* (`PostgresStore`, `JsonlStore`) — the repo's naming rule
  (tech-notes §4).
- **Idempotent write** — a write that can be applied twice with the same end state as
  once. The absorber for at-least-once redelivery.
- **Upsert** — insert-or-update in one statement; in Postgres, `INSERT … ON CONFLICT …
  DO UPDATE`.
- **Natural key** — a uniqueness rule made of real-world columns (who/where/when), as
  opposed to a surrogate id. Our dedupe lives on natural keys.
- **Migration** — a versioned, append-only SQL script; the schema's git history, applied
  by Flyway.
- **Connection pool** — a cache of open database connections (HikariCP here), because
  opening one is milliseconds of TCP + auth you don't want per statement.

## The concept

The seam earns its place twice. Today it lets T3 and T4 coexist: `TRADEBENCH_SINK=jsonl`
writes evidence files, `db` writes the real path, and `Pump` cannot tell —
*that it cannot tell is the point*. Tomorrow it is where fan-out slots in: architecture
§3.5's plan is a composite store (persist to Postgres first, then best-effort publish to
the bus) dropped into this exact socket, with the pump, queues and ack chain untouched.

The deeper idea of the chapter: **put idempotency in the schema, not the code.** Java
dedupe logic (a seen-set, a "have I written this?" check) is state that dies with the
process and lies across instances. A `UNIQUE` constraint is enforced by the same engine
that stores the data, survives restarts, and applies equally to every writer — the stream
today, the REST healer at T6, a backfill import in a year.

## Our code path

All in `market-data-service/src/main/java/dev/amfshr/tradebench/marketdata/store/`.

**`JsonlStore`** — the T3 evidence path, kept honest: one JSON object per line, and the
*first* line is a `meta` record naming user, source, instance, epics and start time — the
attribution travels inside the file (P8: source-attributed always). BigDecimals serialise
at their exact wire scale (D36), which is what makes the files usable as golden evidence.

**`PostgresStore`** — the real path. Its constructor is a fail-closed gate: it resolves
`user_id` and `source_id` by name and throws `unknown source … refusing to invent one`
if the seeds don't know them (P9). Users and sources are *configuration truth* — seeded
in the migration, never auto-created. Instruments are the deliberate exception
(`instrumentId()` auto-registers with `ON CONFLICT DO NOTHING`, then caches): an epic is
a *discovered fact* — the stream told you it exists — not a config decision.

The write methods split by criticality, mirroring the queues:

- **Bars and state changes apply immediately** — `barUpsert.executeUpdate()` inside
  `write(Bar1m)`. No batching, because batching would break the ack chain: chapter 4's
  `remove` happens when `write` *returns*, so `write` returning must mean "the database
  has it". The bar statement is the upsert above — `ON CONFLICT (user_id, source_id,
  instrument_id, start_utc) DO UPDATE` — so a redelivered bar (Lightstreamer replays
  after reconnect; the pump redelivers after a crash window) re-applies to the same row,
  harmlessly. T6's REST heals write under their *own* source (`ig-rest-heal`, seeded in
  V1), so healed rows sit beside streamed rows with provenance intact rather than
  overwriting it (P8).
- **Ticks batch** — `addBatch()`, flushed by `flush()` (the pump's idle moments) or when
  `++pendingTicks >= TICK_BATCH_LIMIT` (500). That second trigger matters more than it
  looks: under a busy stream the pump never idles, so **the batch-full path is the only
  flush there is** — which is why `PostgresStoreTest` drives exactly 499 writes, then the
  500th, and asserts the rows landed with no explicit flush. Boundary equalities get
  exact tests (testing doctrine G5).

Tick dedupe is the schema's, not the store's — `V1__baseline.sql`:

```sql
CONSTRAINT ticks_dedupe UNIQUE (user_id, source_id, instrument_id, ts_utc, bid, ask)
```

with `ON CONFLICT ON CONSTRAINT ticks_dedupe DO NOTHING` on the insert. Read the key
carefully: it includes the *prices*. An exact duplicate (same instant, same bid, same
ask — redelivery or a re-import) drops silently; two ticks in the same millisecond with
*different* prices are two real market events, and both survive. Dedupe must never
flatten the finest truth (the T4 Q2 ruling).

Every fact row carries `user_id` and `source_id` from V1 — not because multi-user exists
yet (everything is `default-user` today) but because retrofitting a dimension onto
billions of rows is a migration you never want (platform doctrine, PRD §7). Timestamps
are `timestamptz` named `*_utc`, prices are `numeric` fed from BigDecimal — scale exact,
no float rounding, ever.

**The schema is a versioned contract.** Flyway applies `db/migration/V1__baseline.sql` at
`Database.connect()` — no persistence ⇒ do not run. And because D17 makes `market_data` a
*published read product* (other services will read these tables directly), accidental
schema drift is an interface break. `SchemaDriftTest` guards it: run Flyway against a
fresh `postgres:16-alpine` Testcontainer, `pg_dump --schema-only` *inside* the container
(`execInContainer` — no local pg_dump version skew), normalise away the noise (comments,
`SET` lines, and pg_dump's randomised `\restrict`/`\unrestrict` guard lines), and
byte-compare against the committed snapshot in
`src/test/resources/schema/expected-schema.sql`. Drift fails the build and writes
`build/schema-actual.sql` so the diff is one command away; an *intended* change means
copying the new render over the snapshot — a visible, reviewable act.

Two infrastructure choices in a paragraph each. **HikariCP** (`Database.java`): a pool of
4, tiny on purpose — the store checks out one connection for its whole life
(single-threaded by contract, prepared statements prepared once), and the pool mostly
exists so future components share the plumbing. The advisory lock pointedly does *not*
use the pool — that story is chapter 6. **Testcontainers, manual lifecycle**
(`PostgresTestBase`): plain `@BeforeAll`/`@AfterAll`, one container per test class, no
JUnit-extension dependency — fewer moving parts between a red test and its cause.

## The scars

> **Scar** — **Duplicate rows from replay** · playbook §4.1
>
> IG re-sends completed candles and Lightstreamer replays after reconnects — so naive inserts
> duplicate. But over-eager dedupe is worse: it can flatten same-millisecond ticks that are
> real, distinct market events.
>
> **Lesson.** Idempotency lives in the schema, not the code: `ON CONFLICT` upserts for bars, a
> price-inclusive unique key for ticks (T4 Q2) — dedupe that never flattens the finest truth.

Redelivery is not hypothetical: IG genuinely re-sends completed candles (playbook §4.1 —
"dedupe on start-time per epic"), and Lightstreamer replays after reconnects. The
engineering playbook's §1.4 answer — at-least-once plus idempotent-by-key, carried into
CLAUDE.md as standing doctrine — meets this repo's own ruling that dedupe must never
flatten the finest truth (T4 Q2: prices belong in the tick key), and the schema is where
both land. The drift gate is
triage D23's inheritance — schema surprises found at deploy time, promoted here into a
CI-failing byte-diff. And the exact-500 boundary test is doctrine G5 in miniature: the
mutation that breaks `>=` into `>` survives any sloppier test; the exact one kills it.

*Previous: [Chapter 4 — The pump and ack-after-apply](04-the-pump-and-ack-after-apply.md) · [Field Manual index](README.md) · Next: [Chapter 6 — The advisory lock and single-instance](06-the-advisory-lock-and-single-instance.md)*
