# Collection-service operability — observed & managed without SSH

**Status: CLOSED** (2026-09-30). Resolves the *operability architecture*: how the deployed
24/7 collection service is observed and its data retrieved without SSH — the surface shape,
the access/network posture, and the hosting direction. **Spawns epic E9** (Operator Console /
Market Data Manager v0) and its **T1**, which carries the full event/metric/data-model design.
Decision **D24**. One item deliberately **left open** and carried to E1 (see below): the
streaming-schedule model (Q1).

## Why now

Alex wants the deployed box to run unattended and be observable/manageable **without logging
onto the server** — a basic admin surface for results, gaps, and download links for the
Parquet archives / DB exports, "all available via REST," with access secured (he floated
mTLS). This is the first operational slice of PRD §5.1 (Market Data Manager) + the §9
"dashboard = informational" observability tier — PRD-native, phase-1-spine-aligned, not new
scope.

## The survey, condensed

**Grounding (seed-pack-librarian + decision log + PRD).**
- The Python prototype had **no dashboard/UI/API** — it ran on the MacBook Air (never
  internet-facing) and was operated by SSH + a push digest email + an outbound heartbeat + a
  SQL `service_events` table + a quiet-is-healthy warnings log. The unified coverage map was
  *designed but never built* (data-platform-design §6: "build the map as a first-class thing").
  The Java plan budgeted an inbound **Actuator health + coverage JSON view** (no UI yet).
- **Three time-concepts, not one** (the "Mon–Fri 06:00–17:00" trap): a per-market **stream
  window** (config, venue-local — governs subscribe/unsubscribe, *not* process lifecycle;
  triage D37 retired the daily start/stop world under an always-on host), **`DLG_FLAG`
  market-state from the stream** (governs whether silence alarms — D25, no calendar), and an
  **expected trading calendar** (classifies gaps so the coverage map isn't all-red at 3am).
- **"No REST for data" is not blanket.** D4 (events) + D17 (the `market_data` DB is a
  published read-only data product; bulk reads via read-only creds, "absurd over an API")
  cover the *data plane*. An **operational/admin plane** (health, gaps, downloads) is a third
  plane and was always intended — no conflict.
- **The inbound surface is genuinely new ground.** The inherited posture is "no inbound app
  ports; outbound-only to IG + heartbeat; SSH/Tailscale to get in." So the access posture is
  the real decision.

**The lesson (industry).** Observability splits into *push for acting* (heartbeat +
absence-alarm; the digest) and *pull for browsing* (a dashboard) — the prototype already
lives on the right side. Auth-at-the-edge paradigms: **mTLS** (strongest for machines; heavy
human cert-ops), **edge-gate + IdP / Zero Trust** (Cloudflare Access/IAP — low toil, SSO,
browser-anywhere; origin must be reachable only through the edge), **overlay network**
(Tailscale — no public surface, needs a client), **app-level auth** (simplest to picture,
weakest — you write auth and expose the app). Downloads: **pre-signed object-store URLs** are
the standard (time-limited, no proxying bytes). Cloudflare does **not** run JVMs (Workers =
JS/WASM isolates; Pages = static); the JVM + Postgres run on your own small VM, and
**Cloudflare Tunnel** (`cloudflared` dials out) fronts it with **zero inbound ports** — which
*preserves* the inherited outbound-only posture while Access authenticates at the edge.

## Rulings

- **R1 — The console is a separate read-model app, not an inbound surface on the collector.**
  A static SPA + a thin read-only service over the `market_data` data product (D17 read-only
  creds) + R2. The collector stays outbound-only and single-purpose (P7); it *produces*
  everything observable into its own DB (`service_events`, status/heartbeat rows, gap rows) +
  R2. Mirrors D11 (markdown write-model → web read-model). *Alex ruled ("really happy with
  this").*
- **R2 — Human access is edge-gated via Cloudflare Tunnel + Access; mTLS reserved for machine
  clients.** `cloudflared` dials out (no inbound ports on the box — the inherited posture
  survives); Cloudflare authenticates per person (email/SSO, free < 50 users) at the edge. The
  Electron+baked-cert path was weighed and set aside for humans: it is "possession = access"
  (extractable from the `asar`), can't revoke one person without rotating all, and drags a
  signed-desktop-app distribution pipeline onto non-technical family — where the Tunnel already
  seals the box *and* the edge authenticates, for free, with zero install. mTLS/service-tokens
  are the right tool the day a *machine* consumer needs the ops API (Access can enforce client
  certs there). *Alex ruled ("genuinely like the cloudflare solution").*
- **R3 — Hosting: a single small cloud VPS + docker-compose + R2; not owned hardware yet.**
  Hetzner leading (~€4–7/mo), one compose stack (collector + `postgres:16` + `cloudflared` +
  nightly `pg_dump → R2`). Renting now beats waiting to buy a NUC (D2 deploy-first; the compose
  stack makes a later owned-hardware migration trivial). The Mac soak is the pre-cloud runway;
  staging added later. **The formal host ruling lands in E1-T7** — this fixes the direction.
  *Alex ruled ("happy with this… I don't own a NUC currently").*
- **R4 — It is its own epic (E9), prioritised.** A distinct read-model deployable, mirroring
  how E2 (docs site) was a standalone read-model epic. E1 keeps only the collector-side
  producers + deploy. *Alex ruled ("jot the epic in").*
- **R5 — v1 is read-only: observe + download.** Control actions (start/stop streaming,
  trigger re-heal) are deferred and, when they come, belong on the event/command plane — never
  REST-into-the-collector. *Strawman default; Alex to confirm at E9-T1 kick-off.*
- **R6 — The console serves three data classes; full design is E9-T1.** (a) **Time-series
  metrics** (tick rate, reconnects, `db_pending`, last-tick-age, JVM heap/GC, pacer budget) →
  Micrometer → Prometheus → Grafana, *additive* (a container behind the same tunnel; add when
  Spring lands / time-series depth is wanted). (b) **Discrete events** — `service_events` v2
  (lifecycle, resilience, data-quality, heal, **errors, warnings**) → a filterable log +
  health/coverage views. (c) **Domain data views** — coverage map, gaps table, per-market×day
  catalogue, download links → the custom SPA over the data product (Grafana can't do these
  well). Plus the unchanged **push tier** (digest + heartbeat = "serious"). *Claude proposed;
  Alex directed E9-T1 to design the schema, metric set, and per-view SQL in full.*

## Left open (carried, not ruled)

- **Q1 — the streaming-schedule model.** The three-clock model (stream window vs `DLG_FLAG`
  watchdog vs expected-calendar) and the window default. This is a **collector-side** concern
  (E1), not the console. Recommendation on record: keep the window configurable but default it
  *generous* (a tight 06:00–17:00 on an always-on box buys ~nothing — a closed DAX just sends
  `DLG_FLAG=CLOSED`, no ticks — while risking clipped data), and let the **expected-calendar**,
  not the window, define "what's a real gap." **Carried to E1-T5 / a focused collector design
  item** for Alex's ruling.

## Spawned work

- **Epic E9 — Operator Console (Market Data Manager v0)**: T1 (this design), then build
  tickets cut after it (read-model service, the SPA, Cloudflare edge + deploy, optional
  Grafana/Prometheus metrics surface).
- **Feedback into E1-T5 slice B (sequencing):** E9-T1's event/metric data-model design should
  land **before** E1-T5 slice B implements the event log — so the collector writes
  `service_events` v2 in the right shape the first time (the initial pass was provisional).
- **E1-T7** records the formal host decision (R3 fixes the direction).

## PRD open-question register

No open question (#1–#11) resolved or created. This session **begins PRD §5.1 (Market Data
Manager)** as an operational slice and advances §8/§9 observability. D24 logged.
