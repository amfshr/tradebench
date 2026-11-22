#!/usr/bin/env bash
# The archive's copy at home (B25, E1-T17): the R2 bucket's Parquet archive copied to this machine with a read-only
# token, then a line per archived day. `copy`, never `sync`: nothing here is ever deleted because R2 dropped it — the
# dumps R2 retires stay too. Run by hand, before an analysis or a restore drill; no scheduler.
#   Usage: scripts/r2-mirror.sh [--dumps]      (--dumps: the database dumps as well)
# Reads TRADEBENCH_R2_ENV (default ~/.config/tradebench/r2-readonly.env), which holds the read-only remote
# RCLONE_CONFIG_R2RO_*, the bucket TRADEBENCH_ARCHIVE_BUCKET and the local folder TRADEBENCH_MIRROR_DIR — all personal
# (G1), so none of them is here. Never prints a value from that file.
set -euo pipefail
cd "$(dirname "$0")/.."
env_file=${TRADEBENCH_R2_ENV:-$HOME/.config/tradebench/r2-readonly.env}
[ -r "$env_file" ] || { echo "r2-mirror: no readable env file at TRADEBENCH_R2_ENV or ~/.config/tradebench/r2-readonly.env" >&2; exit 2; }
set -a; . "$env_file"; set +a
: "${TRADEBENCH_ARCHIVE_BUCKET:?r2-mirror: TRADEBENCH_ARCHIVE_BUCKET is not set in the env file}"
: "${TRADEBENCH_MIRROR_DIR:?r2-mirror: TRADEBENCH_MIRROR_DIR is not set in the env file}"
export RCLONE_CONFIG=/dev/null # the remote is the env file's RCLONE_CONFIG_R2RO_*; no config file to look for

rclone copy "r2ro:$TRADEBENCH_ARCHIVE_BUCKET/platform" "$TRADEBENCH_MIRROR_DIR/platform"
if [ "${1:-}" = --dumps ]; then
    rclone copy "r2ro:$TRADEBENCH_ARCHIVE_BUCKET/dumps" "$TRADEBENCH_MIRROR_DIR/dumps"
fi

summary="$(pwd)/scripts/analysis/archive-summary.sql"
for market in "$TRADEBENCH_MIRROR_DIR"/platform/*/*/; do # the platform user's folder: <source>/<market>/<day>
    [ -d "$market" ] || continue
    echo "== $(basename "$market") ($(basename "$(dirname "$market")"))"
    (cd "$market" && duckdb -no-agent -markdown < "$summary")
done
