-- Tradebench observability data model V2 (E1-T5 slice B; design E9-T1 / decision D25).
-- service_events grows to v2 (dimensions + severity + occurrence/recorded split); bar_gaps and
-- capture_status are born. archives (the T6 manifest) arrives with its writer at T6.

-- service_events → v2 -------------------------------------------------------------------------
ALTER TABLE service_events RENAME COLUMN at_utc TO event_time_utc;
ALTER TABLE service_events RENAME COLUMN payload TO detail;
ALTER TABLE service_events
    ADD COLUMN user_id         smallint REFERENCES users (id),
    ADD COLUMN source_id       smallint REFERENCES sources (id),
    ADD COLUMN instrument_id   smallint REFERENCES instruments (id),
    ADD COLUMN category        text,
    ADD COLUMN severity        text,
    ADD COLUMN correlation_id  text,
    ADD COLUMN recorded_at_utc timestamptz NOT NULL DEFAULT now();

-- Backfill any pre-v2 rows (dev only) so the NOT NULLs can be enforced. Pre-v2 the only event
-- written was the DLG_FLAG change (event_type 'market_state'); rename it to the v2 token so a
-- console query on the new vocabulary surfaces historical dev rows too.
UPDATE service_events SET
    user_id    = (SELECT id FROM users WHERE name = 'default-user'),
    category   = 'data_liveness',
    severity   = 'info',
    event_type = 'market_state_change'
    WHERE user_id IS NULL;

ALTER TABLE service_events
    ALTER COLUMN user_id  SET NOT NULL,
    ALTER COLUMN category SET NOT NULL,
    ALTER COLUMN severity SET NOT NULL;

DROP INDEX service_events_type_idx;
CREATE INDEX service_events_feed_idx ON service_events (event_time_utc DESC);
CREATE INDEX service_events_sev_idx  ON service_events (severity, event_time_utc DESC);
CREATE INDEX service_events_mkt_idx  ON service_events (instrument_id, event_time_utc DESC);

-- bar_gaps: GapDetector.Gap persisted; healed_at / heal_outcome are T6's to set ----------------
CREATE TABLE bar_gaps (
    id              bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id         smallint NOT NULL REFERENCES users (id),
    source_id       smallint NOT NULL REFERENCES sources (id),
    instrument_id   smallint NOT NULL REFERENCES instruments (id),
    gap_from_utc    timestamptz NOT NULL,
    gap_to_utc      timestamptz NOT NULL,
    missing_minutes integer NOT NULL,
    detected_at_utc timestamptz NOT NULL DEFAULT now(),
    healed_at_utc   timestamptz,
    heal_outcome    text,
    CONSTRAINT bar_gaps_span UNIQUE (user_id, source_id, instrument_id, gap_from_utc, gap_to_utc)
);
CREATE INDEX bar_gaps_open_idx ON bar_gaps (instrument_id) WHERE healed_at_utc IS NULL;

-- capture_status: one UPSERTed row per (instance, market) — the console's near-live health ------
CREATE TABLE capture_status (
    instance         text NOT NULL,
    instrument_id    smallint NOT NULL REFERENCES instruments (id),
    updated_at_utc   timestamptz NOT NULL,
    stream_state     text NOT NULL,
    market_state     text,
    last_tick_at_utc timestamptz,
    last_bar_at_utc  timestamptz,
    ticks_total      bigint NOT NULL,
    bars_total       bigint NOT NULL,
    dropped_ticks    bigint NOT NULL,
    malformed        bigint NOT NULL,
    reconnects_total bigint NOT NULL,
    db_pending       integer,
    PRIMARY KEY (instance, instrument_id)
);
