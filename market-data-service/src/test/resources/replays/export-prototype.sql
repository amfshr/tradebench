-- Export a replay window from the prototype's capture as JSONL for the scenario harness
-- (E1-T11; the format: replays/README.md). Source: igtrader_demo, schema igtrader — the
-- per-market tick tables only (G1: market data, never strategy_events or the OMS tables).
-- Bars are synthesised per minute from the ticks (the prototype's bars are mid-price with
-- indicators) and sealed only where the feed was still alive at the minute's end. The prototype
-- stored no Lightstreamer statuses, so the header asks the harness to answer as a healthy server.
--
--   docker exec -i <postgres> psql -U <user> -d igtrader_demo -At \
--     -v anchor='2026-08-04 12:38:08.916+00' -v before_secs=360 -v until_secs=460 \
--     -v name='2026-08-04 silent while connected' -f - < export-prototype.sql > <fixture>.jsonl
--
-- anchor: the instant placed at offset before_secs (here the last DAX tick before the silence);
-- the window runs from anchor - before_secs to anchor + until_secs - before_secs.
\set QUIET on
with params as (
  select :'anchor'::timestamptz as anchor,
         :'anchor'::timestamptz - make_interval(secs => :before_secs) as origin,
         :'anchor'::timestamptz - make_interval(secs => :before_secs) + make_interval(secs => :until_secs) as finish,
         :until_secs::int * 1000 as until_ms
),
ticks as (
  select 'IX.D.DAX.DAILY.IP' as epic, t.utm, t.bid, t.ask, btrim(t.dealflag) as flag
    from igtrader.dax_ticks t, params p where t.utm >= p.origin and t.utm < p.finish
  union all
  select 'IX.D.NASDAQ.CASH.IP', t.utm, t.bid, t.ask, btrim(t.dealflag)
    from igtrader.nasdaq_ticks t, params p where t.utm >= p.origin and t.utm < p.finish
),
minutes as (
  select epic, date_trunc('minute', utm) as minute,
         (array_agg(bid order by utm))[1] as bid_o, max(bid) as bid_h, min(bid) as bid_l,
         (array_agg(bid order by utm desc))[1] as bid_c,
         (array_agg(ask order by utm))[1] as ask_o, max(ask) as ask_h, min(ask) as ask_l,
         (array_agg(ask order by utm desc))[1] as ask_c
    from ticks group by epic, date_trunc('minute', utm)
),
bars as (
  select m.* from minutes m, params p
   where m.minute >= date_trunc('minute', p.origin) + interval '1 minute'
     and exists (select 1 from ticks t where t.epic = m.epic and t.utm >= m.minute + interval '1 minute')
),
lines as (
  select 0::bigint as at, 0 as ord, json_build_object('at', 0, 'kind', 'replay', 'payload', json_build_object(
           'name', :'name',
           'source', 'prototype igtrader_demo: igtrader.dax_ticks + igtrader.nasdaq_ticks; bars synthesised per minute from the ticks; no Lightstreamer statuses were stored, so the server answers as a healthy one would',
           'origin', to_char(p.origin at time zone 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.MS"Z"'),
           'anchor', to_char(p.anchor at time zone 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.MS"Z"'),
           'anchorMs', :before_secs::int * 1000,
           'epics', json_build_array('IX.D.DAX.DAILY.IP', 'IX.D.NASDAQ.CASH.IP'),
           'serverAnswers', true,
           'untilMs', p.until_ms))::text as line
    from params p
  union all
  select round(extract(epoch from (t.utm - p.origin)) * 1000)::bigint, 2,
         json_build_object('at', round(extract(epoch from (t.utm - p.origin)) * 1000)::bigint, 'kind', 'tick',
           'payload', json_build_object('epic', t.epic, 'bid', t.bid::text, 'ask', t.ask::text, 'dealFlag', t.flag))::text
    from ticks t, params p
  union all
  select round(extract(epoch from (b.minute + interval '1 minute' - p.origin)) * 1000)::bigint, 1,
         json_build_object('at', round(extract(epoch from (b.minute + interval '1 minute' - p.origin)) * 1000)::bigint, 'kind', 'bar',
           'payload', json_build_object('epic', b.epic,
             'startUtc', to_char(b.minute at time zone 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS"Z"'),
             'bid', json_build_object('open', b.bid_o::text, 'high', b.bid_h::text, 'low', b.bid_l::text, 'close', b.bid_c::text),
             'ask', json_build_object('open', b.ask_o::text, 'high', b.ask_h::text, 'low', b.ask_l::text, 'close', b.ask_c::text)))::text
    from bars b, params p
)
select line from lines order by at, ord;
