-- archives: the manifest of what the EOD job wrote off the box (D25 R6; E1-T6). One row per object:
-- a market's day as Parquet (one file per kind), later the nightly pg_dump (E1-T7). The console
-- lists these rows and pre-signs object_key — it never lists the bucket.
CREATE TABLE archives (
    id             bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id        smallint NOT NULL REFERENCES users (id),
    source_id      smallint REFERENCES sources (id),
    instrument_id  smallint REFERENCES instruments (id),
    kind           text NOT NULL,
    covers_date    date,
    object_key     text NOT NULL UNIQUE,
    bytes          bigint NOT NULL,
    sha256         text NOT NULL,
    created_at_utc timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT archives_kind CHECK (kind IN ('parquet_ticks_day', 'parquet_bars_day', 'pg_dump')),
    CONSTRAINT archives_day_shape CHECK (
        (kind = 'pg_dump' AND instrument_id IS NULL AND source_id IS NULL)
        OR (kind <> 'pg_dump' AND instrument_id IS NOT NULL AND source_id IS NOT NULL AND covers_date IS NOT NULL)),
    CONSTRAINT archives_sha256 CHECK (sha256 ~ '^[0-9a-f]{64}$'),
    CONSTRAINT archives_bytes CHECK (bytes >= 0)
);
-- One object per market-day per kind: a re-run replaces its row (upsert on this key), never adds a second.
-- A dump's market, source and day are NULL, and NULLs are distinct here, so dumps never collide.
CREATE UNIQUE INDEX archives_market_day ON archives (user_id, source_id, instrument_id, kind, covers_date);
CREATE INDEX archives_by_day ON archives (covers_date DESC);
