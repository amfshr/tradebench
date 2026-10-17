# Runbook — the collector in production

> The operating manual for the deployed collection service (E1-T7, D34): one environment, production on IG
> demo, on a Hetzner CAX11 in Nuremberg. Every secret lives in `/etc/tradebench/*.env` on the box and in no
> repository (G1, D16). The design behind it: `docs/design/sessions/2026-10-17-the-deploy-provider-analysis.md`;
> the platform's shape: `docs/design/architecture/platform.md`.

## 1. First deploy, from zero

**The accounts** (once; keep each value in a password manager, never in a chat or a commit):

| Account | What to create | What to keep |
|---|---|---|
| Hetzner Cloud | an account and a project | — |
| Cloudflare R2 | a bucket (e.g. `tradebench-archive`) and an API token scoped to it, read and write | the access key id, the secret, the account's S3 endpoint |
| Grafana Cloud | a free stack in the **EU** region | its OTLP endpoint, and an access-policy token with metrics and logs write — base64 of `<instance id>:<token>` is the header's value |
| healthchecks.io | one check: period 1 minute, grace 5 minutes (a second, period 1 day, for the nightly dump — optional) | each check's ping URL |
| Tailscale | a free personal account, installed on your Mac | — |

**The box.** In Hetzner's console: a **CAX11** (Arm64) in **Nuremberg**, image **Ubuntu 24.04**, your SSH key,
**Backups on**, and a **firewall** allowing inbound SSH (22) only — it is removed again in step 4. Then:

1. SSH in as root and run the provisioning script from the repository:
   ```
   curl -fsSL https://raw.githubusercontent.com/amfshr/tradebench/main/deploy/provision.sh | bash
   ```
2. `tailscale up --ssh` — follow the login link; the box now appears in your tailnet.
3. From your Mac, check you can reach it over Tailscale: `ssh root@<its tailscale name>`.
4. In Hetzner's console, **delete the firewall's SSH rule.** The box now has no public inbound port at all.
5. Write the three env files from the templates, with real values, mode 600:
   ```
   cd /opt/tradebench/deploy/env
   for f in market-data postgres backup; do cp $f.env.example /etc/tradebench/$f.env; done
   chmod 600 /etc/tradebench/*.env && nano /etc/tradebench/market-data.env   # and the other two
   ```
   The role passwords in `postgres.env` must match `TRADEBENCH_DB_PASSWORD` and `TRADEBENCH_DB_MIGRATE_PASSWORD`
   in `market-data.env`. Pick a new `TRADEBENCH_INSTANCE` name for the box.
6. **Release.** On your Mac: `git tag v0.1.0 && git push origin v0.1.0`. GitHub Actions builds and tests on ARM and
   publishes `ghcr.io/amfshr/tradebench-market-data:v0.1.0` (the Release workflow; a few minutes). The first time,
   make the package public in GitHub's package settings, or log the box in to GHCR with a read-only token.
7. **Deploy.** On the box: `/opt/tradebench/deploy/deploy.sh v0.1.0`, then watch it:
   `docker compose -f /opt/tradebench/deploy/compose.prod.yaml logs -f market-data`. You want `capturing to …
   (migrated; advisory lock held)`, `daily job: heal, then archive to bucket …`, `dead-man ping on`, and the
   stream's `CONNECTED:WS-STREAMING`.
8. **Stop the local collector** on your Mac once the box is streaming: two collectors on one IG key is a second
   Lightstreamer connection on the shared key (the advisory lock cannot see across two databases).

## 2. Every evening (until E9's digest — D30)

Five looks, two minutes. Field Manual chapter 11 §8 has the queries; on the box, open SQL with:
```
docker compose -f /opt/tradebench/deploy/compose.prod.yaml exec postgres psql -U market_data_reader -d market_data
```
1. **healthchecks.io** — the collector's check green (and the dump's, if you made it).
2. **The heartbeat line** — `docker compose … logs --tail 3 market-data`: ticks climbing, `dropped=0`, `sinkFailures=0`.
3. **`capture_status`** — `updated_at_utc` within two minutes, `stream_state` `connected_streaming`.
4. **The day's `job_runs`** — yesterday's `heal` (and `archive`) rows `succeeded`; a missing row is the alarm.
5. **Grafana Cloud** — a glance at the JVM's memory and the log stream for anything loud.

## 3. Every week — the host (D34 R8)

```
df -h /                       # disk: well under 80% (a full DAX year is ~10 GB)
free -m                       # memory: the collector capped at 1 GB, Postgres beside it
docker system df              # images: prune old releases with `docker image prune -a` when it grows
```

## 4. Rolling back

```
/opt/tradebench/deploy/rollback.sh          # the tag before the current one
/opt/tradebench/deploy/deploy.sh v0.1.0     # or any tag, by name
```
A release only ever adds Flyway migrations, so an older collector runs against a newer schema only if no
migration broke it — check the release notes before rolling back across a migration.

## 5. Rotating a secret

Edit the value in `/etc/tradebench/<file>.env`, then restart the one service that reads it:
`docker compose -f /opt/tradebench/deploy/compose.prod.yaml up -d market-data`. For the IG password, the R2 key,
the Grafana token or the ping URL that is all. **A database role's password** changes in Postgres too — the env
file is read only at the volume's first start:
```
docker compose … exec postgres psql -U postgres -d market_data -c "ALTER ROLE market_data_app PASSWORD '<new>'"
```

## 6. Restoring from a dump

```
rclone ls r2:<bucket>/dumps/daily                                    # with backup.env loaded: set -a; . /etc/tradebench/backup.env; set +a
rclone copyto r2:<bucket>/dumps/daily/market_data-<day>.dump /tmp/restore.dump
docker compose … stop market-data
docker compose … exec -T postgres pg_restore -U postgres -d market_data --clean --if-exists < /tmp/restore.dump
docker compose … start market-data
```
A whole-box failure is faster from Hetzner's daily backup: restore the server from the console, then check the
collector came back (§2). The heal fills the bars the outage cost, up to seven days back (Field Manual chapter 12).

## 7. When something is wrong

| You see | It means | Do |
|---|---|---|
| healthchecks.io alarming, the box unreachable | the box or its network is down | Hetzner's console: status, the server's console, a reboot; the restart policy brings the stack back |
| alarming, the box up, the container restarting | the collector exits (`FEED_DEAD`, a config refusal) | `logs market-data` — the `FATAL:` line says why; a config refusal names the variable |
| a `job_runs` row `failed` | a heal or an archive failed | its `detail` says why; it retries on its own (1 h, then doubling) — act only if it keeps failing |
| no `job_runs` row for yesterday | the daily job never ran | the collector was down past 00:10 UTC, or the jobs thread died (a `FATAL: jobs runner died` line) — restart the collector; the job catches up |
| `sinkFailures` above 0 | Postgres blinked and the sink held through it | nothing, if `written` caught up; the episode is a `sink_failure` row |
