# Chapter 6 — The advisory lock and single-instance discipline

Before the capture runner speaks a single byte to IG, it takes a lock in Postgres. This
chapter explains what an advisory lock is, why the *connection it rides on* is the whole
design, and why running two of this service is the one mistake the code refuses to let
you make.

## The jargon

- **Row/table locks** — Postgres's ordinary locks, tied to data: they protect *rows and
  tables* during transactions, and Postgres decides their meaning.
- **Advisory lock** — a lock whose meaning is entirely yours. It protects no data;
  it's a 64-bit key that Postgres will let exactly one session hold. "Advisory" because
  it only constrains code that *asks* — nothing stops a writer that never takes it.
- **Session** — one client connection to Postgres; one backend process on the server.
  Session-scoped advisory locks live exactly as long as their session.
- **Session-scoped vs transaction-scoped** — `pg_advisory_lock` family (held until
  released or the session ends) vs `pg_advisory_xact_lock` (released at commit/rollback).
- **`pg_try_advisory_lock`** — the non-blocking variant: returns `true`/`false`
  immediately instead of queueing. Ours.
- **Re-entrancy** — the same session may acquire the same advisory lock again,
  successfully, any number of times. Within one session, the lock never says no.

## The concept

A single-instance guard needs a mutex that (a) is visible across processes and machines,
and (b) **cannot go stale**. PID files fail (b) famously: the process dies, the file
remains, and every restart script grows a "check if the PID is really alive" wart. A
session-scoped advisory lock is the elegant fix: the lock *is* the connection. Process
dies → TCP drops → Postgres reaps the backend → lock freed. There is no cleanup code
because there is nothing to clean up.

But that elegance has a precondition that pooled connections quietly destroy. A pool like
Hikari doesn't close connections — `close()` *returns the session to the pool, alive*.
Two lies follow:

1. **Release is a lie.** You "close" the lock's connection; the session lives on in the
   pool, still holding the lock. Your service now blocks its own restarts.
2. **The test of the lie passes.** Ask the pool for a connection to verify the lock is
   free, and it may hand you *the same session* — which, by re-entrancy, acquires the
   lock "successfully". Green test, broken release.

Rule worth keeping: **a lock whose lifetime must equal a session's lifetime must own a
dedicated, non-pooled session.** The pool is for statements; the lock is for the process.

## Our code path

`market-data-service/src/main/java/dev/amfshr/tradebench/marketdata/store/SingleInstanceLock.java`:

```java
Connection connection = database.dedicatedConnection();   // raw DriverManager session
... SELECT pg_try_advisory_lock(?)                        // key 0x7472_6462_0001
if (!result.getBoolean(1)) {
    connection.close();
    throw new IllegalStateException("another market-data-service instance holds the
        advisory lock — refusing to run (playbook §7)");
}
```

`Database.dedicatedConnection()` goes straight to `DriverManager` — deliberately around
Hikari. The class then holds that raw connection as its only field; `close()` closes the
session, and closing the session *is* the release. `LOCK_KEY` is a stable, documented
constant (`0x7472_6462_0001` — "trdb", service #1): one advisory key per service, so a
future second service takes its own key, not a collision.

Contention is a **try**-lock plus a loud refusal, not a wait. A second instance is a
config mistake (two terminals, a stray systemd unit), and mistakes should fail with a
named reason (P9) — queueing politely behind the real instance would just hide the
mistake until it fired at the worst time.

**The ordering rule lives in `app/Main.java`:** in the `db` sink case, the sequence is
`Database.connect` → `SingleInstanceLock.acquire` → build the store — and only *after*
all that does the runner log into IG and open a Lightstreamer connection. The loser of
the lock race must die **before touching the broker**, because the thing being protected
isn't really the database (the schema's idempotency would absorb duplicate writes — see
chapter 5). It's the *IG account*: D16's hard constraint is **one API key per account**,
shared by everything Alex runs. A second capture instance means a second Lightstreamer
connection and a doubled request pattern on that one shared key — both instances wobble,
and so does anything else using the account. The lock is single-*writer* discipline
enforced at the cheapest possible point: before the first byte.

(The `jsonl` sink path takes no lock — each run writes its own timestamped file, so
there's nothing to contend for. The lock guards the shared things: the database and the
broker connection.)

The tests, `SingleInstanceLockTest`, are shaped by the scar below:

- `secondInstanceIsRefusedWhileTheFirstHoldsTheLock` — a **genuine cross-session** check:
  each `acquire` opens its own dedicated session, so the refusal proves exclusivity
  between sessions, not re-entrancy within one.
- `closeReleasesTheLockForANewSession` — close the first, acquire with a **new** session.
  This is the test that pins release itself: if `close()` leaked the session (the pooled
  defect), this acquire is refused and the test goes red.

## The scars

> **Scar** — **The pooled lock that lied** · doctrine review F1, 2026-09-28
>
> T4's first single-instance lock rode a Hikari **pooled** connection: `close()` returned the
> connection to the pool still holding the session-level lock, and the release test passed
> *via re-entrancy* — a green test over a broken release.
>
> **Lesson.** A lock whose lifetime must equal a session's must own a dedicated, non-pooled
> connection; and a test must be able to fail — anchor it independently of the code (G5).

Two, and they compound. Playbook §7 records the prototype's original sin — an observed
double launch (a restart race, a stray process a kill missed) that caused upsert
deadlocks, doubled warnings, duplicate stream entries, and two Lightstreamer connections
on one IG key, the last being a terms-of-service risk; "single instance, enforced, before
broker contact" came out of that. Then this repo re-learned the pooled half the hard way:
T4's first lock rode a Hikari connection. The doctrine review (finding F1, 2026-09-28)
caught that `close()` returned the connection to the pool still holding the session-level
lock — and that the release test was passing *via re-entrancy*, the exact false-positive
described above. The fix was structural, not a patch: `dedicatedConnection()`, the raw
session as the lock's identity, and a test rewritten so the defect it exists to catch
would actually turn it red (testing doctrine G5 — anchor expectations independently of
the code under test).