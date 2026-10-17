# The deploy: the box, the bucket, backups, access and shipping

**Status: CLOSED** (2026-10-17). Resolves E1-T7's opening provider analysis: where the collector
runs, where its archive and backups go, how the box is reached, and how a release reaches it — for
one environment (production on IG demo) and no Mac soak (both Alex, 2026-10-17, at the session's
start). Decision **D34**; D24 R3's direction made formal. Spawns E1-T7's build.

## Why now

E1-T6 is merged; the collector heals and archives and wants a home that runs unattended. D24 R3
(2026-09-30) fixed a direction only — a small rented VPS, one compose stack, a bucket, Hetzner
leading — and left the formal ruling to this ticket. Alex asked to compare AWS, GCP, Cloudflare,
Hetzner and others before designing the deploy steps (2026-10-16).

## Settled before the session

- **One environment** — production on IG demo; staging is added when a second environment earns its
  keep (Alex, 2026-10-17). Supersedes the T7 plan's "staging + prod, promoted by release tag": a
  release tag still promotes, to the one environment.
- **No Mac soak** — a week of local running stands as the soak (Alex, 2026-10-17; supersedes his
  2026-09-28 pre-cloud runway).
- **Already ruled elsewhere:** Grafana Cloud's free tier for telemetry and healthchecks.io for the
  dead-man ping (D33); Redis in the stack (D32); Cloudflare Tunnel + Access at E9-T4, not here (D24);
  the archive is S3-protocol and provider-neutral (E1-T6 nod ruling 1); owner / app / read-only
  database roles from the ops script (E1-T6 nod ruling 9).

## The survey, condensed

**Grounding.** The prototype's build plan §6 compared Lightsail London ($12, 2 GB, x86) with
Hetzner's ARM CAX11 (then ~€4), rejected Oracle's free tier for unattended reliability ("idle-reclaim
policies and account-closure horror stories"), and said not to contort the design around free tiers.
The PRD's envelope is ~£20/month steady state, and the bar is "a system Alex doesn't have to think
about between sessions" (PRD §9).

**Prices, checked 2026-10-17.** Hetzner raised cloud prices twice in 2026 (1 April, ~+33% on all
servers; 15 June, new orders only); the **CAX11** — 2 ARM vCPU, 4 GB, 40 GB, 20 TB EU traffic — is
**€5.99/month**. Some reports note CX/CAX stock shortages this year. **Lightsail** 2 GB is $12 (2 vCPU,
60 GB, 3 TB); **DigitalOcean** 2 GB is $12 (1 vCPU). **R2**: 10 GB-month, 1 M writes and 10 M reads free
each month, no egress fees, then $0.015/GB-month. **Hetzner Object Storage**: €6.49/month minimum
(1 TB), with a degraded period in July 2026.

**The lesson, as given.**
- *Location barely matters for capture*: IG stamps every tick and candle itself, so Germany's extra
  15–20 ms to IG London changes nothing in the data.
- *The offsite copy belongs at a different company*: a backup on the box's own provider shares its
  billing, account and regional failure modes.
- *ARM is a non-issue for Java*: Postgres, Redis and the Temurin JRE ship ARM images; DuckDB has a
  `linux_arm64` jar. The one ARM-specific step is building the image for ARM.
- *Memory is the constraint*: the collector JVM with DuckDB and the OpenTelemetry agent ~400–600 MB,
  Postgres ~200–400 MB, Redis ~50 MB, and E9's control service and `cloudflared` ~500 MB later — 2 GB is
  tight once E9 lands, 4 GB comfortable.
- *Growth is small*: a full DAX day is ~100–180k ticks, ~30 MB in Postgres, ~10 GB a year; the day's
  Parquet is a few MB. A 40 GB disk lasts years.

## Rulings (Alex, 2026-10-17 — "I think I agree with all")

- **R1 — The box: Hetzner CAX11** (ARM, 2 vCPU, 4 GB, 40 GB, €5.99/month); the x86 **CX23** the fallback
  if ARM stock is short. Twice the memory for half Lightsail's or DigitalOcean's price; the London
  latency does not touch the data. Accepted risk: Hetzner's 2026 price rises — the compose stack makes
  a move a weekend's work. Ubuntu 24.04 LTS with unattended security upgrades.
- **R2 — The region: Nuremberg** (Falkenstein equal), the closest to IG London of Hetzner's EU sites.
- **R3 — The bucket: Cloudflare R2** — free at our size, already a Cloudflare account, and a different
  company from the box.
- **R4 — Backups, two layers:** a nightly `pg_dump` to R2 keeping seven daily and four weekly copies
  (the portable copy), and Hetzner's daily whole-box backups (~€1.20/month; the fast full restore).
- **R5 — Access: Tailscale for SSH; no public inbound port** — Hetzner's cloud firewall blocks all
  inbound. Outbound only: IG, R2, Grafana Cloud, healthchecks.io, the image registry, OS updates.
  Keeps D24's outbound-only posture; Cloudflare Tunnel joins at E9-T4.
- **R6 — Shipping: GitHub Actions builds the ARM image on a release tag** and pushes it to GitHub's
  container registry (GHCR, free for a public repository); a deploy script on the box pulls the tag
  and restarts the stack; a rollback re-runs it with the previous tag.
- **R7 — The image: a plain Dockerfile** on the Temurin 25 JRE — it carries the OpenTelemetry agent and
  the `linux_arm64` DuckDB jar, which Jib makes awkward.
- **R8 — Host metrics: a weekly hand check from the runbook** (disk, memory); Grafana Alloy for host
  metrics waits for E9. The JVM's own metrics already reach Grafana over OpenTelemetry (D33).
- **R9 — Grafana Cloud's region: EU**, next to the box.

**Cost:** CAX11 €5.99 + backups ~€1.20 + R2 €0 + Grafana Cloud €0 + healthchecks.io €0 + Tailscale €0 ≈
**€7.20/month (~£6.30)**.

## Illustrative sketch (non-committed)

```
  GitHub Actions ── tag v0.x.y ──▶ build linux/arm64 image ──▶ ghcr.io/amfshr/tradebench-market-data:v0.x.y
                                                                        │ pull (deploy script, over Tailscale SSH)
  Hetzner CAX11 · Nuremberg · Ubuntu 24.04 · firewall: no inbound ──────┘
  ├─ docker compose: market-data-service · postgres:16 · redis · nightly pg_dump
  ├─ /etc/tradebench/market-data.env (0600)
  └─ outbound: IG · R2 (archive + dumps) · Grafana Cloud EU (OTLP) · healthchecks.io · GHCR
```

## Spawned work

- **E1-T7 build**, against these rulings: the Dockerfile; the release workflow; the production
  compose file; the deploy and rollback script; the nightly dump with its retention; the database roles
  script; the OpenTelemetry agent and the healthchecks.io ping wired into the collector; the runbook.
- **Accounts Alex creates** (no secret reaches the repository, G1): Hetzner Cloud, Cloudflare R2 (a
  bucket and an access key), Grafana Cloud (EU stack, OTLP endpoint and token), healthchecks.io (one
  check), Tailscale.

## PRD open-question register

No open question resolved or raised. PRD §9's ~£20/month envelope met at ~£6.30.
