# The Tradebench Field Manual

A set of learning pages on how this system actually works — the jargon defined, the
concepts from first principles, and then *our exact code paths* walked with file
references. Where the [architecture doc](../design/architecture/README.md) is the spec and
[decisions.md](../decisions.md) is the why-log, the Field Manual is the *teacher*: each chapter
exists so a builder (present or future) can go from "I've heard the word" to "I could defend
this design in review". This is the **Build track** (D21) — engineering internals, for people
working *on* Tradebench. People working *with* it want the User Guide (the Use track), not this.

**Scar convention.** Each chapter leads its scars with a callout — a blockquote the site
renders as a card: `> **Scar** — **<title>** · <dated cost>`, the story, then a
`> **Lesson.** …` line. No icon, no colour (brand R9); the failure stories get dignity.

Chapters carry their scars deliberately. Almost every rule in this codebase was paid for —
by the Python prototype (the inherited
[IG broker playbook](../inherited/ig-broker-playbook.md) is its ledger) or by an incident
in this repo — and the fastest way to make a rule stick is to tell the story that made it.

## Parts

The manual is organised into **parts** (D22-extended): the cross-cutting engineering
**foundations** that every service reuses, then one part per **subsystem** — today the
market-data service; the DSL engine, backtester, and bots join as they're built. Per-component
*reference* (deps, API surface, resilience posture) lives in
[Architecture](../design/architecture/README.md), not here — the Field Manual *teaches*,
Architecture *specs*.

> Chapters keep their original reading-order numbers for now; the parts group them thematically,
> so within a part the numbers aren't yet contiguous. A later pass renumbers per part and makes
> the cross-references name-based (tracked on the backlog) — deferred so it rides the chapter
> merge, not a churn of its own.

### Foundations — the reusable primitives

The concurrency, time, and single-instance machinery that recurs in every service.

- [Threads and the callback boundary](foundations/02-threads-and-the-callback-boundary.md) —
  whose thread your code runs on, why blocking the session thread stalls everything, and the
  app's full thread inventory.
- [Buffers, queues, and backpressure](foundations/03-buffers-queues-and-backpressure.md) —
  bounded vs unbounded, shed-oldest vs block, why bars are sacred and ticks are expendable,
  and why backpressure must never reach the socket.
- [The advisory lock and single-instance](foundations/06-the-advisory-lock-and-single-instance.md)
  — advisory locks vs real locks, why a pooled connection makes a session lock lie, and why
  one IG key means exactly one capture process.
- [Clocks: wall, monotonic, and sleep](foundations/07-clocks-wall-monotonic-and-sleep.md) — two
  clocks, what each can and cannot promise, how comparing them detects host sleep vs process
  freeze, and why every timing test here runs in milliseconds.

### The market-data service — the IG domain

A price moves in Frankfurt, and milliseconds later a row exists in Postgres. These chapters
follow that journey and the resilience that keeps it honest.

- [Sockets and Lightstreamer](market-data-service/01-sockets-and-lightstreamer.md) — TCP to
  WebSocket, push vs poll, what a Lightstreamer session/subscription really is, the MERGE delta
  trap, and why we wrap the SDK behind a seam.
- [The pump and ack-after-apply](market-data-service/04-the-pump-and-ack-after-apply.md) —
  delivery semantics for grown-ups: at-least-once, idempotency, and the peek → write → remove
  choreography analysed crash-window by crash-window.
- [The capture store and Postgres](market-data-service/05-the-capture-store-and-postgres.md) —
  the seam with two implementations, batching and its exact boundary, idempotency living in the
  schema, Flyway, and the drift gate.
- [The resilience belt](market-data-service/08-the-resilience-belt.md) — pure decision cores,
  one impure shell: the staleness watchdog (quiet vs dead), the stuck-substate escalator,
  backoff, reconnect classification, witness quarantine, and gap detection — then the
  `Supervisor` that runs them: two threads, pushed events vs pulled freshness, feeding the
  watchdog only on advance, and the escalation ladder — each with the outage that taught it.
- [Busses and serving data outward](market-data-service/09-busses-and-serving-data-outward.md)
  — what a message bus buys over a database read, the three consumer shapes,
  persist-then-publish, and replay-then-tail.

- [The failure playbook](market-data-service/10-the-failure-playbook.md) — scenario-first:
  what breaks, what the belt does, what each outage costs, who finds out, how it heals; every
  clock and how they interact; the threads and the one race a fence can't cover; the three
  database tiers; the dated rulings behind every number.

## How to read it

Every chapter stands alone — each defines its own jargon and links onward — so if one thing is
itching, start there. For a first read, **Foundations** gives you the primitives, then **The
market-data service** walks the live pipeline that uses them.

House rules for the Field Manual itself: chapters teach the system *as merged or in review* (they
say so when something is branch-only); code references are repo-relative paths that must stay
real — a renamed class is a book edit; parts form organically as subsystems land; and new
hard-won lessons earn chapters (the DSL and the backtest engine will join when E3 builds them).
