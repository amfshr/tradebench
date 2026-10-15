# The platform app shell, identity and trust, telemetry, and the collector's command plane

**Status: CLOSED** (2026-10-15). Resolves the *shape of everything that sits in front of and
beside the collection service*: one platform front end with a role-gated admin area; one
control-plane service behind it (`control-service`, the control plane to `market-data-service`'s
data plane); human identity at the edge and roles in the app; the service-to-service trust
ladder as a decision; the collector's command plane as *notify, then fetch*; operational
telemetry over OpenTelemetry beside the domain record in the database; a dead-man ping from the
collector's heartbeat; no Spring in the collector; the platform tier's owner named `platform`.
Decisions **D31, D32, D33**; D6, D24, D25 amended; D30 clarified; D17 reaffirmed with one
refinement. **Reshapes epic E9** and trues **E1-T6** for its start on 2026-10-16. One item handed
to T6's design nod: the expected calendar's source (the recorded `market_state_change` spans).

## Why now

Alex is about to finish the collection service (E1-T6 heal + archive this week, E1-T7 deploy the
weekend of 2026-10-17) and then start E9 while the collector runs. He asked for the short-term
future to be clear before T6 starts: how E9's app and backend are shaped, how auth works across
the platform (an issuing service? JWTs between services?), how operational observability (logs,
metrics, OpenTelemetry) sits beside the domain observability already in the database (D25), and
how the platform will tell the collector about job changes later ("a push notification to the
collection service"). His stated frame: one front-end application serving every platform page,
an admin dashboard only admins can view, and "making a Spring E9 app is useless until we have an
app to consume its API".

## The survey, condensed

**Grounding (seed-pack-librarian, the decision log, the prior records).** The pack rules that
the user is a designed-in dimension from day one, the platform *operates* as `default-user`
until a login page exists, cloud deploys sit behind edge gating, "no heavyweight auth/tenancy in
v1", an admin role exists from the start and owns platform-provided datasets, broker credentials
are per-user (PRD §2, §9, §11 phase 5; S1 probe 1). It is **silent** on service identity,
gateways, BFFs, mTLS, certificates and telemetry (Grafana, Prometheus, OpenTelemetry) — the mTLS
idea was Alex's on 2026-09-30. P7 and S1 probe 2 settle the service philosophy: deployable
artefacts and broker boundaries, "microservice just for the sake is not what I want". The
prototype talked between its services on durable Redis Streams only, and its collector had no
dashboard, no API and no inbound port. The architecture spec's §6 (agreed 2026-09-28, never
numbered) already held "edge identity, then tokens — credentials never travel" and a
service-to-service trust ladder: private Docker network → Tailscale between boxes → mTLS only
when something demands certificates. D24's R5 (control on the command plane, never REST into the
collector) was a strawman nobody had confirmed. Two contradictions surfaced: the T6 plan and the
spec's §3.4 assumed T6 brings Spring into the collector for `@Scheduled`, while the E9 plan said
the collector stays plain `Main`; and D25 named a healthchecks.io dead-man ping as the v1 "box
down" alarm without saying who sends it, which D30's letter forbade the collector to do.

**The lesson, as given.**
- *Human identity: edge, then app.* The identity-aware-proxy pattern (BeyondCorp; Cloudflare
  Access): the edge authenticates the person once and forwards a signed assertion
  (`Cf-Access-Jwt-Assertion`, keys at the team's JWKS URL, the email as a claim); the app verifies
  the signature and does authorisation itself. Failure modes: trusting the header unverified;
  self-minted long-lived session tokens with no revocation; building a login flow nobody needs.
- *Service identity*, three rungs: network trust; bearer service tokens (client-credentials
  JWTs, `sub` the service, `aud` the receiver); mTLS from an internal CA (SPIFFE at scale).
  Failure modes: long-lived certificates are long-lived credentials and rotation is the whole
  cost; forwarding a *user's* token between services is the confused deputy — the actor travels
  as a claim inside a service-signed message.
- *The app behind the SPA.* BFF vs API gateway vs microservices-direct; at one or two services
  the BFF *is* the platform's service, and a gateway with nothing behind it is the distributed
  monolith.
- *Commands to a service.* REST push (inbound port, auth, caller-side retries) vs a durable
  command stream (push latency, at-least-once, idempotent, no socket) vs polling. The cure for
  lost or doubled commands is idempotency by `(job id, version)` — already house doctrine.
- *Observability is two things.* Business observability is what happened to the data and
  belongs in the database as rows (D25 did this right). Operational telemetry is how the process
  behaves — logs, metrics, traces — and OpenTelemetry is the vendor-neutral standard: a Java
  agent attaches to any JVM, Spring or not, and exports over OTLP. Self-hosting Grafana + Loki +
  Prometheus + Tempo is several gigabytes of RAM on a €5 box; Grafana Cloud's free tier (10k
  series, 50 GB logs, 14-day retention) keeps the £20/mo NFR.

## Rulings (Alex, 2026-10-15)

- **R1 — One front end.** `web/platform` serves every platform page; the admin area is gated by
  role; the docs site stays its own site (D18). E9's deliverable becomes the platform app's first
  vertical slices, not a separate console app. Amends D24 R1 and R4 (E9 stays an epic). *Alex:
  "on 1 i agree with recommended."*
- **R2 — The control plane is `control-service`.** One Spring Boot service — the control plane to
  `market-data-service`'s data plane — owning a new `control` database (users, roles, jobs,
  settings), holding the read model over `market_data` (read-only creds, D17) and later the
  control actions and the kill switches E6 needs; Spring's earning moment is here and nowhere
  else. "Platform service" was rejected as saying nothing about the role; "tradebench service"
  as conflating the product's name with an app's role. D17 reaffirmed (one database per service;
  a strategy or backtest service brings its own) with one refinement: **a bounded context that
  will plausibly become its own service later (saved strategies, DSL indicators, backtest runs)
  gets its own database from birth**, owned by `control-service` until the split, so the split is
  a credential change and not a data migration. *Alex: "yes i agree on the control service."*
- **R3 — Vertical slices.** The health view end to end (API + page) first, then coverage and
  gaps, then downloads; E9-T2 and T3 merge into slice tickets. *Alex: "on 3 yes."*
- **R4 — Human identity: Access authenticates, the app authorises.** `control-service` verifies
  the Access JWT on every request against the JWKS and maps the email to a user and role in
  `control` (roles `admin`, `member`; Alex is admin). No self-minted user token until a client
  exists that cannot pass through Access. "No heavyweight auth in v1" is kept with roles real.
  *Alex: "on 4 yes."*
- **R5 — The service-to-service trust ladder is a decision.** Private Docker network now (the
  only inter-service traffic for months is read-only SQL into the data product and a command
  stream); service JWTs or mTLS when a second HTTP service exists; Cloudflare Access service
  tokens for machine clients through the edge. mTLS-for-practice is not on the critical path: it
  becomes an explicit ticket when the second Spring app arrives. *Alex: "on 5 yes and when another
  spring app comes along we can do mtls."*
- **R6 — The collector's command plane is notify, then fetch.** The control service writes the
  job row in `control` (the durable truth, full read and write) and publishes a nudge on a Redis
  stream (D4: "job 42, version 7 changed"); the collector fetches the definition from a small
  internal endpoint on `control-service` (service identity per R5), on the nudge, on boot and on
  a slow periodic resync, so a lost nudge loses nothing; it applies idempotently by `(job id,
  version)`; it writes its own `job_runs` into `market_data`, which the control service reads
  back as part of the data product. The collector keeps zero inbound ports and never reads
  another service's tables — Alex's first instinct (the collector reading the control database)
  was argued once against D17 (a second service with credentials into your tables couples it to
  your schema; D17's "absurd over an API" exception is for bulk market data, not job rows) and
  he took the fetch. Redis joins the compose stack. Confirms D24 R5. *Alex: "6 yes, we will build
  redis into the stack"; "yes … the notify then fetch pattern."*
- **R7 — No Spring in the collector.** T6's EOD job is a plain scheduled executor inside the
  existing process; D6's "plain scheduled task" never needed the framework. The collector stays
  plain `Main`. D6 amended; the T6 plan and the spec's §3.4 trued. *Alex: "spring seems heavy
  weight for just that one use case so happy to go without."*
- **R8 — Operational telemetry over OpenTelemetry, beside the domain record.** Structured JSON
  logs on every service; the OpenTelemetry Java agent on every JVM exporting metrics and logs over
  OTLP to Grafana Cloud's free tier; the admin area links out to Grafana rather than re-rendering
  it; domain truth stays in the database (D25). The collector exporting telemetry is operational
  exhaust, not reporting, so D30 stands. Wired at E1-T7 so the first deploy is observable. D25's
  "Prometheus/Grafana additive, later" line becomes this. *Alex: "yes on 8."*
- **R9 — The dead-man ping.** The collector's heartbeat makes one outbound HTTPS GET per minute
  to a healthchecks.io check URL (a liveness signal, not a report — D30 clarified): shutdown
  hooks write rows for orderly stops, `FEED_DEAD` exits and pump deaths, but a `kill -9`, an
  OOM, a box reboot or a database down at the moment of death leave no row, and those are the
  dead-man's cases. Free at our scale; the URL carries a random secret and lives in `.env`
  (G1); no data leaves. Wired at E1-T7. *Alex: "on 9 fine."*
- **R10 — The platform tier's owner is the user `platform`.** The deployed collector persists
  rows under the admin-owned platform tier (PRD §7: platform-provided datasets every user sees),
  so the seed user is renamed from `default-user` to `platform` **now, before the first
  production rows** (a rename after the first deploy is a migration over live rows);
  `TRADEBENCH_USER` names the owner per instance (B1's plan); admin is Alex, mapped at login.
  *Alex: "10 yes."*
- **R11 — E9's ticket shape.** T1 done; slice tickets replace T2/T3; T4 edge + deploy; T5 digest
  + alerts; new tickets for the auth model (R4), the telemetry pipeline (R8, shared with E1-T7),
  and the command plane with jobs control (R6; B1's control half and B9's allowance view folded
  in, after the read-only slices). The epic is renamed for what it now is. *Alex: "yes for 11
  too."*

**Handed to E1-T6's design nod (2026-10-16), not ruled here:** the expected calendar's
*source* for the heal and the coverage map. Alex's 24-hour concern — "how does the belt know the
market is quiet and the feed is not dead" — is answered by D25's three clocks (the generous
stream window; the deal flag, which stands the watchdog down on a closed market, bounded by the
12-hour teaching resubscribe; the expected calendar that decides what a real gap is) and by
chapter 11 §5.1. The open piece is where the calendar comes from, and the recommendation on
record is: **derive it from the recorded `market_state_change` spans** (every flag transition is
a row since E1-T5), counting gaps only inside open spans, with a static per-market calendar as
the fallback for hours the collector itself was down. A quiet-but-open market that ticks less
than once per 90s earns harmless surgical resubscribes; the first weeks' `watchdog_stale` rows at
quiet hours are the evidence for tuning `tickSilent` per market (E1-T7's soak).

## Illustrative sketch (non-committed)

```
                 Cloudflare Access (identity at the edge, per person)
                              │  Cf-Access-Jwt-Assertion
   tradebench.amfshr.dev ─────▼───────────────────────────────── tunnel (cloudflared, outbound)
   ┌──────────────────────────────────────────────────────────────────────┐
   │ web/platform  (one SPA: pages for everyone, /admin gated by role)     │
   │        │ JSON API                                                     │
   │ control-service (Spring Boot)                                         │
   │   verifies the Access JWT per request → user + role from `control`    │
   │   read model over market_data (read-only creds, D17)                  │
   │   control plane: jobs, settings ──▶ Redis Streams (the nudge)         │
   │   internal /jobs endpoint (the fetch) · digest + alerts (E9-T5)       │
   ├──────────────────────────────────────────────────────────────────────┤
   │ market-data-service (plain Main, no inbound)                          │
   │   runs collection jobs; nudge → fetch → apply by (job id, version)    │
   │   writes market_data (the data product) + job_runs                    │
   │   heartbeat → healthchecks.io (dead-man ping, outbound)               │
   ├──────────────────────────────────────────────────────────────────────┤
   │ postgres: databases `market_data`, `control`   ·   redis              │
   │ every JVM: OpenTelemetry agent → OTLP → Grafana Cloud (free tier)     │
   └──────────────────────────────────────────────────────────────────────┘
```

Two planes named for their domains (D18): `control-service` is the control plane and read
model; `market-data-service` is the data plane. Service-to-service trust is the private Docker
network at this rung.

## Spawned work

- **E9 re-cut** (the steward, from this record): renamed for what it is; T2/T3 → vertical-slice
  tickets on `web/platform` + `control-service`; T4 edge + deploy; T5 digest + alerts; new
  tickets — the auth model (R4), the telemetry pipeline (R8), the command plane + jobs control
  (R6, with B1's control half and B9's allowance view folded in; mTLS-for-practice listed as a
  later ticket per R5).
- **E1-T6 trued** for its nod: no Spring (R7); the calendar-source item added to the nod sheet;
  the digest already gone (D30).
- **E1-T14 — the platform user** (small, before T6's first production rows): the `users` seed
  renamed `platform`, `Main` reads `TRADEBENCH_USER` (no code default), tests and fixtures
  follow (R10).
- **E1-T7** gains: Redis in the compose stack (R6), the OpenTelemetry agent + Grafana Cloud
  export (R8), the healthchecks.io ping (R9).
- **Architecture spec** (`docs/design/architecture/README.md`): §2 containers, §3.4 jobs, §5
  frontend, §6 security posture amended to cite D31–D33; the observability design's metrics
  section amended per R8/R9.

## PRD open-question register

- No PRD open question is resolved or raised by this session. PRD §11 phase 5's "login + edge
  gating" is **partly pulled forward**: edge gating and roles arrive with E9 (R4); a login page of
  our own stays phase-5 work. PRD §7's digest placement was already superseded by D30; PRD §2's
  `default-user` phrasing is superseded by R10 (the user `platform`).
