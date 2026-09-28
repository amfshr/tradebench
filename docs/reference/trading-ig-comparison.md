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
