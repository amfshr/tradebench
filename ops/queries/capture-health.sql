-- Capture health at a glance: totals, freshness, per-minute tick rate (last 10 min).
SELECT (SELECT count(*) FROM ticks)                          AS ticks_total,
       (SELECT count(*) FROM bars_1m)                        AS bars_total,
       (SELECT max(ts_utc) FROM ticks)                       AS last_tick_utc,
       (SELECT max(start_utc) FROM bars_1m)                  AS last_bar_start_utc,
       now() - (SELECT max(ts_utc) FROM ticks)               AS tick_age;

SELECT date_trunc('minute', ts_utc) AS minute_utc, count(*) AS ticks
FROM ticks
WHERE ts_utc > now() - interval '10 minutes'
GROUP BY 1 ORDER BY 1 DESC;
