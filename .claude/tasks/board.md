# Tradebench Board

> The single source of truth for work. GitHub is for PRs only — never issues (until outside
> collaborators exist). Ways of working: `.claude/task-workflow.md` · AI operating contract:
> `.claude/ai-framework.md`. Legend: ⬜ queued · 🔶 in progress · ✅ done · 🧊 iced/blocked.
> Tickets carry a DoD; ideation that hasn't earned a ticket lives in `docs/` or the inherited
> ideas, not here.

**Guide (2026-09-27):** E0 (AI operating framework) born urgent and mostly landed the same day
— only enforcement (T4) remains, and it pairs with E1-T1's CI. E1 is the active build epic —
the deploy-first ruling (D2) stands: it ships to the cloud before anything else exists. E3's
language semantics are ruled (design session 2026-09-27, D7–D9); its two design-gate tickets
are cut and plannable. E4–E8 name the horizon so the whole road is visible; they hold no
tickets until their own planning sessions.

**Next-session menu (refreshed 2026-09-27, post E1-T2):**
① *E1-T3 streaming* (Lightstreamer ticks + sealed 1m bars — D13 design nod first) and/or
*E1-T4 persistence* (Flyway schema — can run in parallel with T3 per the epic plan).
② *Run the demo smoke* — `IG_SMOKE=1 ./gradlew :ig-client:demoSmoke` with IG_DEMO_* set
(Alex only; first real-wire contact for T2's session core). ② *E3-T1 vocabulary catalogue
v0* — Claude drafts to `docs/design/`, Alex rules line by line. ③ *E0-T4 guardrail
enforcement* — CI exists; GitGuardian already runs (fold in). ④ *E2 planning / page
designs*. Nothing else blocks: Databento scoping and PRD open Qs #7/#11 are E3-era.

---

## E0 🤖 AI operating framework — ACTIVE (born urgent 2026-09-27)

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

## E1 📡 Collection service, deployed 24/7 — ACTIVE

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
| T5 | **Resilience belt.** Reconnect stack; staleness watchdog on injectable clocks — should-be-ticking derived from the stream itself, monotonic/awake vs wall time split, escalation ladder (triage D25); gap detection writing gap rows; structured `service_events` + quiet-is-healthy warnings stream. **DoD:** scripted failure scenarios (dead socket, silent-while-connected, host suspend) pass on fakes — each test mutation-verified. | 🔶 (nod ruled 2026-09-28; live outage 17:00–17:15 is the motivating specimen) |
| T6 | **Daily completeness + archive + digest (decisions D6).** `@Scheduled` end-of-day job: 1m-bar REST heal (window-clipped, paced, allowance-aware; outcomes classified healed / tickless-at-source / failed — ticks are stream-only, the job never claims otherwise), day's Parquet export to object storage (pick R2 vs B2 in-ticket), digest email (counts, gaps, heal outcomes, archive path). Audited `job_runs` row every run, clean or not. **DoD:** staging produces archive + digest end-to-end; a suppressed digest is detectable (absence = alarm documented in the runbook). | ⬜ |
| T7 | **Deploy.** Dockerfiles + compose; staging + prod configs with separate DBs (promote by release tag); cloud host selection (revisit the phase-1 doc's Lightsail analysis against the ~£20/mo envelope); secrets injection; deploy runbook. **DoD:** staging runs 24/7 for 3 consecutive days unattended with daily digests arriving. | ⬜ |
| T8 | **Acceptance: shadow-diff week vs the Python appliance.** Both systems capture the same DAX sessions for a week; diff tick coverage, 1m bars, and own-aggregated 10m vs the prototype's; write the report; rule on making Tradebench the primary capture. **DoD:** the diff report with every delta explained + Alex's ruling recorded in `docs/decisions.md`. | ⬜ |

## E2 🧰 Board viewer web app — STUB

The first frontend warm-up: a small locally-served React/TS app rendering this markdown board
(and the epics/vision docs) as pages — MD stays the write model for agents, the app is the read
model for humans. **Shape confirmed by Alex 2026-09-27 (D11; PRD open Q#10 resolved).** Low
stakes, real daily value, exercises the frontend toolchain + backend-streaming shape before the
charting UI. **Plan when:** Alex wants a break from E1 or E1-T1 lands the frontend seat.

## E3 🧪 Indicator DSL v0 + backtest spine — DESIGN-GATED

The rest of PRD §11 phase 1: basic chart over stored data → indicator DSL v0 (look-ahead
rejection from day one) → backtest job runner v0 (default FLAT⇄IN_POSITION machine) → first
results page. **Language semantics ruled 2026-09-27**
(`docs/design-sessions/2026-09-27-indicators-and-predicates.md`, decisions D7–D9; PRD Q#6
resolved). Build tickets get cut after the two design gates below land — plus enough
captured/imported data to chew on (E1 + first Databento decisions).

| # | Ticket | Status |
|---|--------|--------|
| T1 | **Vocabulary catalogue v0** (design). Every v0 primitive organised by compute family (recursive / rolling-window / session-anchored / composite / whole-window-refit) with signature, semantics, warm-up, volatility class; carries the unit-awareness typing question (session R8). Claude proposes, Alex rules line by line. **DoD:** catalogue at `docs/design/`; every entry ruled; PRD open Q#5 resolved. | ⬜ |
| T2 | **Acceptance-anchor check** (off-repo — guardrail G1). Walk Pattern 1 (Dad's answered sheet) and the range-bar consolidated spec against the R1–R8 semantics + the T1 catalogue. **DoD:** verdict + any semantic gaps recorded in a session record (no strategy content committed); gaps fed back into catalogue/decisions. | ⬜ (needs T1 first) |

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

- E3 design gates: vocabulary catalogue v0 (E3-T1) → off-repo acceptance-anchor check (E3-T2).
- Databento initial purchase scoping (≥2yr DAX+NASDAQ ticks — PRD §7) — needed by E3, not E1.
- PRD open questions #7–#11 — none block E0/E1. (#6 and #10 resolved 2026-09-27; #5 = E3-T1;
  #4 half-resolved: Gradle confirmed in practice (D12), tick-lake storage format still open.)

## Done history

- **2026-09-27 — Sanity lap** (menu ①): cold-start playback proved the handoff self-sufficient;
  no contradictions found.
- **2026-09-27 — Indicators & predicates design session** (menu ②): answered record filed
  (`docs/design-sessions/2026-09-27-indicators-and-predicates.md`); rulings R1–R8; decisions
  D7–D9; PRD open Q#6 resolved; spawned E3-T1/T2. Evidence: commit `b0387d0`.
- **2026-09-27 — E0-T1/T2/T3 + board/epics/docs pass**: AI operating framework v1 (contract,
  2 agents, 3 skills, directory standard), CLAUDE.md/task-workflow wiring, `docs/design/`
  seat, this board structure (E0 born, E3 design-gate tickets, horizon epics named).
- **2026-09-27 — E1-T1 Foundations** ✅: Gradle 9.8 multi-module skeleton (`core` /
  `ig-client` / `market-data-service`, Java 25 LTS toolchain, `dev.amfshr.tradebench`), CI
  green on mutation-verified walking skeletons, branch protection active (required check
  `build`, admin bypass for docs-only). First `/start-ticket` + `doctrine-reviewer` run in
  anger. Foundation choices logged as D12. Evidence: PR #1 (merged).
- **2026-09-28 — E1-T4 Persistence** ✅: Flyway V1 (user+source on every fact row, _utc
  naming, name-anchored seeds), store.PostgresStore behind the CaptureStore seam
  (ack-after-apply end-to-end), dedicated-session advisory lock (review F1: pooled-
  connection lock was a masked defect), schema drift gate (self-mutation-verified),
  13 mutations killed, D17 topology + compose-managed loopback-only dev Postgres, app/
  ingest/store restructure (Alex's Q1–Q4), db-admin agent. Evidence: PR #5 (merged) +
  live DAX rows in market_data. Deferred by ruling: D22 roles + schema/public design → T6.
- **2026-09-28 — E1-T3 Streaming + capture pipeline** ✅: core domain/time SPIs (D15 mid
  discipline, D38 dataTime), StreamTransport seam + Lightstreamer wrapper (capability-split
  subscriptions), capture queues/pump/JSONL runner with honest heartbeat; 30 mutations
  killed; doctrine-review F1–F5 fixed; trading-ig sweep adoptions; D14/D15/D16 landed
  en route; proven against live Sunday data (500+ ticks, 4 bars, clean counters). Evidence:
  PR #4 (merged) + capture JSONL. Event-stream SPI parked to E3 (Alex's ruling).
- **2026-09-27 — E1-T2 ig-client session + REST core** ✅: §1.6 taxonomy, login→switch→token
  re-read, validate-before-relogin + 61s stagger, sliding-window pacer in the library, REST
  v3 markets/prices keyed on snapshotTimeUTC (fail-loud, never guess — Alex's ruling: client
  throws, T6's healer classifies), injectable timeouts, exact BigDecimals. 15 mutations
  killed; doctrine-review F1–F5 fixed; wire fixtures cross-checked against the prototype's
  scraped labs.ig.com reference (now at `docs/reference/`) + field-proven backfill code.
  D13 (design nod) born from this ticket's feedback; architecture.md v1 seeded. Evidence:
  PR #2 (merged).
