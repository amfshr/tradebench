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

## T5 — Resilience belt ✅ (slices A + B PR #11, 2026-10-01 · slice C PR #12, 2026-10-04 — complete)

**Type** build · **Branch** `e1-t5-resilience` (slices A + B; slice C on `e1-t5c-supervisor-shell`) · **Started** 2026-09-28 · **Blocked by** —

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
process — so err short. **Also ruled 2026-10-03:** observability writes are best-effort-but-loud in
both the pump (step 3) and the belt (step-6 review) — counted, surfaced by the heartbeat, never
fatal — while the sink stays fail-closed (its hold-and-retry refinement is **T9**); shutdown stops
and joins the sweep thread before closing the stream. Pacer discovery (step 5): start at 10/min,
then min(`allowanceAccountOverall`, `allowanceApplicationOverall`) − **5** headroom (≥1) from
`GET /operations/application` — ruled 2026-10-03 (Alex): the account figure is shared by every key
on the account, so the tighter of the two binds; both are refused at the boundary when missing or
non-positive; an unlisted key or a failed read keeps the start and says so (`IG_API_ERROR` + log).
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
**Slice C — ✅ landed (PR #12 from `e1-t5c-supervisor-shell`, merged by Alex 2026-10-04 — completes T5):**
the `Supervisor` shell composing the slice-A cores on a dedicated 1s sweep thread; `IgStreamControl` as
the real `StreamControl` (boot + rebuild on one path, per-item subscribe/unsubscribe, generation-gated
callbacks, bounded boot); `HealthProbe` publishing `capture_status` each 60s heartbeat; gaps + market
state onto the `EventLog` (the capture sink is market-data-only); post-login pacer discovery
(`RequestPacer.CONSERVATIVE_START` 10/min → min(account, application) − 5 via
`GET /operations/application`); `java-test-fixtures` (`FakeStreamTransport`); Field Manual ch. 10 "The
failure playbook" + ch. 08 brought true; component page refreshed. DoD scenarios on fakes — dead socket
(`SupervisorTest`, `IgStreamControlTest`), silent-while-connected (`SupervisorTest`,
`StalenessWatchdogTest`), host suspend (`SupervisorTest.hostSleep…` ×2, `StalenessWatchdogTest`) — each
mutation-verified. 89 mutations killed across steps 2a–6 + the whole-slice review (step 6: 36 of 37 —
the survivor exposed redundant code, removed); doctrine reviews per-step (3–6) + whole-slice
pass-with-findings F1–F10, fixed on-branch except F9 (a declared residual). Tests at merge: ig-client 92
· market-data-service 143 · docs site 63 · 0 failures. The 2026-10-03 rulings above are logged as **D27**.
**Residuals (recorded 2026-10-04 — not tickets; Alex decides whether to ticket). Update 2026-10-05: residuals
2 and 3 are now E1-T11's DoD** — the scenario harness; the PR #14 review confirmed the missing composed-loop test
as the root of its findings 1, 2, 4, 5, 8 and 11. Residual 1 stays unticketed. **Update 2026-10-08: residual 2 is closed,
final at T11's merge (PR #16); residual 3 is closed in its rebaseline half at the same merge, and its rebuild half
closed with T10 A2 on 2026-10-09 — ruled 2026-10-08 (Alex); the 2026-10-09 update below** (`BeltScenariosTest` drives the composed loop over `CaptureAssembly`;
the host-suspend scenario pins the rebaseline — a 16-minute wall jump raises no `WATCHDOG_STALE` and is annotated
`hostSlept`, no rebuild, the client reconnects itself; the rebuild half — the host wakes with the Lightstreamer client
stuck in `DISCONNECTED:WILL-RETRY` and the escalator rebuilds 120s after the wake, measured in awake time — was pinned
at T11 only by the `@Disabled` dead-socket scenario, without the suspend (T11's doctrine review, finding 6), and is
now an A2 scenario: that scenario with a 16-minute host suspend in front of the hang, as ruled — built the hang then the
suspend, below; T11's DoD wording stays as written). **Update 2026-10-09: residual 3 is closed, final — its rebuild half at A2,
`aHostThatWakesWithTheClientStuckIsRebuiltInAwakeTime` (PR #17, merged by Alex 2026-10-09, merge `2a2f289`), the hang then
the suspend as reordered at A2's doctrine review (finding 1) so it discriminates awake from wall time — recorded as a deviation
from the 2026-10-08 ruling's "suspend in front of the hang", with the reason (with the suspend first the scenario did not
discriminate awake from wall time; reordered and `hostSlept=true` asserted, a consistent-wall-clock escalator fails it —
rebuild at 60, not 180); its rebaseline half at T11 (PR #16, 2026-10-08).**
1. The gated demo smoke (`IG_SMOKE=1 ./gradlew :ig-client:demoSmoke`) has not run, so the
   `application-allowance.json` wire fixture is *authored*, not captured — run it and re-golden before
   T6's heal budget leans on pacer discovery. A skew fails safe today: refused at the boundary, 10/min kept.
2. No composed-loop test — `Supervisor` ↔ `IgStreamControl` ↔ `FakeStreamTransport` through the single
   `bind()`. Each layer is tested; the composition is not. **→ E1-T11 (DoD) — closed, final at T11's merge (PR #16, 2026-10-08).**
3. No shell-level suspend → wake → `WILL-RETRY` → rebuild scenario — the rebaseline path is pinned only
   in slice A's `StalenessWatchdogTest`. **→ E1-T11 (DoD) — closed in its rebaseline half at T11's merge
   (PR #16, 2026-10-08)** (`aLidCloseIsAnnotatedNotTreatedAsAnOutage` — renamed `aHostSuspendIsAnnotatedNotTreatedAsAnOutage`
   at A2, ruling 3, PR #17: a 16-minute wall jump, no `WATCHDOG_STALE`, annotated `hostSlept`, no rebuild); **the rebuild
   half → T10 A2 (ruled 2026-10-08, Alex) — closed at A2, final (PR #17, merged 2026-10-09, `2a2f289`):**
   `aHostThatWakesWithTheClientStuckIsRebuiltInAwakeTime` — a variant of the dead-socket scenario
   (`aDeadSocketIsTheEscalatorsToRebuildAtOneHundredAndTwentySeconds`, #5) with a 16-minute host suspend; the ruling placed
   the suspend in front of the hang, and A2's doctrine review (finding 1, medium) reordered it — the hang, then the suspend,
   `hostSlept=true` asserted — so a consistent-wall-clock escalator fails it (rebuild at 60, not 180); recorded as a deviation
   from the ruling's ordering, for that reason: the host wakes with the Lightstreamer client stuck in `DISCONNECTED:WILL-RETRY`
   and the escalator rebuilds it, measured in awake time. **Residual 3 is closed, final (2026-10-09).**

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

## T9 — Sink blip resilience: hold-and-retry for the capture sink ✅ (PR #13, 2026-10-05 — complete)

**Type** build · **Branch** `e1-t9-sink-hold-and-retry` · **Started** 2026-10-04 · **Blocked by** —

**Goal:** a Postgres blip during a tick/bar write costs *nothing* the queues were already holding —
the sink holds the data, reconnects, retries, and says so loudly; it stops (and the process exits 1
at once) only on a failure the taxonomy calls terminal — never on a time budget (ruled 2026-10-04,
D28). Refines the fail-closed contract, never removes it (P8 data
collected forever · P9 fail closed, fail loud · CLAUDE.md: the DB is downstream of decisions, never
upstream).

**Problem — as it stood before this ticket (the 2026-10-03 step-6 doctrine-review discussion; Field
Manual ch. 10, then headed "When the database fails — the pending decision"):** a failed tick/bar write (`PersistenceException`
from `PostgresStore`, which holds one dedicated connection + its prepared statements for its
lifetime) stops the pump; `Main`'s heartbeat notices within ≤60s (`Main.HEARTBEAT`) and
`System.exit(1)`s; the outer loop restarts the job. Fail-closed, but crude: a five-second blip costs
~1–2 min of ticks (never healable — IG does not re-serve ticks) plus a bar gap (healed later by T6
from IG REST) — while the data sat safely in memory: bars stay queued (ack-after-apply, ch. 4) and
the tick queue holds `Buffers.DEFAULT_TICK_CAPACITY` = 100 000 ticks (ch. 10's estimate: ~18 h of
DAX at normal rate). Tier-2 observability writes already self-heal — pooled connection per write,
best-effort-but-loud (ruled 2026-10-03: slice C step 3 for the pump, the step-6 review's F1 for the
belt; both recorded in ch. 10, "The rulings behind it").

**Approach (the ticket's scope):**
1. **Hold the data.** Bars remain queued (already true — ack-after-apply). The pending tick batch
   (≤ `PostgresStore.TICK_BATCH_LIMIT` = 500 ticks `addBatch`'d into the `PreparedStatement`) is
   retained by the store across a reconnect — today it is lost with the dead statement.
2. **Reconnect.** `PostgresStore` re-acquires its connection from the Hikari pool (which hands back
   a healthy connection once Postgres is up) and re-prepares its statements; the pump then retries
   with backoff.
3. **Loud.** A sink-failure counter surfaced in the heartbeat line (like `obsFailures=` /
   `eventWriteFailures=`), plus a `SINK_FAILURE` / `DB_ERROR` service event once the DB is writable
   again (both already in the D25 `EventType` catalogue). The dead-man (box-down) alarm — stale
   `capture_status`, slice C step 4 — fires meanwhile, correctly.
4. **Bounded by the queues, not a clock** (ruled 2026-10-04 — D28 (1)): no retry budget. The hold
   is bounded by what the queues can hold (bars unbounded; ticks shed-oldest at 100 000 ≈ 18 h) and
   the moment shedding starts is announced; a terminal failure stops the pump and exits 1 at once.
   The 10-min default stays on record with its cost: a restart loses what is held and fixes nothing.
5. **Pump death → immediate exit.** Not today's up-to-60s heartbeat latency: an uncaught-exception
   path or a fatal callback from the pump to `Main`.
6. **Tests to doctrine (G5).** A failing sink write against a recovering fake DB → no data lost,
   the retry succeeds, the counter is incremented, the event is written on recovery; boundaries
   exact (the 5s floor, the ladder, the 500th write); failure-mode tests on the terminal,
   stop-mid-hold and still-down paths; every behavioural test mutation-verified. `PumpTest.sinkFailureStopsThePumpKeepsTheBarAndKeepsTheCause` is the current
   fail-closed contract this ticket refines, not removes.

**Not in scope:** the Tier-2 best-effort policy; the belt; the schema.

**Sequencing:** T5 slice C merged (PR #12, 2026-10-04) — unblocked.
**Sequence vs T6 (the EOD heal): ruled 2026-10-04 (Alex) — T9 first.** It is small and fully in hand
and protects live capture now; T6 leans on pacer discovery and the unrun demo smoke (T5 residual 1).

**In-ticket decisions — ruled 2026-10-04 (Alex, the design nod; logged as D28):** (1) **no time
budget** — the queues bound the hold and shedding is announced (the 10-min default is on record with
its cost: a restart loses what is held and fixes nothing); (2) the **blip taxonomy** — SQLSTATE 08 /
57 / 53 / 40 + the pool timeout retry, all else terminal at once; (3) **backoff** = the belt's
`BackoffPolicy` with the playbook tuning; (4) **recording** — one `SINK_FAILURE` on recovery, nothing
attempted while down, `sinkFailures=` on the heartbeat; (5) **immediate exit** via `onDeath` (never on
a stop); (6) **pool `connectionTimeout` 5s**, no dedicated Tier-2 pool.
**Increments — all merged in PR #13 (Alex, 2026-10-05, merge commit `64bb872`):** A store side
(`5e0b7aa`: `PersistenceException.retryable`, `CaptureStore.recover`, `PostgresStore` holds its batch;
9 tests, 8 mutations) · B the pump (`bf78387`: hold / backoff / recover / `SINK_FAILURE` / `onDeath`;
6 scenarios, 9 mutations) · C composition + docs (`a7773f3`: heartbeat `sinkFailures=`,
`Database.CONNECTION_TIMEOUT` 5s pinned; ch. 4, 5, 10 + foundations ch. 2; D28; 1 mutation) · the
ticket-level doctrine review (`215ad09`: pass-with-findings F1–F8, all fixed on-branch — **F1 a real
defect**: pgjdbc drops its batch on a failed `executeBatch`, so a retry on the same statement would have
acknowledged nothing; the store now refuses every write/flush until `recover()` succeeds; the terminal,
idle-flush and tick-write paths pinned; `DB_ERROR` written before a terminal exit; 8 mutations). 26
mutations in all, every restore identical (the PR body carries the per-test table). Tests at merge:
market-data-service 164 · ig-client 92 · docs site 63 · 0 failures.

**Cross-references:** P8 · P9 · CLAUDE.md "the DB is downstream of decisions" · Field Manual ch. 10
(`docs/field-manual/market-data-service/10-the-failure-playbook.md`, "When the database fails" — the
Tier-1 row — now the landed policy — and the former "the pending decision", which this ticket closed) · ch. 4 (the pump, ack-after-apply)
· ch. 5 (the capture store) · D25 (`EventType.SINK_FAILURE` / `DB_ERROR`) · the 2026-10-03 slice C
step-3 pump ruling (observability best-effort-but-loud, sink fail-closed — ch. 10, "The rulings
behind it").

**Refined by E1-T10 (the PR #14 review, 2026-10-05):** findings **6** (`recover()` makes a round trip — the held
batch executes inside recovery, so "recovered" means a write landed — and carries attempt/since across consecutive
episodes; today a database that accepts connections but rejects writes "recovers" every 5s and the ladder never
climbs), **7** (JDBC `socketTimeout` 30s + `tcpKeepAlive` on the pool and `dedicatedConnection()` — D29 (2); a
half-open connection otherwise parks `executeBatch` forever with no `PersistenceException` for the hold to see)
and **20** (the shutdown tail drains everything, makes one `recover()` attempt if the sink is broken at stop, logs
`pendingWrites()` unconditionally) refine this ticket's hold. They landed on T10 slice B, as scenarios on the E1-T11
harness (its eighth and ninth scenarios, plus its container-pause scenario for the socket timeout) — PR #19, merged 2026-10-10.

**DoD anchor (amended 2026-10-04 to D28) — met 2026-10-05 (the tests at merge + the outage drill below):** any outage while the tick queue is not shedding loses zero
bars and zero ticks; the heartbeat shows the failure count; a `SINK_FAILURE` event records the episode
on recovery (a `DB_ERROR` the terminal exit); a terminal failure stops the pump and the process exits
1 immediately; all behavioural tests mutation-verified; ch. 10's Tier-1 row updated from "today: stop
and restart" to the landed policy.

**Added 2026-10-03 (step-4 doctrine review, F3):** the observability pool (`Database`: Hikari, max 4,
default 30s `connectionTimeout`) is shared by the belt's event writes and the heartbeat's
`capture_status` upserts, so a Postgres outage can hold the sweep thread ~30s per event and the
heartbeat ~30s per market — delaying the dead-pump notice past the ≤60s promise. Decide here: a
short `connectionTimeout` for observability writes, or a dedicated small pool (config — Alex).
**Ruled 2026-10-04 (D28 (6)):** a 5s `connectionTimeout` on the shared pool
(`Database.CONNECTION_TIMEOUT`, pinned); no dedicated pool.

**Proven in anger — the outage drill (2026-10-05; Alex at the keyboard, Claude verifying the DB).** The
DoD's "zero bars and zero ticks" claim exercised against the real stack during live DAX capture: the local
Postgres stopped, a 152-second sink outage held and drained. Written so it can be repeated.

*Gate:* the drill only proves anything **while the market is ticking** — confirm `ticks=` rising across two
heartbeats before stopping the database; a flat `ticks=` proves nothing either way.

*Setup:* the local Postgres is compose service `postgres` (`compose.yaml`: container `tradebench-postgres`,
host port 5435, db `market_data`, user from `.env`'s `TRADEBENCH_DB_USER`); capture runs via
`scripts/run-capture.sh`, which sources `.env` (`TRADEBENCH_SINK=db`).

*Routine (UTC):*
1. `docker compose up -d postgres`
2. `scripts/run-capture.sh` — this run: capture began 16:31:00Z; Flyway applied V2 on this start.
3. Wait for two heartbeats with `ticks=` rising (the gate).
4. `docker stop tradebench-postgres`
5. `sleep 90` — the pump notices recovery only at its next scheduled attempt, so the outage it measures
   runs longer than the database's actual downtime (152s here).
6. `docker start tradebench-postgres`
7. Read the log: the `sink unavailable — holding` line, the `sink retry n in …` ladder, `sink recovered
   after …`, and the heartbeats around them.
8. Verify with psql — `docker exec -it tradebench-postgres psql -U tradebench -d market_data` — the five
   queries below.

*Pre-drill scar (recorded so it is not re-learned):* Flyway refused to start — **checksum mismatch on
V1**: the 2026-09-28 date sweep had edited a comment in `V1__baseline.sql` after the dev database had
already run it. Fixed by the `flyway repair` equivalent — `UPDATE flyway_schema_history SET checksum =
<resolved> WHERE version = '1'` — after which V2 applied normally. Lesson: **an applied migration is
immutable, comments included.**

*Log (trimmed, UTC):*

```
16:34:01 ticks=295 bars=3 written=298 dropped=0 … sinkFailures=0 … statusFailures=0
16:34:26 sink unavailable — holding 0 queued writes: PersistenceException: tick batch flush failed
16:34:26 sink retry 1 in 5s
16:34:36 sink still unavailable after attempt 1: … sink recovery failed — still unavailable
16:34:46 sink still unavailable after attempt 2 … retry 3 in 5s
16:34:56 sink still unavailable after attempt 3 … retry 4 in 11s
16:35:06 ticks=397 bars=4 written=333 … sinkFailures=1 … statusFailures=1
16:35:12 sink still unavailable after attempt 4 … retry 5 in 13s
16:35:31 sink still unavailable after attempt 5 … retry 6 in 43s
16:36:11 ticks=525 bars=5 written=333 … sinkFailures=1 … statusFailures=2
16:36:20 sink still unavailable after attempt 6 … retry 7 in 39s
16:36:59 sink recovered after 152s and 7 attempt(s); 303 queued writes to drain
16:37:11 ticks=653 bars=6 written=659 … sinkFailures=1 … statusFailures=2
16:40:11 ticks=932 bars=9 written=940 … sinkFailures=1 … statusFailures=2
```

*Reading the log:* the failure fired on the **idle-flush path** (the review's F3 case); each retry took
~5s longer than its wait — the pool's 5s `connectionTimeout` (D28 (6)) failing fast inside `recover()`;
the waits 5 / 5 / 5 / 11 / 13 / 43 / 39s show the floor, the jittered doubling and the 60s cap of the belt's
`BackoffPolicy` (D28 (3)); `written=` froze at 333 while `ticks=` kept rising — **held, not lost**;
heartbeats slipped ~5s during the outage (the Tier-2 `capture_status` upsert timing out — the documented
worst case; `statusFailures=` 0 → 1 → 2 on the two in-outage heartbeats); recovery drained 303 queued
writes and `written=` (659) matched `ticks=` + `bars=` (653 + 6) on the next heartbeat.

*Verification (psql — the five queries; replace `<drill start>` with the capture start, here
`2026-10-05 16:31:00Z`):*

```sql
select event_time_utc, event_type, severity, detail from service_events where upper(event_type) in ('SINK_FAILURE','DB_ERROR') order by event_time_utc desc limit 5;
select start_utc, start_utc - lag(start_utc) over (order by start_utc) as step from bars_1m b join instruments i on i.id = b.instrument_id where i.epic = 'IX.D.DAX.DAILY.IP' and start_utc >= '<drill start>' order by start_utc;
select gap_from_utc, gap_to_utc, missing_minutes, detected_at_utc from bar_gaps order by detected_at_utc desc limit 5;
select date_trunc('minute', ts_utc) as minute, count(*) from ticks where ts_utc >= '<drill start>' group by 1 order by 1;
select instance, updated_at_utc, stream_state, db_pending, ticks_total, dropped_ticks from capture_status;
```

Results, this run:
1. **Events:** one `sink_failure` row at 16:34:26.59Z — detail `attempts` 7, `outageMs` 152678,
   `ticksShed` 0, `queuedAtRecovery` 303, `cause` = the batch's `BatchUpdateException … FATAL: terminating
   connection due to administrator command` (SQLSTATE 57P01 — class 57, retryable per D28 (2)); **no
   `db_error` row.**
2. **Bars:** `bars_1m` 16:31 → 16:40 present with every `start_utc - lag(start_utc)` step = `00:01:00`,
   including 16:34 / 16:35 / 16:36 — the outage minutes.
3. **Gaps:** `bar_gaps` — 0 rows.
4. **Ticks per minute** 16:31–16:41: 86, 97, 107, 98, 126, 117, 108, 80, 86, 92, 101 — the outage minutes
   (16:34 / 16:35 / 16:36 = 98 / 126 / 117) in line with their neighbours; 302 ticks inside
   16:34:26Z–16:36:59Z, the earliest (16:34:26.337) being the failed batch's own first entry — **the held
   batch landed.**
5. **Status:** `capture_status` fresh at 16:41:11Z — `connected_streaming`, `db_pending` 0,
   `dropped_ticks` 0, `ticks_total` 1020.

**Verdict:** zero bars and zero ticks lost across a 152-second outage — the DoD's central claim, met on the
real stack. `written=` lagging `ticks=` + `bars=` by one on two later heartbeats is a snapshot artefact (a
tick sitting in the queue at the instant the line is printed), not a loss.

## T10 — Resilience belt hardening: the PR #14 review ✅ (ticketed 2026-10-05; started 2026-10-08; done 2026-10-10 — A1 PR #15, merged 2026-10-08; A2 PR #17, merged 2026-10-09; B PR #19, merged 2026-10-10; C PR #20, merged 2026-10-10 — every finding of the PR #14 review resolved)

**Type** build · **Branch** `e1-t10-c-loudness-reuse-docs` (slice C, from main `897fb99`, merged PR #20; A1 merged from `e1-t10-belt-hardening`, PR #15; A2 merged from `e1-t10-a2-detectors-verdicts`, PR #17; B merged from `e1-t10-b-sink-and-database-edge`, PR #19) · **Started** 2026-10-08 · **Blocked by** —

**Goal:** the belt's 32 review findings fixed or answered, each fix carrying the test that would have caught
it — so chapter 10's promises hold on the code, not only in prose.

**Source of truth per finding:** `.claude/reviews/2026-10-05-pr14-resilience-belt.md` — the 2026-10-05
`/code-review ultra` run over the whole belt (E1-T5 slices A–C + E1-T9) as **PR #14**, a review-only PR (base
pinned at `ee35f9b`, head = main; 103 files, +7492/−599; closed unmerged, branches deleted). 32 verified, ranked
findings: two high (#1, #2), eight medium (#3–#10), 22 low (#11–#32); every verdict CONFIRMED except #15
(PLAUSIBLE — it rests on the Lightstreamer SDK's documented throw-on-inactive contract). This plan cites finding
numbers and does not restate the file. **Addendum (2026-10-08):** finding **#33** — one stray per-market resubscribe
the sweep before the session verdict — found by E1-T11's replay of the 2026-08-04 scar and recorded in the review
file's addendum (§33, rewritten the same day to the observed behaviour with a correction note per G8, after T11's
doctrine review proved the first account — "the session verdict never fired; rebuild at T+450" — wrong); **low**
(first recorded as medium); triaged to slice A2 (below). Its one policy call is made: **ruled 2026-10-08 (Alex) — fix in
A2, mechanically, the one-sweep hold; no separate decision entry** (the fix shape and the chapter-10 clause are in the
A2 bullet).

**Policy the fixes stand on — D29 (Alex, 2026-10-05), nothing beyond it:** (1) the watchdog's CLOSED/SUSPEND
stand-down is bounded — the remembered flag is cleared on every rebuild/resubscribe, and a market stood down
for more than 12 hours gets one teaching resubscribe per 12 hours (finding 3); (2) JDBC `socketTimeout` 30s +
`tcpKeepAlive` on the pool and the lock's dedicated connection (finding 7); (3) Tier-2 event writes leave the
sweep thread via a bounded queue drained by one writer thread, drops counted in `eventWriteFailures`
(finding 12); (4) the `closing()`/`GRACEFUL_CLOSE` hush is deleted — the generation gate is the §3.6 hush
(finding 29).

**Slices (Alex's sequencing, 2026-10-08, standing: A1 first with targeted tests — ✅ PR #15 → E1-T11 the harness — ✅ PR #16,
merged 2026-10-08 → A2 as scenarios on it — ✅ PR #17, merged 2026-10-09 → B as scenarios — ✅ PR #19, merged 2026-10-10 → C,
the last slice, one PR — ✅ PR #20, merged 2026-10-10; T10 complete → E1-T12 replay acceptance → the belt's single cloud re-review, now gated only on T12 — every T10 finding is resolved
(the close-out ruling, below) → E1-T6):**

- **A1 — the two highs, targeted tests, first.** **#1** a quarantined market's twin-leg rejection re-admits it:
  gate `SubscriptionError`s for epics in `quarantinedMarkets` in `Supervisor.sweep()`;
  `IgStreamControl.resubscribe` refuses quarantined epics; exposing test `failToTheStrikeCeiling` with four
  errors instead of exactly three. **#2** a bare `DISCONNECTED` or `onServerError` on the live generation
  triggers no rebuild when the markets' last flag reads CLOSED: treat both as a rebuild trigger; test — a bare
  `DISCONNECTED` with CLOSED flags → a rebuild. May ship on its own PR if Alex wants the highs on main quickly.
  **Landed: PR #15, merged 2026-10-08 (`92c0181`)** — 7 scenarios, 9 mutations; the slice review's F1 made the
  terminal trigger a latch asked every sweep (an edge-triggered rebuild paced out inside the 5s floor would have
  stalled the ladder until the budget) and `onServerError` a death too (the nod's "both" restored); F5 (a stray
  `onSubscribeStarted` on a quarantined epic) recorded, not fixed — the witness loop already excludes
  quarantined epics, no observable effect; revisit at A2 #14.
- **A2 — detectors and verdicts, as E1-T11 scenarios.** **#5** the watchdog stands down while the escalator
  holds a substate / the connection is not streaming (no rebuild before the 300s patience); **#18** clear the
  escalator's substate on rebuild so an IG outage ends by the ten-minute budget, not the ceiling; **#4** a `Wait`
  verdict is re-judged when the witness confirms or its window lapses; **#8** strikes once per (epic, attempt) —
  carry the leg `Kind` on `onSubscriptionError`; **#14** `witness.onSubscribeStarted` before a watchdog
  resubscribe; **#13** `giveUp()` short-circuits the rest of the sweep and is idempotent; **#11** observations
  stamped with the connection generation (or the queue drained-and-discarded after `stream.rebuild()`); **#19**
  a `RuntimeException` escaping `sweep()` records `FEED_DEAD{reason: fatal, cause}` and calls `onExhausted`;
  **#21** `rebuilding()` takes the `SubscriptionError`'s own clock stamps; **#3** the bounded CLOSED stand-down
  (D29 (1)); **#29** delete the hush (D29 (4)); **#33** one stray per-market resubscribe the sweep before the session
  verdict — the session verdict ("≥2 markets stale together → rebuild at once") does fire at the second market's 90s
  mark, but staleness is purely time-based and the scar's two feeds died 584ms apart, so at the sweep 90s after DAX's
  last tick only DAX is stale and gets market surgery (the pair dropped and re-asked, a misleading
  `WATCHDOG_STALE{resubscribe}` row); one sweep later both are stale and the session is rebuilt as promised. Low.
  **Ruled 2026-10-08 (Alex): fix in A2, mechanically — the one-sweep hold; no separate decision entry.** The fix: when a
  market crosses its tick-silent threshold and another market is within one sweep of crossing too, the per-market
  remedy waits that one sweep so both are judged together and the session verdict is rendered without the stray
  resubscribe. Chapter 10's row ("≥2 markets stale together → rebuild at once") gains a clause defining "together" as
  within one sweep of each other. `theScarsTwoDeadMarketsEarnOneSessionVerdictAtNinetySeconds` is the exposing test.
  **Also in A2, ruled 2026-10-08 (Alex) after T11's close-out:** **T5 residual 3's rebuild half** — the host wakes with the
  Lightstreamer client stuck in `DISCONNECTED:WILL-RETRY` and the escalator rebuilds 120s after the wake, measured in
  awake time — as a new scenario: the dead-socket scenario (`aDeadSocketIsTheEscalatorsToRebuildAtOneHundredAndTwentySeconds`,
  #5) with a 16-minute host suspend in front of the hang, as ruled (the rebaseline half closed at T11; T11's DoD wording
  stays) — built the hang then the suspend, reordered at A2's doctrine review (finding 1) so the scenario discriminates
  awake from wall time: a recorded deviation from the ruling's ordering, with the reason (T5 § residual 3).
  **Housekeeping — server-first language:** the service is designed for and deployed on a server; the laptop problems
  of the past were problems of running a server on a laptop and are not baked into the program or its language. The
  mechanism stays wherever it adds coverage and resilience — the wall/monotonic clock-divergence discriminator, the
  `hostSlept` annotation, `Tuning.hostSleepSkew`, the `HostSleep` fixture event — but scenarios, test names, code
  comments and docs frame it as a **suspended host** (a paused or live-migrated VM, a hibernated instance, a frozen
  process), never a laptop lid. In A2 (code, rides the slice's PR): rename `aLidCloseIsAnnotatedNotTreatedAsAnOutage` to a
  host-suspend name and purge the lid/laptop framing from `BeltScenariosTest`, `Event.HostSleep`'s javadoc,
  `ReconnectClassifier`'s javadoc, `ReconnectClassifierTest` and `StalenessWatchdogTest`. Field Manual chapters 07, 08
  and 10 reworded 2026-10-08 direct to main (docs-only, the build session). On this plan and the board, "lid close"
  describing our scenario reads "host suspend"; the prototype's captured `host_slept` outages keep the fact with "the
  prototype ran on a laptop" (T12 §). **Started 2026-10-09, merged 2026-10-09 — PR #17; the record in "A2 — merged", below.**
- **B — the sink and the database edge.** **#6** `recover()` makes a round trip — executes the held batch inside
  recovery, so "recovered" means a write landed — and carries attempt/since across consecutive episodes; **#7**
  `socketTimeout` 30s + `tcpKeepAlive` (D29 (2)) on the pool and `dedicatedConnection()`, with a Testcontainers
  pause test; **#20** the shutdown tail drains everything (`while (drainOnce() > 0)`), makes one `recover()`
  attempt if the sink is broken at stop, and logs `pendingWrites()` unconditionally; **#15/#16** per-leg handle
  tracking — forget each leg as its own unsubscribe succeeds, subscribe a new pair into locals and roll back the
  first leg if the second throws, give `FakeStreamTransport` per-handle state (#15 is PLAUSIBLE — it rests on
  the SDK's throw-on-inactive contract); **#17** `connect()` publishes `stream` only after subscribing and
  `start()` catches `RuntimeException` like `rebuild()`; **#30** `@Nullable` on `PostgresStore.brokenBy`;
  **#24** the shutdown hook registered before `control.start()` and chapter 10's join-fence sentence corrected;
  **#12** Tier-2 writes via the bounded queue + writer (D29 (3)). **Started 2026-10-09, merged 2026-10-10 — PR #19; the
  record in "B — merged", below.**
- **C — loudness, reuse, docs.** **#9** a log consumer for the Supervisor (lines on Retry,
  `SUBSCRIPTION_REJECTED`, `MARKET_QUARANTINED`, `IG_API_ERROR`); **#10** shared JDBC helpers (`InstrumentIds`
  + a `Jdbc` holder) instead of the copies in `PostgresObservabilityStore`; **#22** `HealthProbe.publish()` one
  connection/batch per heartbeat, or short-circuit after the first pool-timeout failure — supersedes the
  heartbeat fail-fast note backlog B1 captured 2026-10-05 from the T9 drill (folded here; B1 keeps a pointer);
  **#26** a `CountingEventLog` decorator replacing the six copied best-effort blocks (PacerDiscovery's copy has
  no counter today); **#27** one `FakeClock` and one `RecordingEventLog` test util — a prerequisite shared with
  E1-T11, done in whichever lands first; **#28** `FlakySink` holds a failed tick as the real store does,
  asserting `tick@10, tick@11`; **#31** `StreamEvents.onMalformed(epic, item)` so `Buffers.epicOf` and its tests
  go (closes the slice C step-4 review's `epicOf` follow-up — `41969a7`); **#32** `.gitignore` un-ignores
  `**/src/**/build|out|tmp/`; **#25** the three field-manual links broken by D26 (`components/ig-client.md:26`,
  `core.md:15`, `:28`). **Started 2026-10-10, merged 2026-10-10 — PR #20; the record in "C — merged", below.**
- **#23** (no combined-detector test) *is* the harness — **E1-T11**.

**A2 — merged (PR #17, 2026-10-09).** **Started 2026-10-09** on `e1-t10-a2-detectors-verdicts` (branched from main at `c2d46e8`); design nod
posted and accepted by Alex 2026-10-09 ("I accept your recommendations"). **Four increments, one PR for the slice:** **1 the
watchdog** (#5, #33, #3) · **2 the witness** (#8, #4, #14) · **3 the session** (#18, #11, #13, #19, #21, #29) · **4
housekeeping** (the host-suspend-then-stuck scenario — T5 residual 3's rebuild half; the ruling-3 rename of
`aLidCloseIsAnnotatedNotTreatedAsAnOutage` and the lid/laptop comment purge; chapter 10's known-deviations note removed and
its "together" clause added). **In-ticket decisions accepted at the nod:** `StreamControl.rebuild()` returns an outcome
(CONNECTED / FAILED / FATAL) — a failed connect is re-asked next sweep and only connected rebuilds count toward the ceiling
(#18); observations carry the connection generation and are applied only when current (#11); the teaching resubscribe
reuses the watchdog's resubscribe remedy with a new signal `STAND_DOWN_TEACHING`, recorded as `WATCHDOG_STALE`, the twelve
hours as `Tuning.standDownTeach` (#3); the one-sweep hold window is the sweep interval passed to the watchdog, not a
tunable (#33); the leg kind rides on the rejection (#8); `Supervisor.die(cause)` mirrors the pump's (#19).
**Increment 1 — the watchdog, `92d73f3` (2026-10-09)** (`StalenessWatchdog`, `Supervisor`, `Tuning`, their
tests, and `BeltScenariosTest`): `StalenessWatchdog` renders no verdict while the connection is not streaming and starts
every stopwatch afresh on resume (`onConnection`); the one-sweep hold (`anotherWithinASweep`); the bounded stand-down —
`onDealFlag` now stamps when a closing flag began, `clearDealFlag` forgets it on a re-ask, and a market closed for
`standDownTeach` (12h, D29 (1)) gets one `RESUBSCRIBE` with signal `STAND_DOWN_TEACHING` per period, its silence counted
from the fresh subscription. `Supervisor` feeds a remembered flag only when a tick newer than the market's last re-ask
carries it (`reasking` on retry, watchdog resubscribe and rebuild) and tells the watchdog the connection's state. Four
scenarios switched on: `tryingRecoveryIsGivenItsThreeHundredSecondsBeforeAnyRebuild` and
`aDeadSocketIsTheEscalatorsToRebuildAtOneHundredAndTwentySeconds` (#5),
`theScarsTwoDeadMarketsEarnOneSessionVerdictAtNinetySeconds` (#33),
`aWeekendZombieBehindAClosedFlagIsFoundByTheTwelveHourTeachingResubscribe` (#3 — tightened to exact offsets: the teach at
43201, resubscribes at 43291 and 43411, the session rebuild at 43651). Five new `StalenessWatchdogTest` tests, one new
`SupervisorTest` test, `TuningTest` pins the 12h. Tests: market-data-service 199 (6 skipped), 0 failures. Eight mutations
red and restored byte-identical (judging a down connection; no fresh window on resume; no hold; the teaching period
doubled; teaching every sweep; the teach not restarting the silence; the stale flag fed back; the shell never telling the
watchdog the connection's state). **Remaining `@Disabled` after increment 1:** #4 ×2, #8 (increment 2); #6, #20 ×2 (slice
B).
**Increment 2 — the witness, `a21e8d7` (2026-10-09):** **#8** one strike per (epic, attempt), the leg named on the rejection;
**#4** a `Wait` keeps its strike and `rejudge` renders the held verdict when the witness confirms or its window lapses,
`Judgment.Rebuild` carrying the epic; **#14** a watchdog resubscribe opens a fresh confirm window; **#21** (planned for
increment 3, landed here) verdicts dated when rendered with the rejection in the detail, the outage opened on the sweep's
own clocks. Scenarios switched on: both #4 arms (`aRejectionHeldForAnUnconfirmedWitnessIsJudgedWhenTheWitnessConfirms`,
`aVerdictHeldForAWitnessThatNeverConfirmsIsSessionShapedWhenTheWindowLapses`) and #8
(`aMarketWhoseBothLegsAreRejectedGetsItsTwoSurgicalRetries`). Eight mutations red and restored byte-identical.
**Increment 3 — the session, `6e0a82d` (2026-10-09):** **#18** `StreamControl.rebuild()` returns `Outcome {CONNECTED, FAILED,
FATAL}` — a failed connect is latched and re-asked next sweep, paced; only connected rebuilds count toward the ceiling; the
escalator's substate is reset after a rebuild; **#11** observations carry the connection generation (a generation-carrying
`StreamObserver`, `StreamControl.generation()`) and the shell applies only `expectedGeneration`; **#13** `giveUp` once, the
sweep short-circuits; **#19** `Supervisor.die(cause)` writes `FEED_DEAD{reason: fatal, cause}` and hands over — `run()` and
the harness use it; **#29** `closing()`/`GRACEFUL_CLOSE` deleted, chapter 08 naming the generation gate as the §3.6 hush. One
new scenario: `aFeedThatCannotBeRebuiltDiesByTheBudgetNotTheCeiling` — IG unreachable for good dies by the budget at 780
with fifteen reason rows. Five mutations red and restored byte-identical.
**Increment 4 — housekeeping, `ba7fe45` (2026-10-09):** the stuck-after-a-suspend scenario
`aHostThatWakesWithTheClientStuckIsRebuiltInAwakeTime` (T5 residual 3's rebuild half); the server-first rename
`aLidCloseIsAnnotatedNotTreatedAsAnOutage` → `aHostSuspendIsAnnotatedNotTreatedAsAnOutage` and the last laptop framing out
of the code (ruling 3); chapters 08/10 updated — chapter 10's rows carry the one-sweep "together", the 12h stand-down bound
with a `standDownTeach` numbers-table row, the per-attempt strike and the held verdict, the ceiling counting connected
rebuilds; its known-deviations note reduced to slice B's #6 and #20. One mutation red and restored byte-identical.
**Doctrine review 2026-10-09 — pass-with-findings; seven findings, all fixed on the branch in `3e7ca9a`:** (1) **medium** —
the stuck-after-a-suspend scenario had the suspend before the hang, so it did not discriminate awake from wall time;
reordered — the hang, then the suspend — with `hostSlept=true` asserted; a consistent-wall-clock escalator now fails it
(rebuild at 60, not 180); (2) low — the per-attempt straggler rule could drop a rebuilt pair's first rejection stamped
before the shell read its clock; narrowed to after a surgical retry only, with a unit test; (3) low — `run()`'s catch lost
the stack trace; it now rethrows after `die()`; (4) low — the one-sweep hold's boundary pinned to the nanosecond with two
unit tests; (5) low — the held rejection required non-null by name; (6) low — chapter 08's watchdog section gained the
stand-down bound, the connection stand-down and the one-sweep "together" (seven pieces of judgment; the state diagram's
teach edge); (7) low — the shutdown javadoc lost the hush. Observations accepted, not changed: FAILED re-asks reuse the
original reason row (fifteen `STUCK_SUBSTATE_ESCALATED` rows for one escalation — a slice-C loudness nicety); two mutants
equivalent (the escalator reset behind the re-ask latch; the give-up guard behind the short-circuits). Three mutations red
and restored byte-identical.
**Totals at the PR:** all twelve A2 findings fixed with their exposing tests (#3, #4, #5, #8, #11, #13, #14, #18, #19, #21,
#29, #33); T5 residual 3's rebuild half closed by the reordered scenario (final at the merge, 2026-10-09 — residual 3 closed, final); the server-first rename
done. 25 mutations red and restored byte-identical (8 + 8 + 5 + 1 across the increments, 3 on the review's fixes).
`BeltScenariosTest` at 23: seven scenarios switched on (#3, #4 ×2, #5 ×2, #8, #33), two new
(`aFeedThatCannotBeRebuiltDiesByTheBudgetNotTheCeiling`, `aHostThatWakesWithTheClientStuckIsRebuiltInAwakeTime`) plus the
rename; three remain `@Disabled` for slice B (`aDatabaseThatRefusesWritesIsOneEpisodeClimbingTheLadder`,
`aStopWithABacklogDrainsAllOfItIntoAHealthySink`, `aStopDuringAHoldAfterPostgresReturnedRecoversOnceAndLandsEverything`).
Tests at the PR and at merge: market-data-service 212 (3 skipped — slice B's) · ig-client 99 · docs site 63 · 0 failures;
`./gradlew build` and the docs build green as CI runs them. **PR #17** opened 2026-10-09
(https://github.com/amfshr/tradebench/pull/17), **merged by Alex 2026-10-09 into `main`, merge commit `2a2f289`**; CI green on
the merged head (`build` 52s, GitGuardian pass); the local branch deleted. Commits as merged: `92d73f3` (1/4) · `a21e8d7` (2/4)
· `6e0a82d` (3/4) · `ba7fe45` (4/4) · `3e7ca9a` (the review fixes) · `fb24eb5` (board). The board's Done history carries the
entry (tagged E1-T10); T5 § residual 3 is closed, final. **Next:** slice B as scenarios on the harness (#6, #20 ×2 — the three
`@Disabled`; with #7, #12, #15/#16, #17, #24, #30) → C, one PR per slice — the next build work → E1-T12 → the belt's single cloud
re-review once every T10 finding is resolved (the close-out ruling below) → E1-T6.

**B — merged (PR #19, 2026-10-10).** **Started 2026-10-09** on `e1-t10-b-sink-and-database-edge` (branched from main at `034a9ea`);
design nod posted and accepted by Alex 2026-10-09 ("pls proceed with your recommendations"). **Four increments, one PR
for the slice:** **1 the store and the pool** (#30; #6a — recovery lands the held batch; #7 — `socketTimeout` + `tcpKeepAlive`
with a container-pause test) · **2 the pump** (#6b — the episode survives a provisional recovery until a write lands,
`SINK_FAILURE` once per episode; #20 — the shutdown tail drains everything, one recovery attempt at stop, the pending count
logged unconditionally) — enables the three `@Disabled` scenarios · **3 the stream control** (#15/#16 per-leg handle tracking
with rollback; #17 the stream published only after every market subscribed, `start()` catching runtime exceptions) with new
fake-transport knobs · **4 the Tier-2 writer and the hook** (#12 a bounded queue before the Supervisor's event log drained by
one writer thread, the harness draining it in its hook, drops and writer failures counted; #24 the shutdown hook registered
before boot and chapter 10's join sentence aligned with the 5s-bounded wait; chapter 10's known-deviations note removed).
**In-ticket decisions accepted at the nod:** only the Supervisor's events go through the queue; a failed write on the writer is
dropped and counted (D29 (3)), not retried; an episode ends only when a write lands after a recovery, attempts counting every
retry; #24 aligns the chapter text rather than changing the hook; the socket timeout is injectable (production 30s per D29 (2),
the pause test 2s); the hook registration order in `Main` has no test seam and is verified by inspection.
**Increment 1 — the store and the pool, `54d6ab2` (2026-10-09)** (`Database`,
`PostgresStore`, `DatabaseTest`, `PostgresStoreTest`): `Database` carries `socketTimeout` 30s and `tcpKeepAlive` on the pool and
the lock's dedicated connection (`SOCKET_TIMEOUT`; an injectable overload for tests) (#7, D29 (2)); `PostgresStore.recover()`
executes the held batch inside recovery, so a database that answers connections but refuses writes fails to recover (#6a);
`brokenBy` is `@Nullable` (#30). Tests: `PostgresStoreTest` +2 (a 53100 trigger makes recovery fail retryably and keep the batch,
then land once the trigger is gone; a paused container surfaces as a retryable failure within the timeout and recovers after
unpause), `DatabaseTest` +1 (every connection carries the timeout and keep-alive, pooled and dedicated). market-data-service 215
tests (3 skipped — B's scenarios), 0 failures. Three mutations red and restored byte-identical (recovery only reconnects; the
pool without the timeout — the pause test times out; the dedicated connection without it). **Remaining `@Disabled` after
increment 1:** #6, #20 ×2 (increment 2).
**Increment 2 — the pump, `cb7ec46` (2026-10-09):** **#6b** the pump half — a hold is an episode that survives a provisional recovery
until a write lands: a bar is its own round trip, a flush counts only if ticks were batched since the recovery, an empty flush proves
nothing; `SINK_FAILURE` once per episode, when it is proven over. **#20** the tail drains every batch, makes one no-wait recovery
attempt if the sink is broken at stop, and logs the queued count unconditionally. The fake store recovers with a round trip, as the
real one does (T11's review note closed). The last three `@Disabled` scenarios switched on — #6
`aDatabaseThatRefusesWritesIsOneEpisodeClimbingTheLadder`, #20 `aStopWithABacklogDrainsAllOfItIntoAHealthySink` +
`aStopDuringAHoldAfterPostgresReturnedRecoversOnceAndLandsEverything` — so all 23 scenarios are green and none remain `@Disabled`.
Four mutations red and restored byte-identical; one equivalent mutant recorded (the fake store's round trip — pinned by the real
store's own test).
**Increment 3 — the stream control, `1d3d351` (2026-10-09)** (`IgStreamControl`, `IgStreamControlTest`, `FakeStreamTransport`):
**#15** legs are forgotten one by one as their own unsubscribe succeeds, a refused one kept; **#16** a pair is subscribed into
locals, the PRICE leg rolled back if CHART throws — a pair is both legs or neither; **#17** `connect()` subscribes every market
before publishing the stream, closes a half-built connection after bumping its generation, and `start()` catches
`RuntimeException` and fails loud. `FakeStreamTransport` gained `refuseUnsubscribeOf` / `failSubscribeOf`; four new
`IgStreamControlTest` tests. Five mutations red and restored byte-identical.
**Increment 4 — the Tier-2 writer and the hook, `58fff49` (2026-10-09)** (`QueuedEventLog`, `CaptureAssembly`, `Main`, their
tests, chapters 08/10): **#12** `QueuedEventLog` — the Supervisor's events go onto a bounded queue
(`CaptureAssembly.EVENT_QUEUE_CAPACITY` = 1,000) drained by one writer thread `capture-events`; a full queue refuses and the sweep
counts the drop; a refused write is counted and dropped, never retried (D29 (3)); the shutdown stops the writer last and drains the
rest; `CaptureAssemblyTest` pins the wiring; the harness gives the writer its turn in the hook. **#24** the shutdown hook registered
before boot in `Main` (the hook's registration moved before boot as the nod planned; what the nod declined was changing the join's behaviour, aligning the chapter text to the 5s bound instead — and the review's finding 1 showed the moved hook needed a guard to survive that window). Chapters 08/10 —
five threads, the bounded join, the Tier-2 row — and chapter 10's known-deviations note removed: nothing in it remains true (the
reviewer agreed slice C's findings are not deviations from chapter 10's promises). Four mutations red and restored byte-identical.
**Doctrine review 2026-10-09/10 — pass-with-findings; seven findings, all fixed on the branch in `d5b4ef9` except (6), the board
record, committed as `381d06a`:** (1) **medium** — `Main.join` used the JDK's timed join, which throws on a thread never started, so the hook moved
before boot would have died before closing anything in exactly #24's window; guarded (`Thread.State.NEW` returns at once), `join`
made package-private and pinned by `MainTest` (two tests) — #24 now delivered; (2) low — a hold ended by the tail's own recovery
was never recorded: the tail now marks its recovery as `hold()` does (an episode of one attempt if the drain itself broke the sink),
and the stop-at-38 scenario asserts the `SINK_FAILURE` row; (3) low — a provisional episode across a quiet market merged a later
blip into it: `CaptureStore.recover()` now returns whether it landed held writes, and an episode so proven closes at once
(`PumpTest.aRecoveryThatLandedTheHeldBatchClosesTheEpisodeAtOnce`) — the reviewer offered this as a `Decision: (TBD)`; the build
session took the mechanism option, as it is information the store already has and keeps the nod's rule — **flagged for Alex
(below)**; (4) low — stale docs: chapter 04 (the tail), chapters 05 and 10 (recovery executes the batch; `SINK_FAILURE` once the
episode is proven over), chapter 10's heartbeat counters gained `eventsQueued`; (5) low — the hook's Tier-2 drain was unbounded
while Postgres is down: `QueuedEventLog.drainRemainder()` stops at the first refused write and counts the rest as dropped, with a
test; (6) the board record stopped at increment 1 — the board + this plan brought up to the branch head (`381d06a`, on the branch); (7) nits —
an unused import, a test message, import order, a comment. Four mutations red and restored byte-identical.
**Totals at the PR:** all eight B findings fixed with their exposing tests (#6, #7, #12, #15/#16, #17, #20, #24, #30); three
scenarios switched on, none `@Disabled` remain — `BeltScenariosTest` 23 green; chapter 10's known-deviations note gone. Mutations:
**20 red and restored byte-identical** — 3 + 4 + 5 + 4 across the increments, 4 on the review's fixes; one equivalent mutant
recorded at increment 2 (the fake store's round trip, pinned by the real store's own test). Tests at merge: market-data-service 230
(0 skipped) · ig-client 99 · docs site 63 · 0 failures; `./gradlew build` and the docs build green as CI runs them. **PR #19**
(https://github.com/amfshr/tradebench/pull/19), **merged by Alex 2026-10-10 into `main`, merge commit `8324c74`** (GitHub stamps the
merge 2026-10-09T23:31Z); CI green on the merged head (`build` 48s, GitGuardian pass); the local branch deleted. Commits as merged:
`54d6ab2` (1/4) · `cb7ec46` (2/4) · `1d3d351` (3/4) · `58fff49` (4/4) · `d5b4ef9` (the review fixes) · `381d06a` (board). The
board's Done history carries the entry (tagged E1-T10). **Open for Alex:** whether the `boolean recover()` mechanism (review
finding 3) stands as taken — noted, not a blocker. **Next:** **slice C — the last slice of T10, one PR; T10 closes with it** (#9,
#10, #22, #25, #26, #28, #31, #32; #27 done at T11; **started 2026-10-10, merged 2026-10-10 — PR #20; the record in "C — merged", below**) → E1-T12 → the
belt's single cloud re-review once every T10 finding is resolved (the close-out ruling below) → E1-T6.

**C — merged (PR #20, 2026-10-10).** **Started 2026-10-10** on `e1-t10-c-loudness-reuse-docs` (branched from main at `897fb99`); design nod posted and accepted by Alex
2026-10-10 ("okay fab proceed"). **Three increments, one PR for the slice — the last slice of T10; T10 closes with it:** **1 loudness**
(#9, #26) · **2 reuse and seams** (#10, #31, #28, #22) · **3 docs and conventions** (#25, #32); #27 was done at T11. **In-ticket
decisions accepted at the nod:** **#26** the decorator is `BestEffortEventLog` — the codebase's own term, not the review's
`CountingEventLog`: a refused write is counted and logged, never thrown; one per producer (pump, supervisor, pacer); the heartbeat
line keeps its D27 vocabulary — `obsFailures=` stays the pump's Tier-2 total (the gap store + its events), `eventWriteFailures=`
the belt's (the supervisor's decorator + the queue writer); the pacer's decorator is log-only — discovery runs once at boot,
before the heartbeat exists, so its one possible refusal is a console line, not a heartbeat term (doctrine review 2026-10-10,
finding 3: recorded, not summed); **#9** every event the Supervisor records is also one console line, plus a line on the Retry
verdict (a superset of the four lines the review names); **#22** the batch, not the short-circuit — the status store takes the
list of rows, one pooled connection, one statement, so a failed publish counts once (one acquire timeout per heartbeat, not one
per market); **#25** the three links fixed plus a real dead-link check — a vitest test in the docs site walks the repo's docs and
board on disk and fails on any relative markdown link whose target is missing (a census on 2026-10-10 found exactly the three
review-named links dead), and the web PR lane's paths widened to `docs/**` and `.claude/tasks/**` so it runs on docs-only PRs;
**#28** test fidelity only — the flaky sink holds a failed tick and replays it in `recover`, as the real store does — the faithful
fake exposed `anIdleFlushAfterAProvisionalRecoveryProvesNothing` as pinning a state the real store cannot reach (a tick failure is
never a provisional recovery: the store holds the tick); replaced by
`aProvisionalRecoveryIsProvenByTheBarThatLandsNotByTheReconnect`, which also pins #6's rule; **#31** `onMalformed(epic, itemName)`
as the review wrote; `Buffers` charges the market by epic.
**Totals at the PR:** all eight C findings fixed with their exposing tests (#9, #10, #22, #25, #26, #28, #31, #32; #27 done at T11).
Mutations: **16 red and restored byte-identical** — M1–M14 across the three increments, M15–M16 on the review's fixes. **Doctrine review
2026-10-10 over the whole slice — pass-with-findings:** four low and three nits, all fixed on the branch before the PR (`92e1204`): F1 the
heartbeat's two decorator terms (`obsFailures=`, `eventWriteFailures=`) had no pin → two `CaptureAssemblyTest` tests, mutations M15/M16;
F2 chapter 10 said "per market" in three places about the heartbeat's outage cost → per heartbeat, and D28 (6) carries a dated
amendment; F3 the pacer's decorator is log-only → recorded (the #26 decision above); F4 the dead-link walk's `> 50 files` guard →
sentinels. Judged sound on request: the producers taking `BestEffortEventLog` as a type; `docs/inherited` included in the dead-link
walk; the Supervisor's console line as a change detector, not a wire contract. Tests at merge: `./gradlew build` green, 341 tests;
docs site 65 tests + tsc + build green; `BeltScenariosTest` 23/23, none `@Disabled`. The slice also widened the web PR lane
(`.github/workflows/web-ci.yml`) to `docs/**`, `.claude/tasks/**`, `README.md`, `CLAUDE.md` so the dead-link check runs on docs-only
PRs (noted on E2 T3; the `web` lane is still not a required check). **PR #20** (https://github.com/amfshr/tradebench/pull/20), **merged
by Claude at Alex's word ("okay good go ahead") 2026-10-10 into `main`, merge commit `c0c0407`** (GitHub stamps the merge
2026-10-10T19:46:54Z); CI green on the merged head (`build` 52s, `web` 30s, GitGuardian pass); the branch deleted. Commits as merged:
`6ec21f8` (1/3 loudness — #9, #26) · `cc3a95e` (2/3 reuse and seams — #10, #31, #28, #22) · `8953ec1` (3/3 docs and conventions —
#25, #32) · `eca724d` (the self-check — the snapshot inside the publish's guard) · `92e1204` (the review fixes). The board's Done
history carries the entry (tagged E1-T10). **Two observations from the review, outside the slice:** (1) nothing pins
`Jdbc.lookupId`'s refusal of an unknown user/source ("refusing to invent one" — a fail-closed path, playbook §2.3;
`market-data-service/src/main/java/dev/amfshr/tradebench/marketdata/store/Jdbc.java`) — a pre-existing gap → backlog **B10** (small:
one Testcontainers test per store; mutation: return 0 instead of throwing); (2) Alex's real IG account ids (now redacted) sat in
pre-existing test literals (`ig-client` and `market-data-service` tests) and the wire fixture
`ig-client/src/test/resources/wire/login-response.json` on main — G1's text did not then call an account id a credential and slice C
did not add it — surfaced as open for Alex, not a finding; **closed by PR #21 the same day** (merge `5735640`: both ids replaced
with the synthetic pair `AB12CD`/`AB12CE`; the rule — everything in `.env` is sensitive — now in G1, `27e30d0`; the check's two further hits, the IG login identifier and the instance name, redacted by PR #22, merge `200ca52`). **T10 closes with this merge: every finding
of the PR #14 review is resolved** (A1 #1, #2 · A2 twelve · B nine — #15 and #16 counted apart, fixed together · T11 two, #23 and #27 · C eight — 33 findings,
four slices plus the harness). **Next:** E1-T12 → the belt's single cloud re-review, now gated only on T12 (the close-out ruling below) → E1-T6.

**The A2/B backlog on the harness (E1-T11 — merged 2026-10-08, PR #16; written 2026-10-08, the tenth, #4's window-lapse arm, added at T11's
doctrine review; each verified red for its finding's reason):** the ten `@Disabled` scenarios in `BeltScenariosTest` —
A2: #3 `aWeekendZombieBehindAClosedFlagIsFoundByTheTwelveHourTeachingResubscribe`,
#4 `aRejectionHeldForAnUnconfirmedWitnessIsJudgedWhenTheWitnessConfirms`
+ `aVerdictHeldForAWitnessThatNeverConfirmsIsSessionShapedWhenTheWindowLapses` (no witness when the 30s window lapses →
a session-shaped rebuild at 30), #5 `tryingRecoveryIsGivenItsThreeHundredSecondsBeforeAnyRebuild`
+ `aDeadSocketIsTheEscalatorsToRebuildAtOneHundredAndTwentySeconds`, #8 `aMarketWhoseBothLegsAreRejectedGetsItsTwoSurgicalRetries`
(on a standing `serverRejects(epic, code)` rule — the server refuses the epic, the belt decides each attempt's timing),
#33 `theScarsTwoDeadMarketsEarnOneSessionVerdictAtNinetySeconds` (ruled 2026-10-08: the one-sweep hold; its
session-rebuild-at-451 assertion already holds; the "no market surgery" assertion is the exposing one); B: #6
`aDatabaseThatRefusesWritesIsOneEpisodeClimbingTheLadder`,
#20 `aStopWithABacklogDrainsAllOfItIntoAHealthySink` + `aStopDuringAHoldAfterPostgresReturnedRecoversOnceAndLandsEverything`.
Each fix enables its scenario as the exposing test; the remaining A2/B findings get their exposing tests as each slice
lands (per the DoD). **A2 also carries two items that are not `@Disabled` scenarios (ruled 2026-10-08, Alex):** a new
scenario — the host-suspend-then-stuck variant of #5's dead-socket scenario (T5 residual 3's rebuild half: a 16-minute host
suspend in front of the hang as ruled — built the hang then the suspend at A2, a recorded deviation with its reason, T5 § —
the rebuild 120s after the wake in awake time) — and the server-first rename of
`aLidCloseIsAnnotatedNotTreatedAsAnOutage` (green today) to a host-suspend name, with the lid/laptop framing purged from
the test and code comments (the A2 bullet above). **Noted for the slices (T11's doctrine review, 2026-10-08):** the fake store's `recover()`
deliberately models the unfixed #6 — slice B must give it a round trip with the production fix; the #3 scenario's window
assertions tighten to exact offsets when A2 enables it; a `Supervisor.sweep()` exception is modelled as a harness error
(a test error) until #19 lands. **Update 2026-10-09 (A2 merged — PR #17, `2a2f289`):** all seven A2 scenarios are switched on — #3, #5 ×2 and #33 at
increment 1 (the #3 window assertions tightened to exact offsets as noted), #4 ×2 and #8 at increment 2; the two ruled
additions are built (`aHostThatWakesWithTheClientStuckIsRebuiltInAwakeTime`, reordered at A2's doctrine review so the hang
precedes the suspend; `aLidCloseIsAnnotatedNotTreatedAsAnOutage` → `aHostSuspendIsAnnotatedNotTreatedAsAnOutage`); a
throwing `Supervisor.sweep()` now dies loud (#19 landed). Only slice B's three remain `@Disabled` — #6
`aDatabaseThatRefusesWritesIsOneEpisodeClimbingTheLadder`, #20 `aStopWithABacklogDrainsAllOfItIntoAHealthySink` +
`aStopDuringAHoldAfterPostgresReturnedRecoversOnceAndLandsEverything` (the suite at 212, 3 skipped, 0 failures). **Update 2026-10-10 (B merged — PR #19, `8324c74`):** the last three switched on at B's
increment 2 (`cb7ec46`) — #6 and both #20 arms; none remain `@Disabled` (the suite at 230, 0 skipped, 0 failures).

**Tests to doctrine (G5):** each fix carries the exposing test the review names, mutation-verified (apply the
exact break → red); A2 and B land as scenarios on the E1-T11 harness with chapter 10's numbers as the expected
offsets; the pure-core unit tests stay.

**Not in scope:** new policy beyond D29; the belt's detectors' numbers (chapter 10's table stays as ruled).

**Workflow:** the standard one — design nod → increments → mutation evidence → doctrine review → PR; Alex
merges. Alex may have the cloud review session re-verify afterwards. Chapter 10 carried a "Known deviations"
note (added 2026-10-05) naming what the findings made untrue; it came out as the deviations went — removed at B (increment 4,
`58fff49`; merged PR #19, 2026-10-10).

**DoD (met 2026-10-10 at slice C's merge, PR #20 — every one of the 33 findings resolved across A1/A2/B/C and T11; the A2/B scenarios
green on the harness; chapter 10's known-deviations note removed at B; a doctrine review before each slice's PR; the cloud re-verification
is Alex's option — the single re-review, after T12):** every finding fixed or recorded why not, each fix carrying the exposing test the review names
(mutation-verified per G5); the A2/B findings' scenarios green on the E1-T11 harness; chapter 10's "known
deviations" note removed when the deviations are gone; doctrine review before the PR; Alex may have the cloud
review session re-verify.

**Close-out ruling (Alex, 2026-10-08):** once every finding is resolved **and E1-T12's replay acceptance is
green** (sequenced 2026-10-08: T12 lands before the re-review, so the re-review judges a belt proven against real
captured outages), a review-only branch (base `ee35f9b`, head `main`, as PR #14 was) carries all the belt work —
T10, T11 and T12 — for the cloud review session's re-review; a pass gates E1-T6. **Clarified (Alex, 2026-10-09):** the
re-review runs **once, after all of T10 is complete** — slices B and C merged — in his words, "i only want to re do the
review once all of t10 is complete then i will do the full review once with cloud claude"; no interim pass after A2. His
clarification named only T10, so T12 before the re-review confirmed by Alex 2026-10-10 ("part C, then T12, then review"). A
review-only PR opened 2026-10-09 after A2 on a misreading of that intent (**PR #18**, base pinned at `ee35f9b`, head =
main) was closed unmerged the same day and its two branches deleted; nothing of it stands. **T10 complete (2026-10-10, PR #20):**
every finding is resolved, so the re-review is now gated only on E1-T12 — T12 lands first, per this ruling and Alex's 2026-10-10
confirmation; a pass gates E1-T6.

## T11 — The belt's scenario harness ✅ (PR #16, 2026-10-08 — complete; ticketed 2026-10-05, started 2026-10-08, design nod ruled 2026-10-08)

**Type** build · **Branch** `e1-t11-belt-scenario-harness` · **Started** 2026-10-08 · **Blocked by** —

**Goal:** chapter 10 as executable expectations — the composed loop (`Supervisor` ↔ `IgStreamControl` ↔ `Pump`
↔ the stores) driven through scripted outages with no real waiting, so the belt's fixes can land together
safely and policy drift fails a test.

**Plan — the review's side-task proposal, adopted as written**
(`.claude/reviews/2026-10-05-pr14-testing-framework.md`; summarised here, the file rules):
- **Synchronous driving.** A scenario runner calls `Supervisor.sweep()` and `Pump.cycle()` on a deterministic
  schedule: advance the injected `Clock` by the sweep interval, deliver the fixture events due at that time on
  the runner thread (modelling enqueue-on-the-LS-thread / apply-on-the-next-sweep), one sweep, one pump cycle,
  repeat. The `Sleeper` is a clock-advancer with a runaway guard (as `PumpTest` already does). No real threads
  — until a dedicated concurrency scenario needs them, and then only with a latch-driven fake.
- **Where it lives.** `market-data-service/src/test/.../scenario` (it needs `Supervisor`, `Pump`, `Buffers`,
  `HealthProbe` and the stores). `FakeStreamTransport` (ig-client `testFixtures`) gains scripted
  per-subscription outcomes (confirm after N ms / reject with code / stay silent / confirm then die), delivery
  of ticks and sealed bars to a connection's listeners by item name, and per-handle state so unsubscribing an
  inactive handle throws as the SDK does. One consolidated `FakeClock` and one `RecordingEventLog` first (the
  four and three hand-rolled copies — T10 #27; whichever ticket lands first does it).
- **Fixtures.** A timeline per scenario, one row per event — `{at, kind ∈ {status, tick, bar, confirm, reject,
  silence, db-down, db-up, host-sleep, stop}, payload}`, `at` an offset from scenario start — with the expected
  outcomes beside it: the ordered remedies (resubscribe / rebuild / quarantine, with offsets), the ordered
  events (type, reason, epic), what the sink received and in what order, the heartbeat row at chosen offsets,
  how the run ended. A captured real outage becomes a fixture from its `service_events` rows + the
  Lightstreamer status lines in the log (why T10 #9's status log line matters beyond operability).
- **Assertions — five observables, nothing else:** remedies issued · events recorded · sink writes landed ·
  heartbeat state · exit path. Never the cores' internal fields. Chapter 10's numbers table supplies the
  expected offsets, so policy drift is a failing test.
- **Postgres.** Real (Testcontainers) only for the store/hold scenarios that already exist, plus one
  container-pause scenario for the socket timeout (T10 #7); everywhere else a scripted `CaptureStore` /
  `EventLog` that can be told "fail retryably from T1 to T2", so a db-down can coincide with stream events by
  the clock.
- **The nine scenarios, in the proposal's order:** ① both-legs rejection with a healthy witness (findings 1, 8)
  → ② bare `DISCONNECTED` after `onServerError` with CLOSED flags (2) → ③ `TRYING-RECOVERY` for 200s with ticks
  silent — no rebuild before 300s (5) → ④ `Wait`, then the witness confirms at 31s (4) → ⑤ the weekend zombie:
  CLOSED flag, a dead leg, the Monday open (3) → ⑥ dead socket: `WILL-RETRY` to the 120s rebuild with the
  watchdog standing down → ⑦ host suspend → wake → `WILL-RETRY` → rebuild (T5 residual 3) → ⑧ Postgres restart
  mid-capture: the held batch lands, the ladder climbs when it should (6) → ⑨ shutdown mid-hold: what is lost
  and what is logged (20).
- **What stays, what goes.** Every pure-core unit test stays (the fast, exact, mutation-verified layer). The
  `SupervisorTest` scenarios that exercise one detector against the fake retire as their harness scenario
  lands. `IgStreamControlTest` stays, run against the extended fake.

**Estimate:** ≈ two days — the fixture extensions, a ~200-line runner, the first five scenarios (estimated
2026-10-05, before the nod confirmed the loader, the exporter and the replay in scope; not re-estimated).

**Design nod — ruled (Alex, 2026-10-08).** Four rulings; the first settles the fixture format, open since ticketing:
1. **Fixture format: a typed Java DSL** for the nine policy scenarios (refactor-safe). **Captured replays are
   JSONL** — one event per line `{at, kind, payload}`, read with the Jackson already in the build — not YAML;
   produced by an exporter.
2. **Scenarios for findings not yet fixed are written now**, with chapter 10's expectations, and marked
   `@Disabled("E1-T10 <slice> — finding #n")`; each fix enables its scenario as the exposing test.
3. **`Main`'s wiring is extracted into a `CaptureAssembly`** shared by `Main` and the harness rig, so the harness
   exercises the real composition and shutdown order — closes the reviews' "Main is untested".
4. **No real-thread scenario in T11** — the runner is synchronous. A latch-driven concurrency reproduction is its
   own ticket only if a real race ever surfaces in the drills/soaks.

**Scope confirmed at the nod:** the nine policy scenarios **plus** the JSONL loader, the exporter (two schemas:
the prototype's `igtrader` tables and Tradebench's own `ticks`/`bars_1m`/`service_events` v2), and **one replay
as scenario ten** — the 2026-08-04 silent-while-connected scar (a window of minutes around it, a few hundred KB).
The full captured-outage library and the acceptance-mode rig are **E1-T12**, not this ticket.

**Increments:**
1. The consolidated `FakeClock`/`FakeSleeper`/`RecordingEventLog` (T10 #27) + the `FakeStreamTransport`
   extensions — scripted per-subscription outcomes; update delivery by item name; per-handle state throwing on
   an inactive unsubscribe — each tested. *(Landed `ac79cdd`, 2026-10-08.)*
2. The DSL, the runner, the rig over `CaptureAssembly`, and the six scenarios planned: a quiet healthy stretch;
   quarantine beside a healthy witness (the quarantine half of ①); the bare `DISCONNECTED` overnight (②); a host
   suspend annotated `hostSlept` with no rebuild — the client reconnects itself (the rebaseline half of ⑦, not its
   rebuild arm); the Postgres blip (the hold-and-recover core of ⑧); a stop mid-hold (⑨) — plus three added during
   the build: a stream blink inside a hold, the server's refusal, a schema error. *(Landed `2f16773`, 2026-10-08 —
   nine scenarios green at that point. Not ⑥ dead socket — born `@Disabled` in increment 3 for #5 — and not a
   "suspend → wake → `WILL-RETRY` → rebuild" path; the first wording here claimed both and was corrected at the
   doctrine review, finding 6.)*
3. The `@Disabled` scenarios for #3/#4/#5/#6/#8/#20; retiring the `SupervisorTest` one-detector tests the harness
   subsumes; the loader + the exporter + the scar replay; chapter 08/10 touches. *(Landed `a95022a`, 2026-10-08.)*
4. The doctrine review's fixes (below). *(Landed `59238f0`, 2026-10-08.)*

**Built — what landed (merged 2026-10-08 in PR #16, merge `65b4912`; the commits as merged — the branch was rebased onto main before the merge — `ac79cdd` / `2f16773` / `a95022a`, the doctrine review's fixes `59238f0`):**
- **The harness** — `market-data-service/src/test/java/dev/amfshr/tradebench/marketdata/scenario/`: `Event` (the
  sealed vocabulary: `Status`, `ServerError`, `Confirm`/`Reject` per leg, `Tick`, `Bar` with bid + ask quotes,
  `DbDown`/`DbUp`/`DbBroken`/`DbWritesRefused`, `HostSleep`, `IgDown`/`IgUp`, `Stop`), `Events`, `Scenario` (the DSL
  builder: `markets`, `at`, `every`, `until` — the run ends there: nothing scheduled at or past it is delivered, the
  sweeps and heartbeats due exactly at it still run — `serverAnswers`, `serverRejects`, `origin`), `Observed` (the five observables only —
  remedies connect/disconnect/subscribe/unsubscribe with offsets, events, sink writes landed, heartbeat rows, exit
  path), `ScenarioRunner` (synchronous; time passes only when the pump sleeps — the sleeper hook delivers due events,
  then due sweeps, then due heartbeats; a callback's stamp precedes the sweep that reads it by 1ns; the fake sleeper's
  runaway guard is sized from the scenario's `until`; an exit runs the shutdown order as the JVM hook would;
  `HarnessError` ends the run as a test error when a fixture cannot be applied or `Supervisor.sweep()` throws — never
  a pump death) + `ScenarioRunnerTest` (3 tests), `Replays` (the JSONL loader, fails loud — numbers required for
  `at`/`untilMs`/`code`/`ms`; a replay's clock starts at the header's `origin`, so its ticks keep their fixture
  instants; candles carry `ltv`) + `ReplaysTest` (8 tests). Fakes in `testutil/`: `FakeClock`, `FakeSleeper`, `RecordingEventLog`
  (T10 #27, done here), `FakeSessions`, `ScriptedCaptureStore` (held ticks; a full 500-tick batch flushed as
  `PostgresStore` does; broken-until-recover; the retryable blip `08006`, the write refusal `53100`, the terminal
  `42P01`; its `recover()` deliberately models the unfixed #6), `RecordingStatusStore`, `RecordingGapStore`. Production
  seams: `CaptureAssembly` (the composition root shared by `Main` and the rig), `Supervisor.sweep()` public,
  `Pump.cycle()` / `drainTail()` / `die()` public.
- **`BeltScenariosTest` — 21 scenarios, 11 green:** a quiet healthy stretch (no remedy, every write landed) · a bad
  market quarantined beside a healthy witness · a bare `DISCONNECTED` overnight with CLOSED flags (rebuild at 30; the
  refused rebuild paced to exactly the 5s floor at 35) · the server's refusal in the SDK's order (`DISCONNECTED` then
  `onServerError`: one death, the first signal wins) · a host suspend annotated `hostSlept`, no rebuild (T5 residual 3's rebaseline half) ·
  a Postgres blip held with zero loss (episode dated 100; outage 131s; 7 attempts; 2 heartbeat write failures;
  backlog 82 at the 180s heartbeat) · a stream blink inside a database hold (the supervisor keeps sweeping; the
  `RECONNECT` breadcrumb refused and counted — flips when D29 (3)'s Tier-2 queue lands) · a schema error is terminal
  (the pump dies, `DB_ERROR`, the exit hook runs the shutdown order) · a stop mid-hold drains the tail and logs the
  loss · one market silent beside a streaming neighbour climbs its own ladder (resubscribe 150, 270; rebuild 510) ·
  the 2026-08-04 scar replayed from JSONL (3,177 ticks and 10 candles landed exactly once; no remedy before the
  watchdog's 90s; the heartbeat says `CONNECTED_STREAMING` through the first minute of the silence). **10 `@Disabled`**,
  each written to chapter 10's expectations and verified red today for its finding's reason — the A2/B backlog listed
  by test name in T10 § (#3 · #4 ×2 · #5 ×2 · #6 · #8 · #20 ×2 · **#33**, the new finding below). **Mutation evidence
  (2026-10-08; the per-mutation table goes in the PR body):** 23 production/harness mutations in all — 20 in the build
  session (among them the fake store's hold-before-fail fidelity, the runner's event/sweep/heartbeat ordering, and a
  wrong expected offset — the DoD's own check) and 3 at the doctrine review (harness-error laundering, the guard left
  at default, the generation gate opened) — each red, each restored byte-identical.
- **`SupervisorTest`:** 10 one-detector scenarios retired as subsumed by green harness scenarios
  (`rebuildsHoldOffWithinTheBackoffWindow`, `hostSleepIsAnnotatedNotCountedAsAWildOutage`,
  `hostSleepRebaselinesRatherThanAlarming`, `openMarketGoneTickSilentIsResubscribed`, `closedMarketSilenceStandsDown`,
  `persistentSilenceEscalatesResubscribeThenRebuild`, `subscriptionFailingThriceWithAHealthyWitnessIsQuarantinedNotRebuilt`,
  `aSubCeilingRejectionIsRetriedInPlaceNotLeftToTheWatchdog`, `aBareDisconnectRebuildsEvenWhenEveryMarketReadsClosed`,
  `aServerErrorIsRecordedAndTheDisconnectThatPrecedesItRebuildsOnce`); 29 remain, including
  `aServerErrorAloneIsADeathToo`, which still catches the "`onServerError` is not a death" mutation. **Tests at
  increment 3:** ig-client 99 · market-data-service 185 (9 skipped — the disabled scenarios) · docs site 63 · 0 failures;
  **after the review fixes (the tests at merge):** ig-client 99 · market-data-service 193 (10 skipped) · docs site 63 · 0 failures;
  `./gradlew build` and the docs build green as CI runs them.
- **Replays** — `market-data-service/src/test/resources/replays/`: `README.md` (the format, the library table);
  `export-prototype.sql` (the prototype's `igtrader_demo`: `igtrader.dax_ticks` / `nasdaq_ticks`, bars synthesised per
  minute; **G1** market data only); `export-tradebench.sql` (our `ticks` / `bars_1m` by user and source; validated
  against the local `market_data` database); the fixture `2026-08-04-silent-while-connected.jsonl` (3,188 lines,
  ~378 KB; origin 12:32:08.916Z, anchor = the last DAX tick at offset 360s; both markets' feeds died within 584ms).
  Replays set `serverAnswers` — the harness answers as a healthy server — because the prototype stored no
  Lightstreamer statuses. Replay ticks keep their fixture instants (the clock starts at the header's `origin`) and
  candles carry `ltv` (the review's fidelity finding); the prototype exporter's username is a placeholder.
- **Docs touched:** chapter 10 gained "Where this chapter is tested", and its known-deviations note now lists #33 and
  names the disabled scenarios; chapter 08's scar callout gained a "Replayed" paragraph. Both corrected at the doctrine
  review to #33's observed behaviour (the stray resubscribe; low), as was the review addendum (§33, with a same-day
  correction note per G8).
- **Found by the scar replay — finding #33** (recorded 2026-10-08 as an addendum in
  `.claude/reviews/2026-10-05-pr14-resilience-belt.md`, §33): one stray per-market resubscribe the sweep before the
  session verdict. The session verdict ("≥2 markets stale together → rebuild at once") does fire at the second market's
  90s mark — the scenario's connect assertion `[0, 451]` passes today — but the scar's two feeds died 584ms apart, so
  at the sweep 90s after DAX's last tick only DAX is stale and gets market surgery (the pair dropped and re-asked, a
  misleading `WATCHDOG_STALE{resubscribe}` row); one sweep later both are stale and the session is rebuilt as promised.
  **Low**; triaged to **T10 slice A2**; ruled 2026-10-08 (Alex): fix in A2, the one-sweep hold — no separate decision
  entry (T10 §). *The first
  account — "two per-market ladders instead of the session verdict, rebuild at T+450 instead of T+90", medium — was
  inferred, not observed; the doctrine review proved it wrong and the addendum, chapters 08/10 and this plan were
  corrected the same day (G8).*
- **T5 residual 2** (the composed-loop test) is closed by this work; **residual 3** in its rebaseline half — a
  16-minute wall jump raises no `WATCHDOG_STALE` and is annotated `hostSlept` (`aLidCloseIsAnnotatedNotTreatedAsAnOutage`,
  renamed `aHostSuspendIsAnnotatedNotTreatedAsAnOutage` at A2 — ruling 3); the rebuild half was pinned here only by the
  `@Disabled` dead-socket scenario, without the suspend (doctrine review, finding 6), and closed with T10 A2 as the
  host-suspend-then-stuck variant of that scenario — `aHostThatWakesWithTheClientStuckIsRebuiltInAwakeTime`, PR #17, merged
  2026-10-09 (ruled 2026-10-08, Alex; built the hang then the suspend, a recorded deviation from the ruling's "suspend in
  front of the hang" so the scenario discriminates awake from wall time — T5 §; see "Closed", below). Residual 2 final at
  this merge (PR #16, 2026-10-08); residual 3 final at A2's (PR #17, 2026-10-09).

**Doctrine review (2026-10-08) — pass-with-findings.** Verdict: the harness sound, the production extraction
(`CaptureAssembly`, the public `sweep()`/`cycle()` seams) faithful, every chapter-10 derivation re-derived and right,
G1 clean, all ten `SupervisorTest` retirements confirmed subsumed with no coverage lost, the 20 mutations traced to
their assertions. Findings fixed on the branch before the PR (commit `59238f0`): (1) #33's account
corrected as above; (2) the runner no longer launders a failure of the harness or fixture into a pump death —
`ScenarioRunner.HarnessError` wraps fixture errors and a throwing `Supervisor.sweep()` (review #19) and ends the run
as a test error; (3) the fake sleeper's runaway guard is sized by the runner from the scenario's `until`, so the
12-hour #3 scenario now reaches its window and is red for its genuine reason (the missing teaching resubscribe); (4)
replay fidelity — replay ticks keep their fixture instants (the clock starts at the header's `origin`), candles carry
`ltv`; (5) a callback's stamp strictly precedes the sweep that reads it (1ns); (6) increment 2's wording corrected
(above); (7) the loader requires numbers for `at`/`untilMs`/`code`/`ms` — a missing offset can never mean zero — with
failure-mode tests; (8) the #4 window-lapse arm written as a second `@Disabled` scenario (no witness when the 30s
window lapses → a session-shaped rebuild at 30); (9) the fake connection's `close()` fires the SDK's farewell
`DISCONNECTED` so the generation gate is exercised on every rebuild (a broken gate now shows as a rebuild storm in
three scenarios), and the fake store flushes a full 500-tick batch as `PostgresStore` does; (10) `until`'s contract
stated exactly; the prototype exporter's username is a placeholder. Also: the #8 scenario now uses a standing
`serverRejects(epic, code)` rule (the server refuses the epic; the belt decides each attempt's timing), so it unfolds
under today's behaviour and is red for its reason. Three mutations at the review (harness-error laundering, the guard
left at default, the generation gate opened) → 23 killed in all. **Deferred, noted for the slices:** the fake store's
`recover()` deliberately models the unfixed #6 (slice B gives it a round trip with the production fix); the #3
scenario's window assertions tighten to exact offsets when A2 enables it; a sweep exception is modelled as a test
error until #19 lands. Counts after the fixes: `BeltScenariosTest` 21 (11 green, 10 `@Disabled` — #3, #4 ×2, #5 ×2,
#6, #8, #20 ×2, #33, each run once enabled and red for its finding's reason) · `ScenarioRunnerTest` 3 · `ReplaysTest`
8; tests ig-client 99 · market-data-service 193 (10 skipped) · docs site 63 · 0 failures; `./gradlew build` and the
docs build green as CI runs them.

**Closed 2026-10-08:** PR #16 (`https://github.com/amfshr/tradebench/pull/16`) merged by Alex into `main`, merge commit
`65b4912`; CI green on the merged head (`build` 51s, GitGuardian pass). The board's Done history carries the entry (tagged
E1-T11); T12 is unblocked; T5 residual 2 and residual 3's rebaseline half are final. **Ruled 2026-10-08 (Alex, after the
close-out):** (1) the DoD's "residuals 2 and 3 closed" is met in full for 2 and in its rebaseline half for 3; the rebuild
half is ticketed into T10 A2 as the host-suspend-then-stuck variant of the dead-socket scenario — the DoD wording below
stays as written (**closed 2026-10-09 at A2's merge, PR #17** — built the hang then the suspend, a recorded deviation from
this ruling's ordering, with the reason; residual 3 final); (2) #33 is fixed in A2, mechanically — the one-sweep hold; no separate decision entry; (3) server-first
language — the belt's scenarios, test names, comments and docs frame a **suspended host**, never a laptop lid: the
`aLidCloseIsAnnotatedNotTreatedAsAnOutage` rename and the comment purge ride A2's PR, Field Manual ch. 07/08/10 reworded
2026-10-08 direct to main (docs-only). All three enacted at A2 (PR #17, merged 2026-10-09). Detail: T10 § A2.

**Sequencing (Alex, 2026-10-08, standing):** E1-T10 slice A1 is on main (PR #15, merged 2026-10-08); this harness
is on main (PR #16, merged 2026-10-08); **T10 A2 is on main (PR #17, merged 2026-10-09)** — its seven scenarios switched on
(#3 · #4 ×2 · #5 ×2 · #8 · #33), the host-suspend-then-stuck variant and the ruling-3 rename built; **T10 B is on main (PR #19, merged 2026-10-10)** — its three
scenarios switched on here (#6 · #20 ×2), none `@Disabled` remain; **T10 C is on main (PR #20, merged 2026-10-10) — T10 ✅ complete, every finding resolved**. Next: **E1-T12** replay acceptance (unblocked by this merge) → the belt's single cloud re-review, once every
T10 finding is resolved (T10 § close-out ruling; Alex's 2026-10-09 clarification named only T10; T12 before the re-review
confirmed by Alex 2026-10-10 — "part C, then T12, then review") → E1-T6 (a re-review pass gates it).

**DoD (met at the merge, 2026-10-08; residual 3 closed in its rebaseline half here and in its rebuild half at T10 A2, PR #17,
2026-10-09 — ruled 2026-10-08, see "Closed" above; residual 3 final; wording kept as written):** the nine policy scenarios run deterministically with no real waiting, the scar replay as scenario ten
through the JSONL loader; each scenario's expectations are chapter 10's numbers; the `@Disabled` scenarios for
#3/#4/#5/#6/#8/#20 written to chapter 10's expectations, each awaiting its fix; `Main` and the rig share
`CaptureAssembly`; the harness's own behaviour mutation-verified (a wrong expected offset fails); T5's
residuals 2 and 3 (the composed-loop test; the suspend → wake → `WILL-RETRY` → rebuild shell path) are closed
by it; the subsumed `SupervisorTest` scenarios removed, the pure-core unit tests kept, `IgStreamControlTest` on
the extended fake.

## T12 — Replay acceptance: the captured-outage library and the rig in acceptance mode 🔶 (ticketed 2026-10-08; unblocked 2026-10-08 — E1-T11 merged, PR #16; design nod ruled and started 2026-10-10 — increment 1 in progress)

**Type** build · **Branch** `e1-t12-replay-acceptance` (cut from main `1d09cc4`) · **Started** 2026-10-10 · **Blocked by** —

**Goal:** the belt proven against real captured outages — the gold standard short of the real IG socket: real
parsers, buffers, pump, store and database under a replayed stream. The re-review that closes T10 should judge a
belt proven this way, and T6's heal consumes exactly these bar-gap windows.

**Hard dependency — met:** E1-T11 — the harness, its JSONL loader and its exporter are what this ticket extends; all
three are on main since T11's merge (PR #16, 2026-10-08), so this ticket is unblocked.

**The source — the prototype's capture** (facts read from the prototype database `igtrader_demo`, schema
`igtrader`, 2026-10-08):
- `dax_ticks`: 3.5M rows, 2026-07-29 → 2026-10-08 and still capturing, with a `dealflag` column — so real
  `DLG_FLAG` transitions.
- `service_events`: `offline_seconds` and `detail {replayed, host_slept}` — 728 reconnects, 103 session
  rebuilds, 58 bar gaps, 3 watchdog resubscribes, 1 forced rebuild. The 2h56m silent-while-connected scar is a
  10,569-second reconnect on 2026-08-04; a dozen host-suspend outages of 18–32 minutes carry `host_slept: true` (the
  prototype ran on a laptop).
- `dax_bars_1m` (38k rows): mid-price OHLC with derived indicators — so replay bars are timing-only /
  synthesised from ticks (ours are bid/offer OHLC from the CHART feed).
- The prototype did not store the Lightstreamer status sequence: a replay reconstructs status transitions from
  each reconnect's `offline_seconds` and says so. *(T11's loader: a replay header's `serverAnswers` flag makes the
  harness answer as a healthy server — `CONNECTED:WS-STREAMING`, every subscription confirmed — when the source stored
  no statuses; that is the scar fixture's case. Reconstructing a reconnect's status transitions from `offline_seconds`
  was this ticket's (b) at ticketing — **superseded by the design nod's ruling 1 (Alex, 2026-10-10, below): a connection
  answer the capture did not record is modelled and labelled as our rig speaking, never reconstructed and passed off as
  IG's.**)*
- Tick rate ≈ 10–15k/hour on a busy day — a day is ~140k ticks (~12 MB) — so fixtures are windows of minutes
  around an event.

**Plan:**
- **(a) The exporter** — T11 delivered both schemas (2026-10-08: `export-prototype.sql` for the prototype's
  `igtrader` tables, `export-tradebench.sql` for our `ticks`/`bars_1m`; SQL → JSONL, parameters and commands
  documented in `market-data-service/src/test/resources/replays/README.md`), so (a) reduces to the wrapper script (one
  command: database → fixture) and the curated library. **G1: market data only** — never `strategy_events` or the OMS
  tables.
- **(b) A curated fixture set**, each with expectations written from `service_events`/`bar_gaps` independently
  of the code: the 2026-08-04 scar; three or four `host_slept` host-suspend outages (the prototype ran on a laptop);
  two days with bar gaps; a weekend CLOSED → open transition; one busy hour with no events, expecting "no remedy, no
  event, every tick and bar landed exactly once". **Shaped by the design nod (Alex, 2026-10-10, below), rulings 1 and 2:**
  each fixture's header carries a *recorded* and a *modelled* section; expectations are anchored on recorded facts only;
  where a connection answer is needed the harness's `serverAnswers` healthy-server mode speaks, named as our rig — the
  status reconstruction from `offline_seconds` noted above is superseded, never done; a fixture whose value depends mainly
  on the status sequence is dropped, not faked.
- **(c) The rig in acceptance mode** — the same replay through the real `Pump` and `PostgresStore` into a
  Testcontainers Postgres instead of the scripted stores, asserting the data invariants as rows: every replayed
  tick/bar landed exactly once; `bar_gaps` matches the known gaps; no spurious remedies in healthy windows.
- **(d) Packaging** as a tagged suite excluded from the default unit run, with its own Gradle task and a CI lane
  (nightly or on demand) — the way ig-client's `ig-demo` smoke is gated (`@Tag("ig-demo")`, `excludeTags` on
  `test`, `includeTags` on the registered `demoSmoke` task — `ig-client/build.gradle.kts`).
- **(e) The raw-status event** (added at the nod, ruling 3): every raw Lightstreamer status transition recorded as a
  low-severity event — today the belt records only the classified outcome of a status change (RECONNECT,
  TRANSPORT_DOWNGRADED, CONNECTION_DEAD), not the raw string — so each outage Tradebench itself lives through becomes a
  complete fixture through the existing `export-tradebench.sql`. T12's one change to belt code; the cloud re-review after
  T12 will see it.

**Not in scope:** real threads (ruled at T11's nod); the live tier — the live outage drill (T9 §), T8's
shadow-diff week, T7's staging soak.

**DoD:** the curated set green in both modes (scripted stores and acceptance); expectations anchored on the
prototype's recorded events; the exporter documented; the suite runnable on demand and in its CI lane; a
field-manual note on the replay library (where it lives, how a new outage becomes a fixture); raw status transitions
recorded as events from this ticket on (added at the 2026-10-10 nod, ruling 3).

**Estimate:** ≈ two sessions (the ticket's own; reaffirmed at the nod 2026-10-10 — possibly three with the export session;
roughly 1,000 lines of code plus 2–3 MB of fixture data).

**Design nod — ruled (Alex, 2026-10-10: "okay record that as the t12 nod and start increment 1"); started 2026-10-10** on
`e1-t12-replay-acceptance` (branched from main at `1d09cc4`). Raised by Alex's concern that the prototype never stored the
Lightstreamer status sequence, so a reconstructed sequence "won't be real life". Three rulings:
1. **Replay what was recorded; model what was not, and label it.** A fixture's header carries two sections — *recorded*
   (ticks, bars, `DLG_FLAG` transitions, the prototype's `service_events` and `bar_gaps`, `offline_seconds`) and *modelled*
   (any connection answer). Expectations are anchored on recorded facts only: every replayed tick and bar landed exactly
   once; `bar_gaps` equal the recorded gaps; remedies fire within the recorded outage window. Where a connection answer is
   needed, the harness's existing healthy-server mode (T11's `serverAnswers` flag — our own fake transport answering
   `CONNECTED:WS-STREAMING`, every subscription confirmed) is used and named as our rig speaking — never a reconstructed
   sequence passed off as IG's.
2. **The fixture set leans to what the captures can prove.** The silent-while-connected family (the 2026-08-04 scar —
   status said connected throughout, so its sequence would have proved nothing) is the gold fixture and needs no status
   sequence; gap days, the weekend CLOSED → open transition and the busy healthy hour are pure data invariants;
   host-suspend outages carry real clock divergence and real silence, with only the status at wake modelled and labelled.
   A fixture whose value depends mainly on the status sequence is dropped, not faked — the status-driven detectors
   (`ReconnectClassifier`, `StuckSubstateEscalator`, `CONNECTION_DEAD`) stay proven by the 23-scenario harness, which is
   what it was built for.
3. **Record raw status transitions from now on.** Today the belt records the classified outcome of a status change
   (RECONNECT, TRANSPORT_DOWNGRADED, CONNECTION_DEAD), not the raw string. T12 adds every raw Lightstreamer status
   transition as a low-severity event, so each outage Tradebench itself lives through becomes a complete fixture through
   the existing `export-tradebench.sql` — the first real outage our own service survives is the first fully real one.
   T12's one change to belt code; the cloud re-review after T12 will see it.

**Increments — three, one PR:** **1 the rig in acceptance mode** — the scar replay through the real `Pump`, `PostgresStore`
and `PostgresObservabilityStore` into a Testcontainers Postgres instead of the scripted stores; the data invariants asserted
as rows (exactly once; `bar_gaps` as recorded; no spurious remedy); gated behind a JUnit tag and its own Gradle task, the
`demoSmoke` pattern — built alone, proven on the scar before any export. *(In progress from 2026-10-10.)* · **2 the fixture
library** — the one-command exporter wrapper and six or seven captured windows from the prototype database (a session with
Alex present: read-only selects over market data and the event log, never strategy or OMS tables — G1); each fixture's
header with its recorded/modelled sections. · **3 expectations, the lane, the note, the status event** — per-fixture
expectations from the recorded events; both modes green; a nightly/on-demand CI lane; the field-manual note (where the
library lives, how a new outage becomes a fixture); the raw-status event. Estimate unchanged at ≈ two sessions (the
ticket's own), possibly three with the export session; roughly 1,000 lines of code plus 2–3 MB of fixture data.

**Sequencing (Alex, 2026-10-08, standing):** T11 (✅ PR #16, merged 2026-10-08) → T10 A2 as scenarios (✅ PR #17,
merged 2026-10-09) → T10 B as scenarios (✅ PR #19, merged 2026-10-10) → T10 C (✅ PR #20, merged 2026-10-10 — T10 ✅ complete) → **T12 — in progress (started 2026-10-10)** → the belt's single cloud re-review, now gated only on T12 — every T10 finding is resolved
(T10 § close-out ruling; Alex's 2026-10-09 clarification named only T10 — T12 before the re-review confirmed by Alex 2026-10-10)
→ T6 (a re-review pass gates T6).

**Cross-references:** G1 (market data only — never strategy content) · P8 · E1-T6 (the heal consumes these
bar-gap windows) · E1-T8 (the live-tier acceptance this does not replace) · Field Manual ch. 10 ·
`ig-client/build.gradle.kts` (the `demoSmoke` gating pattern).
