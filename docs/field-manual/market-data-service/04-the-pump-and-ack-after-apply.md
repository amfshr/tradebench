# Chapter 4 — The pump, and exactly what ack-after-apply means

The queues in chapter 3 end at a cliff: events sit in memory, and something must carry
them into the store without losing the ones that matter. That something is
`ingest.Pump` — one small class whose every line is a delivery-semantics decision.

## The jargon

- **Consumer loop** — a thread that repeatedly takes work from a queue and processes it.
  Ours is the single consumer the whole ingest design funnels into.
- **Delivery semantics** — the guarantee a pipeline makes about each event: *at-most-once*
  (never duplicated, may be lost), *at-least-once* (never lost, may be duplicated),
  *exactly-once* (the marketing myth — see below).
- **Ack (acknowledge)** — telling the queue "I'm done with this one, discard it". In our
  pump, ack = removing the event from its queue.
- **Apply** — actually doing the work: the store accepted the write.
- **Ack-after-apply** — the ordering rule that turns a queue into a reliability tool:
  acknowledge *strictly after* the work succeeded.
- **Drain** — one pass that empties whatever the queues currently hold.

## The concept

Every pipeline picks its delivery guarantee whether it means to or not — the choice hides
in one line of code: *when do you remove the event from the queue?*

```
take → remove → apply     removed before applied:  a crash here loses the event   → at-most-once
take → apply  → remove    applied before removed:  a crash here re-applies it     → at-least-once
```

There is no third ordering. "Exactly-once delivery" across a process boundary is a myth —
some failure window always exists between apply and ack. What real systems do is
**at-least-once delivery + idempotent apply**: allow the duplicate, make it harmless. The
duplicate-absorbing half of our story lives in the database schema (chapter 5); the
never-lost half lives here.

Why a *single* consumer? Three reasons. Ordering: one thread draining means bars apply in
arrival order with no interleaving to reason about. Simplicity at the sink:
`PostgresStore` and `JsonlStore` get to be plain single-threaded classes — "only the pump
calls a sink" is a contract, so they need no locks, no thread-safe batching, nothing.
And criticality: with one consumer, "bars before ticks" is one loop's statement order,
not a scheduling negotiation between threads.

## Our code path

`market-data-service/src/main/java/dev/amfshr/tradebench/marketdata/ingest/Pump.java` —
the whole thing is under a hundred lines. The heart is `drainOnce()`:

```java
Bar1m bar;
while ((bar = queues.peekBarNow()) != null) {
    sink.write(bar);          // apply
    queues.removeBarNow();    // ack — strictly after
    written.incrementAndGet();
}
```

**Peek → write → remove**, never `poll → write`. Walk the failure windows:

1. **The write throws.** The bar was *peeked*, not removed — it is still at the head of
   its queue. Nothing was acknowledged that wasn't applied. `PumpTest.
   sinkFailureStopsThePumpKeepsTheBarAndKeepsTheCause` pins this exact scenario:
   after the sink throws, `queues.peekBarNow()` is still non-null.
2. **The process dies between `write` and `remove`.** The bar reached the store *and*
   is still queued — but the process is gone, so the in-memory queue is gone with it.
   No harm: the write landed.
3. **The process dies before the write.** The queue dies with the process and the bar is
   lost from *this* pipeline. This is the honest limit of an in-memory queue —
   ack-after-apply protects against sink failures and shutdown races, not RAM loss.
   Process-death recovery is a different layer's job: Lightstreamer replay on reconnect
   (chapter 8) and the T6 REST heal re-fetch the missing minutes into the same
   idempotent schema.

The drain order is **bars → state changes → ticks**, and it's criticality made into
statement order (P9-adjacent thinking: protect the thing you cannot re-create cheaply).
Bars are the healable backbone (D15: healed 1m is the canonical base — everything higher
derives from it). State changes are rare and tiny. Ticks come last *and* best-effort —
plain `pollTickNow()` with no peek/remove dance, because their queue already sheds oldest
under pressure (chapter 3); pretending ticks are guaranteed after that would be a lie.
`TICK_BATCH = 5_000` bounds one drain's tick work so a firehose can't starve the bar
queue between passes.

**When the sink breaks, the pump stops — loudly, once.** `run()` catches the
`RuntimeException`, stores it in the `volatile failure` field, and exits the loop. It
does *not* retry into the broken sink: the test asserts `barAttempts == 1`, because a
re-drain would throw a fresh exception and bury the original cause — masking the first
error is the sin. The main thread's heartbeat notices the dead pump thread and exits the
process with `FATAL: capture pump died` (`app/Main.java`) — fail closed, fail loud (P9).

**Shutdown is a tail drain.** `stop()` just flips `running`; `run()` then performs one
final `drainOnce()` + `flush()` so the day's last minute survives a Ctrl-C
(`PumpTest.stoppedRunStillDrainsTheTail`). Main's shutdown hook orders the funeral:
close the stream (stop the faucet), `pump.stop()`, join with a 5-second budget, and only
then close the sink — never close a sink a pump might still be writing to.

Two small honesty details. The idle branch of `cycle()` calls `sink.flush()` before
sleeping — quiet moments are when buffered ticks (Postgres batch, JSONL writer buffer)
get pushed to durability, so an idle market is not an unflushed market. And
`writtenCount()` increments only after `sink.write` returns: the heartbeat's `written=`
is sink-accepted truth, deliberately a different number from the queues' received
counters — when they diverge, that divergence *is* the diagnostic. The `Sleeper` is
injected, which is why `PumpTest` runs in milliseconds with a no-op sleeper.

## The scars

> **Scar** — **The invariant that breaks invisibly** · playbook §1.4, test audit
>
> The prototype's test audit found that **ack-after-apply is the easiest invariant to break
> invisibly** — a consumer that acks before applying loses events silently, and nothing goes
> red unless a test pins it.
>
> **Lesson.** Pin ack-after-apply with a mutation-verified test on every consumer — intent is
> not enough. `PumpTest`'s sink-failure test asserts the un-acked bar is still at the queue head.

The prototype's engineering playbook is blunt about consumer loops — §1.4 is titled
"*At-least-once delivery + idempotent consumers, or silent lies*" — and it carries a
sharper, subtler finding from the prototype's test audit: **ack-after-apply is the easiest
invariant to break invisibly**, so it must be *genuinely pinned by tests on every
consumer*, not merely intended. That is why `PumpTest` doesn't just exercise the happy
path — the sink-failure test asserts the un-acked bar is still at the head of the queue,
and the suite was mutation-verified by applying the exact break (remove-before-write) and
watching it go red. The "no re-drain" rule is reasoning rather than a recorded incident,
but the reasoning is airtight: a retry into a broken sink throws a fresh exception over
the original cause, and the first error — the only interesting one — is the thing you
must never bury.