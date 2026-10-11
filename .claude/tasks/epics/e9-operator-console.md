# E9 plan — Operator Console (Market Data Manager v0)

> The per-ticket planning layer for E9: the read-model surface that makes the deployed 24/7
> collection service observable and its data retrievable **without SSH**. A separate `web/` SPA
> + a thin read-only service over the `market_data` data product (D17) + R2, edge-gated by
> Cloudflare Tunnel + Access (D24). The first operational slice of PRD §5.1 (Market Data
> Manager) + the §9 "dashboard = informational" tier. **Plans here are living sketches** —
> refined when `/start-ticket` picks a ticket up; real scope changes go through the board.
>
> Sources: design session `docs/design/sessions/2026-09-30-collection-service-operability.md`
> (rulings R1–R6), decisions D24 (this epic), D30 (the digest and alerts are E9's, 2026-10-11),
> D17 (data product), D11 (read-model pattern), D16 (secrets); PRD §5.1, §8, §9; data-platform-design §6 (the coverage map).

## Epic shape

The collector (E1) stays outbound-only and single-purpose; it *produces* everything observable
into its DB + R2. E9 is the **read model** over that. **Build order:** T1 (design the
event/metric/data model — the gate) → then build tickets cut from T1's design: the read-model
service, the SPA, the Cloudflare edge + deploy, the digest + alerts reporter (T5 — D30), and an
optional Grafana/Prometheus metrics surface. **Dependency into E1:** T1 must land before E1-T5 slice B implements the event log, so
`service_events` v2 is written in the right shape the first time. **Read-only v1** — observe +
download; control is deferred to the event/command plane (never REST-into-the-collector).

The console serves **three data classes** (R6): time-series metrics (Micrometer → Prometheus →
Grafana, *additive*), discrete events (`service_events` v2 — incl. errors/warnings), and domain
data views (coverage map, gaps, catalogue, downloads — the SPA's core job). The push tier
(digest + stale-heartbeat alarm = "serious") is **E9's too since D30** (2026-10-11): the collector
streams and stores — it does not email, alert or report — so **T5** builds the reporter over
`job_runs`, `capture_status`, `bar_gaps` and the archive rows; the digest's content proves the
collector lived, its own absence alarms the reporter (P9 in two layers). Until T5 lands Alex checks
the collector by hand each evening (the accepted cost, ruled explicitly).

---

## T1 — Observability & data-model design ✅ (ruled 2026-09-30)

**Type** design · **Branch** `—` · **Started** 2026-09-30 · **Blocked by** —

**Ruled 2026-09-30 (Alex, R1–R7) → spec: `docs/design/observability-and-data-model.md`; decision
D25.** Q1 (streaming schedule) folded in and resolved (the three-clock model + generous window +
expected calendar). Output feeds **E1-T5 slice B** (`service_events` v2 + `bar_gaps` +
`capture_status`) and **E1-T6** (`archives` + `heal_outcome`). Build tickets T2–T4 detailed from
this design.

The full design of what the console captures and serves — Claude proposes, Alex rules. Covers
the whole surface, not just "service stuff": errors, warnings, performance, and data/coverage
views.

**Scope to design:**
1. **Event schema — `service_events` v2.** A superseding design of the first-pass table. Event
   *kinds* by category (lifecycle: start/stop/config-loaded/window-open/close · resilience:
   reconnect + backoff-state, stuck-substate escalation, session-refresh · data-liveness:
   watchdog-stale/recovered, market-quarantined/released · data-quality: bar-gap (expected vs
   present), bucket-void, oracle-mismatch, malformed · heal/archive: daily-heal outcome,
   backfill, parquet-archived, pg_dump, digest-sent/suppressed · **errors & warnings:** typed
   IG/DB/sink failures, warnings · rate/budget: pacer-discovered, allowance-low/exhausted).
   Each row: id, **event-time (UTC) + recorded-at + monotonic marker** (D38 dataTime doctrine),
   user + source + instance (dimensions from day one), market (nullable), **severity**
   (info/warn/error), event_type, structured detail (JSON), correlation id. Indexing for the
   dashboard queries.
2. **Time-series metric set.** The Micrometer names + types (counter/gauge/histogram) — tick
   rate, bars, dropped, malformed, db_pending, written, pub_failures, reconnects, last-tick-age
   & last-bar-age per market, pacer budget used, JVM heap/GC/threads/uptime, heartbeat
   freshness — and the **Grafana-vs-DIY split**: what belongs in Prometheus/Grafana vs what the
   SPA renders from DB rows. Decide whether the metrics surface ships in v1 or is added later
   (it is additive).
3. **The read-model queries** behind each dashboard view — the SQL over the `market_data` data
   product for: **health** (last-tick-age per market, streaming state, reconnects, drops, from
   status rows), **coverage map** (expected-calendar vs present bars → complete/gap/closed —
   the first-class map data-platform-design §6 always wanted), **gaps table** (open + healed,
   classified), **capture catalogue** (market × source × date-range, counts), **data-quality**
   (oracle-mismatch / malformed rates over time), **downloads** (Parquet by day + `pg_dump`
   backups, as pre-signed links), and the **event log** (filterable by severity/market/type).
4. **Domain/performance views beyond service health** — what a data-quality or
   capture-performance view should show (e.g. tick-rate vs session, gap density, heal
   success-rate trends), so the console teaches the *data*, not just the *service*.

**DoD:** a design doc (`docs/design/…`) + session record with the `service_events` v2 schema,
the metric set + Grafana/DIY split, and the per-view SQL all ruled by Alex; the design is fed
back to **E1-T5 slice B** before it builds the event log. Build tickets (T2+) are cut from it.

---

## T2 — Read-model service ⬜ (build — shape cut from T1)

**Type** build · **Branch** `—` · **Started** — · **Blocked by** T1

A thin **Spring Boot** read-only service (Spring's earning moment; the collector stays plain
`Main`) over `market_data` via **read-only creds** (D17) + R2 for pre-signed download links.
Exposes the JSON API the SPA consumes; Actuator health for free. Localhost-bound behind the
tunnel — no public origin. **DoD:** cut from T1's per-view queries.

## T3 — The console SPA ⬜ (build — shape cut from T1)

**Type** build · **Branch** `—` · **Started** — · **Blocked by** T1, T2

A brand-skinned (Quiet Terminal, D19) `web/` app — health, coverage map, gaps, catalogue,
downloads, event log (errors/warns). Reuses the E2 read-model + brand patterns; thin visual
client (PRD §10). **DoD:** cut from T1.

## T4 — Cloudflare edge + deploy ⬜ (ops — shape cut from T1 / E1-T7)

**Type** ops · **Branch** `—` · **Started** — · **Blocked by** T2, T3, E1-T7

Cloudflare Tunnel (`cloudflared`, outbound — no inbound ports) + Access (per-person edge auth)
fronting the console; deployed alongside the collector in the compose stack (D24). Optional
follow-on: a Grafana/Prometheus metrics surface as a container behind the same tunnel.
**DoD:** cut from T1 + the E1-T7 host ruling.

## T5 — Digest email + alerts ⬜ (born 2026-10-11 from D30 — moved here from E1-T6)

**Type** build · **Branch** `—` · **Started** — · **Blocked by** T2, E1-T6

The daily **capture digest** (counts, gaps, heal outcomes, archive path) and the **stale-heartbeat
alarm** — every notification — read from `job_runs`, `capture_status`, `bar_gaps` and the archive
rows the collector writes (D25). **Email transport decided here** (SMTP vs API service — the decision
E1-T6 carried until D30). **P9 in two layers (D30):** the digest's *content* proves the collector
lived (heartbeat freshness over the day, the heal's and archive's `job_runs` rows, open gaps); the
digest's own *absence* alarms the reporter. **Why here, not in the collector (D30):** the digest is a
report over stored data, so a reader should produce it; mail transport, templates and credentials are
a configuration surface unrelated to streaming and would widen the collector's deploy and its secrets
for no data benefit. **Interim (the accepted cost, ruled explicitly):** no email until this lands —
from the collector's first deploy (E1-T7) Alex checks it by hand each evening: the heartbeat line,
`capture_status` freshness, the day's `job_runs` row. A thin reporter pulled forward before E9 was
declined. **Blocked by** T2 (the natural home — a scheduled task in the read-model service, to be
settled in-ticket) and E1-T6 (the heal/archive `job_runs` rows it reports). **In-ticket decisions:**
email transport (SMTP vs API service); where the reporter runs (inside T2's service or beside it);
the heartbeat alarm's staleness window (reuse D25's `capture_status` freshness). **DoD:** on staging a
day's digest arrives built from the rows; a suppressed digest (the reporter down, or the collector
silent) is alarmed — the failure-mode tests mutation-verified; the evening hand check retires from
the runbook.
