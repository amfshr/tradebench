# Grill Session 5 — Execution Residual + Platform & Ways of Working

> **What this is.** The answered record of the fifth and final product grill session with Alex
> (2026-09-24). Companions: sessions 1–4 in this folder. Much of "execution & risk" was already
> settled in earlier sessions (internal trade management S2-J3, kill switches S2-J7,
> notifications S2-J8, immutability doctrine S3-Q5); this session closed the residuals and the
> platform/ways-of-working area.

**Status: CLOSED — grill series complete.** Two items are Claude recommendations Alex asked for
and has not yet formally confirmed (marked ⚖️ below): monorepo-including-frontend, and the
board/task-system shape.

---

## Q1 — Broker port + second adapter: CONFIRMED

"Yes ports, and we will make an IG client, and a broker simulator." The **simulator is the
port's second adapter** (and doubles as the backtest/paper fill engine), which keeps the
abstraction honest from day one. No real second broker on the horizon — the simulator fills that
structural role.

## Q2 — The runway to live: CONFIRMED

**backtest → paper bot (live stream, simulated fills) → IG demo bot → IG live bot** — "that is
the correct order." Every rung available, none mandatory (per the S3 doctrine: surface
indicators, never gate — going live earlier is the user's choice).

## Q3 — Sizing v1: SIMPLE

Fixed stake per bot config is v1. **Money management (sizing models) comes only after the
backtesting and charting-exploration features are fully fledged** — it is explicitly sequenced
behind them.

## Q4 — Conflicting bots: HARD PREVENT

"No two live registered bots can be configured to trade on the same broker/market combination."
A platform-enforced invariant, not a warning.

## Q5 — Frontend: React/TypeScript SPA

"(a) yes absolutely — if I want a frontend platform this is what is required."

- Architectural instinct (to be designed): **the backend does most if not all of the work and
  streams data to the frontend for visuals** — a thin visual client over a rich backend.
- **Open-source charting library in the frontend: yes** (e.g. TradingView Lightweight Charts;
  range bars will need custom handling regardless).

## Q6 — Backend stack, repo topology, messaging

- **Monorepo for all things Java** — confirmed. **Frontend: Alex was leaning separate repo but
  asked for a recommendation.**
  - ✅ **CONFIRMED by Alex 2026-09-25: ONE monorepo including the frontend** (repo
    `github.com/amfshr/tradebench`, public, Alex's own account). Original recommendation: Rationale:
    solo dev + AI-assisted workflow gains hugely from single-checkout context (agents see API
    contracts and their frontend consumers together); changes cross the API boundary atomically
    in one PR; the frontend has no independent release cadence — it versions with the platform;
    the frontend keeps its own toolchain (pnpm/vite) in its own directory, untouched by Gradle;
    one public repo also tells the whole portfolio story. Extraction later is cheap if it ever
    earns a life of its own.
- **Gradle:** "not sure but maybe" → provisional carry (it's the phase-1 doc's standing plan and
  the sensible multi-module default); confirm at design phase.
- **Messaging: Kafka is on Alex's mind** — open to moving off Redis, happy to keep it too. →
  Formally an **open design decision**. Claude's stated position: Redis Streams as the v1
  default (proved itself in the prototype; tiny ops/cost footprint fits £20/mo and
  minimal-maintenance; Kafka is heavy for a 3-user deployment), with event contracts well-schema'd
  so a later Kafka migration is mechanical rather than building a speculative abstraction layer.

## Q7 — Environments: SEPARATION FROM THE GET-GO

- **Staging env + prod env**, **separate DBs**, from day one.
- **Promote by release tag** — build and deploy from tags.
- **Docker, or Kubernetes depending on the number of services / whether kube is needed.**

## Q8 — Ways of working

- **No direct-to-main unless the PR contains no code changes** (documentation-only). Code/config
  always goes through PR. Public repo, branch protection, CI from the first commit.
- **Task system: open, with a strong instinct.** Considering GH Issues / kanban, but drawn to
  **a custom locally-served web app rendering tickets/epics** — his reasoning, near-verbatim:
  "this project will carry a lot of vision… I never really edit the files [myself] and instruct
  via AI agents, so that could realistically work — reading MD in the IDE is getting a bit
  annoying/old… having a custom-built set of pages may be worth the slight additional time."
  Possibly also linking GH Issues via access tokens into those pages. "Honestly open."
  - ⚖️ **Recommendation (pending Alex's nod): markdown board stays the canonical WRITE model
    (AI agents edit it, git versions it — the pattern proven in the prototype repo); the custom
    web app is the human READ model, rendering the same markdown.** No sync problem because MD
    remains the source of truth. Bonus: the board-viewer app is the perfect first frontend
    warm-up project — low stakes, real daily value, exercises the React/TS + backend-streaming
    toolchain before the high-stakes charting UI. GH Issues integration only if/when outside
    collaborators arrive.

---

## Session-series close-out

All five grill sessions are CLOSED. Deliverables generated from them (2026-09-24):
`../product-requirements.md`, `../prototype-engineering-playbook.md`, `../decisions-triage.md`,
`../seed-pack.md`, plus the workspace README reframe. The spawned design-phase session
(indicators & predicates) remains scheduled.
