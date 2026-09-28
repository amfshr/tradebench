# Chapter 2 — Threads and the callback boundary

Why one rule — *cheap work only on transport callbacks* — shapes the whole service.

## The jargon

- **Thread** — an independent line of execution with its own call stack. The JVM runs many at
  once; they share the same heap (the same objects).
- **Blocking** — a thread waiting: on I/O, a lock, a full queue, `sleep`. A blocked thread
  does nothing else — whatever work only it can do simply stops.
- **Callback** — a method *you* wrote that a *library's* thread calls. The critical, easily
  forgotten consequence: your code runs on their thread, and their thread has other duties.
- **Memory visibility / happens-before** — threads may cache values; a write by one thread is
  only *guaranteed* visible to another across a synchronisation edge (lock, `volatile`,
  or — our choice — a `java.util.concurrent` queue: everything written before `offer` is
  visible after `poll`).
- **Shutdown hook** — a thread the JVM starts on Ctrl-C/SIGTERM; your last chance to close
  cleanly.

## The concept

Lightstreamer always delivers updates by calling your listener **from its own internal
thread** (playbook §2.4). That thread is the session's workhorse: it dispatches every
subscription's updates and services the connection's own protocol duties. So:

> Block the LS thread and you don't slow one subscription — you stall the session.
> A slow DB write inside a callback becomes "IG sees an unresponsive client".

Hence the doctrine, inherited as a golden rule and written into CLAUDE.md: **cheap work only
on transport callback threads; queue the rest.** The callback may do microseconds of pure
work — extract, parse, enqueue — and must never throw (an exception would surface *inside*
the library's machinery, which is exactly where the prototype's DB exceptions once landed).

The hand-off is a queue for a second reason besides latency: **it is the memory fence.** Our
code contains no `synchronized`, no manual locks; producer and consumer share domain objects
safely because `LinkedBlockingQueue`/`ArrayBlockingQueue`/`ConcurrentLinkedQueue` provide the
happens-before edge. The domain objects themselves are records — immutable, so once safely
published they are safe forever.

## Our code path

The running capture process is exactly four threads of ours plus the SDK's own:

| Thread | Born where | Does |
|---|---|---|
| `main` | JVM | builds the object graph (`app/Main.main`), then becomes the heartbeat loop: sleep 60s, check the pump is alive, log honest counters |
| LS SDK threads | `LightstreamerClient` (inside `LightstreamerTransport.connect`) | own the socket; deliver every `onItemUpdate`/`onStatusChange` — the callback boundary |
| `capture-pump` | `Main` (`new Thread(pump, "capture-pump")`) | the single consumer: drains queues into the store — chapter 4 |
| `capture-shutdown` | JVM shutdown hook | orderly close on Ctrl-C |

The boundary crossing, concretely
(`market-data-service/src/main/java/dev/amfshr/tradebench/marketdata/ingest/Buffers.java`):
the LS thread runs `IgStreamSession`'s lambda → `StreamParsers` (pure) →
`Buffers.onTick/onSealedBar` → `StreamAdapter.toDomain` (record allocation — within the
cheap-work budget) → `queue.offer` → **return**. Total: microseconds, no I/O, nothing that
can throw. Everything slow — JDBC, batching, flushing, file writes — lives on `capture-pump`,
on the far side of the queues.

Two lifecycle behaviours in `app/Main.java` complete the picture:

- **Pump death is fatal, loudly** (`Main.java:152-160`): the heartbeat checks
  `pumpThread.isAlive()`; if the consumer died (store failure — the pump captures the cause,
  chapter 4), capture is void from that moment, so the process logs the cause and
  `System.exit(1)`. A capture that is silently not persisting is worse than a dead one —
  P9, fail closed and loud.
- **Shutdown order is load-bearing** (`Main.java:127-150`): `stream.close()` *first* (stop
  the faucet), `pump.stop()` and `join(5s)` (drain what's queued — the pump's `run` does a
  final `drainOnce()` + `flush` after `running` flips), only then `sink.close()`, lock, DB.
  Reverse any pair and you lose the tail: close the sink first and the drain writes into a
  closed store; stop the pump first and the still-open stream keeps filling queues nobody
  drains. And if the pump *doesn't* stop within 5s, the sink is deliberately left open — a
  close racing a write is worse than a file missing its tail.

This is also why `IgStreamSession` isn't in a try-with-resources block despite the IDE's
warning: its lifetime is the process's, its close happens on a different thread (the hook)
in a specific order — a lexical `try` can express none of that. The T5 Supervisor makes the
ownership structural.

## The scars

- **The prototype's anti-pattern** (playbook §2.4): SQL batches built and committed **on the
  library's thread**. A locked DB write blocked the very thread IG needed to deliver updates;
  a DB exception surfaced inside library machinery. Every seam in this chapter exists so that
  cannot be re-created.
- **The debugger scar (this repo, T3 live testing).** Alex set a breakpoint inside the stream
  path while capturing live — and the stream stalled. Obvious in hindsight: a breakpoint
  *pauses the thread that hit it*, and in a callback that thread **is** the LS delivery
  thread — the debugger did precisely what a slow DB write would do. Debugging doctrine that
  fell out of it: breakpoints go on the consumer side of the queues (the pump, the store), or
  you watch the heartbeat counters instead; the callback side gets logging, never pauses.
- **The 5s join guard** is the honest compromise of shutdown: prefer a lost tail with a
  logged warning over a corrupt file/store from a close/write race. The JSONL golden-line
  format (chapter 5) is line-framed partly so a truncated tail damages one line, not the file.

*Previous: [Chapter 1 — Sockets and Lightstreamer](01-sockets-and-lightstreamer.md) · [Book index](README.md) · Next: [Chapter 3 — Buffers, queues and backpressure](03-buffers-queues-and-backpressure.md)*
