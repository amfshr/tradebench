-- Minutes missing between consecutive sealed bars (T5 formalises this as gap rows).
SELECT prev_start + interval '1 minute' AS gap_from,
       start_utc - interval '1 minute'  AS gap_to,
       extract(epoch FROM (start_utc - prev_start))/60 - 1 AS missing_minutes
FROM (SELECT start_utc,
             lag(start_utc) OVER (PARTITION BY instrument_id, source_id, user_id
                                  ORDER BY start_utc) AS prev_start
      FROM bars_1m) g
WHERE prev_start IS NOT NULL AND start_utc - prev_start > interval '1 minute'
ORDER BY gap_from DESC;
