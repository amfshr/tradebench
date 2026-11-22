#!/usr/bin/env bash
# r2-mirror.sh (E1-T17, B25) against stubbed rclone and duckdb: it copies — never syncs — the archive from the
# read-only remote into the configured folder, the dumps only when asked, summarises each market where it lies, and
# refuses without its settings, never printing a value. Run from the repository root: scripts/test/r2-mirror-test.sh
set -u
T=$(mktemp -d)
trap 'rm -rf "$T"' EXIT
mkdir -p "$T/bin"
printf '#!/usr/bin/env bash\necho "$*" >> "%s/rclone.calls"\nmkdir -p "%s/mirror/platform/ig-stream-demo/IX.D.TEST.IP/2026-01-05"\n' "$T" "$T" > "$T/bin/rclone"
printf '#!/usr/bin/env bash\necho "$(pwd)" >> "%s/duckdb.calls"\n' "$T" > "$T/bin/duckdb"
chmod +x "$T"/bin/*
printf 'TRADEBENCH_ARCHIVE_BUCKET=synthetic-bucket\nTRADEBENCH_MIRROR_DIR=%s/mirror\n' "$T" > "$T/r2.env"
mirror() { PATH="$T/bin:$PATH" TRADEBENCH_R2_ENV="$1" scripts/r2-mirror.sh "${@:2}" > "$T/out" 2>&1; }
failures=0
fail() { echo "FAIL: $*"; failures=$((failures + 1)); }

mirror "$T/r2.env" || fail "the mirror failed: $(cat "$T/out")"
[ "$(cat "$T/rclone.calls")" = "copy r2ro:synthetic-bucket/platform $T/mirror/platform" ] \
    || fail "not one copy of the archive into the folder: $(cat "$T/rclone.calls")"
[ "$(cat "$T/duckdb.calls")" = "$T/mirror/platform/ig-stream-demo/IX.D.TEST.IP" ] \
    || fail "the summary did not run in the market's folder: $(cat "$T/duckdb.calls" 2>/dev/null)"
grep -q "^== IX.D.TEST.IP (ig-stream-demo)$" "$T/out" || fail "the market is not named: $(cat "$T/out")"

rm "$T/rclone.calls"; mirror "$T/r2.env" --dumps
grep -qx "copy r2ro:synthetic-bucket/dumps $T/mirror/dumps" "$T/rclone.calls" || fail "--dumps did not copy the dumps: $(cat "$T/rclone.calls")"
grep -q "sync" "$T/rclone.calls" && fail "the mirror syncs — R2's retirements would delete the copy at home"

printf 'TRADEBENCH_MIRROR_DIR=%s/mirror\n' "$T" > "$T/nobucket.env"
mirror "$T/nobucket.env" && fail "the mirror ran without a bucket"
grep -q "TRADEBENCH_ARCHIVE_BUCKET is not set" "$T/out" || fail "the refusal does not name the setting: $(cat "$T/out")"
grep -q "synthetic-bucket\|$T/mirror" "$T/out" && fail "the refusal printed a value from the env file"

[ "$failures" = 0 ] && echo "r2-mirror: copies the archive (and the dumps when asked), summarises each market, refuses without settings" || exit 1
