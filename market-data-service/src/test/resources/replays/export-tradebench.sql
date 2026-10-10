-- Export a replay window from Tradebench's own capture (schema V2: ticks, bars_1m) as JSONL
-- for the scenario harness (E1-T11; the format: replays/README.md). Market data only (G1).
-- The bars are the CHART feed's real bid/ask candles. Two honest gaps: ticks carry no DLG_FLAG
-- (dealFlag is omitted — the harness assumes DEAL), and no Lightstreamer statuses are stored,
-- so the header asks the harness to answer as a healthy server. T12 reconstructs more.
--
--   psql -h <host> -p <port> -U <user> -d market_data -At \
--     -v anchor='2026-10-07 15:00:00+00' -v before_secs=360 -v until_secs=460 \
--     -v name='<what happened>' -v user_name='default-user' -v source_name='ig-stream-demo' \
--     -f export-tradebench.sql | jq -c . > <fixture>.jsonl
\set QUIET on
with params as (
  select :'anchor'::timestamptz as anchor,
         :'anchor'::timestamptz - make_interval(secs => :before_secs) as origin,
         :'anchor'::timestamptz - make_interval(secs => :before_secs) + make_interval(secs => :until_secs) as finish,
         :until_secs::int * 1000 as until_ms
),
dims as (
  select u.id as user_id, s.id as source_id from users u, sources s
   where u.name = :'user_name' and s.name = :'source_name'
),
ticks as (
  select i.epic, t.ts_utc as utm, t.bid, t.ask
    from ticks t join instruments i on i.id = t.instrument_id, params p, dims d
   where t.user_id = d.user_id and t.source_id = d.source_id and t.ts_utc >= p.origin and t.ts_utc < p.finish
),
bars as (
  select i.epic, b.start_utc as minute, b.bid_o, b.bid_h, b.bid_l, b.bid_c, b.ask_o, b.ask_h, b.ask_l, b.ask_c, b.ltv
    from bars_1m b join instruments i on i.id = b.instrument_id, params p, dims d
   where b.user_id = d.user_id and b.source_id = d.source_id
     and b.start_utc >= date_trunc('minute', p.origin) + interval '1 minute'
     and b.start_utc + interval '1 minute' < p.finish
),
epics as (
  select coalesce(json_agg(e.epic order by e.epic), '[]'::json) as list
    from (select epic from ticks union select epic from bars) e
),
lines as (
  select 0::bigint as at, 0 as ord, json_build_object('at', 0, 'kind', 'replay', 'payload', json_build_object(
           'name', :'name',
           'recorded', :'recorded',
           'modelled', :'modelled',
           'source', 'tradebench market_data: ticks + bars_1m (the CHART feed''s bid/ask candles); ticks carry no DLG_FLAG, so dealFlag is omitted; no Lightstreamer statuses are stored, so the server answers as a healthy one would',
           'origin', to_char(p.origin at time zone 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.MS"Z"'),
           'anchor', to_char(p.anchor at time zone 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.MS"Z"'),
           'anchorMs', :before_secs::int * 1000,
           'epics', e.list,
           'serverAnswers', true,
           'untilMs', p.until_ms))::text as line
    from params p, epics e
  union all
  select round(extract(epoch from (t.utm - p.origin)) * 1000)::bigint, 2,
         json_build_object('at', round(extract(epoch from (t.utm - p.origin)) * 1000)::bigint, 'kind', 'tick',
           'payload', json_build_object('epic', t.epic, 'bid', t.bid::text, 'ask', t.ask::text))::text
    from ticks t, params p
  union all
  select round(extract(epoch from (b.minute + interval '1 minute' - p.origin)) * 1000)::bigint, 1,
         json_build_object('at', round(extract(epoch from (b.minute + interval '1 minute' - p.origin)) * 1000)::bigint, 'kind', 'bar',
           'payload', json_build_object('epic', b.epic,
             'startUtc', to_char(b.minute at time zone 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS"Z"'),
             'bid', json_build_object('open', b.bid_o::text, 'high', b.bid_h::text, 'low', b.bid_l::text, 'close', b.bid_c::text),
             'ask', json_build_object('open', b.ask_o::text, 'high', b.ask_h::text, 'low', b.ask_l::text, 'close', b.ask_c::text),
             'ltv', b.ltv))::text
    from bars b, params p
)
select line from lines order by at, ord;
