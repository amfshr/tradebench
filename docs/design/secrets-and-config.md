# Secrets & Configuration — the platform approach (D16)

> How credentials and machine config are handled everywhere: local dev, every deployed
> service, CI, and the multi-user platform era. Designed once, 2026-09-28 (Alex's ask:
> "get this right the first time"). The playbook's scar tissue applies (§1.7 — two real
> leak incidents in one week): secrets live in tiny, single-purpose, radioactive files.

## The five principles

1. **Apps read plain environment variables. Only.** Never files, never stores, never SDK
   calls — the *injector* varies by context (script, direnv, compose, secret manager); the
   app's contract never does. Required variables fail at startup naming the exact key; no
   code defaults for anything machine- or account-shaped.
2. **One flag pins one environment, and the flag selects the credential set.**
   `TRADEBENCH_IG_ENV=demo|live` → the app reads `IG_DEMO_*` or `IG_LIVE_*` exclusively
   (`IgEnvironment.envPrefix()` — already built). Flipping environments can never mix
   credentials with the wrong base URL, *by construction*. A deployed instance is pinned to
   exactly one environment by its env file.
3. **One key per account is IG's reality — isolation comes from discipline, not key
   issuance.** *(Corrected 2026-09-28, Alex + playbook §6: IG issues ONE key per account;
   an earlier draft claimed per-service keys from the scraped reference — the playbook's
   empirical record wins.)* All services **and** humans on an account share its single key,
   each holding their own session/connection (two concurrent LS connections on one demo key
   are field-proven to work; IG's FAQ still warns against multiplying them). The
   discipline that replaces key separation: the >60s login stagger between any two
   processes (built into the client), the per-service single-instance guard (advisory
   lock, T4), a minimal connection count — and no ad-hoc tooling against an account while
   its service streams, or accept the shared-key risk knowingly. Runbook names keys per
   account+environment (`demo-key`, `live-key`), listing every process that uses each.
4. **Service identity ≠ user data.** Env files carry *service* credentials only. In the
   multi-user era (E8 login), platform users' broker credentials are **data**: entered via
   the UI, stored in Postgres encrypted at the application layer (AES-GCM), with the master
   key arriving — like every other secret — through the service's env. Env files never
   grow per-user entries.
5. **Secrets exist in exactly one place per context** (matrix below), reach machines by
   hand (scp/console — rare, deliberate), and appear nowhere else: not the repo, not CI,
   not logs (`toString` masking is tested), not chat/PRs. GitGuardian is the backstop, not
   the plan.

## The matrix — where secrets live, per context

| Context | Location | Loader | Notes |
|---|---|---|---|
| Local dev | `.env` at repo root (gitignored) | `scripts/run-capture.sh` (`set -a; source`), or direnv `.envrc` | `.env.example` documents names; single-quote values (`$` trap) |
| Deployed service (T7+) | `/etc/tradebench/<service>.env`, root-owned `0600` | compose `env_file:` | one file per service instance, each pinning its own IG env |
| CI | **nothing** | — | no broker secrets in CI ever; integration-gated tests don't run there |
| Multi-user era | encrypted DB columns | app decrypts with env-provided master key | E8; never env files |
| Optional local upgrade | 1Password | `op run --env-file` injects at launch | same variable names; secrets never on disk |

## Variable naming (the stable contract)

- `TRADEBENCH_IG_ENV` — `demo` | `live`, required, selects everything IG-side.
- `IG_DEMO_IDENTIFIER / PASSWORD / API_KEY / ACCOUNT_ID` and the `IG_LIVE_*` mirror — only
  the selected set is read.
- `TRADEBENCH_INSTANCE` — every process names itself before acting.
- `TRADEBENCH_*` for all service config (`_CAPTURE_DIR`, `_EPICS`; later `_DB_URL`,
  `_DB_PASSWORD`, `_SMTP_*`, `_MASTER_KEY`).
- Source attribution follows the environment automatically (`ig-stream-demo` /
  `ig-stream-live`) — captured data always knows which wire it came from (PRD §7).

## Demo vs live is its own axis — never derived from the app's dev/staging/prod

**`TRADEBENCH_IG_ENV` is deliberately independent of any application environment.** Each
deployed instance pins its IG side explicitly in its own env file; app-staging↔IG-demo and
app-prod↔IG-live are the *typical* pairings, but they are convention, not code — a staging
instance may point at IG-live (e.g. shadow-diff verification) and a prod instance at
IG-demo (e.g. first cautious deploy), and nothing in the platform assumes otherwise.

**The capture path:** T3–T7 run against IG-demo (the demo account's key). Once deployed
and stable, prod capture moves to the **live account's key** (capture wants the account
that will trade — demo data quality differs, playbook §1.5). Since that key is the
account's only one, its reliability is observable cleanly in the service's events/digest
*provided* human ad-hoc use of the live key stays rare and outside market hours — a
runbook rule, not a mechanism, until IG offers better.

## Lifecycle

Rotation = edit the env file, restart the service (document per-key steps in the T7
runbook). Suspected leak = revoke at IG **first**, rotate second. Adding a service = new
key at IG, new env file, new runbook line. Moving to a cloud secret store later (SSM etc.)
is mechanical: it becomes another injector behind the same variable names.
