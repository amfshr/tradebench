#!/usr/bin/env bash
# backup.sh's dump (E1-T7; D39): driven against stubbed docker, rclone, curl and stat in a temporary tree — the dump
# leaves the ticks out (the Parquet archive holds them), keeps everything else, and still goes to R2 as before.
# Run from the repository root: deploy/test/backup-test.sh
set -u
T=$(mktemp -d)
trap 'rm -rf "$T"' EXIT
mkdir -p "$T/deploy" "$T/etc" "$T/bin" "$T/backups"
sed -e "s#/etc/tradebench/backup.env#$T/etc/backup.env#" -e "s#/var/backups/tradebench#$T/backups#g" deploy/backup.sh > "$T/deploy/backup.sh"
echo "TRADEBENCH_BACKUP_BUCKET=test-bucket" > "$T/etc/backup.env"
printf '#!/usr/bin/env bash\necho "$*" >> "%s/docker.calls"; echo dump-bytes\n' "$T" > "$T/bin/docker"
printf '#!/usr/bin/env bash\necho "$*" >> "%s/rclone.calls"\n' "$T" > "$T/bin/rclone"
printf '#!/usr/bin/env bash\necho 11\n' > "$T/bin/stat"
printf '#!/usr/bin/env bash\nexit 0\n' > "$T/bin/curl"
chmod +x "$T"/bin/* "$T/deploy/backup.sh"
PATH="$T/bin:$PATH" bash "$T/deploy/backup.sh" > "$T/out" 2>&1 || { echo "FAIL: backup.sh exited non-zero"; cat "$T/out"; exit 1; }

failures=0
fail() { echo "FAIL: $*"; failures=$((failures + 1)); }
dump=$(grep "pg_dump" "$T/docker.calls"); dump="pg_dump ${dump#*pg_dump }" # pg_dump's own arguments, not docker's
grep -q -- "--exclude-table-data=ticks" <<< "$dump" || fail "the dump does not leave the ticks out: $dump"
grep -q -- "-Fc" <<< "$dump" || fail "the dump is not the custom format: $dump"
grep -q " market_data$" <<< "$dump" || fail "the dump is not of market_data: $dump"
grep -qE -- "--exclude-table(=| )|-T |--table|-t " <<< "${dump//--exclude-table-data=ticks/}" && fail "the dump leaves out more than the ticks' rows: $dump"
grep -q "copyto .*r2:test-bucket/dumps/daily/market_data-" "$T/rclone.calls" || fail "the dump did not go to R2: $(cat "$T/rclone.calls")"
[ "$failures" = 0 ] && echo "backup: the dump leaves the ticks to the archive, keeps the rest, and goes to R2" || exit 1
