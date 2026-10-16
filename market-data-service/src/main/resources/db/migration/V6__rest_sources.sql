-- The heal's bars come from IG's REST history, not the stream, so they carry their own source
-- (P8) — and a source follows its environment, as the stream's do (docs/design/secrets-and-config.md):
-- a demo heal and a live heal must never share one. V1's 'ig-rest-heal' named no environment; it
-- is left unused rather than renamed, since nothing has written under it.
INSERT INTO sources (name) VALUES ('ig-rest-demo'), ('ig-rest-live');
