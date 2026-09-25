# Tradebench Board

> The single source of truth for work. GitHub is for PRs only — never issues (until outside
> collaborators exist). Ways of working: `.claude/task-workflow.md`. Legend:
> ⬜ queued · 🔶 in progress · ✅ done · 🧊 iced/blocked. Tickets carry a DoD; ideation that
> hasn't earned a ticket lives in `docs/` or the inherited ideas, not here.

**Guide (2026-09-25):** Repo just born. E1 is the active epic — the deploy-first ruling
(decisions D2) says it ships to the cloud before anything else exists. E2/E3 are stubs awaiting
their own planning sessions (E3 waits on the indicators-and-predicates design session).

**Next-session menu (set 2026-09-25 evening, Alex's pick-up list):**
① *Sanity lap* — Claude reads CLAUDE.md → PRD → this board and plays back the project state
(proves the handoff is self-sufficient). ② *Indicators & predicates design session* — the
deep-dive Alex requested (gates E3; anchor to Pattern 1 + the range-bar model; file the
answered record under `docs/design-sessions/`). ③ *Start E1-T1* — Gradle skeleton + CI +
branch protection; first real code, independent of ②. ④ *Page designs* (optional, `design/`) —
workbench layout, results page, or the E2 board viewer. Nothing else blocks: Databento scoping
and PRD open Qs #7/#11 are needed by E3-era work, not now.

---

## E1 📡 Collection service, deployed 24/7 — ACTIVE

**Mission:** Tradebench's first deployed artifact: a standalone Spring Boot service streaming
IG **ticks + 1-minute bars** for DAX into Postgres, resilient and self-reporting, with the
daily **heal → Parquet-archive → digest-email** cycle — running 24/7 in the cloud so that from
its first day no streaming opportunity is missed. Data model carries **user + source dimensions
from day one** (PRD §7). Acceptance for the epic = T8's shadow-diff verdict.

**References:** PRD §7/§11 · `docs/inherited/ig-broker-playbook.md` (§1–§4 are the porting
spec) · `docs/inherited/design/phase1-market-data-build-plan.md` (module map + porting order;
its deploy framing is superseded by S5 staging/prod decisions) ·
`docs/inherited/data-platform-design.md` (capture the finest atom, derive the rest;
CHART:1MINUTE sealed bars only) · engineering playbook §1–§4.

| # | Ticket | Status |
|---|--------|--------|
| T1 | **Foundations.** Gradle multi-module skeleton (`ig-client`, `market-data-service`, shared `core`; `frontend/` seat reserved, empty), CI on PR (build + test), `.editorconfig`, README badge. Enable branch protection on `main` once the CI check exists (required check; admin bypass stays available for docs-only pushes). **DoD:** green CI on a walking-skeleton test in each module; protection active. | ⬜ |
| T2 | **`ig-client`: session + REST core behind the provider port.** REST login/refresh (demo + live envs), typed config (no code defaults; secrets via env beside config — never committed), request pacing guard (IG session rate limits are real — playbook + engineering playbook §4.6), typed errors. **DoD:** unit suite on fakes; one integration-gated smoke that logs into demo. | ⬜ |
| T3 | **Streaming: ticks + sealed 1m bars.** Lightstreamer Java SDK: PRICE (ticks) + CHART:1MINUTE (accept `CONS_END=1` only) per market; cheap-work-only on callback threads, everything queued to one consumer; per-market subscriptions (identity-vs-addressing per triage D16). **DoD:** a live demo session captures a full DAX day of ticks + 1m bars locally. | ⬜ |
| T4 | **Persistence.** Flyway from birth: `instruments`, `ticks`, `bars_1m` (user + source columns from day one), `service_events`, `job_runs`; least-privilege roles (triage D22); testcontainers suite; generated schema render + CI drift gate (triage D23). **DoD:** captured data lands via the real write path; drift gate green. | ⬜ |
| T5 | **Resilience belt.** Reconnect stack; staleness watchdog on injectable clocks — should-be-ticking derived from the stream itself, monotonic/awake vs wall time split, escalation ladder (triage D25); gap detection writing gap rows; structured `service_events` + quiet-is-healthy warnings stream. **DoD:** scripted failure scenarios (dead socket, silent-while-connected, host suspend) pass on fakes — each test mutation-verified. | ⬜ |
| T6 | **Daily completeness + archive + digest (decisions D6).** `@Scheduled` end-of-day job: 1m-bar REST heal (window-clipped, paced, allowance-aware; outcomes classified healed / tickless-at-source / failed — ticks are stream-only, the job never claims otherwise), day's Parquet export to object storage (pick R2 vs B2 in-ticket), digest email (counts, gaps, heal outcomes, archive path). Audited `job_runs` row every run, clean or not. **DoD:** staging produces archive + digest end-to-end; a suppressed digest is detectable (absence = alarm documented in the runbook). | ⬜ |
| T7 | **Deploy.** Dockerfiles + compose; staging + prod configs with separate DBs (promote by release tag); cloud host selection (revisit the phase-1 doc's Lightsail analysis against the ~£20/mo envelope); secrets injection; deploy runbook. **DoD:** staging runs 24/7 for 3 consecutive days unattended with daily digests arriving. | ⬜ |
| T8 | **Acceptance: shadow-diff week vs the Python appliance.** Both systems capture the same DAX sessions for a week; diff tick coverage, 1m bars, and own-aggregated 10m vs the prototype's; write the report; rule on making Tradebench the primary capture. **DoD:** the diff report with every delta explained + Alex's ruling recorded in `docs/decisions.md`. | ⬜ |

## E2 🧰 Board viewer web app — STUB

The first frontend warm-up: a small locally-served React/TS app rendering this markdown board
(and the epics/vision docs) as pages — MD stays the write model for agents, the app is the read
model for humans (grill S5-Q8, PRD §10 ⚖️ pending Alex's formal nod). Low stakes, real daily
value, exercises the frontend toolchain + backend-streaming shape before the charting UI.
**Plan when:** Alex wants a break from E1 or E1-T1 lands the frontend seat.

## E3 🧪 Indicator DSL v0 + backtest spine — STUB

The rest of PRD §11 phase 1: basic chart over stored data → indicator DSL v0 (look-ahead
rejection from day one) → backtest job runner v0 (default FLAT⇄IN_POSITION machine) → first
results page. **Blocked by:** the indicators-and-predicates design session (PRD open Q #5/#6 —
Alex requested it; next session's agenda) and enough captured/imported data to chew on (E1 +
first Databento decisions, PRD open Q on scope of the initial purchase).

---

## Waits / externals

- Indicators-and-predicates grill/design session (Alex + Claude) — gates E3 planning.
- Databento initial purchase scoping (≥2yr DAX+NASDAQ ticks — PRD §7) — needed by E3, not E1.
- PRD open questions #7–#11 — none block E1.

## Done history

*(empty — repo born 2026-09-25)*
