-- job_runs gains the dimensions every table carries (user, source) and the market-day a run is
-- for, so "has this market-day's phase succeeded?" is a query, not a JSON search (E1-T6). The
-- table has never been written, so the NOT NULL columns need no backfill; a database that had
-- rows would fail here, loudly.
ALTER TABLE job_runs
    ADD COLUMN user_id       smallint NOT NULL REFERENCES users (id),
    ADD COLUMN source_id     smallint REFERENCES sources (id),
    ADD COLUMN instrument_id smallint REFERENCES instruments (id),
    ADD COLUMN covers_date   date,
    ADD CONSTRAINT job_runs_status CHECK (status IN ('succeeded', 'failed'));
CREATE INDEX job_runs_by_market_day ON job_runs (job_name, user_id, source_id, instrument_id, covers_date, started_utc);
