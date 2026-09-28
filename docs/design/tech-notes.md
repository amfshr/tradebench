# Tech Notes — language, stack, and code conventions

> **What this is.** The technical-choices companion to `architecture.md`: what we build
> with, which language features we use and avoid, and the code conventions that don't fit
> in CLAUDE.md's short form. Each section links its ruling (D-numbers). Living document —
> grows a section per settled area; **frontend section reserved** (Alex has ideas; written
> with E2 planning). Status: v1, 2026-09-27.

## 1. Stack at a glance

| Layer | Choice | Ruling / why |
|---|---|---|
| Language | **Java 25 LTS** (toolchain-pinned per module) | D12 — start on the current LTS, no mid-life JDK bump |
| Build | **Gradle 9.8, Kotlin DSL, version catalog, wrapper-pinned** | D12; catalog = one place for every version |
| App framework | **Spring Boot — apps only, never libraries** | build plan §2; `ig-client`/`core` stay framework-free |
| JSON | Jackson (databind) | boring, universal; exact-decimal config mandatory (see §4) |
| Null-safety | **JSpecify 1.0, `@NullMarked` packages** | D14 (see §3) |
| Testing | JUnit 6, fakes-first; Testcontainers (T4+); golden wire fixtures | doctrine: playbook §2 / G5 |
| DB | PostgreSQL + Flyway from V1 | PRD §10; user + source columns from day one |
| Messaging | Redis Streams (provisional) | D4 — Kafka is a gated re-decision |
| Frontend | React/TypeScript SPA, own toolchain in `frontend/` | D3/D11; conventions TBD with E2 *(reserved)* |
| Deploy | Docker, Linux only; staging + prod by release tag | PRD §10 — no Windows target, ever |

## 2. Java language & feature policy

- **LTS-only runtime** (currently 25); non-LTS features wait. **No preview features** on
  `main` — anything `--enable-preview` is someone's experiment branch, not the build.
- **Records for value types** — wire DTOs, domain values, config carriers. A record with
  validation lives in a compact constructor (`IgCredentials` is the pattern).
- **Sealed hierarchies** where the domain is genuinely closed (expected: DSL AST nodes,
  engine events). Don't seal speculatively.
- **Virtual threads** are the intended concurrency model for the service's blocking work
  (JDBC, queue drains) — decided properly at T4/T5 when the writer lands, not by default.
- **`BigDecimal` for every price/money value, wire scale preserved** (D36 exact-arithmetic
  discipline). `double` never touches a price. Jackson must be configured:
  `USE_BIG_DECIMAL_FOR_FLOATS` + `STRIP_TRAILING_BIGDECIMAL_ZEROES=false`.
- **`Optional` as a return type only** — never a field, parameter, or collection element.
  Absent fields inside types use `@Nullable` (§3); absent results at API boundaries may use
  `Optional`.
- **Time**: injectable clocks everywhere; monotonic (`nanoTime`-shaped `LongSupplier`) vs
  wall time explicitly distinct; UTC in storage, zone conversions in code at check-time.
- **Package-by-feature** inside modules (`ig.session`, `ig.rest`), not package-by-layer.
- **Comments are lean** (Alex's ruling, 2026-09-28): javadoc for public contracts,
  one-liners preferred; inline comments only for invisible constraints (doctrine that the
  code cannot show); no narration of what the next line does.

## 3. Null-safety: JSpecify `@NullMarked` (D14)

**The convention:** every production package is `@NullMarked` from birth via
`package-info.java` — *everything is non-null unless annotated `@Nullable`*. The
annotation burden lands on the rare nullable spots, which is exactly where reader
attention belongs. `org.jspecify:jspecify` is an `api`-scope dependency (annotations
appear in public signatures; it's ~3KB, zero transitive deps).

Rules of thumb:

- `@Nullable` marks **genuine domain absence** (`PricePoint.lastTraded` — not every
  instrument trades; `IgApiException.errorCode` — not every failure carries a code).
  If null would be a *bug* rather than a meaning, don't mark it — **enforce it** (throw
  naming the field; see `requireDecimal` in `IgRestClient` — adopting D14 immediately
  surfaced that bid/ask were silently nullable, and the fix was fail-loud, not
  `@Nullable`). Annotations describe contracts; they are not a substitute for validation.
- **New package ⇒ new `package-info.java` with `@NullMarked`** — part of the package's
  birth, never retrofitted later.
- **Tests are not annotated** — signal belongs in production contracts.
- **Interop caveat:** unannotated third-party APIs (Jackson, the JDK's older corners)
  yield "unspecified nullness" — treat their returns as nullable at the boundary and
  normalise immediately (null-check or fail loud); never launder them straight into a
  non-null field.
- Why JSpecify over the alternatives (JetBrains/`javax.annotation`/Checker): it's the
  spec the ecosystem converged on (JetBrains, Google, Spring aligned); one standard,
  tool-enforced in IntelliJ, no framework baggage.

## 4. Wire & serialization conventions

- Parsing is **tree-based and explicit** (`readTree` + typed extraction), not annotated
  DTO binding — the wire shape stays visible, and partial/malformed input hits explicit
  policy instead of silent defaults.
- **Never guess about ambiguous wire data** — a candle without `snapshotTimeUTC`, a price
  point without bid/ask: throw naming the field (P9). Callers own blast-radius policy.
- **Golden fixtures carry provenance** (`ig-client/src/test/resources/wire/README.md`):
  playbook-anchored vs authored vs corroborated, re-goldened at first live contact.
- External API knowledge lives in `docs/reference/` (e.g. the scraped IG reference), never
  only in code comments.

## 5. Dependency policy

Every dependency earns its place; the current production set of the whole repo is
**two** (Jackson, JSpecify) plus the Lightstreamer SDK arriving with T3. Libraries
(`core`, `ig-client`) stay framework-free — their consumers include contract tests and
throwaway CLIs that must not drag Spring. Version catalog is the single source of
versions; wrapper pins Gradle; toolchain pins Java.

## 6. Frontend — reserved (provisional shape agreed 2026-09-28)

Full conventions (component architecture, state model, build/CI lane) get written with E2
planning — Alex has ideas queued. **Provisional structure, confirmed in principle:**
`frontend/` is a workspace root (own lockfile/toolchain; CI job keyed on `frontend/**`)
containing **one product SPA** (`platform/` — PRD sub-platforms as feature folders/routes,
never micro-frontends: P7 applies to JS too) and the **E2 board viewer as a separate tiny
app** (`board-viewer/` — dev tool, different stakes/lifecycle; keeps warm-up choices from
prematurely binding the product app). `shared/` is born only when both apps prove a need.
Docs split by altitude: frontend design truth in `docs/design/`, component-level docs with
the code.
