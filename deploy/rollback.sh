#!/usr/bin/env bash
# Roll back to the tag deployed before the current one (E1-T7): the deploy script, re-run with it.
set -euo pipefail
previous=$(cat /etc/tradebench/release.previous 2>/dev/null) || { echo "rollback: no previous release recorded" >&2; exit 1; }
exec "$(dirname "$0")/deploy.sh" "$previous"
