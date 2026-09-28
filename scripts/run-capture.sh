#!/usr/bin/env bash
# One-shot capture run (T3 DoD test). Reads secrets from .env at repo root — never committed.
set -euo pipefail
cd "$(dirname "$0")/.."
if [[ ! -f .env ]]; then
  echo "No .env at repo root — copy .env.example, fill it in (single-quote the password)." >&2
  exit 1
fi
set -a; source .env; set +a
prefix="IG_$(echo "${TRADEBENCH_IG_ENV:-}" | tr '[:lower:]' '[:upper:]')"
for var in TRADEBENCH_IG_ENV TRADEBENCH_INSTANCE TRADEBENCH_CAPTURE_DIR TRADEBENCH_EPICS \
           ${prefix}_IDENTIFIER ${prefix}_PASSWORD ${prefix}_API_KEY ${prefix}_ACCOUNT_ID; do
  [[ -n "${!var:-}" ]] || { echo "Missing $var in .env" >&2; exit 1; }
done
extra=()
[[ "${TRADEBENCH_DEBUG:-}" == "1" ]] && extra+=(--debug-jvm)
exec ./gradlew --console=plain -q :market-data-service:run "${extra[@]}"
