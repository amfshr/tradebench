#!/usr/bin/env bash
# One-shot capture run (T3 DoD test). Reads secrets from .env at repo root — never committed.
# TRADEBENCH_DEBUG=1 suspends the JVM on port 5005 for an IntelliJ Remote JVM Debug attach.
set -euo pipefail
cd "$(dirname "$0")/.."
if [[ ! -f .env ]]; then
  echo "No .env at repo root — copy .env.example, fill it in (single-quote the password)." >&2
  exit 1
fi
set -a; source .env; set +a
prefix="IG_$(echo "${TRADEBENCH_IG_ENV:-}" | tr '[:lower:]' '[:upper:]')"
case "${TRADEBENCH_SINK:-}" in
  jsonl) sink_vars="TRADEBENCH_CAPTURE_DIR" ;;
  db)    sink_vars="TRADEBENCH_DB_URL TRADEBENCH_DB_USER TRADEBENCH_DB_PASSWORD" ;;
  *)     echo "TRADEBENCH_SINK must be 'jsonl' or 'db' in .env" >&2; exit 1 ;;
esac
for var in TRADEBENCH_IG_ENV TRADEBENCH_SINK TRADEBENCH_INSTANCE TRADEBENCH_EPICS $sink_vars \
           ${prefix}_IDENTIFIER ${prefix}_PASSWORD ${prefix}_API_KEY ${prefix}_ACCOUNT_ID; do
  [[ -n "${!var:-}" ]] || { echo "Missing $var in .env" >&2; exit 1; }
done
debug_flag=""
[[ "${TRADEBENCH_DEBUG:-}" == "1" ]] && debug_flag="--debug-jvm"
# shellcheck disable=SC2086 — debug_flag is empty or a single option, splitting is intended
exec ./gradlew --console=plain -q :market-data-service:run $debug_flag
