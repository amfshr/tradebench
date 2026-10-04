# Tradebench Decision Log

> One entry per real decision: the choice, the **why**, and a status (Accepted / Provisional /
> Open). Inherited principles are NOT re-recorded here — they live in the PRD (P1–P10) and
> `docs/inherited/decisions-triage.md` (45 prototype decisions triaged carry/lesson/re-decide/
> retire). This log records decisions made in **Tradebench's own context**, starting at D1.

---

## D1 — Inherit by seed pack, not by port — **Accepted** (2026-09-25)

`docs/inherited/` is a read-only snapshot of the prototype's seed pack (PRD, grill records,
playbooks, triage, matured ideas). Tradebench adopts its *lessons and principles*, never its
code. Supersede inherited docs by writing Tradebench docs, not by editing the snapshot.
**Why:** the prototype proved concepts and banked scar tissue; porting Python would import its
structure along with its lessons, which is exactly what the rebuild exists to avoid.

## D2 — Deploy-first: the collection service ships before anything else — **Accepted** (2026-09-25)

The first built and cloud-deployed artifact is the standalone market-data collection service
(IG client + provider port + streaming app + the daily heal→Parquet-archive→digest cycle),
running 24/7. **Why:** data is the only asset the calendar destroys — every unstreamed week is
gone forever (ticks are unrecoverable from IG REST); everything else waits better than data
does. The Python appliance streams in parallel as the shadow-diff oracle. (PRD §7/§11.)

## D3 — One public monorepo, frontend included — **Accepted** (2026-09-25)

`github.com/amfshr/tradebench`: Gradle multi-module Java services + `frontend/` (React/TS, own
toolchain). **Why:** single-checkout context for AI-assisted development, atomic cross-boundary
PRs, one portfolio repo; the frontend has no independent release cadence. Extraction later is
cheap if ever earned. Public = free branch protection + portfolio; the only unpublishables are
credentials and strategy definitions (PRD §9).

## D4 — Messaging: Redis Streams by default; Kafka is a gated re-decision — **Provisional** (2026-09-25)

Inter-service messaging starts on Redis Streams (prototype-proven; fits the ~£20/mo and
minimal-maintenance NFRs). Event contracts are explicitly schema'd so a later Kafka migration is
mechanical. **Re-decide when:** service count, retention needs, or throughput actually demand a
log-structured broker — not before. (Triage: prototype D8.)

## D5 — The name: Tradebench — **Accepted** (2026-09-25)

Not broker-linked (provider identity is abstracted from product identity); lives under
`amfshr.dev` (`tradebench.amfshr.dev`). **Why:** it names the product's centrepiece — the bench
you work at (charts, backtests, the workbench). Collision check done: nearest existing
"TradeBench" products are a manual trading journal and small tools — acceptably sleepy for a
personal product in its own namespace.

## D6 — Daily completeness job is a plain scheduled task, not Spring Batch — **Accepted** (2026-09-25)

The end-of-day cycle (1m-bar REST heal → Parquet export to object storage → digest email) runs
as a Spring `@Scheduled` task writing an audited `job_runs` row on every run, clean or not.
**Why:** Spring Batch's machinery (job repository, chunking, restartability) is for large
multi-step ETL; this is "find gaps, call REST paced, write a file, send an email." The audit row
carries the D29-style doctrine: the *absence* of the row/digest is itself the alarm. Quartz is
the middle step if persistent cron scheduling is ever needed; Batch is not on the path.

## D7 — One DSL grammar; declarative; predicates are boolean indicators — **Accepted** (2026-09-27)

One expression grammar shared by two declaration kinds (`indicator` / `strategy`); the language
is declarative (pure expressions + recursive series definitions — no statements, loops, or
mutation); a predicate is simply a boolean-typed expression, with fitted-shape matching
decomposed into fit-functions returning records plus comparisons. **Why:** one language means
transferable knowledge and one compiler; declarative purity is what makes compile-time
look-ahead rejection (P3) and the batch ≡ step invariant provable rather than aspirational.
Resolves PRD open Q#6. (Design session 2026-09-27.)

## D8 — Compiler/engine shape: EBNF → AST → visitors → dataflow DAG — **Accepted** (ANTLR provisional, 2026-09-27)

Grammar in EBNF, parsed (ANTLR4 — provisional) to our own dumb-node AST (parser-swappable);
compiler passes as visitors (type check, index/look-ahead analysis, warm-up derivation,
volatility marking); lowered to an incremental dataflow DAG whose nodes declare
seed/step/batch, with batch ≡ step byte-identity tested per node family. Teaching-quality
compile errors are a requirement (prototype D33). **Why:** the DAG buys look-ahead rejection,
derived warm-up, workbench fast-fill, and composition by construction — the synthesis of the
industry survey (Pine-family surface on a dataflow engine). (Design session 2026-09-27.)

## D9 — `[0]` is the forming bar; determinism comes from the event clock — **Accepted** (2026-09-27)

Relative index `[0]` denotes the newest bar of the referenced timeframe that has printed data
as-of the evaluation instant — the industry-standard convention both calibrated users expected
— and may therefore be partially formed. Safety is engine-level, not a language ban:
evaluation happens only at deterministic event-clock instants (home-TF seal by default; tick
events in tick-trigger states); forming higher-TF views are always derived from our own finer
atoms by the one shared aggregation (never the broker's forming candle — broker bars stay the
oracle); `closed()` gives explicit sealed-only access; volatile-vs-stable is compiler-marked
and surfaced, never gated (P10); backtests lacking the finer atoms a volatile reference needs
fail loud and named (P9). **Why:** forming data is partial *present* information, not
look-ahead — P3 stands uncompromised (negative indexes remain compile errors); Pine's repaint
disease came from backtests that couldn't reproduce live's samples, which this engine design
removes by construction. (Design session 2026-09-27, ruling by Alex.)

## D10 — AI operating framework: contract, guardrails G1–G8, protocol skills — **Accepted** (2026-09-27)

AI work on Tradebench runs under an explicit contract (`.claude/ai-framework.md`): Alex holds
rulings/merges/money/broker/deploys; AI proposes, implements ticket-scoped work, reviews, and
keeps records — never rules. Eight citable guardrails (public-repo discipline incl. the
strategy-content ban, logged decisions, board as truth, PR-only, testing doctrine, no external
sends, named-target destructive ops, honest reporting); session protocols codified as skills
(`/sanity-lap`, `/design-session`, `/start-ticket`); two project agents (`doctrine-reviewer`,
`seed-pack-librarian`); a standard `.claude/` + `docs/` directory layout. **Why:** AI is the
standing development partner (PRD §9 AI-workable NFR) and Alex's time is bursty — the rules
must hold in every session, however cold, without re-negotiation. Framework changes are
themselves decisions (G2). Enforcement (pre-commit/CI scans, permissions) is E0-T4.

## D11 — Board viewer: markdown is the write model, the web app is the read model — **Accepted** (2026-09-27)

The in-repo markdown board (`.claude/tasks/board.md`) stays the canonical **write model** —
agents edit it, git versions it. E2's locally-served React/TS app renders it (plus
epics/vision docs) **read-only** for humans, as the first frontend warm-up project. GitHub
Issues get linked only if outside collaborators ever arrive. **Why:** agent-editable planning
artifacts are a PRD §9 NFR (AI-workable codebase); a read-only render adds daily human value
without forking the source of truth. Resolves PRD open Q#10 (grill S5-Q8's ⚖️ recommendation,
confirmed by Alex).

## D12 — Foundation choices at first code: Java 25 LTS, `dev.amfshr.tradebench`, Gradle Kotlin DSL — **Accepted** (2026-09-27)

E1-T1 (PR #1) fixed three durable choices. **Java 25 LTS toolchain** — Alex's ruling: start on
the current LTS rather than last cycle's; supersedes the phase-1 build plan's "Java 21 LTS"
(written before Java 25 had a year of maturity); CLAUDE.md's "Java 21+" convention is
unchanged, 25 satisfies it. **Package root `dev.amfshr.tradebench`** — derived from the
product's own domain (`tradebench.amfshr.dev`, D5); the plan's `com.amfshr.trading.*` was a
pre-Tradebench placeholder. **Gradle multi-module, Kotlin DSL, version catalog,
wrapper-pinned (9.8.0)** — the PRD §10 ⚖️ "Gradle confirm at design" is hereby confirmed in
practice (half of PRD open Q#4; tick-lake storage format remains open). **Why:** a greenfield
platform with years of runway should not plan a mid-life JDK bump; domain-derived packages
match product identity; the wrapper pins every machine and CI to one Gradle.

## D13 — Design nod before implementation on substantial tickets — **Accepted** (2026-09-27)

`/start-ticket` gains a mandatory step: before implementation code is written on any
substantial ticket, AI posts the intended design (modules/packages, key interfaces and
injection seams, in-ticket decisions with recommendations) and waits for Alex's nod or
amendments. Trivial/mechanical tickets may skip, saying so. **Why:** E1-T2 landed
fully-formed and Alex's ruling is that "Claude proposes, Alex rules" applies to code design,
not just product design — the say happens before the code exists, not in review after.
(Framework change per G2.)

## D14 — Null-safety: JSpecify, `@NullMarked` packages from birth — **Accepted** (2026-09-27)

Every production package carries `@NullMarked` in its `package-info.java` from the day it
is born: non-null is the default, `@Nullable` marks genuine domain absence, and
"unspecified" third-party returns are normalised at the boundary. `org.jspecify:jspecify`
rides as an `api` dependency. Tests stay unannotated. **Why:** JSpecify is the standard the
ecosystem converged on; marking the default inverts the annotation burden onto the rare
nullable spots where reader attention belongs — and the adoption pass immediately proved
its worth by surfacing that `PricePoint.bid/ask` were silently nullable (fixed fail-loud,
consistent with the no-guessing ruling). Convention detail: `docs/design/tech-notes.md` §3.

## D15 — Aggregation ruling: healed 1m is the canonical base; ticks are precision; own tick→1m is the oracle — **Accepted** (2026-09-28, Alex)

(a) **Every derived timeframe (10m, 1h, …) aggregates from the healed sealed-1m record** in
one shared implementation — completeness beats fineness for chart/backtest series, and only
the 1m record is healable (ticks are unrecoverable from IG REST by provider design).
(b) **Ticks remain the finest stored truth** — tick-precise triggers (the ARMED pattern),
the forming bar's final partial minute, research — the precision path, never the
completeness path. (c) **Own tick→1m aggregation runs continuously as a verification
oracle** against IG's streamed 1m (PRD §7's verification job, the prototype's oracle
pattern) — a data-quality tripwire that also exposes silently-degraded tick streams; never
the record. (d) **Derivation rule pinned for oracle compatibility:** per-field mid is
computed at 1m and higher timeframes aggregate the 1m *mid* series — aggregating bid/ask
upward and then midding yields different highs/lows whenever the bid-max and ask-max land
in different minutes, which would break T8's shadow-diff against the prototype's proven
formula. Bid+ask OHLC is what is *stored* at 1m (T3 Q1 ruling), so nothing is destroyed
and other derivations stay possible later, clearly labelled.

## D16 — Secrets & config: env-only apps, env-file injection, one key per service — **Accepted** (2026-09-28)

Apps read plain environment variables only; injectors vary by context (local `.env` +
loader script/direnv; deployed `/etc/tradebench/<service>.env` 0600 via compose
`env_file:`; CI carries no broker secrets ever; optional 1Password `op run` locally).
`TRADEBENCH_IG_ENV=demo|live` pins an instance and selects the `IG_DEMO_*`/`IG_LIVE_*`
credential set so environment and credentials can never mix — and it is deliberately
independent of the application's own staging/prod axis (pairings are convention, explicit
per instance). **Corrected same day (Alex):** IG issues ONE key per account (playbook §6
empirical; the per-service-keys draft followed the scraped reference and is retracted) —
all processes share the account key, isolated by the login stagger, per-service
single-instance guards, and connection-count discipline.
Multi-user era: platform users' broker credentials are data (app-encrypted DB columns,
master key from env), never env-file entries. Full design:
`docs/design/secrets-and-config.md`. **Why:** the injector is the only part that varies
across the platform's growth (more services, staging/prod, other users) — freezing the
app-side contract as "plain env" makes every later move mechanical; the playbook's leak
scars (§1.7) drive the tiny-radioactive-file shape and the never-in-CI rule.

## D17 — Database topology: one instance per environment, one database per service; the market-data DB is a published read-only data product — **Accepted** (2026-09-28)

One Postgres **instance** per environment (compose-managed: local dev, the Mac soak,
staging, prod on-box — never RDS-per-service; £20/mo NFR). Inside it, **one database per
service** (`market_data` now; the OMS gets its own later): plain Postgres cannot query
across databases, so "never reach into another service's tables" is enforced by the
engine, not discipline, and per-service credentials scope naturally (D22). **The
exception that makes this a data platform:** the `market_data` database is additionally a
**published data product** — its schema is a versioned contract (the drift gate already
pins it), and other services may hold **read-only** credentials to it for bulk reads
(backtester, charts) that would be absurd over an API; writes remain the collection
service's alone, and everything that isn't the shared data product integrates via events
(D4). One compose file grows from today's dev Postgres into the T7 deploy stack.
**Why:** database-per-service orthodoxy exists to prevent shared-table coupling — we keep
that protection where it matters (operational state) while admitting honestly that the
capture record IS the platform's shared asset, and treating its schema as an API with a
CI-enforced contract is stronger than pretending consumers won't need it.

## D18 — Web naming: one docs site; `web/` workspace; apps named for their domains — **Accepted** (2026-09-28, Alex)

All static documentation is ONE site — `web/docs`, served at `docs.tradebench.amfshr.dev`
(public; the Cloudflare-gated platform SPA at `tradebench.amfshr.dev` links to it): product
docs, the Field Manual, architecture, decisions, the board section, and later the DSL reference and
REST API/integration docs. No second docs site until something real forces it (P7). The
workspace root is `web/` (supersedes `frontend/` — D3's monorepo ruling unchanged), and web
apps are named for the subdomain they serve: `web/docs` live, `web/platform` seat reserved.
**Why:** one static build, one deploy, one place to look; dir↔domain symmetry keeps naming
honest; "frontend" names a layer, `web/` names the artifact family.

## D19 — Brand & visual identity: Quiet Terminal — **Accepted** (2026-09-29, Alex)

Ruled via the 2026-09-29 design session (R1–R11): dark-first Quiet Terminal direction;
token palette (brand.md) with blue accent; Inter + JetBrains Mono; `tradebench▊` wordmark;
two-candles mark; amber attention used at most once per screen; scar callouts with mono
spine and Lesson line. **Standing doctrine from R8: green/red exist only as the market's
up/down + P&L pair — strategy elements use the brand accent; the market and the strategy
never share a colour** (binds all chart UI, E4+). Evidence: Alex's design-tool exploration
(self-documenting decision log, archived under docs/design/research/). Spec:
`docs/design/brand.md`. **Why:** with red/green reserved for market semantics, attention
must be amber, and a brass/amber *brand* would collide with it — restraint that leaves
data loudest wins on the product's own logic.

## D20 — Board & ticket format: a lean template, maintained by an agent — **Accepted** (2026-09-30, Alex)

Tickets and epics follow a formal but lean markdown structure (`docs/reference/board-format.md`)
so the docs site can render them as board/epic/ticket pages: epics carry
`**Since** · **Decisions** · **Manual**`; tickets carry an optional per-section metadata line
`**Type** · **Branch** · **Started** · **Blocked by**` in their epic plan file. The
**`board-steward` agent** (E0-T5) owns authoring and keeping this truthful. Field set is
deliberately **lean** — information that serves a decision (what kind of work, which branch,
when it started, what blocks it), not data for its own sake (Alex); new fields are proposed,
not invented. Existing tickets are retrofitted over time; the site renders whatever is
present, degrading gracefully. **Why:** the write model must carry exactly the structure the
read model needs and no more — and an agent enforcing one template beats hand-kept drift.

## D21 — Docs split by audience: the Field Manual (build) vs the User Guide (use) — **Accepted** (2026-09-30, Alex)

The docs site serves two distinct audiences and must not conflate them. **Build track**
(builder/contributor-facing): the engineering internals — how the app works and why, with
scars — renamed from "the book" to **The Field Manual** (`docs/field-manual/`), alongside
Architecture and Decisions. **Use track** (end-user-facing: Dad, brother): the Product
overview, a future **User Guide** (`docs/guide/`, reserved seat), and the **DSL /
strategy-language reference** — task-oriented, assuming no knowledge of Java, buffers, or
threads. The DSL explainer belongs in the Use track, never the Field Manual. Site nav groups
by these two tracks once the Use track has real content (E3/E4 era). **Why:** a single "book"
serves neither reader — an end user hitting "ack-after-apply" is lost, a builder wading
through "click New Bot" is annoyed; audience is the right top-level axis for docs.

## D22 — Within the Build track: Field Manual teaches, Architecture specs — **Accepted** (2026-09-30, Alex)

The two Build-track docs have distinct jobs and grow differently. **The Field Manual** is
*narrative teaching* — how it works and why, with scars, read front-to-back; it grows by
adding chapters (and sections within a chapter). **Architecture** is *spec + reference* —
the system shape (`architecture/README.md`) plus a **component reference**
(`architecture/components/<module>.md`), one page per built module, read by lookup; it grows
a sub-tree as modules are added. Detailed per-component technical docs live in the component
reference, never the Field Manual; the two cross-link. Rule of thumb: a plot and a scar →
Field Manual; something you'd look up → Architecture. Extends D21. The docs site's nested
sidebar (E2-T6) renders these sub-trees. **Why:** teaching and reference are different reading
modes — conflating them (a spec buried in a story, or a story you can't look things up in)
serves neither; separating them lets each grow in its own shape.

## D23 — Licence: PolyForm Noncommercial 1.0.0 (source-available, commercial rights reserved) — **Accepted** (2026-09-30, Alex)

The public repo carries the **PolyForm Noncommercial License 1.0.0** (`LICENSE.md`), © 2026
Alexander Fisher. Anyone may read, run, and adapt the code for **non-commercial** purposes
(study, research, hobby); **all commercial rights are reserved** — no productizing, no
deploying as a service, no competing use without written permission. The repo is public for
portfolio and transparency (D3), not as an open-source grant. **Why:** the product is owned by
one person who may monetise it ("beer money", PRD §3) and the PRD forbids productizing for
strangers (§12), so a permissive OSS licence is wrong; but strict all-rights-reserved would
waste the public repo's learning value. PolyForm Noncommercial is a real, lawyer-drafted
licence that lets learners genuinely use the code while keeping every commercial right —
the honest fit for a public craft repo. Considered and rejected: MIT/Apache (gives away
commercial use), bespoke all-rights-reserved (needlessly strict + hand-rolled wording).

## D24 — Operator console: a read-model surface, edge-gated; mTLS reserved for machines — **Accepted** (2026-09-30, Alex)

The deployed collection service is observed and its data retrieved **without SSH** by a
**separate read-model app** — a static SPA + a thin (Spring Boot) read-only service over the
`market_data` data product (D17 read-only creds) + R2 object storage (pre-signed download
links) — **never by an inbound surface on the collector**, which stays outbound-only and
single-purpose (P7) and merely *produces* everything observable into its own DB
(`service_events` v2, status/heartbeat rows, gap rows) and R2. **Human access is edge-gated by
Cloudflare Tunnel + Access:** `cloudflared` dials out (zero inbound ports on the box — the
inherited outbound-only posture survives), Cloudflare authenticates per person (email/SSO, free
< 50 users) at the edge. **mTLS/service-tokens are reserved for future machine clients**
(Access can enforce client certs there). **v1 is read-only** (observe + download); control
actions are deferred to the event/command plane, never REST-into-the-collector. Hosting: a
single small VPS (**Hetzner** leading, ~€4–7/mo) running docker-compose (collector + Postgres +
`cloudflared` + nightly `pg_dump → R2`); owned-hardware migration deferred and trivial (compose
is portable); the **formal host ruling lands in E1-T7**. **Why:** it delivers no-SSH
observability while *preserving* the outbound-only posture the seed pack mandated; matches the
D11 read-model and D17 data-product patterns; and is the lowest-toil, best-auth option
(per-person identity, instant revoke, zero install) for a three-person tool. **Considered and
reserved:** mTLS + a baked-cert Electron app — set aside for humans because it is
"possession = access" (extractable from the build), can't revoke one person without rotating
all, and drags a signed-desktop-app pipeline onto non-technical family, all to avoid a free
edge that also seals the box; it remains the right tool for machine consumers. **Spawns epic
E9** (Operator Console / Market Data Manager v0); its **T1** designs the event/metric/data
model, which feeds E1-T5 slice B before the event log is built. (Design session
`docs/design/sessions/2026-09-30-collection-service-operability.md`.)

## D25 — Observability & data model: `service_events` v2, `capture_status`, `bar_gaps`, `archives`; metrics/Grafana split; generous window + expected calendar — **Accepted** (2026-09-30, Alex)

The collection service's self-record and the Operator Console's read model, ruled at E9-T1
(spec: `docs/design/observability-and-data-model.md`, rulings R1–R7). **`service_events` v2**
supersedes the V1 first pass: user/source/instrument dimensions, a stored `category`, `severity`
(info/warn/error), an occurrence-vs-recorded time split (D38 doctrine), `correlation_id`, and
`jsonb` detail; `event_type` is free text governed by a Java enum (no DB enum — new types must
not need a migration). New tables: **`bar_gaps`** (GapDetector.Gap persisted,
`healed_at`/`heal_outcome` for T6), **`capture_status`** (per-heartbeat UPSERT — the console's
near-live health source; a stale `updated_at_utc` *is* the box-down signal), **`archives`**
(manifest of Parquet/`pg_dump` files the console pre-signs). **Grafana/DIY split:** the
SPA-over-DB owns current status + domain views and ships in v1; Prometheus/Grafana owns
time-series trends + alerting and is additive; a healthchecks.io dead-man's-switch is the v1
serious-tier alarm. **Scheduling (resolves the carried Q1):** three clocks — a per-market
**stream window** (config, venue-local, subscribe/unsubscribe only; DAX defaults *generous*
~06:00–22:00, not a tight 06:00–17:00), the **`DLG_FLAG` watchdog** (alarms on silence, no
calendar), and an **expected trading calendar** (`market_calendar`, XETRA sessions + German
holidays) that — not the window — defines a real gap. **Timeframe scope:** monitoring stays 1m +
tick scoped because higher timeframes are *derived on read* (D15/D9), never separately captured
(a 10m "gap" is a 1m gap); a broker-TF oracle, if ever wanted, arrives as a new `source_id` with
a `timeframe` dimension added then. **Why:** the first `service_events` was a provisional pass;
designing v2 before E1-T5 slice B builds the event log means the collector writes the right shape
once, and the console reads a data product built for it — not one worked around. **Feeds:**
E1-T5 slice B (writes `service_events` v2 + `bar_gaps` + `capture_status`), E1-T6 (writes
`archives` + `heal_outcome`), E9-T2/T3 (read model + SPA).

## D26 — Field Manual organised in Parts: a Foundations part + one per subsystem, formed organically — **Accepted** (2026-10-02, Alex)

The Field Manual (D21 Build track) grows by **parts**, not a flat chapter list: a **Foundations**
part for the cross-cutting engineering primitives that every service reuses (threads & the
callback boundary, buffers/queues/backpressure, the single-instance lock, clocks), then **one part
per subsystem** — today **The market-data service** (the IG domain: sockets/Lightstreamer, the
capture pipeline, the capture store, the resilience belt, busses); the DSL engine, backtester, and
bots get their own parts as E3+ build them. Parts are folders under `docs/field-manual/`, which the
docs-site nested sidebar (E2-T6) renders as groups. Extends **D22** (Field Manual *teaches*,
Architecture *specs*): the **per-component deep-dive reference** (deps, API surface, resilience
posture) lives in Architecture's component section (backlog B3), never duplicated in the Field
Manual. **Why:** a teaching manual reads best as "learn this subsystem", the cross-cutting
primitives belong to no single subsystem so they sit in Foundations and get referenced, and parts
scale (each epic adds a part, not more flat numbers) matching the infra-vs-domain split naturally.
**Migration (Alex's ruling):** the existing nine chapters were regrouped into the two parts
**keeping their reading-order numbers** (non-breaking — the ~31 in-body cross-references stay
valid); clean per-part renumbering + making those cross-references name-based is **deferred to the
chapter-merge pass** (backlog B6), which renumbers anyway. The redundant hand-written prev/next
footers were dropped — the site generates nav from the tree.

## D27 — The resilience belt's operating contract: DB-write tiers, exhaustion as a time budget with `exit(1)`, one streaming rule — **Accepted** (2026-10-03, Alex)

Three cross-cutting rulings from E1-T5 slice C, logged because T7 (deploy), T9 (sink
hold-and-retry) and every later service inherit them. **(1) DB-write tiers.** Tier 1 — product data
(ticks, bars) — is **fail-closed**: a failed write halts the pump and the process exits for the outer
loop to restart (P9; the hold-and-retry refinement is T9). Tier 2 — observability (service events,
gaps, capture status) — is **best-effort-but-loud**: caught, counted (`obsFailures=` /
`eventWriteFailures=` / `statusFailures=` in the heartbeat), never propagated, so the product plane
can never halt to protect a breadcrumb. Tier 3 — decisions — never touch the DB: the cores run on
in-memory state (the DB is downstream of decisions, never upstream). **(2) Exhaustion.** Recovery is
a **time budget** — `Tuning.giveUpAfter` = 10 min of continuous no-streaming from an outage's first
rebuild (the 10-rebuild ceiling stays as a cap) — not a count, because rung cadence is
detector-driven (a 10-rung ladder spans 20 min–3.5 h). Giving up is
`FEED_DEAD{reason: budget | ceiling | fatal_config}` then an orderly **`exit(1)`**, so any restart
policy brings the job back and none is load-bearing (the policy itself is T7's deploy contract);
boot retries retryable IG errors every 30s inside the same budget and fails loud at once on a
rejected configuration (`IgFatalConfigException`, now including `error.security.invalid-details`).
**(3) One streaming rule.** `ReconnectClassifier.isStreaming` — any `CONNECTED:*` substate except the
`STREAM-SENSING` handshake — is the single definition of "data flowing", shared by the reconnect
classifier and the health view: a resume onto a polling fallback ends an outage and resets the
ladder (the degradation is `TRANSPORT_DOWNGRADED`), and the two voices cannot disagree. **Why:** each
was ruled in-ticket (E1 plan T5 §; chapter 10 "The rulings behind it"), but they are contracts other
work depends on, and G2 says a decision lives in the log, not only in the plan that made it.
Supersedes the 09-28 nod's "exhaustion = clean exit(0)".

## D28 — Tier 1 refined: the capture sink holds and retries, with no time budget; the blip taxonomy; terminal failures exit at once — **Accepted** (2026-10-04, Alex)

Refines D27's Tier 1 (E1-T9). **(1) No time budget.** A retryable sink failure holds: bars stay
queued (ack-after-apply), `PostgresStore` keeps its own copy of the pending tick batch, the pump
backs off and asks the sink to `recover()` until it does. The hold is bounded by the queues — bars
unbounded (one a minute per market), ticks shed-oldest at 100 000 (≈ 18 h) — and the moment the tick
queue begins shedding is announced. **Why not a budget:** a restart gains nothing against a database
outage and loses everything held, the opposite of P8; the belt's `exit(1)` is about the JVM or the
stream possibly being at fault, and a retry loop failing only on the database proves the JVM is
fine. The 10-minute alternative (symmetry with `Tuning.giveUpAfter`) is on record with that cost.
**(2) The blip taxonomy** — `PersistenceException.retryable()`, known-transient only: SQLSTATE
classes 08 (connection), 57 (operator intervention), 53 (insufficient resources), 40 (transaction
rollback), plus the pool's connection timeout; the first state in the cause chain decides. Anything
else — integrity, syntax, authorisation, data, an unknown or missing state — is terminal and fails
closed at once. This inverts the ig-client taxonomy's lesson: unknown-means-retryable is what forced
a bounded boot loop. **(3) Backoff** is the belt's `BackoffPolicy` with the playbook tuning (5s
floor, doubling, 60s cap) — no new policy record (P6). The floor also clears HikariCP's 500ms
alive-bypass window, so the second attempt gets a validated connection. **(4) Recording:** nothing
is attempted while the database is down; one `SINK_FAILURE{outageMs, attempts, ticksShed,
queuedAtRecovery}` on recovery, dated from the first failure, written Tier-2; `sinkFailures=` on the
heartbeat line counts episodes; the dead-man alarm fires meanwhile. `DB_ERROR` is reserved for the
terminal case. **(5) Immediate exit:** the pump reports a terminal failure through `onDeath` and
`Main` exits 1 from its own thread at once — never on a deliberate stop, since an exit from inside
the shutdown hook would deadlock. **(6) The shared pool's `connectionTimeout` is 5s**
(`Database.CONNECTION_TIMEOUT`): the heartbeat is held at most 5s per market during an outage and
`recover()` fails fast into the backoff; no dedicated Tier-2 pool. Field Manual ch. 10 "When the
database fails" carries the full argument.
