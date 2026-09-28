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
3. **One API key per service instance, never shared, never used by humans.** IG issues
   multiple keys per account; one login + one LS connection per key is the topology that
   stays inside IG's rules (reference §3). So: the collection service gets its own key, the
   future OMS its own, ad-hoc smokes/CLIs use Alex's personal key — a human poking around
   can never collide with a service's connection or trip the §1.2 login stagger against it.
   Keys are *named* in the runbook (never their values): `capture-demo`, `capture-live`,
   `oms-live`, `alex-dev`.
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
| Deployed service (T7+) | `/etc/tradebench/<service>.env`, root-owned `0600` | compose `env_file:` | one file per service instance; staging=demo file, prod=live file |
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

## Demo vs live, and Alex's live-key plan

Capture ultimately wants the account that will trade (demo data quality differs — playbook
§1.5; the build plan left "which account feeds cloud capture" open). The path: T3–T7 run
demo (`capture-demo` key); once deployed and stable, prod capture moves to **its own live
key** (`capture-live`) — created for the service, never Alex's personal key — and demo
stays as staging's wire. Reliability of the live key is then observable in the service's
own events/digest without any human usage muddying it.

## Lifecycle

Rotation = edit the env file, restart the service (document per-key steps in the T7
runbook). Suspected leak = revoke at IG **first**, rotate second. Adding a service = new
key at IG, new env file, new runbook line. Moving to a cloud secret store later (SSM etc.)
is mechanical: it becomes another injector behind the same variable names.
