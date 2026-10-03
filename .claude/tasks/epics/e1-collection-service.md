# E1 plan — Collection service, deployed 24/7

> **What this is.** The per-ticket planning layer for E1: approach, build order, test plan,
> and the decisions each ticket must settle *inside itself*. The board row stays the index +
> DoD; this file holds the how. **Plans here are living sketches** — refined (not
> re-approved) when `/start-ticket` picks the ticket up; real scope changes still go through
> the board.
>
> Sources: broker playbook §1–§7 (the porting spec), phase-1 build plan §3–§7 (porting order,
> module design, deploy analysis), data-platform-design (atoms, healing, coverage),
> decisions D2/D6/D12.

## Epic shape

**Dependency spine:** T2 (session/REST) → T3 (streaming) → T4 (persistence) → T5 (resilience)
→ T6 (EOD cycle) → T7 (deploy) → T8 (shadow-diff acceptance). Genuine parallelism: T4's
schema/migrations can start alongside T3; T6's Parquet/email halves only need T2+T4.
**Risk concentrates in T5** (the resilience belt is most of what the playbook exists to
transfer — budget it as the long tail) and in T6's provider-allowance realities.
**Trust arrives only at T8** — until the shadow week diffs clean, the Python appliance
remains the capture of record.

---

## T1 — Foundations ✅ (PR #1, 2026-09-27)

Done: Gradle 9.8 Kotlin DSL multi-module (`core`, `ig-client`, `market-data-service`),
Java 25 LTS toolchain, CI + branch protection, walking skeletons mutation-verified. D12.

## T2 — `ig-client`: session + REST core ✅ (PR #2, 2026-09-27)

**Goal:** an authenticated, paced, typed IG REST core the whole platform stands on.
**Build order** (build plan §3 — the playbook's own recommendation):
1. **Error taxonomy first**: fatal-config vs retryable — prevents both classic failure
   modes (retrying a fatal into a lockout; dying on a transient). *(Refined in-build
   2026-09-27: the enum is two-class; "session-dead" is a context, not an error code —
   handled behaviourally by `IgSessionManager.afterFailure()` (cheap GET /session
   validation → fresh login only when tokens are dead, §1.2/§1.6). T5's supervisor calls
   `afterFailure()`; there is no SESSION_DEAD class to look for.)*
2. **Session**: v2/v3 login → account switch → token store (CST/XST from response headers,
   token↔account binding, no login cache); re-login policy; demo/live selected by ONE flag
   that also picks base URL + API key (playbook §1.1–§1.5, §1.7 — preferred-account trap).
3. **REST endpoints E1 needs**: market details (incl. dealing-rules snapshot, §5.1),
   historical prices v3 (T6's heal path — numpoints+1 and TZ traps, §4.2; parse the
   allowance metadata for T6's budget). *(Refined at start 2026-09-27: positions/orders
   stubs dropped — dead uncalled code invites rot; the OMS era adds them against real
   contract tests.)*
4. **Pacing guard**: request budget honouring IG's real session limits (§6).
**Stack:** JDK `HttpClient` + Jackson; zero Spring in this module (rule from T1).
**Config:** typed, no code defaults, secrets via env beside config (G1); instance-named.
**Tests:** unit on fakes/WireMock; **golden bytes** for wire contracts (login + prices
responses); failure-mode tests fail closed (rejected key, expired tokens, 4xx vs 5xx per
taxonomy); one env-gated integration smoke: log into demo, print account + LS endpoint —
excluded from CI by default.
**In-ticket decisions:** none expected to escalate; endpoint DTO shapes settle here.

## T3 — Streaming: ticks + sealed 1m bars ✅ (PR #4, 2026-09-28; live-proven Sunday, Alex's ruling)

*(Design nod ruled by Alex 2026-09-28: bar domain = bid+ask OHLC stored, mid derived
per-field at 1m then aggregated upward — D15; tick = (epic, instant, bid, ask), DLG_FLAG
routed to state machinery not per-tick storage; core.time SPI born now;
core.events revised same-era (Alex 2026-09-28): the event-stream SPI is PARKED to E3's
engine design session (idea doc §8: pull vs push, tick/bar tie-break, seek/stepBack,
forming bars) — nothing consumed the T3 placeholder and its push shape contradicted the
idea doc's pull leaning; library hands parsed DTOs to a caller-supplied consumer, the service owns
the queues; DoD vehicle = env-gated JSONL capture runner, no Spring yet; Lightstreamer
`ls-javase-client:5.3.3`.)*

**Goal:** live DAX ticks + sealed 1m bars flowing through our own transport seam.
**Approach:** wrap `com.lightstreamer:ls-javase-client` (5.2.x — official, maintained;
hand-rolling TLCP buys nothing): connect with `CST-…|XST-…` wiring (§1.1), subscribe PRICE
(ticks) + CHART:1MINUTE per market, **accept `CONS_END=1` only** — in-progress candle
updates die at the parser. Callback discipline (§2.4): parse cheap on the LS thread, hand
off to queues — `ArrayBlockingQueue` shed-oldest for ticks, unbounded for bars; backpressure
never reaches the socket. Connection-status events exposed for T5's supervisor.
Per-market subscriptions (identity-vs-addressing, triage D16).
**Tests:** fake stream behind our own transport interface (these fakes are the seed of the
future simulator pillar); golden fixtures of real LS field maps (§2.1–§2.3 — per-field-mid
consolidation, epoch-ms UTC); sealing-rule tests exact on the CONS_END boundary.
**DoD anchor:** a live demo session captures a full DAX day of ticks + 1m bars locally.
**In-ticket decisions:** tick field set (bid/ask/last per playbook §2.1) recorded in the DTO.

## T4 — Persistence ✅ (PR #5, 2026-09-28)

**Goal:** captured data lands in Postgres through the real write path, schema born right.
**Approach:** Flyway from V1: `instruments`, `ticks`, `bars_1m` (**user + source columns
from day one** — the two first-class dimensions), `service_events`, `job_runs`.
Least-privilege roles (triage D22). Writer consumes T3's queues with **batch upserts,
idempotent by key, ack-after-apply** (at-least-once doctrine). **Single-instance advisory
lock** (`pg_try_advisory_lock`, playbook §7) — no persistence ⇒ do not run (fail closed).
Generated schema render + CI drift gate (triage D23).
**Tests:** Testcontainers suite through the real path; idempotency test = same batch twice,
one row set; drift gate red on an uncommitted schema change (mutation-verify the gate
itself).
**Design nod ruled (Alex, 2026-09-28):** capture rows belong to `default-user` (platform
tier formalised at E4) · tick key = surrogate PK + UNIQUE(user,source,instrument,ts,bid,ask)
ON CONFLICT DO NOTHING (exact dupes drop; same-ms distinct prices both survive —
finest-truth) · `instruments` FK table, not inline epics · plain JDBC + HikariCP + Flyway,
Spring waits for T6 · JSONL back-import deferred.
**In-ticket decisions:** none — tick-lake *format* (PRD Q#4's open half) explicitly does NOT
block: Postgres rows now, Parquet export is T6's job, revisit at scale.

## T5 — Resilience belt (the long tail — budget accordingly)

**Goal:** the service self-heals and self-reports; quiet is healthy, silence is an alarm.
**Pieces** (all pure logic on injectable clocks, no wall-clock reads):
- **Reconnect supervisor** with backoff + ceiling and the **stuck-substate escalation
  ladder** (triage D25 — the WILL-RETRY/TRYING-RECOVERY hang the prototype never fixed:
  build the full ladder from day one).
- **Staleness watchdog**: should-be-ticking derived from the stream itself (a connected
  socket is not a live feed); **monotonic vs wall discriminator** separates host-suspend
  from feed-death (§3.4).
- **Gap detection** writing gap rows as information, not errors (gaps are facts about the
  world; T6 acts on them).
- Structured `service_events` + quiet-is-healthy warnings stream.
**Tests:** scripted failure scenarios on fakes — dead socket, silent-while-connected, host
suspend — **each mutation-verified** (per DoD); boundary tests exact on escalation
thresholds.
**Design nod ruled (Alex, 2026-09-28):** witness quarantine built now (complete belt;
N=1 parity structural) · dedicated `bar_gaps` table (V2, healed_at for T6, coverage seed)
· pacer budget discovery via GET /operations/application in-ticket (start 10/min
conservative, adjust minus headroom post-login) · exhaustion = clean exit(0), container
restart policy is the outer loop at T7 · all §8 timings in one `Tuning` record with
playbook defaults as named constants (policy, not machine config; env overrides only when
actually needed).
**Amended (Alex, 2026-10-03, slice C step 6) — exhaustion re-ruled:** recovery is a **time
budget**, not a rebuild count — `Tuning.giveUpAfter` = **10 min** of continuous no-streaming
from an outage's first rebuild (the 10-rebuild ceiling stays as a cap; rung cadence is
detector-driven — a 10-rung ladder spans 20 min–3.5 h depending on which detector drives
it — so a count was never the number it looked like) →
`FEED_DEAD` (detail `reason`: budget · ceiling · fatal_config) → orderly **`exit(1)`**, not 0:
the mission failed, and 1 restarts under every policy (`always` / `unless-stopped` /
`on-failure`) instead of making `restart: always` load-bearing. Boot retries *retryable* IG
errors under the login gate (30s between attempts, bounded by the same 10-min budget — the
error taxonomy defaults unknown codes to retryable, so an unbounded boot loop would retry a
wrong password forever) and fails loud on a rejected configuration or an exhausted budget, so
a restart during an outage never becomes a login storm; a rejected configuration mid-ladder
stops recovery at once, and `error.security.invalid-details` (wrong identifier/password) is
now classified fatal. A rebuild opens the outage in the classifier (the resume is a
replacement) and superseded connections are gated, so the ladder reset never races the
farewell `DISCONNECTED`. *Why:* a JVM restart is ≈ free during an IG
outage (the feed is already dead; one process per job, B1) and the only cure for a wedged
process — so err short.
**Build slices:** A pure cores (supervise/coverage decision logic + scenario tests) →
B store side (EventLog, V2 bar_gaps, drift regen) → C shell (Supervisor, HealthProbe,
Main rewiring, heartbeat, pacer discovery).
**Slice B builds to the E9-T1 data-model spec** (D25, `docs/design/observability-and-data-model.md`):
the event log = **`service_events` v2** (dimensions + severity + occurrence/recorded split), the
**`bar_gaps`** table (persist `GapDetector.Gap` + `healed_at`/`heal_outcome`), and a
**`capture_status`** per-heartbeat UPSERT (the console's health source) — so the collector writes
the console-ready shapes once. Q1 (streaming schedule) is ruled there too (three clocks; generous
window; expected `market_calendar`).
**Slice B — ✅ done** (PR #11, 2026-09-30): `service_events` v2 + `bar_gaps` + `capture_status` +
the `marketdata.events` vocabulary + the `EventLog`/`GapStore`/`StatusStore` seam over
`PostgresObservabilityStore`; 8 mutations verified; doctrine review pass-with-findings (F1–F4 fixed).
**Slice C — design nod ruled (Alex, 2026-09-30):** the Supervisor is a **per-session `CaptureJob`
unit** (not a global singleton) so multi-user later = run N (backlog B1); observations cheap on the
LS callback, remedies on a dedicated `capture-supervisor` thread composing the five slice-A cores;
**per-item subscribe/unsubscribe built into the transport** (surgical `RESUBSCRIBE` + quarantine,
§3.5 — never a full rebuild for one market's hiccup; also the primitive B1's dynamic reload reuses);
`GapDetector` fed on the pump thread → `GapStore.record` + a `bar_gap` event (DB work off the
callback); decision #1 completed — the pump gains an `EventLog` and emits `market_state_change`
(events are **db-only**; jsonl stays market-data-only); the watchdog reads per-market last-seen
times exposed by `Buffers`; `RequestPacer` gains `setPerMinute(int)` for post-login pacer discovery.
Out of scope → **backlog B1** (stream-jobs): the config/profiles DB, multi-user job manager, dynamic
reload.
**Post-sweep refinement (2026-09-28, trading-ig comparison):** the REST pacer's budget is
discovered at service startup from `GET /operations/application` (minus headroom) or
config-injected — field data shows demo keys enforce 10/min, not the published 30; never
assume the constant.

## T6 — Daily completeness + archive + digest (D6)

**Goal:** every trading day ends healed, archived off-box, and reported — or the silence
itself alarms (P9).
**Approach:** one `@Scheduled` EOD job (plain task, NOT Spring Batch — D6), three phases:
1. **Heal**: diff expected-vs-present 1m bars; REST v3 backfill window-clipped and paced
   (the ~10k-points/week allowance is real — §4.3); outcomes classified
   `healed / tickless-at-source / failed`. **Ticks are stream-only** — provably
   unrecoverable from IG REST; the job never claims otherwise.
2. **Archive**: day's capture → Parquet → object storage (offsite copy, backtest-ready).
   *(Ruling 2026-09-27: `IgRestClient` throws on a candle without `snapshotTimeUTC` — the
   healer catches per-request and classifies the outcome `failed`; blast-radius policy is
   the healer's, never the wire layer's.)*
3. **Digest email**: counts, gaps, heal outcomes, archive path. Audited `job_runs` row
   every run, clean or not — **the absence of the digest is the alarm** (runbook documents
   the check).
**Tests:** heal classification table-driven and exact; allowance/pacing boundary tests;
digest-suppression failure-mode test; Parquet golden file for the day-export contract.
**In-ticket decisions (named on the board):** object store — **R2 vs B2**; also: email
transport (SMTP vs API service) and Parquet writer lib. Settle all three in-ticket, log any
that grow durable consequences.
**Design item queued for this ticket's nod (Alex, 2026-09-28):** the schema/role story —
named schema vs Postgres `public` (currently `public`; likely: dedicated schema +
`search_path`, `public` revoked), owner-vs-app role split (D22, with the T7 ops script),
and DataGrip/read-only role for humans. Decide when Spring lands, migrate via Flyway.
**Post-sweep refinement (2026-09-28):** `exceeded-account-historical-data-allowance` resets
*weekly* — the heal treats it as budget-exhausted (plan via the parsed `Allowance` metadata
before firing), never as a backoff-and-retry error.
**ig-client REST resilience (backlog B2):** the healer is the **first consumer of transient
REST retry-with-backoff** on the ig-client. Decide here: hand-roll (reuse `BackoffPolicy` +
the error taxonomy, allowance-aware) vs standalone Resilience4j — never Spring Retry/Cloud CB
(ig-client stays framework-free). This is the first slice of the platform-wide ig-client REST
resilience that B2 tracks (the broad OMS/enquiries surface rolls it out at E6).

## T7 — Deploy

**Goal:** staging + prod, promoted by release tag; runs unattended.
**Pre-cloud runway (Alex, 2026-09-28):** before any cloud deploy, the deployable stack
runs a multi-day soak on Alex's Mac — the confidence gate T3's single-day capture no
longer carries.
**Approach:** Dockerfile (jib or buildpacks — pick in-ticket) + `docker compose`
(service + `postgres:16` + nightly `pg_dump` → object storage); separate DBs per env;
secrets injected via env files on the box, never committed; outbound-only network posture
(SSH/Tailscale in, IG + heartbeat out); heartbeat ping (healthchecks.io-style) so a dead box
alerts a phone. Host: revisit the build-plan §6 analysis (Lightsail $12 vs Hetzner ~€4)
against the ~£20/mo envelope — **decision recorded in-ticket**.
**DoD anchor:** staging runs 24/7 for 3 consecutive unattended days with daily digests
arriving.
**Runbook:** deploy, rollback (re-tag), secrets rotation, digest-absence response.

## T8 — Acceptance: shadow-diff week

**Goal:** the trust gate. Both systems (this service in the cloud, the Python appliance on
the Air) capture the same DAX sessions for a full week.
**Diff:** tick coverage, 1m bars row-by-row, and own-aggregated 10m vs the prototype's
(the oracle pattern — the strongest argument for capture-first: the oracle already runs).
**Output:** the diff report with **every delta explained** (not just counted), then Alex's
ruling on making Tradebench the primary capture — recorded in `docs/decisions.md`.
**Note:** deltas are expected (clock edges, reconnect windows); the bar is *explained*, not
*zero*.
