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
