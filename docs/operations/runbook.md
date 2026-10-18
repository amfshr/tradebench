# Runbook — the collector in production

> The operating manual for the deployed collection service (E1-T7, D34): one environment, production on IG
> demo, on a Hetzner CPX22 (x86) in Nuremberg. Every secret lives in `/etc/tradebench/*.env` on the box and in no
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

**The box.** In Hetzner's console: a **CPX22** (x86, 2 vCPU, 4 GB) in **Nuremberg**, image **Ubuntu 26.04 LTS** (24.04
also works), public IPv4 and IPv6 both on, your SSH key,
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
   either make the package public in GitHub's package settings, or log the box in to GHCR once with a classic
   token that has only `read:packages` (paste it at the prompt, never on the command line):
   ```
   docker login ghcr.io -u <your GitHub username> --password-stdin
   ```
7. **Stop the local collector** on your Mac *before* the box starts: two collectors on one IG key is a second
   Lightstreamer connection on the shared key, and the advisory lock cannot see across two databases. The minutes
   between are a gap the box's first heal fills from IG's history.
8. **Deploy.** On the box: `/opt/tradebench/deploy/deploy.sh v0.1.0`, then watch it:
   `docker compose -f /opt/tradebench/deploy/compose.prod.yaml logs -f market-data`. You want `capturing to …
   (migrated; advisory lock held)`, `daily job: heal, then archive to bucket …`, `dead-man ping on`, and the
   stream's `CONNECTED:WS-STREAMING`. The deploy writes the running tag to `/opt/tradebench/deploy/.env`, so
   every later `docker compose` command on the box — this runbook's and the nightly dump's — finds the same image.

   **If Postgres's first start failed** (a typo in `postgres.env`, say), its role script never runs again on that
   volume — and Postgres comes back healthy on it, so the deploy can look fine. The sign is the collector's FATAL
   on password authentication, or a missing role `market_data_app`. Before any data exists, and only then, start
   the database afresh:
   `docker compose -f /opt/tradebench/deploy/compose.prod.yaml down -v`, fix the file, and deploy again.
9. **Prove the nightly dump once**, rather than waiting for 02:30:
   ```
   systemctl start tradebench-backup.service
   journalctl -u tradebench-backup --no-pager | tail     # no error; then `rclone ls r2:<bucket>/dumps` shows it
   systemctl list-timers tradebench-backup.timer          # the next run, tonight
   ```
10. **The first day's check** (carried from E1-T6, which could only fake it): the first archive lands in R2 with
    its checksum accepted. R2 verifies `x-amz-checksum-sha256` on upload, so the day's `archive` row `succeeded` in
    `job_runs`, and its two Parquet files listed under the archive's key prefix in the bucket, are the proof.

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
A deploy that fails part-way puts the box back on the release it replaced and says so; the rollback target is
untouched, so `rollback.sh` still means the release before the running one.
A release only ever adds Flyway migrations, so an older collector runs against a newer schema only if no
migration broke it — check the release notes before rolling back across a migration.

## 5. Rotating a secret

Edit the value in `/etc/tradebench/<file>.env`, then restart the one service that reads it:
`docker compose -f /opt/tradebench/deploy/compose.prod.yaml up -d market-data`. For the IG password, the R2 key,
the Grafana token or the ping URL that is all. **A database role's password** changes in Postgres too — the env
file is read only at the volume's first start. `\password` prompts for it, so the new value never lands in the
shell's history or the process list:
```
docker compose -f /opt/tradebench/deploy/compose.prod.yaml exec postgres psql -U postgres -d market_data
market_data=# \password market_data_app
```
Then put the same value in both env files and restart `market-data`.

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
