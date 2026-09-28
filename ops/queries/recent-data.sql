-- Latest ticks and bars with instrument/source/user attribution spelled out.
SELECT i.epic, t.ts_utc, t.bid, t.ask, s.name AS source, u.name AS "user"
FROM ticks t
JOIN instruments i ON i.id = t.instrument_id
JOIN sources s     ON s.id = t.source_id
JOIN users u       ON u.id = t.user_id
ORDER BY t.ts_utc DESC LIMIT 20;

SELECT i.epic, b.start_utc, b.bid_o, b.bid_h, b.bid_l, b.bid_c,
       b.ask_c, b.ltv, s.name AS source
FROM bars_1m b
JOIN instruments i ON i.id = b.instrument_id
JOIN sources s     ON s.id = b.source_id
ORDER BY b.start_utc DESC LIMIT 20;
