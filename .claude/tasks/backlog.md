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
