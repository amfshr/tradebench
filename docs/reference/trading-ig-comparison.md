# trading-ig comparison sweep (2026-09-28)

> Full read of the open-source `trading-ig` Python library (the prototype's dependency)
> against Tradebench's `ig-client`, at Alex's request before completing E1. Verdict: four
> adoption items, everything else is either machinery we deliberately deferred or handling
> worse than ours. Their two famous defects confirmed at source.

## Adopted immediately (E1-T3 branch)

- **KYC-required login block → FATAL_CONFIG.** `error.public-api.failure.kyc.required` is
  clearable only by a human web login; our unknown→RETRYABLE default would have hammered
  the login endpoint against it. Now a fatal family.
- **/prices pagination guard.** The v3 endpoint genuinely pages (their loop reads
  `metadata.pageData` until `pageNumber == totalPages`); our single-shot `pageSize=max`
  call now fails loud naming `totalPages` if a response ever pages, instead of silently
  truncating the window.

## Slotted into the epic plan (design-flavoured)

- **T5-era: pacer budget must be discovered, not assumed.** Their limiter reads the key's
  real allowances from `GET /operations/application` minus a safety margin, with field data
  that demo keys enforce **10/min**, not the published 30. Our pacer mechanism is better
  than theirs; its *number* needs discovery (or config) with headroom at service startup.
- **T6-era: weekly historical allowance is not retry-soon.** They exclude
  `exceeded-account-historical-data-allowance` from transient handling (resets weekly).
  Our heal plans against the `Allowance` metadata *before* firing and must treat that code
  as budget-exhausted-for-the-week, never a backoff-and-retry.

## Their defects, confirmed at source (we design around all of them)

Preferred-account bug: `switch_account` exists and even re-reads tokens, but nothing calls
it — a v2 session silently stays on the profile default. LS user never set
(`acc_number = None`, never assigned). Default v3 price formatting keys on account-timezone
`snapshotTime` (the §4.2 mislabeling trap; only non-default paths use `snapshotTimeUTC`).
Shared-session header mutation (racy `VERSION`/`_method`), floats-with-NaN coercion for
prices, a library `sys.exit(1)` on stream connect failure, import-time logging config.

## Deliberately skipped (right call, revisit at the named era)

v3 OAuth session machinery (60s access tokens, refresh, `IG-ACCOUNT-ID`) — streaming still
needs v2-style CST/XST even from a v3 session, so v2 stays right for a streaming-first
service. Password encryption at login ("required for some regions" — UK fine over TLS;
watch-item; the `encryptedPassword: true` flag must accompany it if ever adopted). Logout
(`DELETE /session` — NB their code shows IG "DELETE" is really POST + `_method: DELETE`
header; matters again for OMS close-position). Endpoint families for later eras: accounts +
preferences, activity/transaction history, all dealing CRUD (E6), market navigation/search/
sentiment, watchlists, `GET/PUT /operations/application` (the GET arrives with the T5
budget-discovery item).

## Deep re-mine — release 0.0.24 (2026-09-30, for E1-T5 planning)

A second, source-cited read at release **0.0.24** (HEAD `1f307f0`), focused on the T5 resilience
questions. Line numbers are exact for that release; findings confirm and extend the sweep above.

- **Resilience is a confirming negative result.** trading-ig has **no** reconnect supervisor,
  backoff ladder, retry ceiling, stuck-substate handling, staleness watchdog, heartbeat, or gap
  detection anywhere (`trading_ig/` grep is empty for all). Its only retries are a caller-supplied
  `tenacity.Retrying` around individual REST calls (no library default; no ceiling in their own
  tests) and a few fixed `sleep(1)` `/confirms` loops (OMS, not T5). The 0.0.24 `ClientListener`
  hook is consumed only by a sample that *prints* the status — so `WILL-RETRY`/`TRYING-RECOVERY`
  stay invisible, the exact D25 hang, **unfixed upstream too.** Takeaway: T5's recovery stack is
  bespoke work no off-the-shelf IG client does — budget it as the long tail.

- **Pacer (slice C): adopt the shape, avoid the mechanism.** Confirmed at source: the limiter reads
  the key's real allowance from `GET /operations/application` and subtracts a hardcoded
  `MAGIC_NUMBER = 2` (`rest.py:221-244`), with a comment that demo enforces **10/min**, live 30 —
  the field evidence behind the item above. Adopt discover-then-subtract-headroom (use
  `allowanceAccountOverall`; the collection service is non-trading); consider headroom **3–5**, not
  2, since T6 heals spend the same key; start at 10/min until the read returns; log published-vs-used.
  **Avoid** their mechanism — two daemon threads feeding size-1 token queues, plus a `sleep(60/rate)`
  that stalls login; a single paced gate on our injectable clock is cleaner and testable.

- **Session recovery (slice C): a trap to design *against*.** Their **v2** CST/XST recovery — the
  token type streaming *requires* — is effectively broken: on a token-invalid response the retryer
  re-runs the same call with the **same dead headers**; nothing re-logs-in (`rest.py:2158-2180`;
  `_check_session` no-ops for v2). Only v3 OAuth self-heals, and streaming can't use v3. Our
  `IgSessionManager.afterFailure()` (cheap `GET /session` → fresh login) is strictly better — keep
  it, and on LS reconnect **re-read CST/XST and re-`setUser(accountId)`** (they leave `setUser(None)`,
  unfixed across releases). Add a failure-mode test: a v2 token-invalid forces a *rebuild*, not a
  same-headers retry loop.

- **Streaming wrapper now has a queue — but it's a toy, and it uses the wrong subscription.** The
  `StreamingManager` (added 0.0.22/0.0.24) has a single **unbounded** `queue.Queue()` + one daemon
  consumer, so "callback-direct, no queue" is outdated — but it's a latest-value cache (no
  persistence, no ack, unbounded growth on stall) and it subscribes **`CHART:{epic}:TICK`**, the
  *item* playbook §2.3 rejects (charting-oriented; and it lacks `DLG_FLAG`, our only market-state
  signal since `MARKET_STATE` was decommissioned May 2026). NB the objection is the **item, not the
  `DISTINCT` mode** — DISTINCT is the correct mode for a tick item; our doctrine simply takes ticks
  from `PRICE:{acc}:{epic}` in `MERGE`, reserving `DISTINCT` for the OMS `TRADE` stream E1 doesn't run.

- **Testing doctrine (answers the open question): fakes for the core, WireMock at REST, a thin demo
  smoke.** trading-ig's biggest testing failure is that its **streaming layer has zero tests** — it
  never built a seam to fake the callback-threaded SDK, which is why the streaming defects survive
  releases. Ruling for the market-data-service: a **hand-written fake of the SDK methods is
  sufficient and correct** for the streaming/resilience core (the behaviours are
  temporal/state-machine — backoff timing, thresholds, freshness — served by injectable clocks +
  deterministic ordering, not "assert method X was called"); back it with **WireMock at the REST
  boundary** (pacer discovery, token-invalid recovery, taxonomy, pagination) and **one gated demo
  smoke, excluded from CI.** Do **not** invest in SDK-mocking frameworks or make demo integration a
  coverage pillar — demo can't be made to hang in `WILL-RETRY` on command. Technique worth stealing:
  their `assert_call_count` + scripted-response-sequence idiom (`retry_test.py:62`) → "retried
  exactly K times at the expected spacing, then stopped at the ceiling," reproduced with WireMock
  scenarios (REST) and a counting fake transport (streaming).
