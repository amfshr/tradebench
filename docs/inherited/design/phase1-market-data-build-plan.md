# Phase 1 build plan — IG client, market-data service, cloud deployment

**Status:** design — written 2026-08-23, graduating the "Module map & Gradle structure" backlog item.
**Scope:** the FIRST build slice of the Java platform: an own-built IG integration + a deployable
market-data capture service, hosted off-laptop (AWS) so streaming no longer depends on the MacBook
Air being awake Mon–Fri. Everything else in the platform vision (README pillars 3–10) is explicitly
*later* — but the module map below reserves their seats so phase 1 doesn't paint us into a corner.

Companion docs: [`../ig-broker-playbook.md`](../ig-broker-playbook.md) (the how-IG-really-behaves
bible — §9 is the Java porting section), [`../data-platform-design.md`](../data-platform-design.md)
(the what-and-why of the capture design: atoms, healing, coverage ledger),
[`../ideas/event-source-and-clock-model.md`](../ideas/event-source-and-clock-model.md) (the clock/
event-source seams phase 1 must respect).

---

## 1. What phase 1 is (and isn't)

**Is:**
1. `ig-client` — a from-scratch IG integration library: REST (session/auth, markets, historical
   prices for healing, dealing rules) + streaming (a thin wrapper over the **official Lightstreamer
   Java SDK**). No dependency on IG's public client libraries.
2. `market-data-service` — a deployable Spring Boot app: subscribes PRICE (ticks) + CHART:1MINUTE
   (sealed bars) per instrument, persists to Postgres, detects gaps, heals surgically over REST,
   keeps the coverage ledger, and reports its own health.
3. A **deployment shape** that runs unattended on a small cloud box (analysis in §6) and later moves
   to the NUC unchanged — the artifact is a container; the host is interchangeable.

**Isn't:** indicators, strategy DSL, signal engine, OMS, simulator, backtester, workbench. Also NOT
phase 1: deriving higher timeframes (3/5/10/15/30/60m) and the Parquet archive export — both are
pure downstream consumers of a healed 1-minute record and slot in as **phase 1.5** without touching
the capture path (`data-platform-design.md` §1, §5).

**On "not using the public library":** confirmed as the right call, and it's already the playbook's
recommendation (§9). One precision worth keeping: *IG's* client libraries (the abandoned
`ig-webapi-java-client` etc.) are what's deprecated — the **Lightstreamer client SDK**
(`com.lightstreamer:ls-javase-client`, 5.2.1, Feb 2025) is a separate, actively maintained product
from Lightstreamer themselves. IG's stream is a standard Lightstreamer server; using their official
client SDK *is* "integrating the API myself" — we own the session, subscription, and resilience
logic. Hand-rolling the TLCP wire protocol instead would cost weeks and buy no additional control.
The REST side has no official Java SDK at all, so that layer is genuinely from scratch.

---

## 2. Repo & module structure

**New repo** (this Python repo stays Dad's live system; the platform is greenfield). Name is Alex's
call — working assumption `amfshr/trading-platform`. Java package root likewise —
`com.amfshr.trading.*` as the placeholder below.

**Gradle multi-module, Kotlin DSL, version catalog** (`gradle/libs.versions.toml`), **Java 21 LTS**
toolchain. (Maven would also be fine; Gradle's multi-module ergonomics and the version catalog win
for a repo that will grow ten modules. Decide for good at repo creation.)

```
trading-platform/
├── settings.gradle.kts
├── gradle/libs.versions.toml          # single place for Spring Boot / LS SDK / Jackson / etc. versions
├── ig-client/                         # ★ phase 1 — pure Java, ZERO Spring (see §4)
├── platform-core/                     # ★ phase 1 — domain types + the clock/event-source SPI (see §4)
├── market-data-service/               # ★ phase 1 — Spring Boot app (see §5)
│
│   # seats reserved — NOT built in phase 1, listed so boundaries are cut with them in mind:
├── indicator-lib/                     # pillar 3 (ideas/indicator-library.md)
├── strategy-dsl/                      # pillar 4
├── engine/                            # pillars 4–5 consumer — one engine, many clocks
├── execution-oms/                     # pillar 6
├── ig-simulator/                      # pillar 7 — fake REST+stream; ig-client's interfaces are its contract
├── backtester/                        # pillar 8
└── workbench/                         # pillar 9 + Richard's visual tool
```

Dependency rules (enforced from commit 1):
- `ig-client` depends on **nothing** of ours; `platform-core` depends on nothing of ours. Apps
  depend on libraries, never sideways between apps.
- **`ig-client` stays framework-free.** It will be consumed by the market-data service, the future
  OMS, the simulator's contract tests, and throwaway CLIs — none of which should drag Spring in.
- Everything time-dependent takes an **injectable clock** and consumes an **event-source interface**
  (defined in `platform-core`) from the first commit — that's the seam the backtester and simulator
  later plug into (`ideas/event-source-and-clock-model.md`). Phase 1 only ever builds the *live*
  implementations, but against the interface.

### Tech stack (proposed)

| Concern | Choice | Why |
|---|---|---|
| Language/runtime | Java 21 LTS | Virtual threads fit blocking JDBC + queue handoff; current LTS |
| App framework | Spring Boot 3.x — **apps only** | Alex's fluency; config/scheduling/Actuator for free |
| REST transport | JDK `HttpClient` + Jackson (in `ig-client`) | Zero framework deps in the library |
| Streaming | `com.lightstreamer:ls-javase-client` 5.2.1 | Official, maintained, natively Java (playbook §9) |
| DB | Postgres, JDBC + Flyway migrations | Continuity with the proven design; Flyway = the migrations discipline the Python repo landed via D24 |
| Testing | JUnit 5, Testcontainers (Postgres), WireMock (IG REST), fake stream via our own transport interface | The simulator pillar grows out of these fakes |
| Build/CI | Gradle + GitHub Actions (build, test, container image) | Boring and portable |
| Packaging | Container image (Spring Boot buildpacks or `jib`) + `docker compose` | Same artifact on AWS today, NUC later |

---

## 3. What ports from the playbook — priority order

The playbook is the requirements doc for phase 1; this is the porting order it itself recommends:

1. **Error taxonomy first** (§1.6) — fatal / retryable / session-dead. Small, prevents both classic
   failure modes (retrying a fatal into a lockout; dying on a transient).
2. **Session & account discipline** (§1.1–§1.5, §1.7) — preferred-account trap, tokens in response
   headers, token↔account binding, no login cache, per-environment keys, no code defaults.
3. **Callback discipline + queues** (§2.4) — cheap work on the LS thread; `ArrayBlockingQueue`
   shed-oldest for ticks, unbounded for bars; backpressure never reaches the socket.
4. **Field semantics** (§2.1–§2.3) — CONS_END sealing, per-field-mid consolidation, epoch-ms UTC.
5. **The recovery stack** (§3) — eager `is_dead`, backoff + ceiling, **stuck-substate escalation**
   (the WILL-RETRY/TRYING-RECOVERY hang the Python version still has a ticket open for — build the
   full escalation ladder from day one in Java), staleness watchdog with the monotonic-vs-wall
   sleep/freeze discriminator (`System.nanoTime()` vs `Instant.now()`, §3.4/§9).
6. **Gap detection, REST v3 backfill + its traps, budget, windows, daily heal** (§4) — plus the
   coverage ledger from `data-platform-design.md` §6 (a first-class thing here, not retrofitted).
7. **Single-instance advisory lock** (§7) — `pg_try_advisory_lock` over JDBC, identical semantics.

---

## 4. `ig-client` + `platform-core` — module design

```
ig-client/
  …ig.error      IgError taxonomy: FATAL | RETRYABLE | SESSION_DEAD  ← port first
  …ig.session    login (v2/v3) → account switch → token store (CST/XST from response headers);
                 re-login policy; demo/live selected by ONE flag that also picks base URL + key
  …ig.rest       markets, dealing rules, historical prices (v3, numpoints+1 + TZ traps, §4.2);
                 rate limiter honouring §6; positions/orders endpoints stubbed for the future OMS
  …ig.stream     IgStreamClient wrapping ls-javase-client:
                   connect(lsEndpoint, accountId, "CST-…|XST-…")   ← §1.1 wiring, identical in Java
                   subscribe(PRICE | CHART:1MINUTE) → SubscriptionHandle
                   listener → hand off to caller-supplied queue (never work on the LS thread)
                   status stream (connection-state changes) → the supervisor consumes these
  …ig.model      records: PriceTick, ChartBar1m (sealed only), MarketDetails, DealingRules, …

platform-core/
  …core.time     Clock SPI (monotonic + wall, injectable); market-zone window logic (UTC storage,
                 zone-aware checks at check-time — §4.4/§9)
  …core.events   EventSource SPI + the live implementation contract (ticks/bars as one ordered
                 stream) — the seam live/replay/fast-forward all implement later
  …core.domain   Epic/instrument identity, Bar/Tick value types (BigDecimal), trading calendar
```

Deliberate omissions: no order placement in phase 1 (the endpoints exist as stubs so the module
shape is honest, but nothing calls them); no per-strategy anything.

---

## 5. `market-data-service` — app design

The Java expression of `data-platform-design.md`, capture side only:

- **Pipeline:** LS callbacks → bounded queues → writer (batch upserts to Postgres). Ticks and
  sealed 1m bars only; in-progress CHART updates discarded at the parser.
- **Healing:** in-service gap detector → surgical REST backfill of exact missing minutes
  (budget-aware — the ~10k-points/week allowance is real, §4.3); end-of-window completeness heal;
  ticks are *unhealable* → mark exclusion windows, don't mend.
- **Coverage ledger:** per (instrument, timeframe, day): expected/present/healed/known-empty, with
  the trading calendar encoded so scheduled closure ≠ missing. This is the future reporting UI's
  data; phase 1 builds the tables + a plain Actuator/JSON view, no web UI yet.
- **Resilience:** reconnect supervisor + full escalation ladder + staleness watchdog + witness-rule
  quarantine if multi-instrument (§3.5) — all pure logic on injectable clocks, fully unit-tested.
- **Windows:** per-instrument streaming windows in-app (belt-and-braces even if the host runs 24/7).
- **Ops surface:** `service_events`-style audit table, quiet-is-healthy warnings log, Actuator
  health, and an outbound **heartbeat ping** (e.g. healthchecks.io, free) so a dead box alerts
  Alex's phone rather than being discovered on Monday.
- **Config/secrets:** typed Spring config; secrets from env/`.env` on the box (SSM Parameter Store
  optional later); no code defaults for required keys; demo/live by flag.

**Acceptance = the shadow diff.** The Python MarketDataService keeps streaming on the Air while the
Java service runs in the cloud; after a week, diff the two 1-minute records row-by-row. The proven
Python capture becomes the **oracle** for the Java rebuild — the same validation doctrine the
prototype used against Dad's SQL triggers. This is the strongest argument for building capture
first: the oracle already exists and is running.

---

## 6. Deployment — the AWS answer

### The free-tier question, answered

AWS restructured the Free Tier on **15 July 2025**. What you get depends entirely on account age:

- **Account created on/after 2025-07-15 (i.e. a fresh one now):** no 750-free-hours anymore.
  Instead **$100 credit at sign-up + up to $100 more** for completing onboarding tasks, on a "free
  plan" lasting **up to 6 months or until credits run out**, whichever first. EC2 usage draws from
  the credits.
- **Account created before 2025-07-15 AND still inside its first 12 months:** the old regime — 750
  h/month of t2/t3.micro free. Mon–Fri streaming (~370 h/month at 05:00–22:00, ~520 h even 24/5)
  fits comfortably. But if the account is older than 12 months, the EC2 free tier is simply over.

So: **Mon–Fri streaming is NOT free-forever on AWS under any current regime.** On a fresh account
the credits comfortably cover ~6 months of this workload — then it's real money.

### What it actually costs (approximate, eu-west-2 London — verify at commit time)

| Option | Shape | ~Monthly | Notes |
|---|---|---|---|
| **Lightsail $12 plan** | 2 GB RAM, 2 vCPU, 60 GB SSD, IPv4 + 3 TB transfer **included**, flat price | **~$12** | Simplest bill; it's EC2 underneath; hourly-capped billing |
| Lightsail $7 plan | 1 GB RAM, 40 GB SSD | ~$7 | Viable with a tuned JVM (`-Xmx256m`) + small Postgres; tight |
| EC2 t4g.micro 24/7 | 1 GB Graviton ~$7 + **public IPv4 ~$3.65** + 20 GB gp3 ~$1.9 | ~$12–13 | The IPv4 charge (since 2024) is why raw EC2 no longer undercuts Lightsail at this size |
| EC2 t4g.micro, scheduled Mon–Fri | ~370 h ≈ $3.5 + IPv4 (while running) + EBS | ~$7 | EventBridge Scheduler start/stop; more moving parts |
| RDS for the DB | db.t4g.micro + storage | +$12–15 | **Skip** — run Postgres on-box; RDS doubles the bill for zero phase-1 benefit |

Genuinely-free alternatives, for completeness: **Oracle Cloud Always Free** (Ampere ARM, up to 4
OCPU/24 GB, free indefinitely — real, but idle-reclaim policies and account-closure horror stories
make it a risky home for a system whose whole point is unattended reliability), and **Hetzner**
CAX11 (~€4/month, 2 vCPU/4 GB ARM — the best raw value in the paid tier if being on AWS isn't
itself a goal).

### Recommendation

**Don't contort the design around free tier.** The honest steady-state cost of the sensible options
is **~£4–10/month** — below one DAX point of stake, and it directly retires a known operational
risk (the Air sleeping on battery mid-session, the mains-until-NUC constraint). Concretely:

1. **Lightsail, London, $12 (2 GB) plan** — or $7 if the first month's memory graphs say 1 GB is
   comfortable. Flat price, IPv4 and transfer included, nothing to schedule.
2. **One `docker compose` stack on it:** `market-data-service` + `postgres:16` + a nightly
   `pg_dump → S3` cron (pennies). No inbound ports except SSH (or Tailscale and not even that).
   Outbound-only to IG + the heartbeat URL.
3. **Run 24/7, let the app's own windows govern streaming.** A stopped host can't heal, alert, or
   be inspected; the ~£4 saved by instance-scheduling isn't worth the blind spots. Revisit later.
4. If a fresh AWS account is opened for this: take the sign-up credits — they make the first ~6
   months free anyway — but treat that as a bonus, not the plan.
5. The NUC remains the long-term home; because the deliverable is a compose stack, "migrate to the
   NUC" is `docker compose up` on different hardware plus a DNS-free heartbeat rename. AWS is the
   interim streaming box the Air was never suited to be.

---

## 7. Build sequence (weekend-sized slices)

Honest sizing: a focused weekend gets slices 1–4 (demo ticks+bars flowing into Postgres from a Java
process). The resilience stack (slice 5) is the long tail — it's most of what the playbook exists
to transfer — and trust arrives only after slice 7's shadow week.

1. **Repo bootstrap** (hours): Gradle multi-module skeleton, version catalog, Java 21 toolchain,
   GitHub Actions build+test, empty modules with the dependency rules wired.
2. **Session + error taxonomy** (half-day): login → account switch → token store; taxonomy ported
   from §1.6; smoke CLI: authenticate against demo, print account + LS endpoint.
3. **Streaming wrapper** (half-day–day): LS SDK connect with CST|XST wiring; PRICE + CHART:1MINUTE
   subscriptions; queue handoff; smoke CLI: print sealed 1m bars for the DAX epic.
4. **Persist path** (day): Flyway schema (bars, ticks, service_events, coverage ledger), writer
   with batch upserts, CONS_END sealing, advisory lock.
5. **Resilience ports** (the long tail, evenings): reconnect supervisor + escalation ladder,
   staleness watchdog, gap detect + REST v3 backfill + budget, windows, EOD heal — each pure-logic
   with injectable clocks, unit-tested as it lands.
6. **Ops surface** (half-day): Actuator health, heartbeat ping, warnings log, coverage JSON view.
7. **Deploy + shadow week**: compose stack to Lightsail; run alongside the Python capture; nightly
   row-diff of the two 1m records; promote when a full week diffs clean.

---

## 8. Open decisions (settle at repo creation, none block slice 1)

- Repo + package naming (`amfshr/trading-platform`, `com.amfshr.trading.*` are placeholders).
- Gradle vs Maven (recommendation above: Gradle Kotlin DSL).
- JDBC flavour inside the service: Spring `JdbcClient` (lean, recommended for phase 1) vs jOOQ
  (revisit when the reporting/analytics surface grows).
- Instrument set at switch-on: DAX only, or DAX + the current watchlist set from day one.
- Session envelope: 05:00–22:00 vs true ~24h capture (`data-platform-design.md` §8) — phase 1 can
  start with the proven windows and widen later; the coverage calendar makes widening safe.
- Whether the demo or live account feeds the cloud capture (dealing rules and data quality differ —
  playbook §1.5; capture wants the account you'll eventually trade).
