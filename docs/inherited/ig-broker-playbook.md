# IG Markets Broker Playbook — Lessons Learned

> **Purpose.** A single, portable reference of everything this project has learned the hard way
> about the **IG Markets API** — session/account behaviour, Lightstreamer streaming resilience,
> and dealing-rule constraints. It is written to be carried into a **fresh (Java) rebuild**: the
> lessons and the *why* are broker-level and language-agnostic; where a battle-tested value or
> pattern lives in the current Python codebase, the file:line is cited so you can port from the
> source of truth rather than rediscover it live against IG.
>
> Everything here was learned against a **demo** account (which doubles as the forward-test env)
> and, where noted, verified on **live**. The IG API version in use is the **v2 CST/X-SECURITY-TOKEN
> session** (not OAuth) with **Lightstreamer** for streaming and **REST v3** for historical prices.

---

## The five golden rules (read these even if you read nothing else)

1. **A fresh login lands on the profile's *preferred* account, not the one you want.** The streaming
   tokens are bound to the active account and every stream item is account-scoped, so if you don't
   explicitly switch, *every subscription silently fails* with the misleading `Invalid account type`.
   This is the "streaming connects but shows nothing" bug. → [§1.1](#11-the-preferred-account-trap-streaming-shows-nothing)
2. **Never hardcode dealing constraints.** IG's `minNormalStopOrLimitDistance` drifted `8→10→12→8→10`
   in a single week, differs demo↔live, and one rule even *changes unit* between environments. Read
   them live at startup, unit-check, keep config only as a fallback. → [§5.1](#51-the-dealingrules-fields)
3. **A `CONNECTED` socket is not a healthy feed.** IG can sit at `CONNECTED:WS-STREAMING` while ticks
   go to zero, and can hang for hours in a `DISCONNECTED:WILL-RETRY` substate. Neither trips a normal
   disconnect handler. You need an independent staleness watchdog and a stuck-substate escalator.
   → [§3.4](#34-silent-while-connected--the-staleness-watchdog), [§3.3](#33-the-stuck-substate-gap--hours-in-will-retry)
4. **The database is downstream of decisions, never upstream.** All business logic (aggregation,
   indicators, geometry, risk) runs in memory. Postgres is the system of record + audit + reporting
   store; it is never read back inside the trade loop. Data *may* heal in the DB after the fact;
   decisions may not.
5. **Do cheap work on the broker's callback thread; hand everything else to a queue.** Lightstreamer
   delivers every update from its own internal thread. Blocking it on a DB write or a lock stalls the
   feed itself. → [§2.4](#24-threading--the-callback-discipline)

---

## Table of contents

- [§1 Session, authentication & account selection](#1-session-authentication--account-selection)
  - [1.1 The preferred-account trap ("streaming shows nothing")](#11-the-preferred-account-trap-streaming-shows-nothing)
  - [1.2 The login-cache curse (stale tokens)](#12-the-login-cache-curse-stale-tokens)
  - [1.3 Token lifecycle & the streaming auth model](#13-token-lifecycle--the-streaming-auth-model)
  - [1.4 Account switching mechanics](#14-account-switching-mechanics)
  - [1.5 Demo vs live](#15-demo-vs-live)
  - [1.6 Error classification: fatal vs retryable vs session-dead](#16-error-classification-fatal-vs-retryable-vs-session-dead)
  - [1.7 Config & credential traps](#17-config--credential-traps)
- [§2 Streaming (Lightstreamer): subscriptions & semantics](#2-streaming-lightstreamer-subscriptions--semantics)
- [§3 Streaming resilience: the recovery stack](#3-streaming-resilience-the-recovery-stack)
- [§4 Data integrity: gap detection, backfill & healing](#4-data-integrity-gap-detection-backfill--healing)
- [§5 Dealing rules & trading constraints](#5-dealing-rules--trading-constraints)
- [§6 Operational limits & rate limits](#6-operational-limits--rate-limits)
- [§7 Single-instance & single-key discipline](#7-single-instance--single-key-discipline)
- [§8 Quick reference: the load-bearing numbers](#8-quick-reference-the-load-bearing-numbers)
- [§9 Java porting notes](#9-java-porting-notes)
- [§10 Open questions still with IG](#10-open-questions-still-with-ig)

---

## 1. Session, authentication & account selection

All IG-specific code in the reference implementation is deliberately isolated in **one file**,
`src/igtrader/common/ig_client.py`, with the error taxonomy in `common/ig_errors.py`. If you port
one thing carefully, port these two.

### 1.1 The preferred-account trap ("streaming shows nothing")

**This is the headline gotcha.** During the first live streaming spike, the Lightstreamer socket
connected fine (`CONNECTED:WS-STREAMING`) but **every subscription was rejected and no data arrived**.
The error was the misleading `ERROR -1: Invalid account type`.

Two compounding causes:

- **Cause A — the preferred-account trap.** A v2 `POST /session` login **always activates whichever
  account is flagged `preferred=true` on the profile** — there is *no login parameter to choose an
  account*. Our profile's preferred account was a **CFD** account, but we trade the **spread-bet**
  account:

  ```
  Z6CS3D  CFD         preferred=True   ← every fresh login lands here
  Z6CS3E  SPREADBET   preferred=False  ← the account we actually trade
  ```

  The session tokens (CST / X-SECURITY-TOKEN) are **bound to the active account**, and the price
  stream item is itself **account-scoped** (`PRICE:{accountId}:{epic}`). Subscribing while the
  session is on the wrong account fails every item — but the *socket* connects (IG validates tokens
  lazily), so it looks like "connected but no data."

- **Cause B — the library never set the Lightstreamer user.** The `trading-ig` library's
  `IGStreamService` left the Lightstreamer `user` as `None` and had no hook to switch account between
  login and connect. (Java relevance: this specific bug is Python-library-specific, but the *shape*
  — "you must set the LS user to the account id explicitly" — is universal.)

**The fix — sequence it explicitly: login → switch account → re-read tokens → set LS user → connect.**

```
1. POST /session               → lands on preferred account; capture accounts[] + currentAccountId
2. PUT  /session {accountId, defaultAccount:false}   → switch to the trading account (refreshes tokens!)
3. re-read CST + X-SECURITY-TOKEN response headers    → tokens changed on the switch
4. Lightstreamer: setUser(accountId); setPassword("CST-<cst>|XST-<xst>")
5. connect, then subscribe
```

Reference: `ig_client.py:424-464`. The load-bearing lines:
`account_id = settings.ig.acc_number or current` (`:445`); `if account_id != current: rest.switch_account(account_id, False)` (`:452-454`); re-read headers (`:456-457`); `setUser` / `setPassword` (`:461-462`).

**The healthy log signature** to look for after a correct bring-up:

```
POST '/session', resp 200
switched active account Z6CS3D -> Z6CS3E
Lightstreamer status: CONNECTED:WS-STREAMING
subscribed: PRICE:IX.D.DAX.DAILY.IP
```

> **Diagnosis footgun we hit.** The spike that found this also happened to probe
> `MARKET:CS.D.EURUSD.TODAY.IP`, but the `MARKET` subscription had already been **decommissioned by
> IG (May 2026)** — so that probe would have failed for a *second, unrelated* reason, muddying the
> signal. When diagnosing "no data," subscribe to something you *know* is current (a PRICE item on a
> live epic), not a legacy item.

### 1.2 The login-cache curse (stale tokens)

**IG serves *cached* `/session` responses to rapid repeat logins (roughly a 60-second window), and
the cached response can carry *stale* tokens.** Every follow-up call then fails
`error.security.account-token-invalid`. Observed live 2026-07-16.

Two consequences:

- **A reconnect/rebuild must NOT re-login.** Reuse the existing REST session object and do a cheap,
  side-effect-free `GET /session` (`read_session`) to validate the tokens. Only when *that* raises
  (tokens genuinely dead) do you drop the cached session and log in fresh. Reference:
  `ig_client.py:424-441`; the supervisor drops the session on exception at `service.py:316`.
- **Stagger independent logins by >60 seconds.** Two processes (e.g. a market-data service and an
  OMS, or a dev checkout and the deployed instance) sharing one API key must not log in within the
  same ~60s window. The production schedule bakes this in (market-data at 06:00, OMS at 06:02).

### 1.3 Token lifecycle & the streaming auth model

- **Streaming has no login of its own.** The Lightstreamer socket authenticates with the REST
  session's tokens: `user = accountId`, `password = "CST-<cst>|XST-<xst>"`.
- **Lightstreamer does NOT accept OAuth — only the v2 CST/X-SECURITY-TOKEN session.** This is why the
  code reads the `CST` and `X-SECURITY-TOKEN` *response headers* off the login and re-reads them after
  every account switch. If you build the client on OAuth v3, streaming will not authenticate.
- **Token lifetime: 6 hours, extended in use, to a hard maximum of 72 hours.** A long-running service
  *will* eventually hit auth-expired reconnect failures; the correct recovery is a full session
  rebuild (fresh login). Therefore **token-expiry errors are retryable, not fatal** (see §1.6).

### 1.4 Account switching mechanics

- **`POST /session` (v2)** activates the `preferred=true` account. Response includes
  `currentAccountId` and the full `accounts[]` list (each with `accountId`, `accountType` ∈
  {`CFD`, `PHYSICAL`, `SPREADBET`}, and `preferred`). **No parameter selects an account at login.**
- **`PUT /session`** switches the active account. Body: `accountId` plus optional
  `defaultAccount: bool`.
  - **`defaultAccount: false` is the one to use.** It switches only the *current session* and leaves
    the profile's permanent preferred account unchanged — so it is safe to call on every
    connect/reconnect. `true` would permanently change the profile default (don't).
  - **Switching refreshes the session tokens** — re-read CST/X-SECURITY-TOKEN afterwards.
  - Switch errors: `error.switch.accountId-must-be-different` (already on it — benign),
    `error.switch.invalid-accountId` (fatal config).
- **Why the account matters for streaming:** the `PRICE`, `ACCOUNT`, and `TRADE` stream items are all
  **account-scoped** in the item name (`PRICE:{accountId}:{epic}`, `ACCOUNT:{accountId}`,
  `TRADE:{accountId}`). `CHART:{epic}:{scale}` is **not** account-scoped — which is a trap: a naive
  "is streaming working?" test that only subscribes to CHART will succeed even on the wrong account
  and hide the problem. Test with a PRICE item.

### 1.5 Demo vs live

- **Environment is chosen by one flag** (`ig_env` = `demo` | `live`) that selects between two
  credential profiles; IG issues **separate API keys and account numbers per environment**, and
  demo/live have **different base URLs**. Flipping environments should require nothing but the flag.
- **Demo doubles as forward-testing** — the same engine points at demo or live.
- **Live and demo genuinely differ in ways that bite:**
  - Dealing-rule *values* differ (see §5). One rule (`minControlledRiskStopDistance`) even **changes
    unit** — `25 POINTS` on demo vs `2 PERCENTAGE` on live for DAX.
  - `minDealSize` differs (DAX demo `0.04` vs live `0.01`).
  - **Demo cannot measure real costs.** IG demo has *no slippage, no interest adjustments, no
    out-of-hours movement, and never rejects on price.* So demo will never surface the min-stop
    rejection or the real slippage that decide the strategy's viability. Treat demo as a
    *correctness/plumbing* environment, not a *cost/edge* environment.

### 1.6 Error classification: fatal vs retryable vs session-dead

This taxonomy (reference: `common/ig_errors.py`, shared by every service) is the most directly
reusable artefact for a new build. Classify every IG error into three buckets:

- **Retryable — a rebuild fixes it (do NOT stop):**
  - Token-expiry: `error.security.account-token-*`, `error.security.client-token-*`, and the
    library's `TokenInvalidException` class.
  - Rate limits, `system.error`, `get.session.timeout`.
- **Fatal config — retry can *never* fix it; stop immediately to avoid an IG lockout:**
  - `error.security.api-key-*`
  - `error.public-api.failure.preferred.account.disabled` / `.not.set`
  - `error.security.account-suspended`
  - `error.switch.invalid-accountId`
  - `validation.*`
  - Match **case-insensitively by family/substring** — IG varies the validation suffix by field name.
- **Session-dead:** a `GET /session` that raises means the tokens are dead → drop the cached session,
  log in fresh (respecting the >60s stagger from §1.2).

> **Why "stop immediately" on fatal config matters:** hammering IG's login endpoint with retries on a
> bad key or a suspended account risks getting the key **revoked**. "Nothing retries forever," and
> some things must not retry at all.

### 1.7 Config & credential traps

- **Username regex: `[A-Za-z0-9\-_]{1,30}` — no dots.** `amfshr.demo` failed with
  `validation.pattern.invalid.authenticationRequest.identifier`; `amfshr-demo` (hyphen) worked.
- **Account-id regex: `[A-Za-z0-9\-]{1,30}` — no underscore** (asymmetric with the username pattern,
  which *does* allow underscore). Neither allows dots.
- **Keep secrets out of the main config file.** In the reference build, non-secret config lives in
  `config.local.toml` and **secrets live in a separate `.env`** (IG username/password/api-key/account
  number per environment). Rationale from two real leak incidents in one week: a config file pasted
  into chat, and a whole-file copy between machines. The `.env` is "too small and too obviously
  radioactive to whole-file-copy."
- **`.env` quoting: single-quote values.** An IG password containing `$` leaks/expands if unquoted
  or double-quoted.
- **No code defaults for required keys.** A missing key should fail *at startup, naming the key* —
  never fall back to a silent default for anything security- or account-shaped.

---

## 2. Streaming (Lightstreamer): subscriptions & semantics

### 2.1 The two subscriptions per market

| Subscription | Item | Mode | Data adapter | Purpose |
|---|---|---|---|---|
| **PRICE** (ticks) | `PRICE:{accountId}:{epic}` | `MERGE` | set explicitly to `Pricing` | bid/ask ticks |
| **CHART 1-minute** (bars) | `CHART:{epic}:1MINUTE` | `MERGE` | **none** — demo/live expose only the default | OHLC bars |
| **TRADE** (OMS only) | `TRADE:{accountId}` | `DISTINCT` | — | order/position updates |

- **PRICE fields:** `TIMESTAMP, BIDPRICE1, ASKPRICE1, DLG_FLAG`.
- **CHART fields:** `UTM, CONS_END, BID_{OPEN,HIGH,LOW,CLOSE}, OFR_{OPEN,HIGH,LOW,CLOSE}`.
- **Mode matters:** PRICE/CHART are `MERGE` (each field snapshot-then-delta); the OMS's TRADE stream
  is `DISTINCT` (each update is a discrete event, not a merge). Don't blanket-apply one mode.

### 2.2 Hard-won field semantics

- **Timestamps (`PRICE.TIMESTAMP`, `CHART.UTM`) are epoch milliseconds delivered as strings.** Parse
  as long, treat as UTC.
- **A CHART candle is complete ONLY when `CONS_END == "1"`.** The CHART stream pushes *partial* candle
  updates continuously as the minute forms; every update where `CONS_END != "1"` must be discarded.
  Persisting partials silently corrupts your bars.
- **CHART carries bid OHLC and offer OHLC separately; the "mid" is computed per field** (mid of the
  two opens, mid of the two highs, …) — deliberately **not** the true high/low of a mid series. This
  matches the legacy SQL oracle's formula; keep it consistent with whatever you compare against, and
  document that it is intentional.
- **`DLG_FLAG` is your market-state signal** (see §3.4). Vocabulary includes
  `CLOSED, CALL, DEAL, EDIT, CLOSINGSONLY, DEALNOEDIT`. **The wire pads it with trailing spaces**
  (`"DEAL "`) — always `.strip()` before comparing.

### 2.3 Two "there is no shortcut" facts

- **No native higher timeframes.** CHART scales are only `SECOND | 1MINUTE | 5MINUTE | HOUR`. There is
  no 2/3/10/15-minute scale. **All higher timeframes must be aggregated in your own code** from the
  1-minute bars. (This repo aggregates 1m → 10m in memory and proved it byte-for-byte against the
  legacy SQL triggers.)
- **`MARKET:{epic}` / `MARKET_STATE` was decommissioned (May 2026).** There is no market-state
  subscription. Derive open/closed from PRICE's `DLG_FLAG`, not from a market-state item. (A
  leftover `MARKET` subscription in old code will just fail.)
- `CHART:{epic}:TICK` exists but is charting-oriented and redundant once you have PRICE bid/ask —
  don't use it.

### 2.4 Threading & the callback discipline

**Lightstreamer always delivers updates by calling your listener from its own internal thread.** This
is not something you arrange; it is how the client works (true for the Java SDK too). The single most
important structural rule:

> **The callback must do only cheap, non-blocking, non-throwing work, then return in microseconds.**

The reference callback does exactly three things: extract fields to a small object, parse to a
`Tick`/`Bar` value (pure, no I/O, malformed input dropped and *never* throws), and put it on a queue.
Everything slow or fallible (DB writes, Redis publishes, reconnects, the heartbeat) happens on a
separate consumer thread that drains the queue.

The prototype's anti-pattern — building SQL batches and running `executemany` + `commit` **on the
library's thread** — meant a slow or locked DB write blocked the very thread IG needs to deliver
updates, and a DB exception surfaced *inside* the library's machinery. Don't do broker I/O and
database I/O on the same thread.

**Queue design (split by criticality):**

- **Bars: unbounded queue, never dropped.** ~1/min, they are the must-persist backbone.
- **Ticks: bounded queue (~100,000), shed-oldest with a counter when the consumer stalls.** Freshest
  data wins; backpressure must **never** reach the socket (IG must never see a slow consumer). At full
  DAX rate (~20/s) 100k ticks is ~83 minutes of buffer.
- **Drain order: bars first, then ticks.**

---

## 3. Streaming resilience: the recovery stack

Resilience is layered. Each layer exists because a specific failure slipped past the layer below it —
they are all "closes a live incident we actually saw," not speculative.

### 3.0 The three layers

1. **Transient blips → Lightstreamer's built-in auto-reconnect (free).** The library owns this; your
   code only observes.
2. **Unrecoverable → a supervisor rebuilds the whole session** (fresh login or session reuse per §1.2,
   then re-subscribe) with exponential backoff.
3. **Subscription failures → a blast-radius judgment** (quarantine one market vs rebuild the session).

### 3.1 `is_dead` is deliberately eager

The **first** `onServerError` marks the connection dead, even if the library *might* have ridden it
out. Rationale: "for a trading feed, one unnecessary rebuild beats trusting a wounded session." A bare
`DISCONNECTED` (auto-reconnect gave up) is also dead. The recoverable substates
`DISCONNECTED:WILL-RETRY` and `DISCONNECTED:TRYING-RECOVERY` do **not** trip `is_dead` — see §3.3 for
why that gap needed its own handler.

### 3.2 Backoff & retry ceiling

- **Backoff formula:** `base * 2^(attempt-1)`, jittered **±50%** (`uniform(0.5, 1.5)`), capped.
- **Concrete defaults:** `backoff_base = 1.0s`, `backoff_cap = 60.0s`, and a **floor**
  `rebuild_min_delay = 5.0s` applied on *every* rebuild — because rapid re-login storms trip IG-side
  caching (§1.2) and throttles. Be kind to IG even on the first retry.
- **Retry ceiling:** `max_consecutive_failures = 10` on the exception path; on the 10th consecutive
  failure, stop cleanly (exit 0). Nothing retries forever.
- **Fatal-config short-circuit:** credential/entitlement errors (§1.6) stop *immediately*, bypassing
  the retry ladder.
- **Keep the reconnect count cumulative across rebuilds** so your heartbeat's `reconnects=N` reflects
  the whole run, not the current connection.

### 3.3 The stuck-substate gap — hours in `WILL-RETRY`

**Live specimen (2026-08-04):** a connection sat **2h56m stuck in `DISCONNECTED:WILL-RETRY`** with no
escalation; separately the OMS's TRADE stream sat 22+ minutes in `TRYING-RECOVERY`. `is_dead` only
fires on a *bare* `DISCONNECTED` or a server error — so a client that hangs in a `DISCONNECTED:*`
*substate* is invisible to it, and the library's own auto-reconnect had given up trying. Only a full
rebuild (fresh REST login + new LS connection) recovers this.

**Mitigation — measure how long the client has sat in a `DISCONNECTED:*` substate and force a rebuild
past a threshold**, using a **monotonic clock** so a host sleep isn't mistaken for an outage.
Status-keyed thresholds because the two substates mean different things:

- **`DISCONNECTED:WILL-RETRY` → escalate sooner (120s).** Server-side recovery has been *abandoned*;
  there is nothing to replay, so waiting only delays capture.
- **`DISCONNECTED:TRYING-RECOVERY` → wait longer (300s).** Recovery is in progress and the server can
  still *replay* the gap losslessly, so it's worth more patience.
- Invariant: `will_retry ≤ rebuild`.

### 3.4 Silent-while-`CONNECTED` — the staleness watchdog

**Live specimen (2026-07-21):** DAX ticks went from ~91/min to **zero for four minutes** while
Lightstreamer reported `CONNECTED:WS-STREAMING` the entire time. No disconnect fired, so the reconnect
layer saw nothing. **A connected socket is not a live feed.** You need a watchdog that measures
*data freshness*, not connection status.

Key design decisions (reference: `market_data/watchdog.py`):

- **Market state comes from the stream, not a calendar.** The watchdog stands down when the last-seen
  `DLG_FLAG` is in `{CLOSED, SUSPEND}` — so weekends/holidays self-suppress with **no calendar and no
  per-market hours config**. An *unknown* flag does **not** suppress (a resubscribe is harmless on a
  closed market and its snapshot teaches the real flag).
- **Two independent staleness signals per market:**
  - **tick-silent:** no tick for **90s** (in-window DAX runs ~90 ticks/min, so 90s is unambiguous).
  - **bar-silent-while-ticks-flow:** no completed 1m bar for **210s** *although ticks still flow* —
    the only way to detect a dead CHART subscription while PRICE is fine (a genuinely tickless minute
    legitimately has no bar, so you can't infer a dead CHART from bar-silence alone).
- **Remedy ladder:** surgical per-market **resubscribe ×2** → full session **rebuild**. If **≥2
  markets are stale together**, it's session-shaped → rebuild directly. (At N=1 the ladder always
  runs — the multi-market shortcut can't fire.)
- **Storm guard:** each remedy **doubles** that market's grace period (base 60s, **capped at 30 min**),
  so a genuinely-silent-but-open market decays to ~1 remedy per 30 min — never a rebuild storm, never
  needs a human. An episode resets **only on positive healing evidence of the same signal kind** that
  provoked it (a bar heals a bar-remedy, a tick heals a tick-remedy) — "any item heals" was rejected
  because flowing ticks would endlessly re-arm a dead-CHART episode.
- **Clock-failure immunity:** host sleep is caught by wall-clock-ahead-of-monotonic skew (>5s);
  process freeze by a monotonic jump between ~1s rounds (>10s). Either re-baselines all stopwatches
  and skips the round, so the watchdog never fires *into* a recovery.
- **Pure logic, no I/O.** Clocks are injectable; the watchdog returns `Resubscribe`/`Rebuild` *values*
  for the service to execute. This makes the whole ladder unit-testable with fake clocks — worth
  replicating.

### 3.5 Witness-rule market quarantine (multi-market blast radius)

With ≥2 markets on one connection (one login, one adapter set), **one bad market's subscription
failure must not rebuild the whole session** and wobble the healthy market. The decision:

- **The witness rule.** A subscription failure *while another market's pair is fully `SUBSCRIBED`* (a
  healthy "witness") means the epic is the only differing variable → **market-shaped** → quarantine
  just that market. **No witness** (N=1, or everything failing together) → **session-shaped** →
  rebuild. A would-be witness still inside its confirm window makes the judgment *wait* (≤30s) rather
  than race a fast rejection into a whole-service rebuild.
- **In-session ladder ×3:** initial subscribe + 2 surgical retries (fresh pair, fresh confirm window)
  → **quarantine** the market (unsubscribe, drop from stream groups, tell the watchdog to forget it,
  emit one event, mark it in the heartbeat). Strikes are **per-session attempt counts, not
  exact-string matches** — a flapping rejection code must not launder the count.
- **Exit is restart-only.** Config-shaped failures (e.g. a bad epic) don't self-heal, so a quarantined
  market stays out across rebuilds; the daily 06:00 restart is a free daily retry, and the heartbeat
  nags every beat.
- **A refused *unsubscribe* escalates to a rebuild** — a dangling subscription would double-deliver
  every update.
- **N=1 parity is structural:** quarantine can't engage without a witness, so the last standing market
  always dies loud; "all markets quarantined" is unreachable.

### 3.6 Reconnect observability — tell "data lost" from "no gap"

When streaming resumes, emit **one summary line** classifying the recovery, because the operational
question is always "did we lose data?":

- **recovered / `replayed=true`:** the server replayed the missed updates — **no data gap**.
- **replaced / `replayed=false`:** a `WILL-RETRY` was seen, so it's a *new* session — **the outage's
  data is gone**; expect gaps and trigger a backfill of the newest minutes (§4).
- **Host-sleep detection:** compare wall-clock outage vs monotonic (awake) time; if they diverge >5s,
  the host slept. (Before this, a 16-minute lid-closed gap was reported as "0.4s offline.")
- **Transport shifts (WS-STREAMING ↔ HTTP-POLLING):** a silent downgrade to long-polling means higher
  latency and sometimes precedes a full drop — log the downgrade at WARNING.
- **Suppress graceful-close noise:** set a `closing` flag before an intentional `close()` so the
  farewell `DISCONNECTED` logs at INFO, not WARNING — otherwise every scheduled daily stop writes a
  scare-line into the warnings file and dilutes "quiet = healthy."

### 3.7 Where all of this is recorded

- **A `service_events` table** (free-text `event_type`) captures every reliability event as
  SQL-queryable rows: `reconnect`, `transport`, `forced_rebuild`, `session_rebuild`, `bar_gap`,
  `bucket_void`, `backfill`, `watchdog_resubscribe`, `market_quarantined`, `window_open/close`,
  `daily_heal`. Writes are best-effort (only when the DB is up).
- **Two log files, rotated at local midnight (the ops day):** a full record, and a **warnings-only
  file** that is the "quiet-is-healthy" record — disconnects, gaps, rebuilds. Retention in *days*
  (30), because the ops question is "show me Tuesday's," never "show me the last 2 MB."
- **A 60s heartbeat** line: `status, ticks, bars, dropped, db_pending, pub_failures, reconnects,
  bar_gaps` plus per-market watchdog ages and `QUARANTINED`/`window-closed` markers, e.g.
  `dax[t=3s b=41s DEAL]`.

---

## 4. Data integrity: gap detection, backfill & healing

> Guiding principle: **"too late" applies to executed *decisions*, not to *data*.** A missed bar can
> and should be healed in place after the fact; a missed *trade decision* cannot be un-missed. So the
> data series always heals; the trade loop never reads healed data back.

### 4.1 Two independent gap detectors

1. **Bar-grid gap (on live bars):** completed 1m bars land on a minute grid. If a new bar's minute is
   more than one minute past the last, `missing = gap_minutes - 1`. Emit a `bar_gap` naming the span,
   plus a `bucket_void` per touched higher-timeframe bucket (a 10m bucket missing any of its 10
   minutes cannot complete). **Dedupe on start-time per epic** (IG re-sends completed candles) and
   **track only the newest bar as the watermark**, so a late re-delivery of an *older* bar can't
   regress the watermark and fake a gap.
2. **Anchor-vs-now gap (for session-start / window / reconnect heals):**
   `last_complete = floor(now) - 1min`; the *currently-forming* minute is excluded because the live
   CHART subscription owns it.

### 4.2 REST v3 backfill — the mandatory choice and its traps

- **Use REST v3 `/prices/{epic}`.** v2's snapshot times were *account-timezone*, which is exactly the
  ambiguity you must avoid.
- **Timezone-proof the request.** IG's `from`/`to` timezone semantics are **unverified**, so the
  service path **requests the last N candles (no request dates) and filters by `snapshotTimeUTC`.**
  Only the CLI/operator path uses date ranges, and even then it filters on `snapshotTimeUTC` so a
  wrong offset can only *under-fetch*, never mislabel a bar. Key everything on `snapshotTimeUTC`.
- **`numpoints + 1` quirk:** while the market trades, IG's "last N" includes the *currently-forming*
  candle, so fetch `N + 1` and discard the partial/overshoot with your window filter.
- **Dead-socket retry-once:** the *first* REST call after an idle period rides a keep-alive socket IG
  closed long ago and dies with a `RemoteDisconnected`-style error. The failed attempt discards the
  socket, so **one retry after a ~2s pause** gets a fresh connection. Deliberately one attempt, no
  backoff ladder — it's a socket refresh, not an outage policy. (Field-proven: the *first* market in
  request order failed repeatedly while the second healed fine right after.)
- **Advance the watermark after a heal** so the next *live* bar doesn't read the just-healed minutes
  as missing and emit a false gap.
- **A heal must never hurt the feed:** every backfill path is swallow-and-log. Classify outcomes:
  `healed` (all minutes back), `tickless_at_ig` (IG returned none — a genuinely tradeless window),
  `partial`, or a raised failure.

### 4.3 Backfill budget

- **`backfill_max_minutes = 120` per incident.** Cap maths: IG allows **10,000 candle points/week**;
  120 minutes ≈ a 2-hour incident ≈ ~1.2% of the weekly budget per market. A 59s outage ≈ 3 points.
  A gap bigger than the cap is treated as a *scheduled stop* (overnight), not an outage, and must
  never spend allowance.
- **1-minute history reaches back ≤ 40 days.**
- Optionally **publish** in-service heals to the live bar stream too (`backfill_publish = true`), to
  rescue an in-flight higher-timeframe aggregate; deep end-of-day history stays DB-only.

### 4.4 Trading & streaming windows (belt-and-braces containment)

- **Windows are per-market properties** (local wall-clock, DST-tracking): a `stream` window and a
  narrower `trade` window, with the invariant **`trade_window ⊆ stream_window`** ("a strategy must
  never act on data the streamer does not carry").
  - Example live values: DAX stream `05:30–18:00`, trade `08:10–14:29`; NASDAQ stream `06:00–18:00`,
    trade `14:30–17:30`. The early stream start is deliberate **warm-up runway** so indicators
    converge before trade-start; DAX stops one minute before the US cash open (≈14:30 UK) because the
    NASDAQ open drags DAX around.
  - **All times are local wall-clock, stated once.** IG stamps data UTC; conversions happen *in code
    at the moment of a check*, never in config, never in stored data. DST transitions fall on Sundays
    when nothing streams.
- **The service window is the *envelope*** = earliest stream-start to latest stream-end over enabled
  markets, gated by a `window_guard` boolean. Outside the envelope the service does its start-up heal
  and then **stops itself (exit 0)** — this makes "restarted after the day's stop, streams all night"
  *structurally impossible* (braces to the scheduler's belt).
- **Per-market window edges inside the running service:** a market subscribes at its opening edge (+
  an opening heal) and unsubscribes at its closing edge (+ a final tail heal + `watchdog.forget`). A
  refused unsubscribe at an edge → session rebuild (dangling sub double-delivers).

### 4.5 Daily completeness heal

- **After the day's stop, run a scan-first heal** (e.g. 18:15 Mon–Fri) that takes the **same
  single-writer advisory lock** as the streamer (so it refuses if the streamer is still up — one
  writer keeps repairs boring).
- **Scan-first:** an anti-join finds only the minutes actually absent from the DB, groups them into
  contiguous runs, and fetches one range per run — allowance is never spent on minutes already held.
- **Write an audit row (`daily_heal`) with `missing_before / healed / remaining`** *even on clean
  days* (a row of zeros). **Absence of the row means the job didn't run — never "all good."**

---

## 5. Dealing rules & trading constraints

> **The meta-lesson, up front:** there are three generations of numbers, and the early ones are wrong.
> **Gen 1 (WRONG):** figures from IG's public dealing-rules *web page* (e.g. "5-point minimum",
> "10-point guaranteed minimum") — do not trust them.
> **Gen 2 (right at the time):** values read off the actual `dealingRules` REST response.
> **Gen 3 (the ongoing reality):** those values *drift*, sometimes daily.
> **→ Read live, never hardcode.**

### 5.1 The `dealingRules` fields

Every rule arrives as a **`{unit, value}` pair**, and **two of the eight arrive as `PERCENTAGE`** —
and one rule *changes unit between demo and live*. **Always check `unit == "POINTS"` and fall back to
your configured value on anything else.** Values below are from a live-demo probe (2026-08-01) for
DAX `IX.D.DAX.DAILY.IP` and NASDAQ `IX.D.NASDAQ.CASH.IP`:

| Rule | DAX | NASDAQ | Meaning |
|---|---|---|---|
| **`minNormalStopOrLimitDistance`** | **8.0 POINTS** | **8.0 POINTS** | Min distance from level to **either** attached leg (stop *and* limit share it). **The load-bearing, drifting one.** |
| `minStepDistance` | 1.0 POINTS | 1.0 POINTS | Granularity of stop/limit steps |
| `minDealSize` | 0.04 (£/pt) | 0.01 (£/pt) | Smallest stake |
| `minControlledRiskStopDistance` | 25.0 POINTS *(demo)* / 2.0 PERCENTAGE *(live)* | 1.0 / 2.0 PERCENTAGE | Min distance for a **guaranteed** (controlled-risk) stop |
| `maxStopOrLimitDistance` | 75.0 PERCENTAGE | 75.0 PERCENTAGE | Max distance a stop/limit can sit from market |
| `controlledRiskSpacing` | 125.0 POINTS | 20.0 POINTS | Valid-level spacing for CR orders (drives `CR_SPACING` rejects) |
| `marketOrderPreference` | AVAILABLE_DEFAULT_OFF | AVAILABLE_DEFAULT_OFF | Market orders **are** available; "off" is only the UI default |
| `trailingStopsPreference` | AVAILABLE | AVAILABLE | Trailing facility exists |

**Market status vocabulary:** `TRADEABLE | EDIT | AUCTION | AUCTION_NO_EDIT | CLOSED | OFFLINE |
SUSPENDED`, plus the composite snapshot value `EDITS_ONLY`. Snapshot the current `marketStatus`
alongside the rules.

Instrument facts worth capturing at startup: `lotSize`, `unit`, `decimalPlacesFactor`,
`scalingFactor`, `forceOpenAllowed`, `stopsLimitsAllowed`, `streamingPricesAvailable`,
`controlledRiskAllowed`, and the dealable `currencies` (you must send a `currencyCode` that is one of
them or you get `CONTACT_SUPPORT_INSTRUMENT_ERROR`).

### 5.2 The drift is real — and IG's risk desk silently chooses which trades happen

Documented timeline for DAX `minNormalStopOrLimitDistance`:

```
8.0  (verified demo AND live 2026-08-01/02)
 → 10.0  (that weekend)
 → 12.0  (overnight 08-03 → 08-04)
 → 8     (back down mid-week)
 → 10.0  (2026-08-10; NASDAQ simultaneously 4.0)
```

The consequence is not academic: during shadow week, two short signals were **rejected** because
their target sat inside the then-current minimum (9.4 vs 12; 10.4 vs 12), while two winners the next
day (9.4, 9.2) only traded because IG had dropped the minimum back to 8 that morning. **"IG's risk
desk, not the model, is choosing which of your trades happen."** If you hardcode the minimum, you are
either rejecting trades IG would allow or sending trades IG will reject.

**Architecture that follows:** read the live value at startup, unit-check it, and treat the catalogue
value as a **fallback only** — for the startup-race window before the live value is available, and for
paper mode. In the reference build the OMS publishes the live value to a shared store and the engine
adopts it, falling back to config if unpublished.

### 5.3 Stops: normal vs controlled-risk vs trailing

- **Normal stop minimum** = `minNormalStopOrLimitDistance` (the drifting one).
- **Guaranteed / controlled-risk stop minimum** = `minControlledRiskStopDistance` — far larger (DAX 25
  POINTS demo / 2 PERCENTAGE live). The public web page's "10 points" was wrong.
- **Guaranteed-stop premium:** ~2 points.
- **Guaranteed stops and trailing stops are mutually exclusive at the contract level.**
  `PUT /positions/otc` enforces: *if `trailingStop == true` then `guaranteedStop` must be false.* You
  cannot have both. (For a tight-trailing strategy this rules guaranteed stops out entirely — the
  trail distance can't even reach the normal minimum, let alone the CR minimum.)

### 5.4 How IG rejects orders (the surprising ones)

- **Rejections arrive *inside* an HTTP 200** as `dealStatus: "REJECTED"` with a `reason` — a different
  error family from HTTP-level `error.public-api.*` codes. **Deal rejections are never retryable
  verbatim.** Your happy-path 200 handler must inspect `dealStatus`.
- **Too-close attached leg → `ATTACHED_ORDER_LEVEL_ERROR`.** Confirmed empirically when a target sat
  inside the minimum. A **wrong-side** stop-entry *also* rejects as `ATTACHED_ORDER_LEVEL_ERROR` (not
  a wrong-side-specific code).
- **One minimum governs *both* legs.** If either the stop or the target sits inside the minimum, IG
  rejects the **whole bracket** — not just the offending leg.
- **Opposing positions on one epic are refused** (`OPPOSING_POSITIONS_NOT_ALLOWED`) — IG won't hold a
  long and a short (positions *or* working orders) on the same epic on one account. Two
  opposite-direction strategies on one epic/account will collide; gate this yourself upfront rather
  than letting it hit IG. Same-direction parallel strategies stay legal via `forceOpen = true`.
- **Market-closed edits are "move-away-only"** (`MOVE_AWAY_ONLY_LIMIT/_STOP/_TRIGGER_LEVEL`) — you can
  widen but not tighten. Surprisingly, under `EDITS_ONLY` a *new* working order with an attached stop
  was **accepted** and rested — closed-market rejections apply to position/deal ops, not
  working-order placement.
- **Size rejections:** `MINIMUM_ORDER_SIZE_ERROR`, `SIZE_INCREMENT` (the increment value is **not**
  exposed in `dealingRules` — you must observe it on the first reject), `FINANCE_REPEAT_DEALING` (too
  much size on a market in a short period), `INSUFFICIENT_FUNDS`/`MARGIN_ERROR`.
- **`/confirms` can 404 before the confirmation lands.** The streamed CONFIRMS event arrives
  ~60–150ms after the REST confirm; reconcile against both and don't treat an early 404 as failure.

### 5.5 The constraint-failsafe principle — "watch the true level, rest a clamped second-best"

This is the central strategic idea for trading *around* a moving constraint, and it generalises beyond
IG. **The problem:** a tight trailing stop wants to sit inside IG's minimum distance; IG then
**rejects the amendment** — and a rejected amendment *silently leaves the old level resting*, so the
strategy degrades to "no trail at all" with no error surfaced.

**The principle:**

1. **Rest the placeable order.** If the true level is placeable, rest it. If not, **clamp** it to the
   nearest level IG will accept and rest *that*.
2. **Monitor the true level yourself — but only when the constraint actually bit.** If the clamp
   forced the resting order off the true level, watch the market tick-by-tick and close at market on
   touch; the clamped resting order becomes the **outage failsafe** (if your process dies, IG still
   has a protective order in the book).

**Stop vs target asymmetry:**

- **The stop always self-watches.** A stop fills at market either way, so watching costs nothing and
  dodges amend-time rejection. Clamp direction (measured from the transacting-side quote,
  conservatively): long sell-stop `min(desired, bid − minDist)`; short buy-stop
  `max(desired, ask + minDist)`.
- **The target is a limit,** and a legal limit fills at its exact level (strictly better than a market
  close), so its self-watch is **clamp-gated** — imposed only on the narrow-band setups IG actually
  rejects. Clamp direction (measured from the *entry* level, because a working order's attached-leg
  minimum is measured from the order level — note this is *flipped* vs the stop): long sell-limit
  `max(desired, entry + minDist)`; short buy-limit `min(desired, entry − minDist)`.
- **The clamp is asymmetric in cost:** a clamped *stop* costs you (wider stop), but a clamped *limit*
  can only ever fill *further into profit* than the true target.

**Empirically confirmed working:** a trail moved the true stop inside the minimum, so the resting
broker leg was clamped ~3.5 points further out while the engine watched the true level and closed at
market on touch. The failsafe was never needed, and the amendment was never rejected.

### 5.6 Cost arithmetic & sizing (why demo can lie)

- **Spread:** DAX ≈ **1.2 points**, paid once per trade.
- **Slippage decides viability.** Stop orders become market orders on trigger, so slippage hits the
  entry, the initial stop, and every trail amendment. For the worked example strategy, the break-even
  win rate went from 67% (0pt slippage) to 73% (spread only) and up steeply with slippage
  (0pt→73%, 1pt→80%, 2pt→86%). "Viability is decided between zero and two points of slippage."
- **Minimum stake £0.01/pt**; respect the (unexposed) `SIZE_INCREMENT`; precision ≤ 12 dp. Shadow
  runs used £5/pt then £0.5/pt.
- **Margin: banded deposit factor**, ~5% base (DAX ≥ 2800 £/pt band → 6%; NASDAQ ≥ 1584 $/pt → 10%).
  A £0.5/pt DAX at ~25,600 margins ≈ £640.
- **Size is fail-closed config:** an unstated stake must not trade (empty size default → no trade),
  and exposure-growing verbs (place/amend) refuse when trading flags are off, while cancel/close
  *always* work — a kill-switch must never trap a position.
- **Demo cannot measure any of this** (no slippage, no rejects on price) — see §1.5.

### 5.7 Intraday-only is a permanent, deliberate constraint

Flat by session end; nothing held overnight or over weekends. This eliminates the DFB overnight
funding charge (never applies intraday) and gap risk (a 12-pt stop becoming a 60-pt weekend loss). A
**flatten belt** — the OMS force-flattens at `trade_end + grace`, independent of the engine — is the
belt-and-braces backstop.

---

## 6. Operational limits & rate limits

| Limit | Value | Notes |
|---|---|---|
| Streaming subscriptions per connection | **40** | Opening extra connections to exceed this is a ToS breach — key can be revoked. |
| Historical price data points | **10,000 / week** per account | A "point" = a returned candle. Shared across keys/services. |
| 1-minute history depth | **≤ 40 days** back | Daily bars go back ~15 years. |
| Per-app (API key) non-trading requests | **60 / min** | |
| Per-account non-trading requests | **30 / min** | Shared across keys. |
| Per-account trading requests | **100 / min** | |
| Token lifetime | **6h**, extended in use, **72h** hard max | See §1.3. |
| Concurrent Lightstreamer connections | IG FAQ says "do not create multiple" | **But see below.** |

> **Nuance learned empirically (2026-07-23 drill):** the strict "one connection only" reading is
> **not** how it behaves in practice — **two concurrent connections on one demo key worked**, and IG
> issues one key per account anyway. So the model adopted is **all services share the one key**, each
> holding its own session/connection. This is exactly why the single-instance guard (§7) matters:
> an *accidental* double-launch creates a *third* connection and risks the ToS line.

---

## 7. Single-instance & single-key discipline

- **Guard against double-launch with a Postgres advisory lock** taken at startup, *before any IG
  contact* (`pg_try_advisory_lock` on a fixed key; a distinct key per service). The lock frees
  automatically when the holder's connection dies — no stale pidfiles. A second instance refuses to
  start.
- **Why:** an observed double launch (a restart race / a stray process a kill missed) caused upsert
  deadlocks, doubled warnings, duplicate stream entries, and **two Lightstreamer connections on one IG
  key** — the last being a ToS risk.
- **Stagger independent logins >60s** (§1.2) since services share one key.
- **The OMS reconciles against IG after any new-session stream restore** — IG is authoritative for
  open positions. Reconcile on startup and after every session rebuild; never trust in-memory state
  across a reconnect.

---

## 8. Quick reference: the load-bearing numbers

| Thing | Value |
|---|---|
| Backoff | `1.0 · 2^(n-1)` s, ±50% jitter, cap `60s`, floor `5s` |
| Retry ceiling (consecutive failures) | `10` → clean stop |
| Same-subscription-error / quarantine strikes | `3` |
| Subscribe confirm window | `30s` |
| Tick queue cap (shed-oldest) | `100,000` (~83 min @ 20/s) |
| DB retry gap | `5s` |
| Watchdog tick-silent / bar-silent | `90s` / `210s` |
| Watchdog grace base / cap / max resubscribes | `60s` / `1800s` (30 min) / `2` |
| Stuck-disconnect rebuild: WILL-RETRY / TRYING-RECOVERY | `120s` / `300s` |
| Host-sleep skew / process-freeze discriminators | `5s` / `10s` |
| Backfill cap per incident | `120` min (~1.2% of weekly budget) |
| Backfill dead-socket retry | one attempt, `2s` pause |
| Heartbeat | `60s` |
| Historical budget / 1m depth / subs-per-conn | `10,000/wk` / `40 days` / `40` |
| Token lifetime | `6h`, `72h` max |
| Min stake | `£0.01/pt` |
| Spread (DAX) | ~`1.2` points |
| `minNormalStopOrLimitDistance` (DAX, as of 2026-08-10) | `10.0 POINTS` — **read live, do not trust this** |

---

## 9. Java porting notes

The lessons above are broker-level. Concrete mapping for a Java rebuild:

- **Streaming client:** use the **official Lightstreamer Java SDK** (`ls-javase-client`). It is
  natively Java, so the callback-threading discipline (§2.4) is *the same* — `SubscriptionListener` /
  `ClientListener` callbacks fire on the Lightstreamer session thread; do cheap work and hand off to a
  queue. The `setUser(accountId)` / `setPassword("CST-…|XST-…")` wiring (§1.1) is identical across
  languages.
- **REST client:** there is no official IG Java SDK — roll your own with OkHttp or Spring
  `WebClient`/`RestClient`. The Python-`trading-ig`-specific bugs (never-set LS user, no switch hook,
  import-time logging config) **won't apply** — but every *IG-side* behaviour will: preferred-account
  landing (§1.1), the login cache (§1.2), token binding to the active account, tokens in *response
  headers*, the `dealStatus:"REJECTED"`-inside-200 pattern (§5.4).
- **Clocks:** use `System.nanoTime()` for the monotonic/awake clock and `System.currentTimeMillis()`
  (or `Instant.now()`) for wall-clock — the sleep/freeze discriminators in §3.3/§3.4 depend on
  comparing the two. On some platforms the monotonic clock runs *through* host sleep while the wall
  clock jumps; that's what the >5s skew check catches.
- **Queues:** `ArrayBlockingQueue` (bounded, shed-oldest — poll-and-discard on full) for ticks;
  `LinkedBlockingQueue` (unbounded) for bars. Never let backpressure reach the socket.
- **Advisory lock:** `SELECT pg_try_advisory_lock(?)` over JDBC works exactly as in §7 — the lock is
  tied to the connection and releases when it closes.
- **Time zones:** keep everything UTC in storage (`snapshotTimeUTC`); do local-wall-clock window
  conversions in code at check-time (`ZonedDateTime` with the market's zone), never in config or
  stored data (§4.4). DST transitions land on Sundays when nothing streams.
- **Config/secrets:** IG issues **separate keys and account numbers per environment**; select
  demo/live with one flag that also picks the base URL. Keep secrets in a separate, single-quoted
  secrets file, never in the main config, with **no code defaults** for required keys (§1.7).
- **Port the error taxonomy first** (§1.6). It is small, high-leverage, and prevents both the
  "retried a fatal error into a lockout" failure and the "stopped on a transient token expiry" failure.

---

## 10. Open questions still with IG

These were unresolved at the time of writing; the reference build ships the **conservative
assumption** (entry-anchored / current-market measurement) and audits an `amend_rejected` event if
wrong. Re-confirm them for the Java build:

1. Is the minimum distance measured from the **working-order level** or from the **current market**?
2. **One** minimum for both legs, or **separate** minimums per leg?
3. At **amend** time, is the distance measured from market or from the deal/order level — and can a
   *tightening* amendment be rejected outright?
4. Does the minimum change **intraday**, or only **overnight**?
5. Confirm `ATTACHED_ORDER_LEVEL_ERROR` is the definitive too-close code.

---

### Source map (reference Python implementation)

If you need the exact behaviour, these are the authoritative files in this repo:

| Concern | File |
|---|---|
| Login → switch → connect; session reuse; token re-read | `src/igtrader/common/ig_client.py` |
| Error taxonomy (fatal / retryable / session-dead) | `src/igtrader/common/ig_errors.py` |
| Streaming callback, queues, reconnect supervisor, gap detection, windows | `src/igtrader/market_data/service.py` |
| Field parsing (CONS_END, mid, epoch ms, DLG_FLAG) | `src/igtrader/market_data/parsing.py` |
| Staleness watchdog | `src/igtrader/market_data/watchdog.py` |
| REST v3 backfill (TZ-proofing, numpoints+1, dead-socket retry) | `src/igtrader/market_data/backfill.py` |
| Dealing-rule read + unit check + constraint clamps | `src/igtrader/order_management/service.py`, `src/igtrader/signal_engine/paper.py` |
| IG constraints inventory (grid, rejection catalogue, request contracts) | `docs/order-management/ig-constraints.md` |
| Decision log (search D21, D25, D26, D29, D37, D42, D43, D45, D46) | `docs/decisions.md` |
| IG API contract notes (session, accounts, subscriptions, limits) | `.claude/notes/ig-api-reference-summary.md` |
| trading-ig library quirks | `.claude/notes/trading-ig-upstream.md` |
| Catalogue config with drift history in comments | `src/igtrader/config.default.toml` |
