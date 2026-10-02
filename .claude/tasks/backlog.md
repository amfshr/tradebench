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

**Captured** 2026-09-30 · **Likely home** a future epic (post-E1 spine) · **Relates to** PRD §5.1 (stream-job setup), §2 (tenancy/admin) · D16 (per-user creds), D17 (DB topology) · architecture §3.4 (stream-jobs trajectory)

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
