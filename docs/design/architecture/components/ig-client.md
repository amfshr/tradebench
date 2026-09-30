# ig-client — the IG broker library

**What it is.** A deliberately *dumb* library that speaks IG (REST + Lightstreamer) behind
vendor-neutral seams and decides nothing. Resilience policy lives in the consumer
(market-data-service), not here — the client throws typed errors and lets the service rule.

## Responsibilities

- **Session** (`ig.session`): `IgSessionManager` — login → account switch → token re-read;
  validate-before-relogin; `LoginRateGate` staggers logins >60s (one API key per account, D16).
- **REST** (`ig.rest`): `IgRestClient` — markets/prices/allowance, keyed on `snapshotTimeUTC`,
  fail-loud (never guesses a timestamp); exact `BigDecimal` bid/ask; a sliding-window
  `RequestPacer`.
- **Streaming** (`ig.stream`): `StreamTransport` seam with `LightstreamerTransport` the only
  vendor-aware class; `StreamParsers` pure and never-throwing; `IgStreamSession` splits
  `subscribePrice` / `subscribeChart1m` — pairing is the *service's* policy, not the client's.
- **Errors** (`ig.error`): typed fatal-vs-retryable families (e.g. `failure.kyc.required`).

## Boundaries

- Role-named interfaces (`HttpTransport`, `StreamTransport`), vendor-named impls
  (`JdkHttpTransport`, `LightstreamerTransport`) — swapping a provider is one class.
- Injectable timeouts and `Sleeper`; wire fixtures carry a provenance ledger.

*Teaches the concepts: Field Manual [ch. 1 — Sockets & Lightstreamer](../../../field-manual/01-sockets-and-lightstreamer.md).*
