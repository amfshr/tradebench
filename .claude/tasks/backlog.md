# Tradebench Backlog

> **The pre-ticket staging area.** Requirements and ideas that pop up mid-work land here —
> *captured, not committed* — so they persist across sessions without being re-explained, and
> without forcing a ticket/epic/doc before they're ready. The **board** (`board.md`) holds
> committed work (tickets with a DoD); this file holds what hasn't earned that yet. Items
> graduate from here into an epic/ticket on the board, or into an enduring `docs/` spec, and are
> struck through (or removed) when they do. Lean entries, stable `B<n>` ids. The `board-steward`
> agent maintains this alongside the board; the docs site may render it as a "Backlog" view later.

---

## B1 — Stream-jobs service (multi-user, multi-market capture)

**Captured** 2026-09-30 · **Likely home** a future epic (post-E1 spine) · **Trigger** E9 Operator Console gains save-credentials + save-jobs (Alex, 2026-10-03) · **Relates to** PRD §5.1 (stream-job setup), §2 (tenancy/admin) · D16 (one key per account; multi-user creds as encrypted DB data), D17 (DB topology) · IG playbook §7 (single-instance & single-key discipline) · E1-T5 slice C (the belt), E1-T7 (host ruling), E8 (multi-user goes real), E9 (the trigger) · architecture §3.4 (stream-jobs trajectory)

The market-data-service's wider role: a **central service running N capture jobs**, not one
hardwired market. Each platform user's broker key = account = **one Lightstreamer session**
multiplexing *that user's* markets (D16: one key per account). So N users → N sessions (one
connection each); markets are subscriptions on the shared connection, not connections of their
own. Threads scale ~linearly with *users* (a capture job = `{session + queues + pump +
supervisor}`), the data rates are tiny, and a single small VM handles family-scale easily; shard
users across containers only if it ever outgrows one box (P7).

Three pieces, all **out of scope for E1-T5** (which stays single-user, config-at-startup):
- **Config/profiles DB** — a `stream_jobs` table (user, provider, account, **encrypted
  credentials** per D16, market set, schedule, enabled) that the service reads to know which jobs
  to run. Adding Dad's market = a row.
- **Multi-user job manager** — reads the config and starts/stops a `CaptureJob` per enabled row.
- **Dynamic reload** — add/remove a market (or a job) to a *live* session without a restart and
  without wobbling the other markets.

**Foundations E1-T5 slice C already lays that this epic reuses** (so it's a slot-in, not a
rewrite): (a) **per-item subscribe/unsubscribe** in the transport — built for §3.5 surgical
recovery, and exactly the primitive dynamic add/remove needs; (b) the **per-session `CaptureJob`
unit** structure — multi-user is then "run N of them." The per-item-vs-rebuild choice is a
transport-layer concern *below* the connection/user layer, independent of the number of users.

**Codebase review 2026-10-03 — the three concrete gaps B1 must close** (each verified against the code):
1. **`SingleInstanceLock` is one global advisory key per service** (`pg_try_advisory_lock(0x7472_6462_0001L)`,
   `market-data-service/…/store/SingleInstanceLock.java`): a 2nd/3rd collector job against the same DB is
   **refused at startup** ("another market-data-service instance holds the advisory lock"). The guard exists
   to protect the **IG key** — no accidental second Lightstreamer connection on a shared key (IG playbook
   §7) — not the database, so B1 scopes it to what it protects: **per job** (keyed on the instance name, or
   better on `(user, source)`). A double-launch of the *same* job stays refused.
2. **`Main` hardcodes `USER = "default-user"`** and `V1__baseline.sql` seeds only that user: B1 needs a
   `TRADEBENCH_USER` config + a `users` row per platform user (e.g. Alex's brother). Env-file-per-job is fine
   until D16's multi-user era moves users' broker credentials into app-encrypted DB columns (E8).
3. **One env file + one service unit per job** (three jobs = three processes) — belongs to the deploy/host
   ticket (E1-T7 host ruling), not the service code.

**Trigger (Alex, 2026-10-03):** "my main concern with the ability to run multiple jobs realistically comes
when I make the E9 dashboard and add user functionality to save credentials and jobs — then we will need to
run multiple jobs." B1 becomes necessary when the **E9 Operator Console gains save-credentials + save-jobs**;
until then single-job is fine. (D16 already places users' broker credentials as encrypted DB data in the
multi-user era / E8 — not re-litigated here; E9 is Alex's stated trigger.)

**Isolation findings (2026-10-03 — design intent, so it isn't lost):**
- **Process-per-job is the isolation boundary.** The service exits the JVM on fatal conditions —
  `System.exit(1)` on pump death today (`Main`); the ruled exhaustion exit via the Supervisor's
  `onExhausted` (slice C rewired `Main` — PR #12, 2026-10-04). Co-hosting jobs as threads in one JVM would let one job's exit
  kill the others — so **B1 = one process per job, never threads-in-one-JVM**; that is what makes "one
  connection must not break another" (Alex's paramount requirement) hold. *Sharpens the "run N" picture
  above — N processes, not N threads; the job manager becomes a process launcher. Alex confirms when B1 is cut.*
- The resilience belt (E1-T5 slice C: `Supervisor` + cores + `StreamControl` + `Buffers` + `Pump`) is
  entirely per-connection instance state — nothing static/shared. Nothing in the belt, the pump, or the
  schema changes for B1.
- **IG level is naturally isolated when keys are distinct** (Alex prod key / Alex demo key / brother's own
  key = three sessions, three LS connections, three rate budgets — `RequestPacer`/`LoginRateGate` are per
  process). D16's "all processes share the account key" caveat only bites when two processes share *one*
  key (e.g. the future EOD heal job sharing Alex's prod key with the live collector — the login stagger).
- **Schema is already multi-job-ready:** `user_id + source_id` in every key (`ticks_dedupe UNIQUE(user,
  source, instrument, ts, bid, ask)`, `bars_1m PK(user, source, instrument, start)`, `bar_gaps_span`
  likewise), `capture_status` keyed `(instance, instrument)`, `service_events` carries `instance`; Flyway
  migrates at connect under its own lock. Different `(user, source)` never collide; the same market captured
  twice by one `(user, source)` dedupes idempotently.
- Witness quarantine (§3.5) needs ≥2 markets per connection to ever quarantine; a 1-market job degrades to
  rebuild-only (by design — no witness ⇒ session-shaped).

**Example job mapping** (jobs 1 and 2 are already distinguishable today — same user, different source):

| Job | user | source | key | instance |
|---|---|---|---|---|
| 1 — Alex, prod, 2 markets | `default-user` | `ig-stream-live` | Alex `IG_LIVE_*` | `alex-live` |
| 2 — Alex, demo, 3 markets | `default-user` | `ig-stream-demo` | Alex `IG_DEMO_*` | `alex-demo` |
| 3 — brother, his choice, several | new `users` row | `ig-stream-live` or `-demo` | his own `IG_*` set | `brother-…` |

**Heartbeat fail-fast during a database outage** (captured here 2026-10-05 from the T9 outage drill) → graduated to
**E1-T10 #22** (the PR #14 review's finding 22: `HealthProbe.publish()` one connection/batch per heartbeat, or
short-circuit after the first pool-timeout failure); no longer a B1 item.

## B2 — ig-client REST resilience (platform-wide), and revisit "resilience lives in the consumer"

**Captured** 2026-10-02 · **First consumer** E1-T6 (heal retry) · **Broad surface** E6 Execution (OMS) · **Relates to** E1-T2 (ig-client), D16, `docs/design/architecture/components/ig-client.md`, the trading-ig research (retry patterns)

The **ig-client is the platform's REST foundation, not just the stream wrapper.** As the platform
grows, it carries the whole IG REST surface — OMS orders, market enquiries, dealing rules,
account/positions, activity & transaction history, watchlists, `/operations/application` — consumed
by many services and (via the jar) downstream apps. Today's stance is *"resilience policy lives in
the consumer; the client throws typed errors and lets the service rule"* — right for one consumer,
wrong once there are many (every caller re-implementing retry, and the **failure reason must be
carried through** each hop). When the REST surface broadens we want **basic retry/resilience built
into or wrapped around the client**: typed failure-reason propagation (the taxonomy already carries
it) + retry-with-backoff on transient failures + allowance/rate-limit awareness (the `RequestPacer`),
so every caller inherits sane behaviour by default.

**Decision when it lands:** hand-roll vs **standalone Resilience4j** — *never* Spring Retry /
Spring Cloud CircuitBreaker (ig-client is framework-free by design, T1). A **circuit breaker is
largely the wrong tool for IG** (single downstream, no fallback, no inbound load) — but a shared
**retry + rate-limit + typed-error** wrapper is right. This likely **revisits the
"resilience-in-the-consumer" stance**: a client-side resilience wrapper (an opt-in policy object)
rather than per-consumer loops. **First concrete slice: E1-T6's healer** — transient REST retry,
allowance-aware (weekly `exceeded-allowance` = budget-exhausted, *not* retry). Broad rollout: E6.

## B3 — Component-reference docs: full per-jar coverage (the pages are too thin)

**Captured** 2026-10-02 · **Likely home** an Architecture docs pass (D22; docs-only → main, G4), per component as it matures · **Relates to** E2-T6 (component reference born), D22

The `docs/design/architecture/components/*.md` pages are currently a few bullets each — too thin.
Each component/jar deserves **full reference coverage**: **dependencies** (what it pulls in + why)
· **purpose** · **consumers** (who uses it, now + planned) · **client/transport choices + the *why*
not the alternative** (e.g. JDK vs Apache HttpClient; the Lightstreamer SDK) · **ins/outs** (the
API surface — key interfaces in and out) · **resilience model** (what it handles vs delegates, and
the growth path — see B2 for ig-client) · **principles** (the design stances) · **growth seams**
(what's there for future features). Scope: `core`, `ig-client`, `market-data-service`, and each new
module. **ig-client is the first to deepen** (stable + about to be heavily used). Stays *reference*
(D22) — complements, never duplicates, the Field Manual's narrative teaching; can ride the epic that
grows each component or a dedicated docs pass.

## B4 — ig-client: a default-assembly convenience (composition-root ergonomics)

**Captured** 2026-10-02 · **Trigger** a 2nd composition root (today only `Main`) · **Relates to** B2, P6, config discipline (G1)

Observation (Alex): the explicit-DI constructors make every caller re-wire the same standard
collaborators (clock, `Sleeper.SYSTEM`, the `RequestPacer` policy, `LoginRateGate`,
`JdkHttpTransport`) — so `Main` is the de-facto composition root, and a 2nd service would copy that
assembly (duplication + drift risk). The DI itself is **right** (keep it — it's what makes the seams
testable and vendor-swappable); what's missing is a **convenience factory/builder** that wires the
standard **behavioural** defaults so the common case is one call (**P6** — degrade to the simple
case), with the full constructors kept for tests/injection. **Guardrail:** the factory wires
behavioural defaults only (`SystemClock`, `Sleeper.SYSTEM`, the pacer rate policy) — **never
machine-specific config** (env + credentials stay caller-supplied from env, G1). Not now (one
consumer); lands with the 2nd.

## B5 — ig-client REST: segregate by category as the surface grows

**Captured** 2026-10-02 · **Trigger** the REST surface + consumers multiply (E6 OMS, backtester, charts) · **Relates to** B2, D22, ISP

Observation (Alex): `IgRestClient` is a concrete class with no interface; IG's REST API is large and
splits into natural categories (market data, dealing/OMS, accounts, activity/history, watchlists,
operations). One concrete class is **right today** (YAGNI — one impl, one consumer; T2's "add methods
as needed, drop unused stubs"). As the surface + consumers grow, split into **category-segregated
role interfaces** (e.g. `MarketDataApi`, `DealingApi`, `AccountApi`, `HistoryApi`) so each consumer
depends only on its slice (**ISP**) and can fake it in tests — mirroring the stream side's
role-named-interface / vendor-named-impl pattern. The value is the *category* split (a single giant
interface is no better than the god-class); deferred to multi-consumer, not built speculatively.

## B6 — Field Manual: clean per-part renumbering + name-based cross-references (the merge pass)

**Captured** 2026-10-02 · **Trigger** the "bigger chapters" merge pass · **Relates to** D26, D22

The nine chapters were regrouped into parts (D26) **keeping their original reading-order numbers**,
so within a part the numbers aren't contiguous (Foundations = 2,3,6,7; The market-data service =
1,4,5,8,9). Renumbering now would have broken ~31 in-body cross-references ("chapter 5 is
line-framed", "chapter 8's watchdog", …) and the 9 `Chapter N —` headings. The clean pass:
**renumber per part (1…n) and convert every chapter cross-reference to name-based** ("the
capture-store chapter") so the numbering is reorg-proof for good. Fold it into the **chapter-merge
pass** (making chapters bigger — the other organic Field Manual job) since that renumbers and
rewrites prose anyway; doing it standalone would be pure churn. Also covers the matching heading
cleanup (drop `Chapter N —` from H1s, or renumber them).

## B7 — AI operating framework (E0): legible board state, a "how we build" (SDLC) guide on the docs site, and a retrospective practice

**Captured** 2026-10-03 · **Likely home** E0 — extend it with tickets, or close it and open a successor (Alex's call; framework §5 records new agents/skills on the E0 family, §6 already schedules a contract review "when E1 ships … and at every epic boundary") + a Build-track docs pass (docs-only → main, G4) · **Trigger** Alex (2026-10-03): "after this epic" — a retrospective session once E1 closes (nearest checkpoint: E1-T5's close — reached 2026-10-04, PR #12) · **Relates to** PRD §9 (AI-workable NFR) · E0 (T1 the contract, T2 agents v1, T3 skills v1, T5 `board-steward` — all done; T4 guardrail enforcement — queued) · `.claude/ai-framework.md` (§1 roles, §2 G1–G8, §3 session protocols, §4 directory standard, §5 agents & skills inventory, §6 evolution) · `.claude/task-workflow.md` · `docs/reference/board-format.md` · D10, D13 (the framework's own decisions) · D20 (lean template; new fields proposed, never invented) · D21/D22 (docs split: Build track = Field Manual + Architecture + Decisions; Use track = Product + User Guide + DSL reference) · D26 (Field Manual parts) · E1 plan T5 § (slices A/B/C + numbered steps — the worked precedent) · E1-T9 (born from a step review)

**The problem, as a reader of the board and the docs site (Alex, 2026-10-03):** "as a reader I don't
understand why it's open or what its tickets were, and there is no user guide or SDLC section in the
[docs] pages — an explanation of what and why: the agents and skills and the intended flows we take
during development." Checked against the artefacts: E0's header reads `ACTIVE (born urgent
2026-09-27)` with four of five tickets done; the one open ticket, **T4 guardrail enforcement**
(pre-commit + CI secret scan, `.claude/settings.json` permissions baseline — neither exists yet),
carries the note "pairs with E1-T1's CI", and E1-T1 closed 2026-09-27; the *why open* lives in one
clause of the board's guide prose ("E0 lacks enforcement (T4)"), not on the epic itself. **E0 has no
plan file** (`.claude/tasks/epics/` holds e1, e2, e9 only), so its tickets carry no metadata lines
and no plan bodies — their detail pages render from the board rows alone. On the site, the contract
(`.claude/ai-framework.md`) and `task-workflow.md` are **not rendered at all** (the content glob
covers `.claude/tasks/**` only; nav groups: Product · Field Manual · Design · Decisions · Reference ·
Inherited · Board) — a site reader meets the framework only second-hand, via the E0 rows and
D10/D13 in the decision log. Note: the D21 **User Guide** seat (`docs/guide/`) is the *end-user* Use
track (Dad, brother) — not the home for this.

**Scope sketch (not ruled — ideation):**
- **A "how we build" section in the Build track** (name and seat TBD — Alex rules; it is neither a
  Field Manual chapter (D22: teaches code paths, with scars) nor an Architecture spec): the roles
  ("Claude proposes, Alex rules"), G1–G8 in plain words, the skills (`/sanity-lap`,
  `/design-session`, `/start-ticket`) and agents (`doctrine-reviewer`, `board-steward`,
  `seed-pack-librarian`, `db-admin`) and when each is used, and the intended flows: cold start →
  design session (answered record filed same day) → ticket (`/start-ticket`: scope check → design
  nod, D13 → branch → build in **slices and numbered steps**, each staged for Alex's review →
  `doctrine-reviewer` → PR restating the DoD, Alex merges) → close-out (board, done history, menu).
  Worked precedent: E1-T5 — slices A/B/C, numbered steps in the commit log (`1/6`, `2a/6`,
  `2b-i/6`, `2b-ii/6`, `3/6`, `4/6`, `5/6`, `6/6` — all landed, PR #12), and a step-6 review that re-ruled exhaustion
  and ticketed E1-T9.
- **Board hygiene for E0:** make its state legible — why it is open, what its tickets were and are
  (a plan file with metadata lines + plan bodies, per `board-format.md`), and **close or extend it
  explicitly** (T4's "pairs with E1-T1" note is stale; the §6 review clause is unexercised).
- **A retrospective practice:** a retro session after each epic and/or spontaneously — what went
  well, what the reviews caught that planning should have, what to change in skills/agents/docs;
  possibly a `retrospective` skill or agent added to the pipeline (framework §5's bar: a repeated
  need, recorded on the board).
- **Strengthen the AI side for** (Alex's list): retrospectives · multi-workflow sessions ·
  support/operations flows · docs-update flows (keeping docs true when the code they cite moves) ·
  end-to-end tasks and **validation tickets** · more formal structuring of tickets into slices and
  build steps. Anything that adds a template field (a slice/step field, a validation ticket type) is
  proposed to Alex first, never invented (D20).

**Alex's words (2026-10-03):** "I'm thinking, after this epic, of having a retrospective session and
maybe creating an agent/skill for this and adding that to the AI pipeline and SDLC lifecycle as
random/spontaneous or after each epic — strengthen the skills and AI to do retrospectives,
multiple-workflow sessions, support, docs updates, end-to-end tasks and validation tickets,
structuring the tickets into slices and build steps more formally, etc."

## B8 — Tick-queue overflow during a prolonged sink hold: spill-to-disk (jsonl) vs simply raising `Buffers.DEFAULT_TICK_CAPACITY`

**Captured** 2026-10-05 (Alex's question during the E1-T9 ticket review; his own assessment: "unlikely") · **Trigger** a real outage that ever approaches the tick-queue bound (`ticksShed` > 0 in a `SINK_FAILURE`, or the shedding announcement in the log) — not scheduled · **Relates to** E1-T9 / D28 (1) (the hold is bounded by the queues; shedding announced) · D27 (Tier 1 fail-closed) · `Buffers.DEFAULT_TICK_CAPACITY` = 100 000 · `ticks_dedupe` (V1 — replay idempotent) · the dead-man alarm (E1-T5 slice C, stale `capture_status`) · P8 · P9 · Field Manual ch. 10 "When the database fails"

The hold (E1-T9) is **lossless until the tick queue sheds** — 100 000 ticks ≈ 18 h of DAX at the normal
rate; bars are unbounded. The dead-man alarm fires within minutes of an outage, so a multi-hour database
outage is an **operator problem first** — the queue bound is the backstop behind a human, not the first
line. Two ways to move the bound if it ever matters:
- **Spill-to-disk (jsonl):** a second durability path — and with it disk-full handling, replay ordering,
  dedupe on replay (though `ticks_dedupe` already makes a replay idempotent), and a replayer to test. Real
  machinery for a case Alex rates unlikely.
- **Simply raise `Buffers.DEFAULT_TICK_CAPACITY`:** the cheap alternative — 100k ticks ≈ 10 MB, so several
  times that is still small; one constant, no new path.

Not scheduled; revisit if a real outage ever approaches the queue bound (the 2026-10-05 drill's 152s outage
shed 0 ticks and peaked at 303 queued writes — far below it).

## B9 — REST-fetch collection jobs (non-streaming) and allowance visibility

**Captured** 2026-10-08 (Alex) · **Likely home** a cross-cutting epic once both apps are deployed and past v0 — the platform web app (E9) *and* the market-data service, one spine exercise · **Trigger** after E1 (T6, T7) and E9's platform app are deployed and off v0 — Alex's call when; not scheduled · **Relates to** E1-T6 (the heal is the same REST primitive — a REST-only job is a scheduled backfill with no stream to heal; D6: a plain scheduled task) · E9 (jobs and the allowance view belong in the console) · B1 (per-user jobs and credentials — the job model B9 extends) · D27/D28 (the collector's resilience tiers apply) · D16 + IG playbook §7 (a REST job on a key a live stream also uses — the shared-key case B1 flags) · P8 (source-attributed always) · IG playbook §4.3 (historical-data allowance — 10,000 candle points/week; 1m depth ≤ 40 days), §6 (request allowances) · `IgRestClient` → `ApplicationAllowance` (`GET /operations/application`; golden `wire/application-allowance.json`), `RequestPacer`, `PacerDiscovery` · the seeded `sources` (`V1__baseline.sql`: `ig-stream-demo`, `ig-stream-live`, `ig-rest-heal`)

Not every user wants to stream. A **second job type pulls bars by REST** — e.g. one end-of-day fetch of
the day's 1-minute bars per market (other timeframes later), scheduled — instead of a 24/7 Lightstreamer
stream, and the platform **shows the IG allowances that bound it**: the per-account / per-application
request allowances `GET /operations/application` already parses (`IgRestClient` → `ApplicationAllowance`;
`PacerDiscovery` sets the REST budget from the tighter of the two, minus `HEADROOM` because T6's heal spends
the same key), and the historical-data allowance of ~10,000 candle points / week per account (playbook
§4.3, §6 — shared across keys and services) that the T6 heal is also budgeted against. A REST collector
would be another spender on those same per-account budgets — hence *visibility*, not just pacing.

Why it is a growth area for the spine: it is **cross-cutting**, and a clean exercise in thinking the spine
further once both apps are deployed and past v0 —
- **Platform web app (E9):** job definition and UI, the allowance view, per-user credentials — B1's job
  model gains a second job type.
- **Market-data service:** a scheduled REST collector reusing **E1-T6's heal path** (window-clipped, paced,
  allowance-aware — a REST-only job *is* a scheduled backfill with no stream to heal), the
  `RequestPacer`/`PacerDiscovery` budget and the allowance parsing; **its own source name**, e.g.
  `ig-rest-<env>`, so REST-collected rows carry their provenance (P8) beside the seeded `ig-stream-<env>`
  and `ig-rest-heal` sources — never conflated with streamed bars. D27/D28's resilience tiers apply; a REST
  job sharing a live stream's key is the D16 shared-key case B1 already flags (login stagger, one request
  budget).

Not scheduled. Trigger: after E1 (T6, T7) and E9's platform app are deployed and off v0 — Alex's call when.

## B10 — `Jdbc.lookupId`'s fail-closed refusal of an unknown user/source is unpinned

**Captured** 2026-10-10 (an observation of E1-T10 slice C's doctrine review, outside the slice — a pre-existing gap) · **Likely home** a small E1 test ticket, or folded into E1-T12's acceptance-mode work (the same Testcontainers rig) · **Trigger** the next time a store test is touched, or when E1-T12 stands up the acceptance rig — Alex's call; not scheduled · **Relates to** `market-data-service/src/main/java/dev/amfshr/tradebench/marketdata/store/Jdbc.java` (`lookupId` — E1-T10 #10's shared helper) · engineering playbook §2.3 (fail-closed paths get failure-mode tests) · P9 (fail closed, fail loud) · the seeded `users`/`sources` rows (`V1__baseline.sql` — "schema seeds are the source of truth")

`Jdbc.lookupId` throws `IllegalStateException("unknown <kind> '<name>' — schema seeds are the source of truth; refusing to
invent one")` when a user or source name has no row. That is the fail-closed path the stores lean on, and nothing pins it:
no test asserts the throw. **Small:** one Testcontainers test per store that looks up an unseeded name and expects the
`IllegalStateException`; mutation to verify it — make `lookupId` return `0` instead of throwing → red.

## B11 — Recovery accounting: the inputs to the open `boolean recover()` decision

**Captured** 2026-10-11 (the belt's cloud re-review, `.claude/reviews/2026-10-11-belt-re-review.md` — findings 9, 10 and 17; none gates E1-T6) · **Likely home** the ticket that rules `boolean recover()` — Alex's open item from E1-T10 slice B (its doctrine review's finding 3: `CaptureStore.recover()` returns whether it landed held writes, so a proven episode closes at once — the mechanism option taken on-branch, open for Alex, not a blocker) · **Trigger** that ruling, or the first real outage whose `SINK_FAILURE` row reads wrong — not scheduled · **Relates to** D28 (4) (one `SINK_FAILURE` per episode, `sinkFailures=` counts episodes; the episode dated from its first failure) · E1-T10 #6 ("recovered means a write landed") and #20 (the tail's one no-wait recovery) · `Pump` (`hold()`, `recover()`, `landed()`, `drainTail`), `PostgresStore.recover()`, `Main.join` / `Database.CONNECTION_TIMEOUT` · Field Manual ch. 04, 05, 10

Three accounting gaps in the hold's bookkeeping, each verified by trace in the re-review; no data at risk in any of them — the breadcrumbs lie, the ticks and bars do not:
- **Review 9 (medium) — a bar-side failure with ticks held is counted as a new episode every ~5s.** `recover()` reports `landsHeld` true because the tick batch lands, so `landed()` closes the episode and writes `SINK_FAILURE`; the still-queued bar fails again on the next cycle with the same cause → a fresh episode, `sinkFailures` climbing and `attempts` restarting while the store hammers the failing upsert. Needs a cause that fails `bars_1m` while tick inserts succeed (a row lock held > 30s on the same bar by a manual repair or T6's heal; a statement-scoped cancel). Review's fix: record the write kind that opened the episode and keep it provisional until that kind lands, or carry `attempts`/`since` across an episode closed in the same hold-return cycle.
- **Review 10 (medium) — the stop-mid-hold tail's one recovery cannot finish inside the hook's 5s join.** `drainTail`'s single `recover()` blocks for the pool's full `CONNECTION_TIMEOUT` (5s) while Postgres is down; `Main.join` bounds the pump join at the same 5s, so the hook logs "pump did not stop within 5s" (the wrong story — the pump was doing #20's recovery), skips `sink.close()`, and the "pump failure at shutdown" line is a race. The harness never sees it (`ScenarioRunner.shutdown` joins with `() -> true` and runs `drainTail` inline). Review's fix: join the pump for longer than one pool acquire (e.g. `CONNECTION_TIMEOUT + 2s`, a `CaptureAssembly` constant; the sweep keeps 5s), or have `drainTail` log its own failure/pending line on the pump thread before returning; align chapter 04.
- **Review 17 (low) — `SINK_FAILURE.outageMs` is measured when the episode is proven, not when the sink recovered.** A provisional recovery (`landsHeld=false`) stays provisional until the next bar or batched tick lands, so the quiet gap until the proving write is booked as outage — a 10s Friday-night blip over a closed weekend becomes a row claiming ~49h on Sunday, and a second blip joins the same episode under #6's "episode continues" rule; the console's "sink recovered after Ns" and the row disagree. Review's fix: stamp `recoveredMono` in `recovered()` and write `outageMs` from it (a separate `provenAfterMs` if the proof delay is wanted on the row); exposing test: provisional recovery at t=5s, advance `FakeClock` 3,600s with no data, land a bar, assert `outageMs == 5_000`.

## B12 — Harness hygiene: the runaway guard laundered into a pump death, and where the harness could lie green

**Captured** 2026-10-11 (the belt's cloud re-review, `.claude/reviews/2026-10-11-belt-re-review.md` — findings 11 and 14; neither a belt defect, neither gates E1-T6) · **Likely home** the next ticket that touches the harness (`market-data-service/src/test/…/scenario`) — the review's own suggestion · **Trigger** that ticket; not scheduled · **Relates to** E1-T11 (the harness; its contract — a guard tripping "ends the run as an error, not an exit", `59238f0`) · E1-T10 #19 (a throwing sweep is deliberately `supervisor.die(e)`) · E1-T12 (acceptance mode's observables) · `ScenarioRunner`, `FakeSleeper`, `FakeStreamTransport`/`FakeConnection`, `ScriptedCaptureStore`, `PostgresStores`, `Observed` · G5 (a test that can pass for the wrong reason)

- **Review 11 (medium) — the harness launders its own runaway guard into a verdict on the system.** `FakeSleeper`'s guard throws a plain `IllegalStateException` inside `Pump.cycle()`; the runner rethrows only `HarnessError` and treats every other RuntimeException as the pump dying — a `DB_ERROR{cause: "…runaway wait…"}` row, `exitAsked = "pump died → exit(1)"` and the full shutdown — so a stop()/deadline bug that makes the pump spin, or a fixture whose `dbUp` is scheduled past `until`, is reported as a system verdict a test asserting `exits.size()==1 && how().startsWith("pump died")` can accept. The `BUSY_LIMIT` guard and a `probe.publish()` throw inside the hook launder the same way; the runner's contract comment is stale on its third item. Review's fix: give the guard its own type (or have the runner's `onSleep` wrapper throw `HarnessError` when `sleeps > maxSleeps`), wrap `observeWire`'s throws likewise, reword the contract to the current `die()` semantics.
- **Review 14 (low) — where the harness could lie green: the instrument's limits worth writing down.** (a) `finish()` flushes the store directly, bypassing `Pump.flush()`/`landed()` — a provisional episode whose closing ticks arrive in the last hook never records its `SINK_FAILURE`, and the single `cycle()` can enter `hold()` on a broken sink and run to the guard; (b) acceptance observables are presence-by-timestamp (`tick@ms`/`bar@s`, no bid/ask/OHLC/epic), the dedupe constraint hides a doubled write, heartbeats are in-memory snapshots in both modes, `capture_status`/`bar_gaps` are `count(*)`'d and `severity`/`category` are not read — chapter 10's "reads the rows back as the observables" over-claims and the test names' "exactly once" is unprovable from these rows; (c) unexpressible orderings — the clock never advances inside `stream.rebuild()`, `FakeConnection.close()` fires the farewell synchronously so chapter 10's `[STREAMING(new), DISCONNECTED(old)]` race is never produced at system level, `serverAnswers` always greets and confirms, the fake's refusal knobs (`refuseUnsubscribe`/`failSubscribeOf`) reach no `Event`, a status aimed at a torn-down connection vanishes at the generation gate with no `HarnessError`, handles stay active after `close()` and update listeners are not generation-gated; (d) fidelity — `ScriptedCaptureStore` fails a tick at `write()` where `PostgresStore` fails at `flush()`, and `Observed.landed` means different things per mode; (e) anchoring — three stale comments, a weak inequality (`landed.size() < ticksDelivered` where 29 of 40 is derivable), `ReplaysTest` covering 4 of 14 line kinds. Review's fix: `finish()` through a `Pump` method; a `failAtFlush` mode and one flush-edge scenario; read tuples and `capture_status` rows back in acceptance mode; expose the refusal knobs as Events; `activeFor` throws when closed; state `landed`'s per-mode meaning; fix the comments and the test name. The two mutation claims the finders made were checked by the review and hold (deleting the pump's idle flush is caught; replacing the post-rebuild clock re-read with `now` is caught at unit level).

## B13 — Replay curation: the scar header, the busy test's count, the `DLG_FLAG` premise, the exporter's truncation

**Captured** 2026-10-11 (the belt's cloud re-review, `.claude/reviews/2026-10-11-belt-re-review.md` — finding 13, low; its (e) PLAUSIBLE) · **Likely home** the next fixture added to the replay library (`market-data-service/src/test/resources/replays/`) — the first outage Tradebench itself survives, exported through `scripts/export-replay.sh`'s `tradebench` branch · **Trigger** that fixture; not scheduled · **Relates to** E1-T12 (ruling 1 — every header carries `recorded`/`modelled`; ruling 2 — the two dropped fixture families and the weekend drop's evidence) · E1-T11 (the T11-era scar export) · `Replays.parse`, `ReplayAcceptanceTest`, `scripts/export-replay.sh`, the replay README · Field Manual ch. 10 "The replay library (E1-T12)" · the E1 plan T12 § and the board's T12 row (the premise to true — planning markdown, the `board-steward`'s surface)

Four small gaps, none a belt defect: **(a)** the gold scar fixture (`2026-08-04-silent-while-connected.jsonl`, the T11-era export) carries no `recorded`/`modelled` sections while chapter 10 and ruling 1 say every header carries both, and `Replays.parse` requires neither — the two tests that lean on it hardest anchor on a comment; **(b)** `ReplayAcceptanceTest`'s busy window pins `CONNECTION_STATUS == 1` and the allowed row types but not the `MARKET_STATE_CHANGE` count its comment claims ("the two DEAL flags are the only rows") — derivable as 2 from `Buffers` with all 4,384 ticks DEAL, and absent; **(c)** the E1 plan T12 § and the board's T12 row still say the prototype's `dealflag` column gives "real `DLG_FLAG` transitions" while the replay README and the weekend-drop ruling say every row reads DEAL — the drop's evidence and its premise contradict each other in the record; **(d)** `scripts/export-replay.sh` truncates `$out` before `psql` has succeeded (under `set -euo pipefail` an abort leaves an emptied tracked fixture) and validates nothing about the header; **(e)** PLAUSIBLE, for the curator: the 2026-08-10 header's own arithmetic implies a prototype-side clock reset ~11s into the window that `recorded` does not mention — whether it was a connect/rebuild is unproven, and the test's `connect == [0]` is a claim about our rig's modelled boot, consistent with the nod. Review's fix: add the two sections to the scar header and make `Replays.parse` require them; `assertEquals(2, o.events(MARKET_STATE_CHANGE).size())`; true the plan's premise; write the export to a temp file, validate the header, then `mv`.

## B14 — The session verdict's `≥ 2` is absolute, not relative to the session's size (N > 2 markets)

**Captured** 2026-10-11 (the belt's cloud re-review, `.claude/reviews/2026-10-11-belt-re-review.md` — finding 15, low, PLAUSIBLE; a design question, not a defect) · **Likely home** the nod that adds a third market — Dad's NASDAQ, Richard's three to five (Alex, 2026-10-11, at E1-T12's doctrine review: "when there are several users, it will be easy to have sessions with more than two markets") · **Trigger** that nod; not scheduled · **Relates to** IG playbook §3.4 (the two-market argument the rule ports — "at N=1 the ladder always runs") · `StalenessWatchdog` (`stale.size() >= 2` renders the session verdict) · E1-T10 #33 (the one-sweep hold) · E1-T12 ruling 4 and E1-T13 ruling 1 (the session ladder's reset) · B1 (multi-market sessions per user) · Field Manual ch. 08

`stale.size() >= 2` renders a session REBUILD with no per-market surgery first, whatever N is. The rule is a port of the playbook's two-market argument; nothing argues it for N > 2, and the record now expects three-to-five-market sessions soon. Five markets, two thin at dawn (the recorded 2026-08-05/08-10 DAX pattern), three streaming: at 90s both thin markets are stale → every healthy feed is torn down, each with a candle gap and a `RECONNECT{replayed=false}`. A decision for Alex when the third market lands: all (or a ruled majority of) non-stood-down markets stale together, a minority getting market surgery first.

## B15 — The remaining lows of the belt's re-review: the Tier-2 writer's liveness, small cleanups, the config-shaped lows; the heartbeat's untested lock wiring (E1-T13's doctrine review)

**Captured** 2026-10-11 (the belt's cloud re-review, `.claude/reviews/2026-10-11-belt-re-review.md` — findings 18, 19, 20 (a)–(h), and 16; each verified by trace, none gates E1-T6) · **Likely home** a hygiene slice after E1-T6, or the cloud review of T6 — Alex's call · **Trigger** either; not scheduled · **Relates to** D29 (3) (the Tier-2 writer) · D29 (1) (the stand-down bound rests on the snapshot re-teaching the flag — 20 (d)) · D29 (5) as E1-T13 amends it (16) · D27 (2) · `QueuedEventLog`, `Main`'s heartbeat loop, `Supervisor`, `IgStreamControl`, `Database`, `PostgresStore`/`PostgresObservabilityStore`, `FakeClock`/`FakeSleeper` · config discipline (CLAUDE.md: every CLI/job names its instance before acting) · Field Manual ch. 10 · E1-T13 (`SingleInstanceLock.verify()`, `Main.verifyLock`, `CaptureAssembly`) · E1-T7 (the deploy ticket, where the heartbeat/exit contract is next touched)

- **E1-T13's doctrine review, judgment 1 (2026-10-11) — a testable `heartbeat()` on `CaptureAssembly`, the lock as a port.** `Main.verifyLock`'s wiring — three lines, the same shape as the untested thread-alive exits beside it — is driven by no test: `SingleInstanceLockTest` covers the lock's logic and all three standings (HELD / REACQUIRED / UNVERIFIABLE) plus the refusal on a real Postgres, but nothing exercises the heartbeat loop calling it and exiting on a refusal. Judged acceptable at T13 (the untested lines are wiring). The fix: a `heartbeat()` on `CaptureAssembly` taking the lock as a port, so the harness drives the heartbeat's checks (lock, thread liveness) and the exit they ask for. **To ride E1-T7** (deploy), where the heartbeat/exit contract is next touched.
- **Review 18 — the Tier-2 writer thread has no liveness backstop.** `QueuedEventLog.deliver` catches `RuntimeException` only, so an `Error` from the delegate (an OOM in Jackson's `detail().toString()`, a `NoClassDefFoundError` on first driver use) ends the `capture-events` thread; `Main`'s heartbeat loop checks the pump and supervisor threads and never `eventsThread`. After 1,000 more belt events the queue is full and every later `RECONNECT`/`WATCHDOG_STALE`/`FEED_DEAD` row is dropped while the process lives on — not silent (the JVM prints the Error, `eventsQueued=1000` pins, `eventWriteFailures=` climbs, each drop a console line), but the pump and the supervisor get an exit on death and the writer does not (chapter 10 names only those two threads). Fix: `if (!eventsThread.isAlive())` in the heartbeat loop (log FATAL and exit, or restart the writer); `catch (Throwable)` in `deliver()` so the cause is logged.
- **Review 19 — small cleanups the range leaves.** (a) `streamDown` ≡ `downReason != null` everywhere — one fact checked twice; (b) the re-arm sequence spelled at two live sites in two orders (inert) and half again on the rebuild path — one `rearm(epic, mono)` would fold the two live sites; (c) `PostgresStore` and `PostgresObservabilityStore` spell the same two user/source SQL literals (the two `InstrumentIds` caches are deliberate, #10; the lookups are not); (d) the watchdog's session verdict is four coupled fields, `fed()`'s `isEmpty()` arm setting the flag on every tick in steady state; (e) `FakeClock`/`FakeSleeper` beside ig-client's `FakeTime` (constrained by ig-client's "depends on nothing of ours"; `PumpTest`'s `NO_SLEEP` lambda could be a `FakeSleeper`); (f) `Database` renders `socketTimeout` with `toSeconds()`, so a sub-second value becomes "0" = disabled — only the package-private test overload can pass one.
- **Review 20 (a)–(h) — the remaining lows**, each verified; (g) is the `rebuild()` "returns false" javadoc nit E1-T13's docs increment takes as 12 (j). **(h) is the one with a data consequence:** `TRADEBENCH_EPICS` is split, stripped and filtered but never de-duplicated or checked for emptiness — a repeated epic makes `IgStreamControl.connect()` subscribe two PRICE+CHART pairs and keep only the second's handles (a `HashMap.put` overwrite), so every tick is delivered twice into `Buffers` and a later RESUBSCRIBE or quarantine drops only the owned pair while the orphan streams until the next `closeStream()`; and `TRADEBENCH_EPICS=','` passes `required()` (non-blank) → `epics=[]` → a service that connects, subscribes nothing and heartbeats healthy with `ticks=0`. The empty case is pre-belt; the orphan-handle consequence is new to the per-market bookkeeping. Fix: `.distinct()` the stream and throw `IgFatalConfigException` on an empty list (config discipline: the runner names its outputs before acting). The others: (a) PLAUSIBLE — the rebuild path stamps `flagClearedMono` after the subscribes went out, so a PRICE snapshot landing in the microseconds before the stamp reads a CLOSED market as open and tick-silent, one extra rebuild if the race is ever won (run `reasking(survivor)` before the rebuild); (b) PLAUSIBLE — `Observation.Subscribed` carries no stamp, so a confirm from a pair a Retry replaced is credited to the new attempt — harmful only under a leg-alternating flap; fix (a) and (b) together by stamping before the subscribes; (c) CONFIRMED — the FAILED re-ask `rebuild(downReason, now)` drops the epic and detail the first attempt carried, so re-asked `SUBSCRIPTION_REJECTED`/`CONNECTION_DEAD` rows name nothing (keep `downEpic`/`downDetail` beside `downReason`); (d) PLAUSIBLE — the weekend stand-down bound rests on a CLOSED item's snapshot parsing as a full tick (`parseTick` needs TIMESTAMP, BID, ASK and DLG_FLAG all present); if IG sends the flag with blank prices the flag is never re-taught and the closed pair is judged dead from Saturday 10:00 — teach the flag independently of a full tick parse, and capture one real weekend snapshot before T6 leans on the bound; (e) PLAUSIBLE — the 1,000-slot refuse-newest queue has no severity-aware admission, so a backlog of INFO `connection_status` rows could refuse a later `FEED_DEAD` — no paced belt path sustains the rate it would take, and `FEED_DEAD`'s console line always precedes the write; belt-and-braces only; (f) PLAUSIBLE — the `synchronized setPerMinute` can wait up to a window behind an `acquire()` sleeping under the monitor (pre-range code), practically unreachable at boot. 20 (i), the G1 note on PR #24's own diff page (pre-redaction values at `-` lines, inherent to the pinned base), is closed by the PR's closure and its branches' deletion.
- **Review 16 — a subscribe snapshot counts as "data" and closes the D29 (5) ladder (PLAUSIBLE).** `fed()` closes the session ladder on any `lastTickMono` advance, `Buffers.onTick` stamps receipt time regardless of the update's own TIMESTAMP, and the MERGE default (no `isSnapshot()` filter, no `setRequestedSnapshot`) delivers each item's last known values on every (re)subscribe — the very snapshot D29 (1) relies on to re-teach the flag — so a zombie that answers every subscribe with a snapshot and then sends nothing would be rebuilt every ~90s plus the handshake, not every 10 minutes; the 2026-08-04 scar cannot settle it (the prototype never asked the zombie) and the harness's `serverAnswers` never delivers a snapshot. **Moot: E1-T13 ruling 1 merged 2026-10-11 (PR #25)** (`319b52c`, merge `287e14e` — the session ladder is time-decayed; data no longer resets it at all, so there is no data door for the snapshot to open) — noted here so the record shows why no fix was made. If that change were ever reverted the review's fix stands: close the ladder on data-time advance (`lastTickAt` newer than at the verdict), or carry `ItemUpdate.isSnapshot()` through `TickUpdate` and skip only the ladder reset for snapshots.
