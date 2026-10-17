#!/usr/bin/env bash
# The nightly dump (E1-T7, D34 R4): pg_dump from the Postgres container, compressed (custom format), copied to
# R2 with rclone; every Sunday's also kept as a weekly. Retention: dailies older than 8 days and weeklies older
# than 29 days are deleted. Run by tradebench-backup.timer; needs /etc/tradebench/backup.env.
set -euo pipefail
cd "$(dirname "$0")"
set -a; . /etc/tradebench/backup.env; set +a
: "${TRADEBENCH_BACKUP_BUCKET:?}"
day=$(date -u +%F)
dump="/var/backups/tradebench/market_data-$day.dump"
mkdir -p /var/backups/tradebench
trap 'rm -f "$dump"' EXIT
docker compose -f compose.prod.yaml exec -T postgres pg_dump -U postgres -Fc market_data > "$dump"
[ -s "$dump" ] || { echo "backup: the dump is empty — refusing to upload it" >&2; exit 1; }
rclone copyto "$dump" "r2:$TRADEBENCH_BACKUP_BUCKET/dumps/daily/market_data-$day.dump"
if [ "$(date -u +%u)" = 7 ]; then
    rclone copyto "$dump" "r2:$TRADEBENCH_BACKUP_BUCKET/dumps/weekly/market_data-$day.dump"
fi
rclone delete --min-age 8d "r2:$TRADEBENCH_BACKUP_BUCKET/dumps/daily"
rclone delete --min-age 29d "r2:$TRADEBENCH_BACKUP_BUCKET/dumps/weekly"
echo "backup: market_data-$day.dump ($(stat -c %s "$dump") bytes) uploaded"
if [ -n "${TRADEBENCH_BACKUP_PING_URL:-}" ]; then
    curl -fsS -m 10 --retry 3 "$TRADEBENCH_BACKUP_PING_URL" > /dev/null || echo "backup: ping failed" >&2
fi
