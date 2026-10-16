-- The platform tier's owner is the user 'platform' (D31, R10 — design session 2026-10-15): the
-- deployed collector persists rows under the admin-owned platform tier, PRD §7's platform-provided
-- datasets every user sees. Renamed in place before the first production rows: the row's id is
-- unchanged, so nothing keyed by user_id moves. V1's seed and V2's lookup are immutable under the
-- Flyway checksum, hence a migration rather than an edit.
UPDATE users SET name = 'platform' WHERE name = 'default-user';
