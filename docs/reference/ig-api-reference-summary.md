> **Provenance.** Copied verbatim 2026-09-27 from the private prototype repo
> (`FisherNE/ig-algorithmic-trader`, `.claude/notes/ig-api-reference-summary.md`) with
> Alex's permission — it summarises labs.ig.com's public API documentation (no credentials,
> no strategy content). Internal file paths below refer to the prototype repo and won't
> resolve here. labs.ig.com blocks non-browser fetchers (403/500 without a Chrome UA), so
> this scrape is the working reference until re-captured.

# IG Labs API — reference summary (REST + Streaming)

> Local summary of what's actually documented at `labs.ig.com`, captured
> 2026-07-16 (the site blocks requests without a real browser User-Agent — plain
> `curl`/`WebFetch` got 403/500 until a full Chrome UA header was sent).
> No OpenAPI/Swagger spec exists for this API (see the "No machine-readable
> spec" section at the bottom) — this doc is the closest thing to one we have.
>
> Full reference index: `labs.ig.com/rest-trading-api-reference.html` (REST,
> ~35 resources) and `labs.ig.com/streaming-api-reference.html` (Lightstreamer).
> This file summarises the three areas most relevant to us: **Streaming** (what
> `market_data/service.py` subscribes to), **`/session` + `/accounts`**
> (what `common/ig_client.py` authenticates with — directly explains the two
> live bugs in `.claude/notes/trading-ig-upstream.md`), and — added 2026-08-02
> for the 🏦 OMS build — the **dealing endpoints** (§4; the OMS-facing
> analysis + LIVE probed values live in `docs/order-management/ig-constraints.md`).

---

## 1. Streaming API (Lightstreamer) — every subscription IG offers

Data adapter is `Pricing` for PRICE; unspecified (default) for the others. All
items are `MERGE` mode unless noted.

### PRICE — live tradeable price (what we use for ticks)
```
PRICE:{account identifier}:{epic}          (MERGE)
```
Key fields: `BIDPRICE1..5` / `ASKPRICE1..5` (DOM ladder tiers — we only use
tier 1), `BIDSIZE{1-5}` / `ASKSIZE{1-5}`, `MID_OPEN`, `HIGH`, `LOW`,
`NET_CHG` / `NET_CHG_PCT`, `TIMESTAMP` (UTC millis), `DELAY` (0/1),
`DLG_FLAG` (`CLOSED`, `CALL`, `DEAL`, `EDIT`, `CLOSINGSONLY`, `DEALNOEDIT`,
`AUCTION`, `AUCTIONNOEDIT`, `SUSPEND`). We currently parse `TIMESTAMP`,
`BIDPRICE1`, `ASKPRICE1`, `DLG_FLAG` (see `market_data/parsing.py`).

### MARKET — ⚠️ DEPRECATED, ALREADY DECOMMISSIONED
```
MARKET:{epic}                              (MERGE)   -- and its alias "L1"
```
> "This subscription reaches end of life on 1 May 2026 and will be
> decommissioned on 8 May 2026. L1, an alias for MARKET, is also affected.
> Please migrate to the PRICE subscription before then."

**As of today (2026-07-16) this is already past decommissioning** — `MARKET`
items no longer work. Good news: we never used it for market data (we use
`PRICE` + `CHART`). Bad news: our diagnostic spike script (used while debugging
Ticket 1) subscribed to `MARKET:CS.D.EURUSD.TODAY.IP` as a test probe — that
probe would now fail for this reason too, on top of the account-mismatch bug.
Worth remembering if `MARKET` ever resurfaces in older sample code/blog posts.

### ACCOUNT — balance/margin/PnL push updates
```
ACCOUNT:{account identifier}               (MERGE)
```
Fields: `PNL`, `DEPOSIT`, `AVAILABLE_CASH`, `PNL_LR`/`PNL_NLR` (limited/
non-limited risk), `FUNDS`, `MARGIN`, `MARGIN_LR`/`MARGIN_NLR`,
`AVAILABLE_TO_DEAL`, `EQUITY`, `EQUITY_USED`. Not used yet — relevant to the
OMS later (risk failsafes, live PnL) rather than MarketDataService.

### TRADE — order/position/working-order lifecycle (the OMS's future subscription)
```
TRADE:{account identifier}                 (DISTINCT)
```
One item, three logical sub-streams distinguished by which field is populated:
- **`CONFIRMS`** — trade confirmations: `direction`, `dealId`, `dealStatus`
  (`ACCEPTED`/`REJECTED`), `affectedDeals[]` (each with its own `status`:
  `AMENDED`/`DELETED`/`FULLY_CLOSED`/`OPENED`/`PARTIALLY_CLOSED`), `epic`,
  `level`, `limitLevel`/`stopLevel`, `guaranteedStop`, `repeatDealingWindow`.
- **`OPU`** (Open Position Updates) — `dealId`, `dealIdOrigin`, `status`
  (`OPEN`/`UPDATED`/`DELETED`), `direction`, `level`, `size`,
  `limitLevel`/`stopLevel`, `trailingStopDistance`/`trailingStep`, `channel`.
- **`WOU`** (Working Order Updates) — `dealId`, `status`
  (`OPEN`/`UPDATED`/`DELETED`), `orderType` (`LIMIT`/`STOP`), `timeInForce`
  (`GOOD_TILL_CANCELLED`/`GOOD_TILL_DATE` + `goodTillDate`), `limitDistance`/
  `stopDistance`, `currency`.

This is exactly what `tmp/prototype-reference/ig_orderstream.py` (the prototype) subscribed to —
confirms the OMS design (D7: order-status streamer, in-memory source of truth)
is subscribing to the right, current item.

### CHART — consolidated (candle) data — what we use for 1-minute bars
```
CHART:{epic}:{scale}                       (MERGE)
  {scale} = SECOND | 1MINUTE | 5MINUTE | HOUR
```
**Only four native scales exist.** No `2MINUTE`/`3MINUTE`/`10MINUTE`/
`15MINUTE` — confirms our design (D3/architecture/README.md §3.2): higher timeframes
(10-minute etc.) **must** be aggregated in Python from `1MINUTE` bars; IG has
no native subscription for them. There never was a shortcut we missed.

Fields: `LTV`/`TTV` (last-traded / incremental volume), `UTM` (epoch millis),
`DAY_OPEN_MID`/`DAY_NET_CHG_MID`/`DAY_PERC_CHG_MID`/`DAY_HIGH`/`DAY_LOW`,
OHLC in three price bases — `OFR_{OPEN,HIGH,LOW,CLOSE}`,
`BID_{OPEN,HIGH,LOW,CLOSE}`, `LTP_{OPEN,HIGH,LOW,CLOSE}` (last-traded-price,
for instruments where that applies) — `CONS_END` (1 when the candle closes,
else 0), `CONS_TICK_COUNT`. We currently parse BID/OFR O/H/L/C, `UTM`,
`CONS_END` and compute mid ourselves (`market_data/parsing.py`) — matches
`tmp/prototype-reference/MarketDataService_Dax_v1.py`'s approach.

```
CHART:{epic}:TICK                          (DISTINCT)
```
Per-tick (not candle) feed: `BID`, `OFR`, `LTP`, `LTV`, `TTV`, `UTM`, plus the
same `DAY_*` fields as above. We don't use this — `PRICE` already gives us
tradeable bid/ask ticks; `CHART:TICK` would be a second, mostly-redundant tick
source (its `BID`/`OFR` are pricing-adjacent but this item is meant for
charting, not dealing).

---

## 2. `/session` and `/accounts` — directly explains our two live bugs

See `.claude/notes/trading-ig-upstream.md` for the full bug write-up; this is
the underlying API contract that makes both bugs make sense.

### `POST /session` (v2) — login
- Request: `identifier` (username) **must match regex `[A-Za-z0-9\-_]{1,30}`**
  — no dots allowed. This is *exactly* why `amfshr.demo` failed with
  `validation.pattern.invalid.authenticationRequest.identifier` and
  `amfshr-demo` (hyphen) worked.
- Response includes `currentAccountId` and the full `accounts[]` list, each
  with `accountId`, `accountType` (`CFD`/`PHYSICAL`/`SPREADBET`), and
  `preferred` (bool). **Login always activates whichever account has
  `preferred=true`** — there is no login parameter to pick a different one.
  Session tokens (`CST` / `X-SECURITY-TOKEN`, returned as response headers)
  are bound to that active account.

### `PUT /session` — switch active account
- Request: `accountId` (regex `[A-Za-z0-9\-]{1,30}` — no underscore, unlike
  the username pattern) + optional `defaultAccount: bool`.
- **`defaultAccount: false`** (what we pass) switches the session only —
  your IG profile's preferred account is unchanged, so this is safe to call
  on every connect/reconnect.
- Response confirms `dealingEnabled`, `trailingStopsEnabled`, etc. for the
  now-active account. Crucially, **switching refreshes the session tokens** —
  this is why our fix re-reads `CST`/`X-SECURITY-TOKEN` after calling it,
  before building the Lightstreamer connection.
- Errors worth knowing: `error.switch.accountId-must-be-different` (switching
  to the account you're already on), `error.switch.invalid-accountId`.

### `GET /accounts` — list accounts on this login
Matches what our diagnostic spike used (`fetch_accounts()`) to discover
Alex's two accounts (`Z6CS3D` CFD/preferred, `Z6CS3E` SPREADBET). Also
returns `balance.{available,balance,deposit,profitLoss}` and `status`
(`ENABLED`/`DISABLED`/`SUSPENDED_FROM_DEALING`) per account — useful later
for a pre-flight sanity check in the OMS.

### Error-code catalogue (common across `/session`, `/accounts`, etc.)
Every endpoint shares the same `error.security.*` / `error.public-api.*`
vocabulary. The ones our fatal-vs-retryable split in `market_data/service.py`
(`_is_fatal_config_error`) is designed to catch:
`error.security.api-key-missing/-invalid/-disabled/-revoked/-restricted`,
`error.public-api.failure.missing.credentials`,
`error.public-api.failure.preferred.account.disabled/.not.set`,
`error.security.account-token-invalid/-missing`,
`error.security.client-token-invalid/-missing`. All 401/403, all
un-retryable — confirms fail-fast-don't-retry is the right policy for this
whole error family, not just the one we hit.

---

## 3. Facts from the REST & Streaming *guides* (fetched 2026-07-16)

- **Token lifetimes:** CST / X-SECURITY-TOKEN are valid **6 hours**, extended
  while in use up to a **hard max of 72 hours**. The streaming guide explicitly
  notes a Lightstreamer re-connection can fail once tokens have expired, and
  re-authentication is then required — i.e. a full session rebuild (fresh
  login) is the documented recovery, which is exactly what
  `MarketDataService`'s supervisor does. Corollary: **token-expiry errors are
  retryable**, not fatal.
- **Error responses:** always `{"errorCode": "..."}` with 4xx/5xx status.
  Validation-failure codes have variable suffixes named after the offending
  field (e.g. `invalid.request.forceOpen`) — so error *families/prefixes* are
  the stable thing to match on, not exact strings.
- **Subscription cap: 40 simultaneous subscriptions per connection.** Opening
  multiple connections to get more is a breach of IG's ToS and can get API
  permissions revoked (contact IG to raise quotas instead).
- **Streaming auth does NOT accept OAuth tokens** — v2 CST/XST sessions (or
  v3 + `GET /session?fetchSessionTokens=true`) are required. Our v2 usage is
  deliberate, not incidental.
- LS connection identifier = active account id; password =
  `CST-{cst}|XST-{xst}` (matches our `IGStreamConnection`).

### Documented default limits (FAQ, fetched 2026-07-16)

| Limit | Value |
|---|---|
| Per-app (API key) non-trading requests | **60 / min** |
| Per-account non-trading requests | **30 / min** (shared across keys) |
| Per-account trading requests (create/amend) | **100 / min** |
| Historical price data points | **10,000 / week** |
| Streaming subscriptions per connection | **40** |
| Concurrent LS connections | **"Please do not create multiple concurrent connections — may lead to your API key being suspended"** (per key; manage the 40 by un/re-subscribing) |

REST limits cannot be increased. No API fees at default quotas. Historical
data availability: 1-min resolution ≤ 40 days back; daily ≤ 15 years.

**Topology implication:** one login + one LS connection **per process/API key**.
IG accounts can issue multiple API keys → give MarketDataService and the OMS
their own keys, so each key has exactly one LS connection (squarely inside the
rule) and its own 60/min non-trading budget. A REST session and its stream
share ONE login — Lightstreamer authenticates with the REST session's tokens.

---

## 4. Dealing endpoints (REST) — captured 2026-08-01/02 for the 🏦 OMS

> Scraped from `labs.ig.com/reference/{working-orders-otc, working-orders-otc-
> deal-id, positions-otc, positions-otc-deal-id, confirms-deal-reference}.html`
> (Chrome-UA required, as above) and cross-checked by Alex in the API companion.
> IG annotates request schemas with `[Constraint: …]` rules — quoted here
> verbatim where load-bearing. trading-ig 0.0.24 call-mapping quirks and the
> live dealingRules values are in `docs/order-management/ig-constraints.md`.

**Shared field rules:** `dealReference` pattern `[A-Za-z0-9_\-]{1,30}`
(optional, user-defined — returned in confirms/OPU/WOU, so it works as a
correlation key); epic pattern `[A-Za-z0-9._]{6,30}`; expiry pattern
`(\d{2}-)?[A-Z]{3}-\d{2}|-|DFB`; `currencyCode` `[A-Z]{3}`, restricted to the
instrument's `currencies`; `size` precision ≤ 12 decimal places.

### POST `/workingorders/otc` (v2) — create working order
Required: `currencyCode, epic, expiry, guaranteedStop, level, size,
timeInForce, direction, type`. Enums: `direction` BUY|SELL; `type` LIMIT|STOP;
`timeInForce` GOOD_TILL_CANCELLED|GOOD_TILL_DATE (`goodTillDate`: UTC
`yyyy/mm/dd hh:mm:ss` or epoch-ms). Constraints: set only one of
{stopLevel,stopDistance}; only one of {limitLevel,limitDistance};
GOOD_TILL_DATE ⇒ set goodTillDate; guaranteedStop=true ⇒ stopDistance only.
Response: `{dealReference}`.

### PUT `/workingorders/otc/{dealId}` (v2) — amend working order
Body: goodTillDate, guaranteedStop, **level (NotNull — every amend must
(re)send the entry level)**, limitDistance/limitLevel, stopDistance/stopLevel,
timeInForce, type. Same one-of constraints; guaranteedStop=true ⇒ set
stopLevel. Response: `{dealReference}`.

### DELETE `/workingorders/otc/{dealId}` (v2) — cancel working order
Empty body. Response: `{dealReference}`.

### POST `/positions/otc` (v2) — create position (direct open)
Enums: `orderType` LIMIT|MARKET|QUOTE; `timeInForce` EXECUTE_AND_ELIMINATE|
FILL_OR_KILL. Constraints: ANY attached stop/limit (level or distance) ⇒
`forceOpen` must be true; MARKET ⇒ no level/quoteId; LIMIT ⇒ level, no
quoteId; QUOTE ⇒ level+quoteId; trailingStop=true ⇒ set
stopDistance+trailingStopIncrement, no stopLevel, guaranteedStop must be
false; guaranteedStop=true ⇒ only one of {stopLevel,stopDistance}; the usual
one-of pairs. Response: `{dealReference}`.

### DELETE `/positions/otc` (v1) — close position(s)
Body: **set only one of {dealId, epic}** (epic ⇒ also set expiry); `direction`
= the OPPOSITE of the position; `size` NotNull (smaller than the position ⇒
partial close); `orderType` MARKET ⇒ no level/quoteId, LIMIT ⇒ level.
Response: `{dealReference}`.

### PUT `/positions/otc/{dealId}` (v2) — amend open position (the trail path)
Body: guaranteedStop, limitLevel, stopLevel, trailingStop,
trailingStopDistance, trailingStopIncrement — **levels only, no distances**.
Constraints, verbatim: guaranteedStop=true ⇒ set stopLevel AND trailingStop
must be false; **trailingStop=true ⇒ guaranteedStop must be false** (guaranteed
and trailing are mutually exclusive — settles half of the 🧭 email's Q2) and ⇒
set trailingStopDistance+trailingStopIncrement+stopLevel. Response:
`{dealReference}`.

### GET `/confirms/{dealReference}` (v1) — deal confirmation
IG's note: fallback for the streamed CONFIRMS. (trading-ig calls it
automatically after every dealing mutation — each verb costs 1 trading +
1 non-trading request.) Response: `dealStatus` ACCEPTED|REJECTED; `status`
AMENDED|CLOSED|DELETED|OPEN|PARTIALLY_CLOSED; `affectedDeals[]` each with
status AMENDED|DELETED|FULLY_CLOSED|OPENED|PARTIALLY_CLOSED; `reason` = the
deal-rejection enum; plus date, dealId, dealReference, direction, epic,
expiry, guaranteedStop, level, limitDistance/Level, profit, profitCurrency,
size, stopDistance/Level, trailingStop.

**The `reason` enum** (SUCCESS = accepted; the rest arrive as
`dealStatus: "REJECTED"` INSIDE an HTTP 200 — a different error family from
the `error.*` HTTP codes; abridged meanings + reachability analysis in
`docs/order-management/ig-constraints.md` §3):
ACCOUNT_NOT_ENABLED_TO_TRADING, ATTACHED_ORDER_LEVEL_ERROR,
ATTACHED_ORDER_TRAILING_STOP_ERROR, CANNOT_CHANGE_STOP_TYPE,
CANNOT_REMOVE_STOP, CLOSING_ONLY_TRADES_ACCEPTED_ON_THIS_MARKET,
CLOSINGS_ONLY_ACCOUNT, CONFLICTING_ORDER, CONTACT_SUPPORT_INSTRUMENT_ERROR,
CR_SPACING, DUPLICATE_ORDER_ERROR, EXCHANGE_MANUAL_OVERRIDE,
EXPIRY_LESS_THAN_SPRINT_MARKET_MIN_EXPIRY, FINANCE_REPEAT_DEALING,
FORCE_OPEN_ON_SAME_MARKET_DIFFERENT_CURRENCY, GENERAL_ERROR,
GOOD_TILL_DATE_IN_THE_PAST, INSTRUMENT_NOT_FOUND,
INSTRUMENT_NOT_TRADEABLE_IN_THIS_CURRENCY, INSUFFICIENT_FUNDS,
LEVEL_TOLERANCE_ERROR, LIMIT_ORDER_WRONG_SIDE_OF_MARKET,
MANUAL_ORDER_TIMEOUT, MARGIN_ERROR, MARKET_CLOSED, MARKET_CLOSED_WITH_EDITS,
MARKET_CLOSING, MARKET_NOT_BORROWABLE, MARKET_OFFLINE,
MARKET_ORDERS_NOT_ALLOWED_ON_INSTRUMENT, MARKET_PHONE_ONLY, MARKET_ROLLED,
MARKET_UNAVAILABLE_TO_CLIENT, MAX_AUTO_SIZE_EXCEEDED,
MINIMUM_ORDER_SIZE_ERROR, MOVE_AWAY_ONLY_LIMIT, MOVE_AWAY_ONLY_STOP,
MOVE_AWAY_ONLY_TRIGGER_LEVEL, NCR_POSITIONS_ON_CR_ACCOUNT,
OPPOSING_DIRECTION_ORDERS_NOT_ALLOWED, OPPOSING_POSITIONS_NOT_ALLOWED,
ORDER_DECLINED, ORDER_LOCKED, ORDER_NOT_FOUND, ORDER_SIZE_CANNOT_BE_FILLED,
OVER_NORMAL_MARKET_SIZE, PARTIALY_CLOSED_POSITION_NOT_DELETED [sic],
POSITION_ALREADY_EXISTS_IN_OPPOSITE_DIRECTION,
POSITION_NOT_AVAILABLE_TO_CANCEL, POSITION_NOT_AVAILABLE_TO_CLOSE,
POSITION_NOT_FOUND, REJECT_CFD_ORDER_ON_SPREADBET_ACCOUNT,
REJECT_SPREADBET_ORDER_ON_CFD_ACCOUNT, SIZE_INCREMENT,
SPRINT_MARKET_EXPIRY_AFTER_MARKET_CLOSE, STOP_OR_LIMIT_NOT_ALLOWED,
STOP_REQUIRED_ERROR, STRIKE_LEVEL_TOLERANCE, SUCCESS,
TRAILING_STOP_NOT_ALLOWED, UNKNOWN, WRONG_SIDE_OF_MARKET.

Every dealing endpoint shares the standard HTTP exception vocabulary already
summarised in §2 (`error.security.*` / `error.public-api.*` — the
fatal-vs-retryable split).

### GET `/markets/{epic}` (v3) — market details (the dealingRules read)
Returns `instrument` (expiry, currencies w/ isDefault, lotSize, margin bands,
forceOpenAllowed, stopsLimitsAllowed, controlledRiskAllowed, expiryDetails…),
`dealingRules` (each rule a `{unit: POINTS|PERCENTAGE, value}` pair —
minNormalStopOrLimitDistance, minControlledRiskStopDistance, minStepDistance,
minDealSize, maxStopOrLimitDistance, controlledRiskSpacing +
marketOrderPreference, trailingStopsPreference) and `snapshot` (marketStatus,
bid/offer, high/low, decimalPlacesFactor, scalingFactor). Live probed values
per market: `docs/order-management/ig-constraints.md` §1. NB dealingRules are
account-/environment-shaped — read live at startup (D42), never hardcoded.

## No machine-readable spec (OpenAPI/Swagger) exists

Checked directly (2026-07-16): `labs.ig.com`'s REST/Streaming reference is
hand/generator-produced static HTML — one page per resource, no
`swagger.json`/`openapi.yaml`/`.apib`/`.raml` linked anywhere. `ig.docs.apiary.io`
looked promising but is an unrelated, abandoned demo account squatting the "ig"
subdomain (Apiary's default "Notes API" boilerplate, last touched 2015 — not
IG Markets). No credible community-maintained OpenAPI spec exists on GitHub
either — every wrapper library (`trading-ig`, `igmarkets`, node/PHP clients)
hand-writes its bindings from these HTML pages, same as we're doing. If we ever
want one, generating it from these pages (they're consistently
`field (Type) description` structured) would be a legitimate small project —
nobody has done it yet.
