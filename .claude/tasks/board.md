# Tradebench Board

> The single source of truth for work. GitHub is for PRs only — never issues (until outside
> collaborators exist). Ways of working: `.claude/task-workflow.md` · AI operating contract:
> `.claude/ai-framework.md`. Legend: ⬜ queued · 🔶 in progress · ✅ done · 🧊 iced/blocked.
> Tickets carry a DoD; ideation that hasn't earned a ticket lives in `backlog.md` (this dir) —
> the pre-ticket staging area — and enduring design lives in `docs/`, not here.

**Guide (refreshed 2026-10-08):** **E2 complete** (docs site live + licensed, D23). **E1 active** —
**T1–T5, T9 and T11 merged** (live DAX → Postgres; the resilience belt complete — slices A + B **PR #11**, slice C
the Supervisor shell **PR #12**, merged 2026-10-04, its operating contract **D27**; the capture sink's
hold-and-retry **PR #13**, merged 2026-10-05, Tier 1 refined as **D28** and proven in a 152s live outage
drill — zero bars, zero ticks lost). **The whole belt was independently reviewed 2026-10-05** (`/code-review
ultra` over **PR #14**, a review-only PR — base pinned pre-belt, head = main — closed unmerged): 32 verified,
ranked findings → **E1-T10** belt hardening + **E1-T11** the scenario harness; the four policy rulings the
findings needed are **D29**; the records live in `.claude/reviews/`. **T10 slice A1** (the two highs) merged
2026-10-08, **PR #15**. **T11 the scenario harness merged 2026-10-08, PR #16** (merge `65b4912`): the belt as one
system under a synchronous runner — `BeltScenariosTest` 21 scenarios, 11 green, 10 `@Disabled` to their E1-T10
findings, each verified red for its finding's reason; the 2026-08-04 scar replayed from JSONL (both exporters
landed); `CaptureAssembly` shared by `Main` and the rig; the replay surfaced finding **#33** (low — one stray
per-market resubscribe the sweep before the session verdict; → T10 A2, **ruled 2026-10-08 (Alex): fix in A2, the
one-sweep hold** — no separate decision entry). Next in E1 (Alex's sequencing, 2026-10-08): **T10 A2/B/C** as
scenarios on the merged harness, one PR per slice (the ten disabled scenarios are the backlog, plus A2's two ruled
additions — the host-suspend-then-stuck variant of the dead-socket scenario and the server-first rename of the
host-suspend scenario, Alex 2026-10-08) → **T12** replay acceptance (the captured-outage library
from the prototype's `igtrader` database + the rig in acceptance mode; ⬜, **unblocked** by T11's merge — T11
delivered both exporters) → a review-only branch of all the belt work for the cloud session's re-review (a pass
gates T6) → **T6** (EOD heal · archive · digest). T5's residual 2 is closed (final at T11's merge); residual 3 is
closed in its rebaseline half at T11 — a 16-minute wall-clock jump raises no `WATCHDOG_STALE`, the resume is
annotated `hostSlept`, no rebuild — and its rebuild half closes with T10 A2 (**ruled 2026-10-08, Alex**: the host
wakes with the client stuck in `DISCONNECTED:WILL-RETRY` and the escalator rebuilds 120s after the wake, in awake
time — a variant of the dead-socket scenario with a 16-minute host suspend in front of the hang; T11's DoD wording
stays); residual 1 (the gated demo smoke) stays unticketed in the E1 plan (T5 §). **E9
🎛️ Operator console** born from the 2026-09-30 operability design session (D24) — the deployed
collector observed & its data retrieved *without SSH* (read-model SPA + thin service over the
`market_data` data product + R2, edge-gated by Cloudflare Tunnel + Access; hosting = a small
Hetzner VPS + compose, formal host ruling at E1-T7). **E9-T1 (observability & data-model design)
is ✅ ruled** (2026-09-30, R1–R7 → `docs/design/observability-and-data-model.md`, D25): the
`service_events` v2 / `bar_gaps` / `capture_status` / `archives` schema, the metric + Grafana/DIY
split, the per-view SQL, and the streaming-schedule (Q1 resolved: generous window + expected
calendar). Field Manual read: **done.** E0 lacks enforcement (T4). E3 language semantics ruled
(D7–D9), design-gate tickets plannable. E4–E8 name the horizon.

**Next-session menu (refreshed 2026-10-08):** the belt's independent review (**PR #14**,
review-only, closed unmerged) is triaged — 32 findings → **E1-T10** + **E1-T11**, rulings **D29**; Alex's
sequencing (refreshed 2026-10-08): the two highs first (✅ PR #15), then the harness (✅ PR #16), then the rest as scenarios, then T12 replay acceptance, then the belt's re-review, then T6. ① **E1-T10 slice A2 — detectors and verdicts as harness scenarios** (T10 🔶; **E1-T11 the harness is ✅ — PR #16 merged by Alex 2026-10-08, merge `65b4912`**, on main: `BeltScenariosTest` 21 scenarios, 11 green, 10 `@Disabled` to their findings; the 2026-08-04 scar replayed from JSONL; `CaptureAssembly` shared by `Main` and the rig; detail in the E1 plan T11 § and the Done history). Each `@Disabled` scenario is enabled by its fix as the exposing test — **A2** = #3 · #4 ×2 · #5 ×2 (+ the host-suspend-then-stuck variant of the dead-socket scenario — T5 residual 3's rebuild half, ruling 1 below) · #8 · #33 (ruled: the one-sweep hold) + the ruling-3 rename of the host-suspend scenario (by test name in the E1 plan T10 §); A2's other findings (#11, #13, #14, #18, #19, #21, #29) get their exposing tests as the slice lands; one PR per slice, doctrine review before it, Alex merges. **Rulings taken 2026-10-08 (Alex, after T11's close-out):** (1) **T5 residual 3** — closed in its rebaseline half at T11; its rebuild half (the host wakes with the Lightstreamer client stuck in `DISCONNECTED:WILL-RETRY`, the escalator rebuilds 120s after the wake, measured in awake time) is an A2 scenario — the dead-socket scenario (#5) with a 16-minute host suspend in front of the hang; T11's DoD wording stays as written. (2) **#33** — fixed in A2, mechanically, no separate decision entry: the **one-sweep hold** (when a market crosses its tick-silent threshold and another is within one sweep of crossing too, the per-market remedy waits that one sweep so both are judged together and the session verdict is rendered without the stray resubscribe; chapter 10's "≥2 markets stale together" row defines "together" as within one sweep of each other); `theScarsTwoDeadMarketsEarnOneSessionVerdictAtNinetySeconds` is the exposing test. (3) **Server-first language** — the service is designed for and deployed on a server; the host-sleep mechanism stays wherever it adds coverage (the wall/monotonic discriminator, `hostSlept`, `Tuning.hostSleepSkew`, the `HostSleep` fixture event) but every scenario, test name, comment and doc frames it as a **suspended host** (a paused or live-migrated VM, a hibernated instance, a frozen process), never a laptop lid — A2 housekeeping renames `aLidCloseIsAnnotatedNotTreatedAsAnOutage` and purges the lid/laptop framing from the test and code comments (code — rides A2's PR); Field Manual ch. 07, 08 and 10 reworded 2026-10-08 direct to main (docs-only, the build session); detail in the E1 plan T10 § A2. Then **B** (the sink and the database edge — its harness scenarios #6 · #20 ×2) → **C** (loudness, reuse, docs) → **E1-T12 replay acceptance** (⬜, **unblocked** by T11's merge — the captured-outage library from the prototype's `igtrader` database + the rig in acceptance mode through the real `Pump`/`PostgresStore` into Testcontainers; T11 delivered both exporters, documented in `market-data-service/src/test/resources/replays/README.md`, so T12's (a) reduces to the wrapper script + the curated library) → a **review-only branch of all the belt work** for the cloud session's re-review (Alex, 2026-10-08; a pass gates T6) → **E1-T6 daily completeness + archive + digest** (D6) — T5 residual 1 (run the gated demo smoke, re-golden `application-allowance.json`) comes first if T6's heal budget leans on pacer discovery. ② **E9 build tickets** — T2 read-model service, T3 SPA, T4 Cloudflare edge (cut from the E9-T1
spec; E1-T5 is on main, so runnable). Optional: E0-T4 guardrail enforcement; formal E1-T7 host ruling; a
**B7 retrospective** (its stated checkpoint — E1-T5's close — is reached; Alex's call). Backlog: B1
stream-jobs (multi-user) — reviewed 2026-10-03 against the code (`backlog.md`): three gaps (per-service
advisory lock → per-job · hardcoded `default-user` → `TRADEBENCH_USER` + `users` rows · env file + unit per
job → E1-T7), one process per job, trigger = E9 gains save-credentials + save-jobs (Alex); its 2026-10-05
heartbeat fail-fast note is folded into **E1-T10 #22**; B7 AI-framework (legible E0, a "how we build" guide,
a retrospective practice); **B8** tick-queue overflow during a prolonged sink hold — spill-to-disk (jsonl)
vs simply raising `Buffers.DEFAULT_TICK_CAPACITY` (captured 2026-10-05 from Alex's T9-review question,
his own assessment "unlikely"; not scheduled — revisit if a real outage ever approaches the queue bound);
**B9** REST-fetch collection jobs (non-streaming) and allowance visibility — a second job type that pulls
bars by REST on a schedule (e.g. one end-of-day fetch of the day's 1m bars per market) instead of streaming,
with the IG allowances that bound it (the request allowances `GET /operations/application` already parses +
the 10,000 points/week historical budget the T6 heal shares) shown in the console; cross-cutting E9 + the
market-data service, its own `ig-rest-<env>` source (P8) (captured 2026-10-08 from Alex; not scheduled —
trigger: E1 T6/T7 and E9's platform app deployed and off v0, Alex's call when).

---

## E0 🤖 AI operating framework — ACTIVE (born urgent 2026-09-27)

**Since** 2026-09-27 · **Decisions** D10, D13 · **Manual** —

**Mission:** make AI a reliable standing development partner (PRD §9's AI-workable NFR):
explicit roles, hard guardrails, session protocols, and a standardized `.claude/` + `docs/`
structure — so any session, however cold, is safe and productive by default.

**References:** `.claude/ai-framework.md` (the contract) · engineering playbook §2 (the
testing doctrine G5 binds to) · PRD §9.

| # | Ticket | Status |
|---|--------|--------|
| T1 | **The contract.** `.claude/ai-framework.md`: roles (Claude proposes, Alex rules), guardrails G1–G8, session protocols, directory standard; CLAUDE.md + task-workflow.md wired to it; `docs/design/` seat created. **DoD:** a cold session can state the rules it operates under from the read-first chain alone. | ✅ 2026-09-27 |
| T2 | **Project agents v1.** `doctrine-reviewer` (pre-PR diff review against conventions, testing doctrine, guardrails) + `seed-pack-librarian` (prototype knowledge with citations + triage status). **DoD:** both defined and invocable; proven in anger on first real use (E1-era). | ✅ 2026-09-27 (`doctrine-reviewer` proven on PR #1; `db-admin` added 2026-09-28) |
| T3 | **Project skills v1.** `/sanity-lap`, `/design-session`, `/start-ticket` — each protocol codified from a session that actually worked. **DoD:** each skill invocable and produces its artifact. | ✅ 2026-09-27 (all three proven same day; `/start-ticket` drove E1-T1 → PR #1) |
| T4 | **Guardrail enforcement.** Pre-commit + CI scan for credentials and strategy-content patterns (a seeded fake-secret fixture must go red — mutation-verified, per doctrine); `.claude/settings.json` permissions baseline; hooks only if a real need shows. **DoD:** planted violations caught in CI; permissions documented in the framework doc. | ⬜ (pairs with E1-T1's CI) |
| T5 | **`board-steward` agent.** Authors/maintains tickets & epics to the formal template (`docs/reference/board-format.md`, D20 — type · branch · started · blocked-by; epic since/decisions/book) and keeps the board truthful (statuses, dates, done-history vs reality); planning-markdown only, never product code; never fabricates, never self-marks ✅. **DoD:** agent defined + invocable; board-format spec written; proven by authoring/retrofitting real tickets. | ✅ 2026-09-30 (agent + board-format.md spec written, D20; retrofitted E2 tickets; in the ai-framework inventory) |

## E1 📡 Collection service, deployed 24/7 — ACTIVE

**Since** 2026-09-27 · **Decisions** D2, D14, D15, D16, D17, D27, D28, D29 · **Manual** ch. 1–10

**Mission:** Tradebench's first deployed artifact: a standalone Spring Boot service streaming
IG **ticks + 1-minute bars** for DAX into Postgres, resilient and self-reporting, with the
daily **heal → Parquet-archive → digest-email** cycle — running 24/7 in the cloud so that from
its first day no streaming opportunity is missed. Data model carries **user + source dimensions
from day one** (PRD §7). Acceptance for the epic = T8's shadow-diff verdict.

**Ticket plans:** `.claude/tasks/epics/e1-collection-service.md` — per-ticket approach, build
order, test plan, in-ticket decisions. **References:** PRD §7/§11 ·
`docs/inherited/ig-broker-playbook.md` (§1–§4 are the porting spec) ·
`docs/inherited/design/phase1-market-data-build-plan.md` (module map + porting order;
its deploy framing is superseded by S5 staging/prod decisions) ·
`docs/inherited/data-platform-design.md` (capture the finest atom, derive the rest;
CHART:1MINUTE sealed bars only) · engineering playbook §1–§4.

| # | Ticket | Status |
|---|--------|--------|
| T1 | **Foundations.** Gradle multi-module skeleton (`ig-client`, `market-data-service`, shared `core`; `frontend/` seat reserved, empty), CI on PR (build + test), `.editorconfig`, README badge. Enable branch protection on `main` once the CI check exists (required check; admin bypass stays available for docs-only pushes). **DoD:** green CI on a walking-skeleton test in each module; protection active. | ✅ 2026-09-27 (PR #1) |
| T2 | **`ig-client`: session + REST core behind the provider port.** REST login/refresh (demo + live envs), typed config (no code defaults; secrets via env beside config — never committed), request pacing guard (IG session rate limits are real — playbook + engineering playbook §4.6), typed errors. **DoD:** unit suite on fakes; one integration-gated smoke that logs into demo. | ✅ 2026-09-27 (PR #2; smoke run clean vs live demo 2026-09-28 — switch path unexercised, profile prefers the configured account) |
| T3 | **Streaming: ticks + sealed 1m bars.** Lightstreamer Java SDK: PRICE (ticks) + CHART:1MINUTE (accept `CONS_END=1` only) per market; cheap-work-only on callback threads, everything queued to one consumer; per-market subscriptions (identity-vs-addressing per triage D16). **DoD:** a live demo session captures a full DAX day of ticks + 1m bars locally. | ✅ 2026-09-28 (PR #4; Alex's ruling: Sunday's live capture — 500+ ticks, 4 sealed bars, 0 dropped/malformed — suffices; the multi-day local soak moves to T7's pre-cloud runway) |
| T4 | **Persistence.** Flyway from birth: `instruments`, `ticks`, `bars_1m` (user + source columns from day one), `service_events`, `job_runs`; least-privilege roles (triage D22); testcontainers suite; generated schema render + CI drift gate (triage D23). **DoD:** captured data lands via the real write path; drift gate green. | ✅ 2026-09-28 (PR #5; live DAX → Postgres via the real path, all dimension-attributed; drift gate green) |
| T5 | **Resilience belt.** Reconnect stack; staleness watchdog on injectable clocks — should-be-ticking derived from the stream itself, monotonic/awake vs wall time split, escalation ladder (triage D25); gap detection writing gap rows; structured `service_events` + quiet-is-healthy warnings stream. **DoD:** scripted failure scenarios (dead socket, silent-while-connected, host suspend) pass on fakes — each test mutation-verified. | ✅ 2026-10-04 (nod ruled 2026-09-28, the 17:00–17:15 live outage its specimen. **Slices A + B merged PR #11** (2026-10-01) — 7 pure cores (23 mutations) + the observability store (`service_events` v2 / `bar_gaps` / `capture_status`, 8 mutations); **slice C merged PR #12** (2026-10-04, completes T5) — the `Supervisor` shell, `IgStreamControl`, `HealthProbe` → `capture_status`, gaps + market state onto the `EventLog`, post-login pacer discovery; DoD scenarios (dead socket · silent-while-connected · host suspend) pass on fakes, each mutation-verified; 89 mutations killed across the slice-C steps + whole-slice review; doctrine reviews per-step + whole-slice F1–F10 fixed on-branch, F9 a declared residual; rulings → **D27**; 3 residuals recorded, E1 plan T5 §) |
| T6 | **Daily completeness + archive + digest (decisions D6).** `@Scheduled` end-of-day job: 1m-bar REST heal (window-clipped, paced, allowance-aware; outcomes classified healed / tickless-at-source / failed — ticks are stream-only, the job never claims otherwise), day's Parquet export to object storage (pick R2 vs B2 in-ticket), digest email (counts, gaps, heal outcomes, archive path). Audited `job_runs` row every run, clean or not. **DoD:** staging produces archive + digest end-to-end; a suppressed digest is detectable (absence = alarm documented in the runbook). | ⬜ |
| T7 | **Deploy.** Dockerfiles + compose; staging + prod configs with separate DBs (promote by release tag); cloud host selection (revisit the phase-1 doc's Lightsail analysis against the ~£20/mo envelope); secrets injection; deploy runbook. **DoD:** staging runs 24/7 for 3 consecutive days unattended with daily digests arriving. | ⬜ |
| T8 | **Acceptance: shadow-diff week vs the Python appliance.** Both systems capture the same DAX sessions for a week; diff tick coverage, 1m bars, and own-aggregated 10m vs the prototype's; write the report; rule on making Tradebench the primary capture. **DoD:** the diff report with every delta explained + Alex's ruling recorded in `docs/decisions.md`. | ⬜ |
| T9 | **Sink blip resilience — hold-and-retry for the capture sink (Tier-1 product-data writes).** Today a failed tick/bar write (`PersistenceException` from `PostgresStore`) stops the pump and `Main`'s heartbeat exits 1 within ≤60s — fail-closed but crude: a 5s Postgres blip costs ~1–2 min of ticks (never healable) + a bar gap (T6 heals), while bars sat queued (ack-after-apply) and the tick queue held 100 000. Instead: **hold** (bars stay queued; the pending ≤500-tick batch survives the reconnect) → `PostgresStore` **re-acquires** its pooled connection + re-prepares → the pump **retries with backoff**; **loud** (sink-failure counter in the heartbeat line beside `obsFailures`/`eventWriteFailures`; `SINK_FAILURE`/`DB_ERROR` event on recovery — D25 catalogue; the dead-man alarm fires meanwhile, correctly); **bounded by the queues, not a clock** (ruled 2026-10-04, D28: no retry budget — a restart would lose what is held and fix nothing about the database; the moment the tick queue begins shedding is announced); terminal failures → **immediate** exit(1), not heartbeat latency. Refines — never removes — the fail-closed contract (`PumpTest.sinkFailureStopsThePumpKeepsTheBarAndKeepsTheCause`). Not in scope: Tier-2 policy, the belt, the schema. P8 · P9 · Field Manual ch. 10 "When the database fails". **DoD:** (amended 2026-10-04 to D28 — no time budget) any outage while the tick queue is not shedding loses zero bars and zero ticks; the heartbeat line shows the sink-failure count; a `SINK_FAILURE` event records the episode on recovery (a `DB_ERROR` the terminal exit); a terminal failure stops the pump and the process exits 1 immediately; every behavioural test mutation-verified — boundaries exact (the 5s floor, the ladder, the 500th write), failure-mode tests on the terminal, stop-mid-hold and still-down paths; ch. 10's Tier-1 row updated from "today: stop and restart" to the landed policy. | ✅ 2026-10-05 (PR #13, merged by Alex, commit `64bb872` — T9 complete. Ticketed 2026-10-03 at the slice C step-6 review ("ticket the tier-1 hold and retry"); started 2026-10-04 on `e1-t9-sink-hold-and-retry` — Alex ruled **T9 before T6**; design nod ruled 2026-10-04 → **D28** (no time budget · the blip taxonomy · the belt's `BackoffPolicy` reused · one `SINK_FAILURE` on recovery, `DB_ERROR` before a terminal exit · immediate exit via `onDeath`, never on a stop · pool `connectionTimeout` 5s). Landed: `PersistenceException.retryable()`, `CaptureStore.recover()`, `PostgresStore` holding its own tick batch and refusing every write/flush until recovered (review F1 — a real defect: pgjdbc drops its batch on a failed `executeBatch`, so a retry on the same statement would have acknowledged nothing), the `Pump` holding → backing off 5s → 60s → recovering, `sinkFailures=` on the heartbeat, terminal → `DB_ERROR` + `onDeath` → exit 1 at once; Field Manual ch. 4, 5, 10 + foundations ch. 2 brought true. Increments A–C + the ticket-level doctrine review pass-with-findings F1–F8, all fixed on-branch; tests at merge market-data-service 164 · ig-client 92 · docs site 63, 0 failures; 26 mutations killed (A 8 · B 9 · C 1 · review 8). **Proven in anger 2026-10-05:** a 152s Postgres stop during live DAX capture — 7 attempts on the ladder, 303 held writes drained on recovery, one `sink_failure` row, every 1m bar present, `bar_gaps` empty, zero ticks lost; routine + evidence in the E1 plan T9 §) |
| T10 | **Resilience belt hardening — the PR #14 review.** Fix the 32 verified, ranked findings of the belt's 2026-10-05 independent review (`.claude/reviews/2026-10-05-pr14-resilience-belt.md` — the source of truth per finding; two high, eight medium, the rest low) plus its addendum's **#33** (found 2026-10-08 by E1-T11's scar replay; low — first recorded as medium, corrected the same day at T11's doctrine review), under the four policy rulings **D29** (bounded CLOSED/SUSPEND stand-down · JDBC `socketTimeout` 30s + `tcpKeepAlive` · Tier-2 writes off the sweep thread via a bounded queue + one writer · the `closing()`/`GRACEFUL_CLOSE` hush deleted). Slices: **A1** the two highs with targeted tests, first — #1 a quarantined market's twin-leg rejection re-admits it, #2 a bare `DISCONNECTED`/`onServerError` with CLOSED flags triggers no rebuild; **A2** detectors and verdicts (#3, #4, #5, #8, #11, #13, #14, #18, #19, #21, #29, and #33 — one stray per-market resubscribe the sweep before the session verdict; ruled 2026-10-08 (Alex): fix in A2, the one-sweep hold, no separate decision entry) as E1-T11 scenarios, plus two ruled additions (Alex, 2026-10-08): T5 residual 3's rebuild half as a host-suspend-then-stuck variant of the dead-socket scenario, and the server-first rename of the host-suspend scenario (`aLidCloseIsAnnotatedNotTreatedAsAnOutage`) with the lid/laptop framing purged from the test and code comments; **B** the sink and the database edge (#6, #7, #12, #15/#16, #17, #20, #24, #30); **C** loudness, reuse, docs (#9, #10, #22 — folds backlog B1's heartbeat note, #25, #26, #27 — shared with T11, #28, #31, #32). #23 (no combined-detector test) *is* the harness — E1-T11. Not in scope: new policy beyond D29; the detectors' numbers. **DoD:** every finding fixed or recorded why not, each fix carrying the exposing test the review names (mutation-verified, G5); the A2/B findings' scenarios green on the E1-T11 harness; chapter 10's "known deviations" note removed when the deviations are gone; doctrine review before the PR; Alex may have the cloud review session re-verify. | 🔶 (**A1 ✅ merged 2026-10-08 — PR #15**, merge `92c0181`: findings #1 and #2, 7 scenarios, 9 mutations, the slice review's F1 made the terminal trigger a latch; **A2/B/C next** as scenarios on the merged E1-T11 harness (✅ PR #16, 2026-10-08), one PR per slice — the harness's ten `@Disabled` scenarios (written 2026-10-08, each verified red for its finding's reason) are the backlog: A2 #3 · #4 ×2 · #5 ×2 (+ the host-suspend-then-stuck variant — T5 residual 3's rebuild half, ruled 2026-10-08) · #8 · #33 (ruled 2026-10-08, Alex: fix in A2, the one-sweep hold) + the ruling-3 rename (`aLidCloseIsAnnotatedNotTreatedAsAnOutage` → a host-suspend name; lid/laptop framing purged from `BeltScenariosTest`, `Event.HostSleep`'s and `ReconnectClassifier`'s javadoc, `ReconnectClassifierTest`, `StalenessWatchdogTest`), B #6 · #20 ×2; then **E1-T12** replay acceptance; then a review-only branch of all the belt work for the cloud session's re-review before E1-T6 (Alex, 2026-10-08); ticketed 2026-10-05 from the PR #14 triage) |
| T11 | **The belt's scenario harness.** Adopt the review's side-task proposal as written (`.claude/reviews/2026-10-05-pr14-testing-framework.md`): a synchronous scenario runner driving `Supervisor.sweep()` + `Pump.cycle()` on a deterministic schedule — advance the injected `Clock`, deliver due fixture events on the runner thread, one sweep, one pump cycle; the `Sleeper` a clock-advancer with a runaway guard, no real threads — in a `market-data-service/src/test/…/scenario` package; `FakeStreamTransport` extended (scripted per-subscription outcomes, tick/bar delivery by item name, per-handle state that throws on an inactive unsubscribe); one `FakeClock` + one `RecordingEventLog`; timeline fixtures `{at, kind ∈ {status, tick, bar, confirm, reject, silence, db-down, db-up, host-sleep, stop}, payload}` with expected outcomes beside them; assertions on five observables only (remedies, events, sink writes, heartbeat state, exit path), chapter 10's numbers as the expected offsets; Postgres real (Testcontainers) only for the store/hold scenarios + one container-pause scenario, a scripted `CaptureStore`/`EventLog` elsewhere; nine scenarios in the proposal's order. **Design nod ruled (Alex, 2026-10-08):** (1) **fixture format** — a typed Java DSL for the nine policy scenarios; captured replays are **JSONL** (one event per line `{at, kind, payload}`, read with the Jackson already in the build — not YAML), produced by an exporter — the fixture-format TBD is closed; (2) scenarios for findings not yet fixed are **written now** to chapter 10's expectations and marked `@Disabled("E1-T10 <slice> — finding #n")`, each enabled by its fix as the exposing test; (3) `Main`'s wiring is extracted into a **`CaptureAssembly`** shared by `Main` and the harness rig, so the harness exercises the real composition and shutdown order (closes the reviews' "Main is untested"); (4) **no real-thread scenario** — the runner is synchronous; a latch-driven concurrency reproduction is its own ticket only if a real race ever surfaces in the drills/soaks. **Scope confirmed:** the nine policy scenarios **plus** the JSONL loader, the exporter (two schemas: the prototype's `igtrader` tables and Tradebench's own `ticks`/`bars_1m`/`service_events` v2) and **one replay as scenario ten** — the 2026-08-04 silent-while-connected scar (a window of minutes around it, a few hundred KB). **DoD:** the nine policy scenarios run deterministically with no real waiting, the scar replay as scenario ten through the JSONL loader; each scenario's expectations are chapter 10's numbers; the `@Disabled` scenarios for #3/#4/#5/#6/#8/#20 written, each awaiting its fix; `Main` and the rig share `CaptureAssembly`; the harness's own behaviour mutation-verified (a wrong expected offset fails); T5's residuals 2 and 3 closed by it; the subsumed `SupervisorTest` one-detector scenarios removed, the pure-core unit tests kept, `IgStreamControlTest` on the extended fake. | ✅ 2026-10-08 (**PR #16**, merged by Alex, merge commit `65b4912` — T11 complete. Ticketed 2026-10-05; design nod ruled 2026-10-08 (the four points above); started 2026-10-08 on `e1-t11-belt-scenario-harness`; three increments + the doctrine-review fixes, commits as merged `ac79cdd` · `2f16773` · `a95022a` · `59238f0`. Landed: `BeltScenariosTest` 21 scenarios — 11 green, 10 `@Disabled` to E1-T10 findings (#3 · #4 ×2 · #5 ×2 · #6 · #8 · #20 ×2 · #33), each verified red for its finding's reason; the 2026-08-04 scar replayed from JSONL through `Replays` (two exporters — the prototype's `igtrader` schema and Tradebench's own); `CaptureAssembly` shared by `Main` and the rig; ten `SupervisorTest` one-detector scenarios retired, no coverage lost; 23 mutations killed; doctrine review pass-with-findings, all fixed on the branch; tests at merge ig-client 99 · market-data-service 193 (10 skipped) · docs site 63 · 0 failures; CI green on the merged head. The replay found **#33** (low; review addendum; → T10 A2 — ruled 2026-10-08 (Alex): fix in A2, the one-sweep hold). T5 residual 2 closed, final; residual 3 closed in its rebaseline half here, its rebuild half closes with T10 A2 as a host-suspend-then-stuck variant of the dead-socket scenario (ruled 2026-10-08, Alex — this DoD's wording stays as written). Detail: E1 plan T11 §) |
| T12 | **Replay acceptance: the captured-outage library and the rig in acceptance mode.** The gold standard short of the real IG socket — real parsers, buffers, pump, store and database under a replayed stream of real captured outages. The source is the prototype's capture (facts read from the prototype database `igtrader_demo`, schema `igtrader`, 2026-10-08): `dax_ticks` 3.5M rows, 2026-07-29 → 2026-10-08 and still capturing, with a `dealflag` column (real `DLG_FLAG` transitions); `service_events` with `offline_seconds` and `detail {replayed, host_slept}` — 728 reconnects, 103 session rebuilds, 58 bar gaps, 3 watchdog resubscribes, 1 forced rebuild; the 2h56m silent-while-connected scar is a 10,569-second reconnect on 2026-08-04; a dozen host-suspend outages of 18–32 minutes carry `host_slept: true` (the prototype ran on a laptop); `dax_bars_1m` (38k rows) is mid-price OHLC with derived indicators, so replay bars are timing-only / synthesised from ticks (ours are bid/offer OHLC from the CHART feed); the prototype did not store the Lightstreamer status sequence, so a replay reconstructs status transitions from each reconnect's `offline_seconds` and says so. Tick rate ≈ 10–15k/hour on a busy day (a day ≈ 140k ticks, ~12 MB), so fixtures are windows of minutes around an event. Plan: (a) the exporter — T11 delivered both schemas 2026-10-08 (`export-prototype.sql` · `export-tradebench.sql`, SQL → JSONL, documented in `market-data-service/src/test/resources/replays/README.md`), so (a) reduces to the wrapper script + the curated library — **G1: market data only**, never `strategy_events` or the OMS tables; (b) a curated fixture set, each with expectations written from `service_events`/`bar_gaps` independently of the code — the 2026-08-04 scar; three or four `host_slept` host-suspend outages; two days with bar gaps; a weekend CLOSED → open transition; one busy hour with no events, expecting "no remedy, no event, every tick and bar landed exactly once"; (c) the rig in **acceptance mode** — the same replay through the real `Pump` and `PostgresStore` into a Testcontainers Postgres instead of the scripted stores, asserting the data invariants as rows (every replayed tick/bar landed exactly once; `bar_gaps` matches the known gaps; no spurious remedies in healthy windows); (d) packaging as a tagged suite excluded from the default unit run, with its own Gradle task and a CI lane (nightly or on demand), the way ig-client's `ig-demo` smoke is gated. Why: the re-review should judge a belt proven against real captured outages; T6's heal consumes exactly these bar-gap windows. Not in scope: real threads (ruled at T11's nod); the live tier (the live outage drill, T8's shadow-diff week, T7's staging soak). **DoD:** the curated set green in both modes; expectations anchored on the prototype's recorded events; the exporter documented; the suite runnable on demand and in its CI lane; a field-manual note on the replay library (where it lives, how a new outage becomes a fixture). | ⬜ (**unblocked 2026-10-08** — its hard dependency E1-T11 is merged, PR #16: the harness, the JSONL loader and both exporters are on main (`market-data-service/src/test/resources/replays/README.md`), so plan item (a) reduces to the wrapper script + the curated library; ticketed 2026-10-08, Alex's sequencing T10 A2/B/C → **T12** → the belt's re-review → T6; estimate ≈ two sessions) |

## E2 🧰 The docs site (`web/docs` → docs.tradebench) — ✅ COMPLETE (T1–T6 merged; live at docs.tradebench.amfshr.dev)

**Since** 2026-09-28 · **Decisions** D11, D18, D19 · **Manual** —

The first frontend warm-up: a small locally-served React/TS app rendering this markdown board
(and the epics/vision docs) as pages — MD stays the write model for agents, the app is the read
model for humans. **Shape confirmed by Alex 2026-09-27 (D11; PRD open Q#10 resolved).** Scope
grew naturally 2026-09-28: the read model now also renders `docs/` — the product overview and
the Field Manual — as Alex's "online book of pages". Low stakes, real daily value, exercises the
frontend toolchain before the charting UI.

**Ticket plans:** `.claude/tasks/epics/e2-docs-site.md` (T1 carries the frontend design
nod N1–N4). **Working shape:** git worktree `.claude/worktrees/e2-docs-reader`, branch
`e2-t1-docs-reader` (main + the docs-only commits cherry-picked), alongside E1-T5.

| # | Ticket | Status |
|---|--------|--------|
| T1 | **Docs & board reader v0.** Vite/React/TS app at `web/docs` (tech-notes §6 shape as ruled by D18): sidebar over `docs/` + the board, GFM, rendered mermaid, highlighted code, rewritten internal links, light/dark, reading-first typography; content via dev-server glob of the repo's markdown — no backend; doc edits hot-reload. **DoD:** the Field Manual, product overview, PRD, decisions and board all render pleasantly with working nav. | ✅ 2026-09-28 (PR #6; 21 behavioural tests, 9 mutations killed incl. review F1's deletion mutant; doctrine F1–F6 all fixed pre-open; v0.2 chrome + lightbox + theme toggle per Alex's iterative rulings; D18 renames rode the PR) |
| T2 | **Board read model + designed pages.** Parse board + epic files into epic cards, status chips, attention-first tickets, done-history timeline, epic pages with metadata/decisions/history rails (board.md enriched with per-epic since/decisions/book; updated derived); designed landing page; top nav + ⌘K search; brand-themed mermaid + framed lightbox; sidebar section dividers; graceful raw-markdown fallback; parser mutation-verified. Design intent (R11b): the brand exploration's board/epic IA. **DoD:** the board page reads as a dashboard. | ✅ 2026-09-29 (PR #7; 42 tests, ~10 mutations killed across two campaigns + doctrine F1/F2; brand re-skin D19 + D18 renames rode along; **deferred: ticket-detail pages → T5, book scar-callouts → T4**) |
| T3 | **Build & CI lane.** Static build snapshotting content; `web/**`-keyed CI job; preview story. Hosting ruled **GitHub Pages** (D18); custom domain **live: docs.tradebench.amfshr.dev** (Cloudflare CNAME + PAGES_BASE=/, HTTPS enforced — Alex, 2026-09-29). **DoD:** one command yields the static site; CI green. | ✅ 2026-09-29 (pages.yml deploy + web-ci.yml PR lane; site live on custom domain) |
| T4 | **Docs polish & structure — the final E2 sweep.** (a) scar callouts (R9) rendered + led across all nine chapters; (b) book → **Field Manual** (D21: docs split by audience — Build track vs a reserved User-Guide Use track); (c) decision log as a designed D-card page (status chips, dates, bodies); (d) `design-sessions` nested under `design` (one Design group). **DoD:** scars render as R9 cards; decision log is a designed page; design-sessions nested; rename ruled. | ✅ 2026-09-30 (branch e2-t4-docs-sweep; D21) |
| T5 | **Ticket-detail pages.** Clickable `/board/epics/:slug/:ticket` — structured header (breadcrumb, status chip, title), a done-when checklist from the ticket's DoD, the rich plan section, and a lean metadata panel (type · branch · started · blocked-by · epic · PR); board + epic ticket rows link to it. Spawned the board-format spec (D20) + the `board-steward` agent (E0-T5). **DoD:** a ticket opens as its own page with header, checklist, plan body, and lean metadata; rows link to it. | ✅ 2026-09-30 (PR #8; ticket pages + epic-view redesign to the design; 60 tests, doctrine F1–F7 fixed pre-merge; deployed) |
| T6 | **Nested docs tree + IA for growth.** (a) recursive **nested sidebar** — folders render as collapsible subgroups at any depth (today it's flat: `design/sessions/` shows as slash-labels, not a nested group); (b) split `architecture/README.md` → an `architecture/` folder with a **`components/<service>/` reference sub-tree** (detailed per-component technical docs); (c) record the split — Field Manual = narrative teaching (chapters/sections), Architecture = spec + component reference — extending D21. **DoD:** the sidebar nests real directories; architecture is a browsable folder with a component reference; the FM-vs-Architecture rule is recorded. | ✅ 2026-09-30 (branch e2-t6-nested-tree; recursive nested sidebar; architecture → folder + components/<module> reference; D22) |

## E3 🧪 Indicator DSL v0 + backtest spine — DESIGN-GATED

**Since** 2026-09-27 · **Decisions** D7, D8, D9 · **Manual** —

The rest of PRD §11 phase 1: basic chart over stored data → indicator DSL v0 (look-ahead
rejection from day one) → backtest job runner v0 (default FLAT⇄IN_POSITION machine) → first
results page. **Language semantics ruled 2026-09-27**
(`docs/design/sessions/2026-09-27-indicators-and-predicates.md`, decisions D7–D9; PRD Q#6
resolved). Build tickets get cut after the two design gates below land — plus enough
captured/imported data to chew on (E1 + first Databento decisions).

| # | Ticket | Status |
|---|--------|--------|
| T1 | **Vocabulary catalogue v0** (design). Every v0 primitive organised by compute family (recursive / rolling-window / session-anchored / composite / whole-window-refit) with signature, semantics, warm-up, volatility class; carries the unit-awareness typing question (session R8). Claude proposes, Alex rules line by line. **DoD:** catalogue at `docs/design/`; every entry ruled; PRD open Q#5 resolved. | ⬜ |
| T2 | **Acceptance-anchor check** (off-repo — guardrail G1). Walk Pattern 1 (Dad's answered sheet) and the range-bar consolidated spec against the R1–R8 semantics + the T1 catalogue. **DoD:** verdict + any semantic gaps recorded in a session record (no strategy content committed); gaps fed back into catalogue/decisions. | ⬜ (needs T1 first) |

## E9 🎛️ Operator console (Market Data Manager v0) — ACTIVE (born 2026-09-30, design session)

**Since** 2026-09-30 · **Decisions** D24 · **Manual** —

**Mission:** the deployed collection service, observed and its data retrieved **without SSH** — a separate read-model app (a `web/` SPA + a thin read-only service over the `market_data` data product + R2, edge-gated by Cloudflare Tunnel + Access, D24). Health, coverage/gaps map, capture catalogue, errors/warnings log, and Parquet/DB-export download links. First operational slice of PRD §5.1; read-only v1. The collector stays outbound-only and single-purpose (P7) — E9 reads what it produces.

| # | Ticket | Status |
|---|--------|--------|
| T1 | **Observability & data-model design.** Full design (Claude proposes, Alex rules) of: the `service_events` v2 schema (lifecycle / resilience / data-quality / heal + **errors & warnings**; severity, dimensions, dataTime + monotonic); the time-series metric set + the **Grafana-vs-DIY split**; and the read-model SQL behind each view (health, coverage map, gaps, catalogue, downloads, event log) + domain/performance views. **DoD:** a design doc + session record with schema, metric set, and per-view queries ruled; fed back to E1-T5 slice B before the event log is built. | ✅ 2026-09-30 (ruled R1–R7; spec `docs/design/observability-and-data-model.md`, D25; Q1 folded + resolved; feeds E1-T5 slice B) |
| T2 | **Read-model service.** Thin Spring Boot read-only service over `market_data` (read-only creds, D17) + R2 pre-signed links; the JSON API + Actuator health; localhost behind the tunnel. **DoD:** cut from T1's per-view queries. | ⬜ (blocked by T1) |
| T3 | **The console SPA.** Brand-skinned (D19) `web/` app: health, coverage map, gaps, catalogue, downloads, errors/warns log; reuses the E2 read-model + brand patterns. **DoD:** cut from T1. | ⬜ (blocked by T1, T2) |
| T4 | **Cloudflare edge + deploy.** Tunnel (`cloudflared`, outbound — no inbound ports) + Access (per-person auth) fronting the console; deployed alongside the collector (D24). Optional: a Grafana/Prometheus metrics surface behind the same tunnel. **DoD:** cut from T1 + the E1-T7 host ruling. | ⬜ (blocked by T2, T3, E1-T7) |

## Horizon epics — named, unplanned (cut from PRD §5/§11 when their time comes)

- **E4 🔭 Workbench proper** — exploration mode (multi-panel, multi-TF, scrub/step, visible
  triggers), indicator library semantics (namespace, versions, clone/export), Databento ingest
  + the platform-dataset tier, upload tool + true source attribution. (PRD phase 2.)
- **E5 🧬 Strategy platform** — full state-machine DSL (Pattern 1 compiles = acceptance),
  strategy replay mode, results depth (Monte Carlo, trade→replay jump), overnight job
  orchestration. (PRD phase 3.)
- **E6 ⚡ Execution** — broker port + simulator adapter matured, paper → IG demo → IG live
  bots, risk pages + kill switches, notifications, reconciliation; live last, behind the
  D46-style failsafe layer. (PRD phase 4.)
- **E7 📊 Analytics** — results dashboards scoped per strategy identity (P2) across demo and
  live: daily/weekly/monthly. (PRD phases 4–5.)
- **E8 ✨ Platform polish** — login + edge gating (multi-user goes real), sharing, wizards +
  DSL lessons, the copilot, money management, NASDAQ second market, live chart streaming view
  (may justifiably pull earlier — high-joy). (PRD phase 5.)

## Waits / externals

- CI pipeline stages design (PR lane / main lane / release lane; artifact naming, required
  checks) — sketch proposed 2026-09-28; draft `docs/design/ci-pipeline.md` when E1-T7 or
  E0-T4 planning starts.
- E3 design gates: vocabulary catalogue v0 (E3-T1) → off-repo acceptance-anchor check (E3-T2).
- Databento initial purchase scoping (≥2yr DAX+NASDAQ ticks — PRD §7) — needed by E3, not E1.
- PRD open questions #7–#11 — none block E0/E1. (#6 and #10 resolved 2026-09-27; #5 = E3-T1;
  #4 half-resolved: Gradle confirmed in practice (D12), tick-lake storage format still open.)

## Done history

- **2026-10-08 — E1-T11 The belt's scenario harness merged (PR #16)** ✅: chapter 10 as executable expectations — the
  composed loop (`Supervisor` ↔ `IgStreamControl` ↔ `Pump` ↔ the stores) driven by a synchronous `ScenarioRunner` over a
  typed Java DSL (`Scenario` / `Event` / `Observed` — five observables only: remedies, events, sink writes, heartbeat
  state, exit path), no real threads, no real waiting; `Main`'s wiring extracted into `CaptureAssembly`, shared by `Main`
  and the rig. `BeltScenariosTest` 21 scenarios — 11 green, 10 `@Disabled` to E1-T10 findings (#3 · #4 ×2 · #5 ×2 · #6 ·
  #8 · #20 ×2 · #33), each written to chapter 10's expectations and verified red for its finding's reason — the A2/B
  backlog; the 2026-08-04 silent-while-connected scar replayed from JSONL through `Replays` (3,177 ticks + 10 candles
  landed exactly once; two exporters — the prototype's `igtrader` schema and Tradebench's own — documented in
  `market-data-service/src/test/resources/replays/README.md`); ten `SupervisorTest` one-detector scenarios retired, no
  coverage lost; one `FakeClock` / `FakeSleeper` / `RecordingEventLog` (T10 #27, done here). 23 mutations killed (20 in
  the build, 3 at the review), each restored byte-identical; doctrine review pass-with-findings, all fixed on the branch
  (`59238f0`). Tests at merge: ig-client 99 · market-data-service 193 (10 skipped) · docs site 63 · 0 failures; CI green
  on the merged head. The replay found **#33** — one stray per-market resubscribe the sweep before the session verdict
  (low; first recorded as medium, corrected the same day per G8; → T10 A2 — ruled 2026-10-08 (Alex): fix in A2, the one-sweep hold). T5
  residual 2 (the composed-loop test) closed, final; residual 3 closed in its rebaseline half here — its rebuild half
  (the host wakes with the client stuck in `DISCONNECTED:WILL-RETRY`, the escalator rebuilds 120s after the wake in
  awake time) closes with T10 A2 as a host-suspend variant of the dead-socket scenario (ruled 2026-10-08, Alex; the
  DoD wording stays). E1-T12 unblocked. Commits as merged (the branch was rebased onto main first): `ac79cdd`
  (1/3 the fakes) · `2f16773` (2/3 the harness, nine scenarios green) · `a95022a` (3/3 the finding scenarios, the scar
  replay, the exporters, the retirements, docs) · `59238f0` (the review fixes). Evidence: PR #16 (merged by Alex
  2026-10-08, merge commit `65b4912`).
- **2026-10-08 — E1-T10 slice A1 merged (PR #15) — the belt's two high findings fixed** ✅: #1 a quarantined
  market stays quarantined (`Judgment.Ignored` in the core; `IgStreamControl.resubscribe` refuses a quarantined
  epic) and #2 a terminal disconnect rebuilds the session (`ReconnectClassifier.isTerminal`; a new
  `CONNECTION_DEAD` reason event; the death latched and asked every sweep, so a refusal recurring inside the
  pacing floor climbs the ladder; `onServerError` latches the same death — one death, one rebuild). 7 scenarios,
  9 mutations killed; the slice's doctrine review (pass-with-findings — F1 the latch) fixed on-branch; chapter
  10's known-deviations note loses #1 and #2. Merged by Claude on Alex's instruction once CI was green (merge
  `92c0181`). Evidence: PR #15.
- **2026-10-05 — The resilience belt's independent review (PR #14, review-only, closed unmerged) → E1-T10 + E1-T11; D29**:
  Alex ran `/code-review ultra` over the whole belt (E1-T5 slices A–C + E1-T9) as **PR #14**, a review-only PR with its
  base pinned at `ee35f9b` (pre-belt) and its head at main — 103 files, +7492/−599 — closed unmerged the same day, both
  branches deleted. Its **32 verified, ranked findings** (two high, eight medium, 22 low; eleven finder angles plus one
  independent pass, eight adversarial verifiers; refuted candidates listed, not counted) are at
  `.claude/reviews/2026-10-05-pr14-resilience-belt.md`; its answer to the brief's side task — a testing framework for the
  belt — at `.claude/reviews/2026-10-05-pr14-testing-framework.md`. Triage (Alex): the fixes are **E1-T10** (slice A1 the
  two highs first with targeted tests; A2/B/C as scenarios once the harness exists), the harness is **E1-T11** (the
  proposal adopted as written; fixture format `Decision: (TBD)`), and the four policy rulings the findings needed are
  **D29** (bounded CLOSED/SUSPEND stand-down · JDBC `socketTimeout` 30s + `tcpKeepAlive` · Tier-2 writes via a bounded
  queue + one writer · the `closing()` hush deleted). Ruled: `.claude/reviews/` is the home of all AI review records,
  never the public docs. Fixes land in the local session under the standard workflow (design nod → increments →
  mutation evidence → doctrine review → PR; Alex merges); the cloud review session may re-verify afterwards. Evidence:
  PR #14 (closed, unmerged) · commits `66573a6` (the findings) and `f8f94cb` (the proposal).

- **2026-10-05 — E1-T9 Sink blip resilience merged (PR #13) — hold-and-retry for the capture sink, proven in anger** ✅:
  Tier 1 refined (**D28**): `PersistenceException.retryable()` — the blip taxonomy (SQLSTATE 08 / 57 / 53 /
  40 + the pool timeout retry; the first state in the cause chain decides; unknown is terminal);
  `CaptureStore.recover()`; `PostgresStore` holds its own tick batch, acknowledges only once it lands,
  `recover()` re-acquires a pooled connection and re-binds, and the store refuses every write/flush until
  recovered (review F1 — a real defect: pgjdbc drops its batch on a failed `executeBatch`, so a retry on
  the same statement would have acknowledged nothing); the `Pump` holds on a retryable failure, backs off
  on the belt's `BackoffPolicy` (5s floor → 60s cap), writes one `SINK_FAILURE{cause, outageMs, attempts,
  ticksShed, queuedAtRecovery}` on recovery, counts `sinkFailures=` on the heartbeat, announces shedding,
  honours `stop()` within 250ms; a terminal failure → `DB_ERROR{cause, queued}` best-effort + `onDeath` →
  `Main` exits 1 at once from its own thread; `Database.CONNECTION_TIMEOUT` 5s. No time budget. Field
  Manual ch. 4, 5, 10 + foundations ch. 2 brought true. Rulings (Alex, 2026-10-04) logged as **D28** (1–6).
  Increments A `5e0b7aa` · B `bf78387` · C `a7773f3` · review fixes `215ad09`; ticket-level doctrine review
  pass-with-findings F1–F8, all fixed on-branch. Tests at merge: market-data-service 164 · ig-client 92 ·
  docs site 63 · 0 failures; 26 mutations killed (A 8 · B 9 · C 1 · review 8), every restore identical (the
  PR body carries the per-test table). **Proven in anger — the outage drill (2026-10-05, Alex at the
  keyboard):** the local Postgres stopped during live DAX capture → the sink held for 152s over 7
  attempts (waits 5 / 5 / 5 / 11 / 13 / 43 / 39s — the floor, the jittered doubling, the 60s cap),
  `written=` froze at 333 while `ticks=` kept rising, recovery drained 303 queued writes; psql verified one
  `sink_failure` row (attempts 7, outageMs 152678, ticksShed 0, queuedAtRecovery 303, cause SQLSTATE 57P01),
  no `db_error`, every 1m bar 16:31 → 16:40 present at `00:01:00` steps, `bar_gaps` empty, the outage
  minutes' tick counts in line with their neighbours, `capture_status` fresh with `db_pending` 0 and
  `dropped_ticks` 0 — **zero bars and zero ticks lost.** Pre-drill scar: Flyway refused on a V1 checksum
  mismatch (the 2026-09-28 date sweep had edited a comment in an applied migration) — repaired by
  resetting the recorded checksum; an applied migration is immutable, comments included. Full routine,
  log and the five verification queries: E1 plan T9 §. Evidence: PR #13 (merged by Alex, commit `64bb872`).

- **2026-10-04 — E1-T5 slice C merged (PR #12) — T5 Resilience belt complete** ✅: the `Supervisor`
  shell composing the slice-A cores on a dedicated 1s sweep thread; `IgStreamControl` as the real
  `StreamControl` (boot + rebuild on one path, per-item subscribe/unsubscribe, generation-gated
  callbacks, bounded boot); `HealthProbe` publishing `capture_status` each 60s heartbeat; gaps +
  market state onto the `EventLog` (the capture sink stays market-data-only); post-login pacer
  discovery (`RequestPacer.CONSERVATIVE_START` 10/min → min(account, application) − 5 headroom via
  `GET /operations/application`); `java-test-fixtures` (`FakeStreamTransport`); Field Manual ch. 10
  "The failure playbook" + ch. 08 brought true; component page refreshed. DoD met on fakes — dead
  socket (`SupervisorTest`, `IgStreamControlTest`), silent-while-connected (`SupervisorTest`,
  `StalenessWatchdogTest`), host suspend (`SupervisorTest.hostSleep…` ×2, `StalenessWatchdogTest`)
  — each mutation-verified. Tests at merge: ig-client 92 · market-data-service 143 · docs site 63 ·
  0 failures; 89 mutations killed (steps 2a 5 · 2b-i 4 · 2b-ii 4 · 3 3 · 4 17 · 5 9 · 6 36 of 37 — the
  survivor exposed redundant code, removed · whole-slice review 11). Doctrine reviews per-step (3, 4,
  5, 6) + whole-slice pass-with-findings F1–F10, all fixed on-branch before merge except F9 (a
  declared residual). Rulings (Alex, 2026-10-03) logged as **D27**: DB-write tiers (Tier 1 product
  data fail-closed · Tier 2 observability best-effort-but-loud · Tier 3 decisions never touch the DB);
  exhaustion = time budget `Tuning.giveUpAfter` 10 min → `FEED_DEAD{reason}` → orderly `exit(1)`; one
  streaming rule `ReconnectClassifier.isStreaming`; pacer headroom min(account, application) − 5 (E1
  plan T5 §). Three residuals recorded in the E1 plan T5 § — not ticketed, Alex decides: the gated
  demo smoke unrun (`application-allowance.json` authored, not captured), no composed-loop test, no
  shell-level suspend → wake → `WILL-RETRY` → rebuild scenario. Follow-up already ticketed: **E1-T9**
  sink hold-and-retry (sequence vs T6: Alex). Evidence: PR #12 (merged by Alex, commit `8872075`).

- **2026-10-01 — E1-T5 slices A + B merged (PR #11)** ✅: the resilience belt's foundation on main —
  slice A's 7 pure decision cores (`supervise/` + `coverage/`, 23 mutations) and slice B's
  observability store (migration V2: `service_events` v2 / `bar_gaps` / `capture_status`; the
  `marketdata.events` vocabulary; the `EventLog`/`GapStore`/`StatusStore` seam over
  `PostgresObservabilityStore`; 8 mutations; doctrine review pass-with-findings, F1–F4 fixed). Slice
  C (the Supervisor shell) followed on `e1-t5c-supervisor-shell` → PR #12, merged 2026-10-04 (entry above).

- **2026-09-30 — Operability design session + E9-T1** ✅: two same-day design sessions on the
  deployed collector's operability. (1) *Architecture/access* → **D24** + record
  `2026-09-30-collection-service-operability.md`: a read-model Operator Console (new epic **E9**)
  over the `market_data` data product + R2, edge-gated by **Cloudflare Tunnel + Access** (box
  stays outbound-only; mTLS reserved for machine clients); hosting = a small **Hetzner** VPS +
  compose (formal ruling at E1-T7). (2) *Observability & data model* (**E9-T1**, R1–R7) → **D25**
  + spec `docs/design/observability-and-data-model.md`: `service_events` **v2** (dimensions +
  severity + occurrence/recorded split), `bar_gaps`, `capture_status`, `archives`; the
  metric/Grafana-DIY split; and the streaming schedule (**Q1 resolved** — three clocks, generous
  window, expected calendar). Feeds E1-T5 slice B (writes the v2 schema) + E1-T6 (`archives`).
  Grounded via the seed-pack-librarian + a trading-ig 0.0.24 deep re-mine.

- **2026-09-30 — E2-T6 Nested docs tree + IA** ✅ (**closes E2**): recursive **nested sidebar**
  (folders → collapsible subgroups at any depth; `buildTree` builds a real hierarchy,
  `flattenLeaves` powers the page-turn); **architecture** split into a folder with a
  `components/<module>` reference (core, ig-client, market-data-service); **D22** (Field Manual
  teaches / Architecture specs, extends D21). 62 tests, tree logic mutation-verified; docs
  link-integrity clean after the move. Evidence: PR #10 (merged 2026-09-30). **E2 is complete: T1–T6.**

- **2026-09-30 — E2-T4 Docs polish & structure** ✅: the final docs sweep — **D21** (docs
  split by audience: book → **Field Manual** / Build track, reserved User Guide / Use track);
  decision log as a designed D-card page (`DecisionsPage` + `parseDecisionLog`); **scar
  callouts** (R9) rendered + led across all nine chapters; `design-sessions` nested under
  `design`. 62 tests; doctrine pass-with-findings, F1 (decision-log silently dropping D8 —
  qualifier-text date) + F1–F7 all fixed pre-merge. Evidence: PR #9 (merged) + live site.
  E2 tickets T1–T5 done; **T6 (nested tree + component-reference IA) is the last to close E2.**

- **2026-09-30 — E2-T5 Ticket-detail pages + epic-view redesign** ✅: clickable ticket pages
  (`/board/epics/:slug/:ticket`) with done-when checklist + lean metadata; epic view rebuilt to
  the design (id+status chips, WHY THIS EPIC, Open/Done counts + dates, dated+described decisions
  rail); boxy shaded chips + de-blued rows. Write-model infra: board-format spec (D20) + the
  `board-steward` agent (E0-T5) that keeps the board truthful. 60 tests, ~13 mutations verified;
  doctrine pass-with-findings, F1 (market-green on a non-market element) + F1–F7 fixed pre-merge.
  Evidence: PR #8 (merged) + live site. Only T4 remains to close E2.

- **2026-09-29 — E2-T2 The docs read model** ✅: `web/docs` grew from a markdown reader into
  a read model — board.md parsed into a dashboard (epic cards, attention-first tickets,
  done-history timeline) + epic pages with real-data metadata/decisions/history rails
  (board.md enriched with per-epic since/decisions/book, updated derived); designed landing;
  top nav + ⌘K search palette; Quiet Terminal re-skin (D19); brand-themed mermaid + framed
  fit-to-viewport lightbox; themed scrollbars. 42 tests, ~10 mutations killed; doctrine
  review pass-with-findings, F1 (surviving history-boundary mutation) + F1–F6 all fixed
  pre-merge. D18 renames + custom domain (docs.tradebench.amfshr.dev, HTTPS) rode along;
  T3 closed. Evidence: PR #7 (merged) + live site. Deferred, tracked: T4 (scar callouts),
  T5 (ticket-detail pages).

- **2026-09-27 — Sanity lap** (menu ①): cold-start playback proved the handoff self-sufficient;
  no contradictions found.
- **2026-09-27 — Indicators & predicates design session** (menu ②): answered record filed
  (`docs/design/sessions/2026-09-27-indicators-and-predicates.md`); rulings R1–R8; decisions
  D7–D9; PRD open Q#6 resolved; spawned E3-T1/T2. Evidence: commit `b0387d0`.
- **2026-09-27 — E0-T1/T2/T3 + board/epics/docs pass**: AI operating framework v1 (contract,
  2 agents, 3 skills, directory standard), CLAUDE.md/task-workflow wiring, `docs/design/`
  seat, this board structure (E0 born, E3 design-gate tickets, horizon epics named).
- **2026-09-27 — E1-T1 Foundations** ✅: Gradle 9.8 multi-module skeleton (`core` /
  `ig-client` / `market-data-service`, Java 25 LTS toolchain, `dev.amfshr.tradebench`), CI
  green on mutation-verified walking skeletons, branch protection active (required check
  `build`, admin bypass for docs-only). First `/start-ticket` + `doctrine-reviewer` run in
  anger. Foundation choices logged as D12. Evidence: PR #1 (merged).
- **2026-09-27 — E1-T2 ig-client session + REST core** ✅: §1.6 taxonomy, login→switch→token
  re-read, validate-before-relogin + 61s stagger, sliding-window pacer in the library, REST
  v3 markets/prices keyed on snapshotTimeUTC (fail-loud, never guess — Alex's ruling: client
  throws, T6's healer classifies), injectable timeouts, exact BigDecimals. 15 mutations
  killed; doctrine-review F1–F5 fixed; wire fixtures cross-checked against the prototype's
  scraped labs.ig.com reference (now at `docs/reference/`) + field-proven backfill code.
  D13 (design nod) born from this ticket's feedback; architecture/README.md v1 seeded. Evidence:
  PR #2 (merged).
- **2026-09-28 — E1-T3 Streaming + capture pipeline** ✅: core domain/time SPIs (D15 mid
  discipline, D38 dataTime), StreamTransport seam + Lightstreamer wrapper (capability-split
  subscriptions), capture queues/pump/JSONL runner with honest heartbeat; 30 mutations
  killed; doctrine-review F1–F5 fixed; trading-ig sweep adoptions; D14/D15/D16 landed
  en route; proven against live Sunday data (500+ ticks, 4 bars, clean counters). Evidence:
  PR #4 (merged) + capture JSONL. Event-stream SPI parked to E3 (Alex's ruling).
- **2026-09-28 — E1-T4 Persistence** ✅: Flyway V1 (user+source on every fact row, _utc
  naming, name-anchored seeds), store.PostgresStore behind the CaptureStore seam
  (ack-after-apply end-to-end), dedicated-session advisory lock (review F1: pooled-
  connection lock was a masked defect), schema drift gate (self-mutation-verified),
  13 mutations killed, D17 topology + compose-managed loopback-only dev Postgres, app/
  ingest/store restructure (Alex's Q1–Q4), db-admin agent. Evidence: PR #5 (merged) +
  live DAX rows in market_data. Deferred by ruling: D22 roles + schema/public design → T6.
- **2026-09-28 — Docs pass** (on branch `e1-t5-resilience`, rides the T5 PR): `docs/`
  front door (`docs/README.md` map), `docs/product/overview.md` (technical product doc;
  seat named for the strategy-language reference), `docs/field-manual/` (nine teaching chapters,
  socket → Postgres → outward), architecture §3.4 trajectory + §3.5 fan-out plan, board
  refresh. Purpose: Alex's read/review checkpoint before T5 slices B/C.
- **2026-09-28 — E2-T1 The docs site v0** ✅: `web/docs` (Vite/React/TS, npm workspaces) —
  repo markdown as pages: sidebar tree, GFM, client-rendered mermaid + lightbox, highlighted
  code + copy, rewritten internal links, scroll-spied ToC, prev/next, breadcrumbs,
  Inter/JetBrains Mono, light/dark + persisted toggle; no backend (dev-server glob,
  hot-reload). 21 behavioural tests, 9 mutations killed; doctrine review F1–F6 fixed
  pre-open (F1 caught an overstated mutation claim — deletion mutant now dies). T3 lanes:
  web-ci.yml PR check + pages.yml → **first GitHub Pages deploy green, site live**. D18
  ruled in-review (one docs site; `web/` naming). Evidence: PR #6 (merged) +
  amfshr.github.io/tradebench/.
- **2026-09-29 — Brand & visual identity design session** ✅ (same-day close): Alex ran a
  ten-turn exploration in Claude's design tool that produced its own decision log
  (D-01…D-09); Claude audited (full WCAG AA pass computed; red/green reservation
  verified) and proposed R1–R11; Alex ruled all as recommended. Quiet Terminal direction,
  token palette, Inter+JBM, `tradebench▊` wordmark, two-candles mark, amber-once
  attention, market-vs-strategy colour doctrine (R8, standing), scar callout treatment.
  Artifacts: `docs/design/brand.md` (spec) · D19 · session record CLOSED · exploration
  export archived under `docs/design/research/`. Re-skin rides the T2 PR.
