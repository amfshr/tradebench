# Decisions Triage — `docs/decisions.md` → the Java platform

> **What this is.** A verdict on every decision in the prototype's decision log
> ([`docs/decisions.md`](../../docs/decisions.md), D1–D46; the log contains no D32), triaged
> 2026-09-24 for the greenfield Java platform. **How to use it in the new repo:** the *Carry*
> group seeds the platform's own decision log (re-recorded there in the platform's terms, each
> citing its prototype ancestor); the *Carry-as-lesson* group informs designs without binding
> mechanisms; the *Re-decide* group is a checklist of design-phase questions with their
> reopening factors named; the *Retire* group needs no action but records what each episode
> taught. The prototype log remains the authority on each decision's full original rationale.
>
> Verdict rubric: **Carry** = adopt the principle essentially as-is · **Carry as lesson** = the
> *why* transfers, the mechanism will differ · **Re-decide** = right question, new context may
> change the answer · **Retire** = prototype-specific, moot, or superseded.

---

## Carry
- **D1 — Clean rebuild, not an extension**: Rebuild for structure, migrate concepts, keep no obligation to old code → this is literally the Java platform's founding move, repeated one level up; the prototype itself is now the "concepts, not code" donor.
- **D3 — Business logic in the app; DB downstream of decisions**: All aggregation/indicators/rules run on in-memory state; Postgres is record + reporting, never a compute step in the trade loop → language changes, principle doesn't; it's the platform's spine.
- **D6 — Streaming decoupled from trading via runtime switch**: Data capture always runs; trading is opt-in → maps directly to the non-negotiable standalone collection service + live-trading-as-last-rung.
- **D7 — OMS: status in memory, DB audit-only, reconcile on startup**: In-memory source of truth, broker authoritative for open positions, startup reconciliation → the execution-service pattern transfers whole.
- **D10 — Swappability where it pays**: Abstract only where a second implementation is real → the new broker port with IG + mandatory simulator adapters is this decision executed properly; Spring makes it native.
- **D16 — Identity vs addressing; per-market streams; payload tripwires**: Stable slug keys vs mutable broker epics, per-market streams for independent replay, consumers assert epic before acting → carries as-is; new dimensions (source, user) extend the scheme, they don't replace it.
- **D21 — Heal data at rest; "too late" applies to executed decisions, not data**: Bounded REST backfill of provably-lost bars; the series always heals in place, only actions taken are immutable → core doctrine for a collection service meant to run for years.
- **D23 — Generated schema render beside migrations**: Executable truth (migrations) + readable truth (generated dump) + CI drift gate → works identically over Flyway; keep the both-and.
- **D25 — Staleness watchdog**: Should-be-ticking derived from the stream itself (no calendars), split tick/bar silence signals, surgical-first remedy ladder, exponential storm guard, never needs a human → near-verbatim requirements for the Java IG adapter in an always-on service.
- **D26 — Quarantine by witness rule; fail-closed per-market flags**: One bad market costs that market; witness evidence decides blast radius; enabled ⇔ exactly "1", absent = off, ANDed with a global kill → directly prefigures per-bot + global kill switches, fail-closed.
- **D28 — Consumer contract: ack-after-apply, keyed dedupe, gaps are information**: At-least-once + idempotent keyed apply, poison acked-and-dropped, identity tripwire fatal → transport-agnostic; holds verbatim whether Redis Streams or Kafka wins.
- **D33 — Strategies are data; look-ahead dies at compile time**: Formal expression language, per-name availability registry, load-time look-ahead rejection with teaching errors, generated violation test matrix → the settled DSL facts adopt exactly this core; distribution changes, doctrine doesn't.
- **D38 — Two contracts (bar record vs tick moment); orders hold numbers; events tell the whole story**: Stale pre-crash ticks never drive decisions; levels resolve to numbers at placement; full rule-trace audit, fire-and-forget; restart honesty; decisions on data time → engine doctrine, transfers whole.
- **D42 — True exit in the engine; broker holds a clamped failsafe**: Watch the model's real level tick-by-tick, rest the placeable second-best at IG → this IS the internal-trade-management execution doctrine the new platform names as settled (PRD P4).
- **D45 — OMS execution contract, fail-closed everywhere**: Unstated stake can't trade, exposure-growing verbs gated but cancel/close always work, unknown deals stand aside, stale requests discarded, adopt only broker-confirmed facts, key-columns + raw-payload audit, one id threading end-to-end → a dense keeper set for the new execution layer.
- **D46 — Constraint failsafes are a free, model-driven runtime layer**: Rest the nearest-placeable order, self-monitor the true level only when the constraint bit; the DSL says WHAT, the runtime owns HOW it survives broker rules and outages → the named north-star; carries with its hard-won empirical footnote (IG measures placement minimums from the current market).

## Carry as lesson
- **D4 — SQL triggers as verification oracle**: Run an independent parallel computation and diff to the decimal until trusted → the trigger oracle is dead; the pattern reincarnates as IG's 1m bars continuously verifying own tick→bar aggregation.
- **D5 — Three separate services with a durable stream boundary**: Independent processes, each restartable alone → the decomposition principle carries; the actual service map (collection, backtest jobs, web API, bots) will be drawn fresh.
- **D14 — CI gates merges; rules by convention where enforcement is unavailable**: A red build must block a merge somewhere → carries as posture; the Java/TS toolchain and monorepo will use different checks, and branch protection is now actually available (public repo).
- **D15 — Topology per natural scope; isolation, not speed**: IG's 40-subs/connection cap and one-connection-per-key are broker facts to keep; engine-per-market was isolation-driven, never throughput → per-bot isolation reasoning transfers, topology redrawn.
- **D19 — Plain-SQL, forward-only, versioned migrations**: One transaction per file, tracked, contiguous; plain SQL is what the DBA reviews → the discipline carries; the hand-rolled runner is replaced by Flyway (the prototype called itself "Flyway-in-the-jar, for us").
- **D20 — No code defaults; packaged defaults + local overlay + env; fail naming the key**: Missing required config stops startup readably → Spring profiles/relaxed-binding provide the mechanism natively; keep the no-silent-defaults and committed-inventory disciplines.
- **D22 — Schema from birth, least-privilege roles**: Dedicated schema, DML-only service role, read-only role, admin DDL identity → roles/schema hygiene carries; Dad's in-DB derived/indicator layer does not — indicators belong to the DSL now.
- **D24 — One mechanism for schema truth**: No side-channel provisioning; everything is a migration; scale/config changes land in one PR → the one-source-of-truth lesson carries regardless of what the physical layout becomes.
- **D27 — Versioned-artifact deploys from tags; CI integration tier + drift gate on pure migrated state**: Carries as shape (containers promoted by release tag, testcontainers, drift check ordering); uv-tool/launchd mechanics die with the platforms.
- **D29 — The service knows its window; completeness jobs write rows even when clean**: Belt-and-braces self-guarding, retry-once on first-call-after-idle, "absence of the row means the job didn't run" → doctrines carry; macOS sleep/caffeinate specifics die on Linux.
- **D30 — Instance identity is a legible name; tests get throwaway infra**: Required namespacing so instances can't collide by accident; a test run must be structurally unable to clobber a real environment → carries as staging/prod DB separation + testcontainers.
- **D31 — Secrets split from config**: Config safe to paste/diff; secrets small and radioactive → the split carries; the vehicle becomes container/orchestrator secret injection, and a multi-user platform likely justifies the secret manager the prototype parked.
- **D34 — The P3 kernel locks; hierarchy of truth**: Answered Q&A sheets beat recovered old code as model authority; the entry archetype is a working bracket order, the fill is the broker's moment → the calibration-record discipline and the working-order archetype inform the strategy DSL; the specific rules are Dad's strategy content.
- **D35 — Mid judges, sided transacts (S1); failsafe numbers**: All rule evaluation on MID, bid/ask only where the broker transacts → S1 is fill-engine/backtester doctrine to keep; the window values and Dad's limit numbers are per-strategy data now.
- **D36 — Decimal chain, warm-up replays raw data through the live path, complete-buckets-only**: Exact-arithmetic discipline, no second warm-up implementation, heals recompute forward, acceptance by full-history oracle diff → aggregation-engine doctrine for the tick→bar pipeline; the T-SQL rounding quirks stay buried with the port.
- **D37 — Windows are market properties; trade ⊆ stream; conversions in code, never config**: Carries as config truth; the daily start/stop scheduler world dissolves under an always-on Linux collection service, and trading windows become bot/strategy properties.
- **D39 — Calibration = file diff + re-derived replay, never an engine rebuild**: The trail as a declared leg slot, not an engine constant → the property Alex asked for is a DSL design requirement; Pattern 1's ten lines are content, not platform.
- **D40 — Value slots vs condition slots; variations suffix the family**: The `parse_value` seam and never-conflate-variant-results → both feed the new DSL; the naming half is superseded by the stronger rule (any tweak = a new strategy identity, PRD P2).
- **D41 — User strategy layer without a release**: A strategy experiment must not require a PR/release; overrides must be loud; user files get identical validation → the why transfers; the mechanism becomes DB-backed, user-attributed strategies instead of a shadowing directory.
- **D44 — Broker simulation staged; sim as test double at the API boundary**: The sim-behind-the-port shape carries; the staging answer flips — the new platform makes the simulator a mandatory second adapter and the backtest/paper fill engine from day one.

## Re-decide
- **D8 — Redis Streams, not Pub/Sub**: Durable, replayable delivery — never silently lose market events → the requirement stands, but Redis-vs-Kafka is explicitly reopened by Linux containers, multi-service scale, and retain-forever data volumes; Memurai is dead with Windows. (Working default: Redis Streams, per grill S5-Q6.)
- **D9 — Self-contained local stacks; no shared environments**: Right for two developers with no server → reopened by staging + prod environments with separate DBs and a hosted multi-user web platform; local-dev-parity survives, "nothing shared" doesn't.
- **D17 — Table-per-market, hard-line provisioning**: Per-market tables were Dad's-machinery-shaped → reopened by the data-source and user dimensions, indexes-first growth, and the open tick-lake format (partitioning/columnar options); the fail-at-startup "no persistence ⇒ do not run" gate carries regardless.
- **D43 — Intraday-only is permanent**: Flat by session end, always → the gap-risk/independence reasoning is strong, but on a multi-strategy backtest-first platform it becomes a declarable per-strategy/per-bot policy (plausibly still the live-bot default) rather than a global permanent rule.

## Retire
- **D2 — Live only; no backtesting**: Deliberately reversed — the new platform is backtest-first with live as the last rung; the episode taught that demo-as-forward-test alone starves model validation.
- **D11 — Stack: Python 3.11, trading-ig, pydantic-settings**: Wholesale replaced by Java 21/Spring Boot + React/TS; the one durable habit (isolate the broker SDK behind your own client) already lives in D10/D45.
- **D12 — Persistence port with two adapters from the start**: The pyodbc adapter was built, then deleted when D18 landed — the episode's lesson is don't build a second adapter for a backend you hope to retire; the port principle itself is D10's.
- **D13 — Tooling: uv, single package, pytest, NSSM**: Python packaging and NSSM are dead with the platform; the replay-fixture testing habit it seeded survives independently as the frozen-fixture doctrine.
- **D18 — Dad migrates to Postgres; SQL Server retires**: Executed and moot — the new platform starts converged on Postgres; the episode proved converging backends early beats maintaining dialect adapters.

---

**Count check:** 45 decisions found (D1–D31, D33–D46; the log skips D32, which D33 references
once but which was never written) = 16 Carry + 20 Carry-as-lesson + 4 Re-decide + 5 Retire.
