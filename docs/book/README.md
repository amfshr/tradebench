# The Tradebench Book

A set of learning pages on how this system actually works — the jargon defined, the
concepts from first principles, and then *our exact code paths* walked with file
references. Where the [architecture doc](../design/architecture.md) is the spec and
[decisions.md](../decisions.md) is the why-log, the book is the *teacher*: each chapter
exists so a reader (present or future — including the users this platform is for) can go
from "I've heard the word" to "I could defend this design in review".

Chapters carry their scars deliberately. Almost every rule in this codebase was paid for —
by the Python prototype (the inherited
[IG broker playbook](../inherited/ig-broker-playbook.md) is its ledger) or by an incident
in this repo — and the fastest way to make a rule stick is to tell the story that made it.

## Chapters

The stream is a journey: a price moves in Frankfurt, and some milliseconds later a row
exists in Postgres. The chapters follow it in order, then step back for the machinery
around it.

**Part I — the data's journey**

1. [Sockets and Lightstreamer](01-sockets-and-lightstreamer.md) — TCP to WebSocket, push
   vs poll, what a Lightstreamer session/subscription really is, the MERGE delta trap,
   and why we wrap the SDK behind a seam.
2. [Threads and the callback boundary](02-threads-and-the-callback-boundary.md) — whose
   thread your code runs on, why blocking the session thread stalls everything (including
   under a debugger), and the app's full thread inventory.
3. [Buffers, queues, and backpressure](03-buffers-queues-and-backpressure.md) — bounded vs
   unbounded, shed-oldest vs block, why bars are sacred and ticks are expendable, and why
   backpressure must never reach the socket.
4. [The pump and ack-after-apply](04-the-pump-and-ack-after-apply.md) — delivery semantics
   for grown-ups: at-least-once, idempotency, and the peek → write → remove choreography
   analysed crash-window by crash-window.
5. [The capture store and Postgres](05-the-capture-store-and-postgres.md) — the seam with
   two implementations, batching and its exact boundary, idempotency living in the schema,
   Flyway, and the drift gate.
6. [The advisory lock and single-instance](06-the-advisory-lock-and-single-instance.md) —
   advisory locks vs real locks, why a pooled connection makes a session lock lie, and why
   one IG key means exactly one capture process.

**Part II — the machinery around it**

7. [Clocks: wall, monotonic, and sleep](07-clocks-wall-monotonic-and-sleep.md) — two
   clocks, what each can and cannot promise, how comparing them detects host sleep vs
   process freeze, and why every timing test here runs in milliseconds.
8. [The resilience belt](08-the-resilience-belt.md) — pure decision cores, one impure
   shell: the staleness watchdog, the stuck-substate escalator, backoff, reconnect
   classification, witness quarantine, and gap detection — each with the outage that
   taught it.
9. [Busses and serving data outward](09-busses-and-serving-data-outward.md) — what a
   message bus buys over a database read, the three consumer shapes, persist-then-publish,
   and replay-then-tail.

## How to read it

Front to back works — Part I is the pipeline in data order. But every chapter stands
alone: each defines its own jargon and links onward. If one thing is itching, start there.

House rules for the book itself: chapters teach the system *as merged or in review* (they
say so when something is branch-only); code references are repo-relative paths that must
stay real — a renamed class is a book edit; and new hard-won lessons earn chapters (the
DSL and the backtest engine will join when E3 builds them).
