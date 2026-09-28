-- Tradebench capture schema V1 (E1-T4). User + data-source are first-class dimensions on
-- every fact row from day one (PRD §2/§7); rulings in the T4 design nod (2026-09-29).

CREATE TABLE users (
    id   smallint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    name text NOT NULL UNIQUE
);
INSERT INTO users (name) VALUES ('default-user');

CREATE TABLE sources (
    id   smallint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    name text NOT NULL UNIQUE
);
INSERT INTO sources (name) VALUES ('ig-stream-demo'), ('ig-stream-live'), ('ig-rest-heal');

CREATE TABLE instruments (
    id   smallint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    epic text NOT NULL UNIQUE,
    name text
);

CREATE TABLE ticks (
    id            bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id       smallint NOT NULL REFERENCES users (id),
    source_id     smallint NOT NULL REFERENCES sources (id),
    instrument_id smallint NOT NULL REFERENCES instruments (id),
    ts_utc        timestamptz NOT NULL,
    bid           numeric NOT NULL,
    ask           numeric NOT NULL,
    -- Exact duplicates (redelivery / re-import) drop idempotently; same-millisecond ticks
    -- with different prices both survive (finest-truth, T4 Q2 ruling).
    CONSTRAINT ticks_dedupe UNIQUE (user_id, source_id, instrument_id, ts_utc, bid, ask)
);
CREATE INDEX ticks_series_idx ON ticks (instrument_id, source_id, user_id, ts_utc);

CREATE TABLE bars_1m (
    user_id       smallint NOT NULL REFERENCES users (id),
    source_id     smallint NOT NULL REFERENCES sources (id),
    instrument_id smallint NOT NULL REFERENCES instruments (id),
    start_utc     timestamptz NOT NULL,
    bid_o numeric NOT NULL,
    bid_h numeric NOT NULL,
    bid_l numeric NOT NULL,
    bid_c numeric NOT NULL,
    ask_o numeric NOT NULL,
    ask_h numeric NOT NULL,
    ask_l numeric NOT NULL,
    ask_c numeric NOT NULL,
    ltv   bigint,
    PRIMARY KEY (user_id, source_id, instrument_id, start_utc)
);

CREATE TABLE service_events (
    id         bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    instance   text NOT NULL,
    event_type text NOT NULL,
    at_utc     timestamptz NOT NULL,
    payload    jsonb
);
CREATE INDEX service_events_type_idx ON service_events (event_type, at_utc);

CREATE TABLE job_runs (
    id           bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    job_name     text NOT NULL,
    instance     text NOT NULL,
    started_utc  timestamptz NOT NULL,
    finished_utc timestamptz,
    status       text NOT NULL,
    detail       jsonb
);
