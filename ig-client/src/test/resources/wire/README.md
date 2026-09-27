# Wire fixture provenance (read before trusting these bytes)

These fixtures were **authored from the broker playbook's documented wire facts, not
captured from live IG** (T2 had no live capture path). That boundary matters when weighing
them as evidence:

- **Playbook-anchored** (independently documented in `docs/inherited/ig-broker-playbook.md`):
  `snapshotTimeUTC` as the timezone-proof key (§4.2) · the dealing-rules `{unit, value}`
  pairs, names, and demo values incl. the PERCENTAGE units (§5.1) · `currentAccountId` +
  `accounts[]` shape and the preferred-account scenario (§1.1/§1.4) · CST/X-SECURITY-TOKEN
  as response headers (§1.3) · the error codes (§1.6) · the 10,000/week allowance (§6).
- **Authored model** (the author's best reconstruction — NOT independently anchored):
  allowance field names (`remainingAllowance`/`totalAllowance`/`allowanceExpiry`), the
  `openPrice.{bid,ask,lastTraded}` structure, `lastTradedVolume`, the login response's
  non-essential fields, and the exact login/switch request body shapes.

**Real-wire validation:** the gated demo smoke (`demoSmoke`, T2 DoD) exercises POST/PUT
`/session` only. `/markets` and `/prices` parsing meets the real wire first via T6's heal
path and verification tooling — **re-golden these fixtures from captured bytes at first
live contact** and note the capture date here.

All credentials/tokens in fixtures and tests are obvious fakes; account ids and the
identifier reuse values the inherited playbook already publishes.
