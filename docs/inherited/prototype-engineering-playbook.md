# The Prototype Engineering Playbook — non-broker lessons from `ig-algorithmic-trader`

> **What this is.** The companion to [`ig-broker-playbook.md`](./ig-broker-playbook.md). That
> document banks everything the prototype learned about **IG the broker**; this one banks
> everything it learned about **building, testing, operating, and steering** a trading system —
> lessons that hold whatever the broker or the language. Written 2026-09-24 at the pivot point
> (Python prototype shelved to data-capture-only; Java platform greenlit). Each lesson names the
> scar that taught it, because the *why* is the part worth inheriting.

---

## 1. Architecture lessons

**1.1 The database is downstream of decisions, never upstream.** All business logic runs in code
on in-memory state; the DB is the system of record and reporting store, never read back inside
the decision loop. This is also *precisely* what makes one engine runnable live and in backtest —
the golden rule and the backtesting enabler are the same rule.

**1.2 Injectable clocks and event sources, from the first commit.** The prototype's staleness
watchdog was pure logic over injected clocks and was therefore fully unit-testable; everything
time-dependent (watchdogs, windows, backoff) should take a clock. On the platform this graduates
from testing tactic to foundation: the live/replay/fast-forward split *is* the clock abstraction.
Corollary learned the hard way: distinguish **monotonic/awake time from wall time** — the
prototype's outage escalator only avoided false-firing across host sleep because it kept them
separate.

**1.3 Do cheap work on the transport's callback thread; queue everything else.** Never let
business logic ride a streaming client's thread. One consumer loop owning state, transports
feeding it queues, is easy to reason about and proved crash-clean.

**1.4 At-least-once delivery + idempotent consumers, or silent lies.** Durable streams
(consumer groups, ack-after-apply) rather than pub/sub: a trading system must not silently drop
market events. The subtle part the test audit exposed: *ack-after-apply must be genuinely
pinned by tests* on every consumer — it's the easiest invariant to break invisibly.

**1.5 Ports where change is plausible, not everywhere.** Broker behind an interface, persistence
behind a port — those paid for themselves (SQL Server left the system without touching business
logic; paper/live execution flipped behind one verb surface). But an abstraction with only one
real implementation drifts: **give every port a second implementation early** (the simulator is
the broker port's; the backtester is the event-source's).

**1.6 Keep a verification oracle running against anything you reimplement.** The Python data
plane was proven by diffing against the SQL triggers over full history before the triggers were
retired. The platform's built-in analogue: broker-provided 1m bars continuously checking own
tick→bar aggregation. Never migrate maths without an oracle period.

**1.7 Namespace every instance; guard against doubles.** All shared-infrastructure keys carry an
instance namespace (`demo:`/`dev:`); every service holds a single-instance lock. Two copies of a
trading service is an incident, not a curiosity.

**1.8 State machines + audited transitions.** The strategy lifecycle as an explicit machine,
with every transition written to an event table *with the rule trace that caused it*, is what
made "why didn't it trigger?" answerable from data. Auditability is a feature of the
representation, not a logging afterthought.

## 2. Testing doctrine

**2.1 Strategy definitions are calibration input — tests never pin their content.** Shipped
strategy files are *the model*, tuned freely; the permanent behaviour-coverage set is **frozen
reference fixtures** (`ref-*`) that tests own outright. Tuning the model must never break the
suite; breaking the *behaviour contract* must always break it. (Alex's 2026-08-16 ruling.)

**2.2 AI-written suites can be self-confirming — audit them adversarially.** With implementation
and tests authored concurrently by AI, green ≠ safe. The prototype's independent audit ran a
**19-mutation campaign** (single-line domain-rule breaks vs the full suite): **14 survived**.
Findings pattern: expectations copied from the code under test, fixtures reading their own
hardcoded copies, invariants "pinned" in name only. Standing practice thereafter — and for the
platform from day one: **every new behavioural test is verified by applying the exact mutation
it exists to catch and watching it go red.**

**2.3 Classify anchors: independent vs self-referential.** A test is only evidence if its
expected values come from somewhere *other than the code under test*: hand-computed walks from
the spec sheet, an oracle, a live probe, golden bytes. Track which tests are independently
anchored; treat the self-referential ones as change detectors, not correctness evidence.

**2.4 Fail-closed paths must be tested to actually fail closed.** The audit found fail-closed
gates that failed *open* on a dependency error — and no test noticed, because nobody had written
the "Redis is down, does it refuse?" test. Every gate gets a failure-mode test, not just a
happy-path one.

**2.5 Pin wire contracts with golden bytes.** Round-trip tests (encode→decode) pass under a
coordinated rename on both sides. Byte-literal golden messages are what actually catch
producer/consumer skew — essential once components deploy separately (it *is* the
compatibility test between independently-deployed services).

**2.6 Test boundary equalities explicitly.** Off-by-one at a threshold (`<=` vs `<`, exact-touch
arms vs invalidates, the window-close tick) is where trading logic actually breaks. Each
boundary gets an exact-equality test, each spec line gets an independence test (its minimal
violating nudge fails it alone).

**2.7 Replay fixtures are the integration currency.** Recorded real data replayed through the
full stack — deterministic, fast, brutal. The prototype's fixture/replay estate replaced an
external oracle entirely (Alex's ruling on the range-bar model). Build the platform's fixture
capture/replay machinery early; it is also, conveniently, most of a backtester.

## 3. Configuration & secrets

**3.1 No code defaults for anything machine-specific.** Hosts, ports, namespaces: **required**
in local config, absent from code. A wrong default silently pointing at the wrong resource is
how the Sep-2026 outage became possible; a missing key that refuses to boot is a feature.

**3.2 Secrets live beside config, never in it, never committed.** Credentials/DB passwords in an
env file next to the local config; committed defaults contain nothing sensitive and *fail
closed* (empty stake sizes = no trading). The public-repo platform hardens this: strategy
definitions and credentials are the two things that must never leak.

**3.3 Every CLI/operation must name the instance it's acting on — before acting.** The 🪪 scare:
a status command silently resolved to the *dev* database and reported alarming nonsense about
prod. Rule: echo `instance · db · namespace · config-path` first; refuse to run when two config
homes are visible. Output that names no target makes a correct answer indistinguishable from a
disaster.

## 4. Operations

**4.1 A false-positive health check is worse than none.** `ensure-infra` "succeeded" daily for a
week while services crash-looped against a stranger's database — its success condition matched
*any* running postgres, not *ours*. Health checks must verify the **specific named resource with
its expected identity**, and infrastructure that can be silently claimed by another tenant
(default ports!) must not be assumed.

**4.2 A dead deploy must tell you within a day.** The streaming stack was down for seven trading
days before a human asked. Silence must be treated as a signal: heartbeats with *absence*
alarms, an end-of-day "here is what I captured" digest — push, not pull. (This scar wrote the
platform's notification requirements.)

**4.3 A laptop is not a server.** macOS sleep semantics (`caffeinate` ignored on battery,
dark-wake slivers) produced outages and reconnect storms that looked like broker problems and
were not. The platform targets always-on Linux containers, full stop — and any residual
laptop-hosted phase is treated as degraded by definition.

**4.4 Quiet-is-healthy observability.** Structured service events + a warnings stream that is
*empty when things are fine*. Reconnects, gaps, heals, forced rebuilds all leave rows;
dashboards are for browsing, alerts are for acting. Never make the operator grep logs to learn
something the system already knew.

**4.5 Automated repairs leave audit rows and classify their outcomes.** Every backfill/heal
records healed / provider-has-nothing / failed — because "gap fixed" and "gap unfixable at
source" are different facts and reporting reads them differently. Repair without a receipt is
just another silent mutation.

**4.6 Respect provider rate limits *in the tooling*, not just the service.** The outage backfill
got 403'd by IG's session limit when run rapid-fire; the fixed script spaced runs and clipped
windows. Any operator tool that loops over a provider API needs pacing built in.

## 5. Process & ways of working

**5.1 Spec-gate ambitious builds.** The range-bar ruling, after weeks of requirement churn: *no
build until a fully determined spec with no holes — then the whole pipeline in one go.* For
model/strategy work especially, half-baked requirements cost more than waiting. (Its complement
is 5.2: capturing requirements as answerable questions is how a spec *becomes* determined.)

**5.2 Question sheets → answered records = calibration truth.** The pattern that worked with
Dad and again in the platform grill sessions: structured questions, free-form answers, the
**answered sheet filed as the permanent record** that designs cite. Requirements conversations
evaporate; answered sheets don't.

**5.3 A decision log with the WHY and a status.** `docs/decisions.md` (46+ entries, each
Accepted/Provisional/Open with rationale) is the single most valuable document the prototype
produced — it's what makes "inherit the lessons, not the code" possible at all. The platform
keeps one from D1.

**5.4 One board, in-repo, agent-editable; epics with missions.** The task board as a versioned
markdown file was the workflow's backbone: epics carry a mission statement and explicit
`Blocked by:`; closed work keeps a done-history with evidence. Tickets only exist once execution
exists — ideation flows through `ideas → research → design → decisions` instead (the maturation
pipeline), because forcing thinking into tickets closes ideas before their design earns its
shape. Platform addition: the board stays the *write model*; a small web viewer becomes the
human *read model*.

**5.5 PR discipline with one pragmatic valve.** Code/config always via PR + CI; documentation-
only changes may land direct. Release discipline that earned its keep: releases are
self-contained (schema + defaults ship in the artifact), a release gate asserts completeness
(it caught real misses twice), consolidated behaviour-change notes per release, and **a version
number is never an authorisation** — going live on real money is an explicit decision with its
own gates, whatever the tag says.

**5.6 Reconcile the board with reality before trusting it.** Boards and branches drift during
intense stretches (a week of releases once sat unmerged while the board froze). Periodic
reconciliation sessions — board vs git vs deployed reality — caught it. Schedule them; don't
assume.

## 6. Working with AI (meta-lessons the platform is designed around)

**6.1 The codebase is the prompt.** What made AI collaboration effective here: per-module docs
mirroring the source tree, a CLAUDE.md that states load-bearing principles (not just commands),
decision log + board as shared memory, and small services with typed boundaries. The platform
treats "AI-workable" as an NFR (PRD §9) — it's what makes bursty, dry-month development viable.

**6.2 Independence is the antidote to AI self-confirmation.** Lessons 2.2/2.3 exist *because*
implementation and tests came from the same model. Mutation-verify, anchor independently, keep
oracles. Trust, but make the suite bleed on demand.

**6.3 Let AI do the sweep, keep rulings human.** The division that worked: AI proposes,
inventories, audits, drafts; Alex rules (spec-gating, doctrine, scope, money). Every ruling gets
written down where the next session finds it — rulings that live only in a chat die there.
