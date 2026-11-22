-- One line per archived day of one market (E1-T17, B25): ticks, bars, bars the heal filled from IG's history, the first
-- and last bar, the minutes missing between them, the holes and the longest. Run from the market's folder in the mirror
-- (scripts/r2-mirror.sh does): duckdb -no-agent -markdown < scripts/analysis/archive-summary.sql
SET TimeZone='UTC';
WITH t AS (SELECT regexp_extract(filename,'(\d{4}-\d{2}-\d{2})') AS day, count(*) n FROM read_parquet('*/ticks.parquet', filename=true) GROUP BY 1),
b AS (SELECT regexp_extract(filename,'(\d{4}-\d{2}-\d{2})') AS day, start_utc, source FROM read_parquet('*/bars.parquet', filename=true)),
bs AS (SELECT day, count(*) bars, count(*) FILTER (WHERE source LIKE 'ig-rest-%') healed,
        strftime(min(start_utc)::timestamp,'%H:%M') first_bar, strftime(max(start_utc)::timestamp,'%H:%M') last_bar,
        ((epoch(max(start_utc))-epoch(min(start_utc)))/60+1 - count(*))::int missing_inside FROM b GROUP BY 1),
g AS (SELECT day, start_utc, ((epoch(start_utc)-epoch(lag(start_utc) OVER (PARTITION BY day ORDER BY start_utc)))/60 - 1)::int AS gap FROM b),
gs AS (SELECT day, count(*) FILTER (WHERE gap>0) holes, max(gap) longest, arg_max(strftime(start_utc::timestamp,'%H:%M'), gap) longest_ends FROM g GROUP BY 1)
SELECT bs.day, dayname(bs.day::date)[1:3] dow, t.n ticks, bs.bars, bs.healed, bs.first_bar, bs.last_bar, bs.missing_inside, gs.holes, gs.longest, gs.longest_ends
FROM bs LEFT JOIN t USING (day) LEFT JOIN gs USING (day) ORDER BY 1;
