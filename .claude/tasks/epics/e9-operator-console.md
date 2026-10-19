# E9 plan — The platform app + `control-service` (operator console v0)

> The per-ticket planning layer for E9: the platform app and its control plane. **One front end**,
> `web/platform` (D18), serves every platform page; the operator console — the deployed 24/7
> collection service observed and its data retrieved **without SSH** — is its **role-gated admin
> area**, not a separate app (D31). Behind it one Spring Boot service, **`control-service`**, the
> control plane to `market-data-service`'s data plane: owner of the `control` database (users,
> roles, jobs, settings), the read model over the `market_data` data product (read-only creds, D17)
> + R2, the digest and alerts (D30), later the control actions and the kill switches execution
> needs. Edge-gated by Cloudflare Tunnel + Access (D24); roles in the app (D32). The first
> operational slice of PRD §5.1 (Market Data Manager) + the §9 "dashboard = informational" tier.
> **Plans here are living sketches** — refined when `/start-ticket` picks a ticket up; real scope
> changes go through the board.
>
> Sources: design sessions `docs/design/sessions/2026-09-30-collection-service-operability.md`
> (R1–R6) and `docs/design/sessions/2026-10-15-platform-shell-identity-and-telemetry.md` (R1–R11 —
> the reshape, `9f2a2e2`); decisions D24 (this epic, as amended 2026-10-15), D30 (the digest and
> alerts are E9's, 2026-10-11), **D31** (one front end, `control-service`, vertical slices, the
> `platform` user), **D32** (edge identity then app roles; the trust ladder; notify-then-fetch),
> **D33** (OpenTelemetry → Grafana Cloud; the dead-man ping), D17 (data product), D11 (read-model
> pattern), D16 (secrets), D4 (Redis Streams); PRD §5.1, §8, §9; data-platform-design §6 (the
> coverage map).

## Epic shape

The collector (E1) stays outbound-only, single-purpose and plain `Main` (D6 amended, R7); it
*produces* everything observable into its DB + R2 and takes its jobs by **notify, then fetch**
(D32 (3)) — zero inbound ports, never another service's tables. E9 is the **platform app over
that**: `web/platform` + `control-service`, built in **vertical slices** (R3) — never a backend
without the page that consumes it. **Build order:** T1 ✅ (the event/metric/data model — the gate)
→ **T2 Slice 1** the health view end to end (the `control-service` skeleton, the `control`
database, the auth model, the `web/platform` shell with its admin area and the health page) →
**T4** edge + deploy with **T7** telemetry on `control-service` (the first deploy observable,
D33) → **T3 Slice 2** coverage map · gaps · event log → **T5** digest + alerts (retires the evening
hand check — D30) and **T6 Slice 3** catalogue + downloads (both after E1-T6's rows) → **T8** the
command plane + jobs control (the write side, after the read-only slices; B1's control half + B9's
allowance view folded in) → **T9** mTLS-for-practice, 🧊 until a second Spring app exists (R5). The
sequence after T2 is Alex's to re-order; each ticket's blockers are the technical ones only.
**Timing (Alex, 2026-10-15):** E9 starts once the collector is deployed (E1-T7, the weekend of
2026-10-17) and runs while the collector runs.

Three data classes (the 2026-09-30 session's R6) still frame the views: time-series metrics — now
**operational telemetry over OpenTelemetry to Grafana Cloud** (D33), the admin area linking out,
never re-rendering; discrete events (`service_events` v2 — incl. errors/warnings); and domain data
views (coverage map, gaps, catalogue, downloads — the admin area's core job). The push tier (digest
+ stale-heartbeat alarm = "serious") is **E9's since D30** (2026-10-11): **T5** builds the reporter
inside `control-service` over `job_runs`, `capture_status`, `bar_gaps` and the archive rows; the
digest's content proves the collector lived, its own absence alarms the reporter (P9 in two
layers). Until T5 lands Alex checks the collector by hand each evening (the accepted cost, ruled
explicitly); the dead-man ping (D33, wired at E1-T7) covers the deaths that leave no row.

**The ticket shape as cut 2026-10-15 (R11) — three calls the `board-steward` made, recorded so
they can be re-ruled:** (1) **the auth model is inside Slice 1 (T2), not its own ticket** — the
health page lives in the admin area, the admin area is gated by role, so Slice 1 cannot be end to
end without JWT verification and the user/role mapping; a separate ticket would either block T2
outright (a backend before its page — what R3 forbids) or hold nothing T2 does not need on day
one; what remains of identity later (user administration, Access service tokens for machines,
mTLS) sits with T8 and T9. (2) **the catalogue + downloads are a third slice ticket (T6)**, not
folded into T3 — its blocker differs (E1-T6's `archives` rows and the R2-vs-B2 ruling) and R3
names downloads as the third slice. (3) **telemetry is split by JVM:** E1-T7 wires the collector
and stands up the Grafana Cloud export (the first deploy must be observable); **T7** wires
`control-service` and the admin area's link-out.

---

## T1 — Observability & data-model design ✅ (ruled 2026-09-30)

**Type** design · **Branch** `—` · **Started** 2026-09-30 · **Blocked by** —

**Ruled 2026-09-30 (Alex, R1–R7) → spec: `docs/design/observability-and-data-model.md`; decision
D25.** Q1 (streaming schedule) folded in and resolved (the three-clock model + generous window +
expected calendar). Output feeds **E1-T5 slice B** (`service_events` v2 + `bar_gaps` +
`capture_status`) and **E1-T6** (`archives` + `heal_outcome`). Build tickets T2–T4 detailed from
this design; re-cut as vertical slices 2026-10-15 (D31, R3).

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
   (it is additive). *(Since D33 the metrics half is OpenTelemetry → Grafana Cloud — E1-T7 and
   T7; the names still guide the dashboards.)*
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

## T2 — Slice 1: the health view end to end ⬜ (cut 2026-10-15 — replaces the old T2 read-model service + T3 console SPA)

**Type** build · **Branch** `—` · **Started** — · **Blocked by** —

**What it is (R1–R4, D31, D32 (1)):** the first vertical slice — one page, end to end, behind real
identity. Merges the old T2 (the read-model service) and T3 (the console SPA) as R3 rules: never a
backend without the page that consumes it. Nothing technical blocks it — E1-T5's `capture_status`
and `service_events` v2 are on main; it starts once the collector is deployed (Alex's plan).

**Increments (one ticket; one PR unless the nod says otherwise):**
1. **`control-service` skeleton.** A Spring Boot module (Spring's earning moment — here and
   nowhere else, D31; the collector stays plain `Main`), Java 25 (D12), JSpecify `@NullMarked`
   from birth (D14); the **`control` database** with Flyway from birth — `users` (email, role,
   created) and what the role mapping needs, nothing speculative; **read-only credentials into
   `market_data`** (D17 — the data product; the service never writes it); Actuator health.
   Localhost-bound behind the tunnel — no public origin. Config discipline: no code defaults for
   anything machine-specific; the two database URLs and the Access team domain come from env
   beside config (G1).
2. **The auth model (R4, D32 (1)) — built here, not apart.** Every request carries
   `Cf-Access-Jwt-Assertion`; the service verifies the signature against the team's JWKS URL
   (cached; refreshed on an unknown key id), checks audience and expiry, and maps the email claim
   to a `users` row → role (`admin`, `member`). **Alex is admin, mapped at login** — the first row.
   An unverified, expired, wrong-key or unknown-email request is refused; no self-minted token (a
   client that cannot pass Access does not exist yet). The **local dev story** — how a developer
   runs the stack without Cloudflare in front — is an in-ticket decision (a dev-only signing key
   with its own JWKS that the tests also use is the obvious shape; never a header-trust bypass).
3. **The health read model.** D25's health view over `capture_status` (per-instance, per-market
   rows: last tick/bar age, streaming state, reconnects, drops, heartbeat freshness) + the latest
   `service_events` of severity warn/error — the T1 per-view SQL, served as JSON.
4. **`web/platform` shell + the health page.** A Vite/React/TS app at `web/platform` (D18 — its
   own app beside `web/docs`; the E2 patterns and the Quiet Terminal brand reused, D19); one SPA
   for every platform page; the **admin area** mounted behind a role gate the API enforces (the
   SPA hides, the service refuses); the **health page** consuming increment 3 — the first thing
   Alex sees without SSH. Thin visual client (PRD §10).

**Tests:** the JWT verification's failure modes against a test JWKS and signed test tokens — each
refusal a test, each mutation-verified (drop the signature check → red; accept a wrong audience →
red; accept an expired token → red); the role gate (a member on an admin route → refused);
Testcontainers for both databases (the `control` migration from empty; the health SQL pinned
against seeded `capture_status` rows, exact at the staleness boundary); a `web/platform` build in
CI (the `web/**` lane, as E2 did it).

**In-ticket decisions:** the local dev identity story (above); the JWT library (the JDK + a JOSE
library vs Spring Security's resource-server support — Spring is already in this service); the
API shape (one JSON endpoint per view, as T1 sketched). Settle in-ticket; log any that grow
durable consequences.

**DoD:** on a local stack a request bearing a valid Access JWT for an admin renders the health
page from live `capture_status` rows; a member and an unverified / wrong-key / expired request
are refused (the failure-mode tests mutation-verified); the `control` database migrates from
empty; the service never writes `market_data`; the `build` and `web/**` CI lanes green.

## T3 — Slice 2: coverage map, gaps, event log ⬜ (cut 2026-10-15)

**Type** build · **Branch** `—` · **Started** — · **Blocked by** T2

**What it is (R3):** the next three admin-area views, each end to end over the T1 read model.
- **Coverage map** — expected calendar vs present 1m bars → complete / gap / closed per market per
  hour (data-platform-design §6 — the first-class map it always wanted). **The calendar's source
  follows E1-T6's nod ruling** (2026-10-16): the recommendation on record derives the open spans
  from the recorded `market_state_change` events (every flag transition is a row since E1-T5),
  counts gaps only inside open spans, and keeps a static per-market calendar (D25's
  `market_calendar`) as the fallback for hours the collector itself was down. T3 reads the same
  source the heal uses — one truth about "open".
- **Gaps table** — `bar_gaps` (open + healed, classified; `heal_outcome` and `healed_at` once
  E1-T6 writes them).
- **Event log** — `service_events` v2 filterable by severity, market, type and time; errors and
  warnings first; `correlation_id` surfaced.

**Tests:** each view's SQL pinned by a Testcontainers test against seeded rows, exact at the
boundaries (a gap on a span's edge; a closed hour counted closed, not gap; a healed gap no longer
open); each page renders from the fixture.

**DoD:** each view renders from real rows on the local stack; the coverage map's calendar source
matches E1-T6's ruling; the SQL pinned as above.

## T4 — Edge + deploy ⬜ (ops — shape cut from T1 / E1-T7; re-cut 2026-10-15 for the one app)

**Type** ops · **Branch** `—` · **Started** — · **Blocked by** T2, E1-T7

Cloudflare Tunnel (`cloudflared`, outbound — no inbound ports) + Access (per-person identity at
the edge — the JWT T2 verifies; email/SSO, free < 50 users) in front of **the one app**:
`web/platform` served, `control-service` behind it, at the platform's hostname (D18 naming);
deployed beside the collector in the compose stack (D24 as amended by D31; the host ruled at
E1-T7 — Hetzner leading). `control-service` reaches `market_data` over the private Docker network
with the read-only role (D17; R5's first rung). **Slice 1 alone is deployable** (R3) — T4 does not
wait for T3. **Sequenced with T7** so the first deploy of `control-service` is observable (D33).
**DoD:** the admin's health page reachable at the hostname through Access with no port open on
the box; the `control` database and the read-only `market_data` role live on the staging host;
the runbook covers deploy, rollback and Access policy changes.

## T5 — Digest email + alerts ⬜ (born 2026-10-11 from D30 — moved here from E1-T6; its home `control-service` since D31)

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
declined. The dead-man ping (D33, E1-T7) covers the deaths that leave no row in the meantime — a
liveness signal, not a report. **Blocked by** T2 (its home is `control-service` — D31 says the control
plane sends the digest and alerts; a scheduled task inside it, the shape settled in-ticket) and E1-T6
(the heal/archive `job_runs` rows it reports). **In-ticket decisions:** email transport (SMTP vs API
service); the reporter's shape inside `control-service`; the heartbeat alarm's staleness window (reuse
D25's `capture_status` freshness). **DoD:** on production a day's digest arrives built from the rows; a
suppressed digest (the reporter down, or the collector silent) is alarmed — the failure-mode tests
mutation-verified; the evening hand check retires from the runbook.

## T6 — Slice 3: the capture catalogue and downloads ⬜ (cut 2026-10-15)

**Type** build · **Branch** `—` · **Started** — · **Blocked by** T3, E1-T6

**What it is (R3 — "then downloads"):** the **capture catalogue** (market × source × date range,
counts — the T1 query over the `bars_1m`/`ticks` dimensions) and **downloads**: the `archives`
manifest E1-T6 writes (Parquet by day; `pg_dump` backups) served as **pre-signed links** minted by
`control-service` from the object store (R2 vs B2 — E1-T6's in-ticket ruling decides the store;
this ticket inherits it), never a public bucket. Its own ticket because its blocker differs from
Slice 2's — nothing to list until E1-T6's rows exist.

**Tests:** the catalogue's counts exact against a seeded Testcontainers fixture; an expired link
and a tampered link refused (failure-mode tests); the link's TTL pinned.

**DoD:** an archived day downloads through a pre-signed link from the admin area; an expired or
tampered link is refused; the catalogue's counts match the fixture exactly.

## T7 — Telemetry on `control-service` + the Grafana link-out ⬜ (cut 2026-10-15 from D33; re-scoped 2026-10-19 — the collector's dashboards moved to E1-T16)

**Type** ops · **Branch** `—` · **Started** — · **Blocked by** T2, E1-T7

**What it is (R8, D33):** the pipeline's second JVM. **E1-T7 wires the collector** — the Grafana
Cloud free-tier account, the OTLP endpoint and credentials in `.env`, the OpenTelemetry Java
agent attached in the collector's container, structured JSON logs — so the first deploy is
observable. **This ticket wires `control-service`:** JSON logs (the request's user as a field,
never a token), the agent in its container exporting metrics + logs over OTLP to the same stack,
service name and instance as resource attributes; the **admin area links out** to Grafana rather
than re-rendering it. **The collector's dashboards are E1-T16's** (re-scoped 2026-10-19): E1-T16
builds the collector's metrics and its two dashboards ("Collector — operations", "Collector —
domain", JSON under `deploy/grafana/`, the "Tradebench" folder); **this ticket adds
`control-service`'s panels beside them** (heap/GC/threads/uptime, request rates and latencies —
D25's metric names where they still apply). Domain truth stays in the rows (D25). Traces later.

**Tests:** the leak check (G1) over the exported shapes — no `.env` value, no market data, no
token in a log line or a metric label (a seeded fake value must trip it — mutation-verified).

**DoD:** `control-service`'s logs and metrics visible in Grafana Cloud beside the collector's on
staging; the admin area's link resolves; the leak check green. Lands with or right before T4's
deploy.

## T8 — The command plane + jobs control ⬜ (cut 2026-10-15 from D32 (3); gained heal control 2026-10-16)

**Type** build · **Branch** `—` · **Started** — · **Blocked by** T3, E1-T7

**What it is (R6, D32 (3)):** the write side of the control plane, after the read-only slices.
Confirms D24 R5 — control on the command plane, never REST into the collector.
- **The `control` jobs table + REST + pages.** A job: user, provider, account (the credential
  reference), market set, schedule/window, enabled, **version** (bumped on every edit — the
  idempotency key). Admin-area pages to list, create, edit, enable/disable. **B1's control half
  folded in** (R11): **save credentials** — per-user broker credentials as app-encrypted `control`
  columns (D16's multi-user era arrives here; one key per account, IG playbook §7) — and **save
  jobs**. B1's remainder (N jobs as N processes, the per-job advisory lock, the job manager as a
  process launcher — its gaps 1 and 3) stays in the backlog; gap 2 is closed by E1-T14.
- **The nudge.** `control-service` writes the row (the durable truth) and publishes "job *n*,
  version *v* changed" on a Redis stream (D4; Redis in the stack since E1-T7).
- **The internal fetch endpoint.** A small endpoint on `control-service` returning a job's
  definition by id — service identity per R5: the private Docker network at this rung, no token
  until a second HTTP service exists.
- **The collector's consumer — E9/E1 joint work**, landing in `market-data-service` under the
  collector's rules: no Spring (D6 amended), zero inbound ports, never another service's tables
  (D17). Fetch on the nudge, **on boot** and on a **slow periodic resync** (so a lost nudge loses
  nothing); apply idempotently by `(job id, version)` — a replayed or reordered nudge is a no-op;
  at-least-once, ack-after-apply (house doctrine). The collector writes its own `job_runs` into
  `market_data`, which `control-service` reads back as part of the data product. Applying to a
  *live* session (add or remove a market without wobbling the others — B1's "dynamic reload")
  rides the per-item subscribe/unsubscribe E1-T5 slice C built.
- **B9's allowance view folded in** (R11): the IG request allowances (`GET /operations/application`
  → `ApplicationAllowance`) and the ~10,000 points/week historical budget the heal spends, shown
  in the admin area beside the jobs. B9's REST-fetch job type itself stays in the backlog.
- **Heal control (gained 2026-10-16 — D25 amended, E1-T6's ruling 4 re-ruled; Alex).** The
  **auto-heal toggle** per market — on by default for the platform tier; off → gaps are recorded
  and left open — and a **manual "fetch and heal this market-day"** command that calls E1-T6's
  healer entry point. Both travel the command plane like any job edit (never REST into the
  collector, D24 R5).

**In-ticket decisions:** the allowance figures' source — the collector's recorded budget events
(D25's rate/budget kinds: `pacer-discovered`, `allowance-low/exhausted`) vs `control-service`
asking IG itself (D16's one-key-per-service rule argues against a second holder of the key); the
resync period; the stream's consumer-group and ack shape (D4); the collector's behaviour when
`control-service` is unreachable at boot (P9 — fail closed, fail loud; surfaced at the nod, not
decided here); what "apply" means for a job whose process is not running (B1's process-per-job —
surface, do not build the launcher). **Open for Alex, not implemented past:** where user
administration (a `member` row for the brother/Dad; a role change) lands — this ticket or a later
one.

**Tests:** the nudge dropped → the resync alone delivers the change (failure-mode test); a
replayed nudge → no second apply (mutation: drop the version check → red); a reordered pair (v7
then v6) → v7 stands; the collector holds no credential for `control` (its env carries none); a
saved credential never appears in a log, an event row or a response body (G1 — the leak check
with a seeded fake).

**DoD:** on the local stack an edited job reaches the running collector by nudge and by resync
alone, applied exactly once per `(job id, version)`; credentials saved encrypted and never leaked;
the allowance view renders; the collector unchanged in its posture (no inbound port, no `control`
credential). With auto-heal off for a market, a gap is recorded and stays open; the manual fetch-and-heal command heals that market-day through E1-T6's healer entry point, idempotently (Alex, 2026-10-16).

## T9 — mTLS-for-practice 🧊 (gated 2026-10-15 — R5: a second Spring app)

**Type** ops · **Branch** `—` · **Started** — · **Blocked by** —

**What it is (R5, D32 (2)):** the service-to-service trust ladder climbs a rung only at its
trigger. Now: the private Docker network is the boundary — the only inter-service traffic for
months is read-only SQL into the data product, a Redis stream and one internal fetch. **When a
second Spring/HTTP service exists** (a strategy or backtest service — D17's refinement gives it
its own database from birth): service JWTs or **mTLS from an internal CA** between it and
`control-service` — mTLS chosen for the practice if Alex still wants it then (*"when another
spring app comes along we can do mtls"*). Cloudflare Access **service tokens** for machine
clients through the edge (D24). A user's token is never forwarded between services; the actor
travels as a claim in a service-signed message. Long-lived certificates are long-lived
credentials: **rotation is the whole cost**, scripted or not at all.

**Gate, not a ticket id:** a second Spring/HTTP service on the board. Not on the critical path
(Alex, 2026-10-15).

**DoD (cut when the trigger fires):** certificate issuance and rotation scripted and rehearsed;
a call with no cert or a wrong cert refused (failure-mode test, mutation-verified); the
architecture spec §6 updated with the rung reached.
