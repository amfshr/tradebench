-- The platform tier's owner is the user 'platform' (D31). Renamed in place: the row's id is
-- unchanged, so nothing keyed by user_id moves. V1's seed and V2's lookup are immutable under the
-- Flyway checksum, hence a migration rather than an edit.
UPDATE users SET name = 'platform' WHERE name = 'default-user';
