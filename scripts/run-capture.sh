#!/usr/bin/env bash
# One-shot capture run (T3 DoD test). Reads secrets from .env at repo root — never committed.
set -euo pipefail
cd "$(dirname "$0")/.."
if [[ ! -f .env ]]; then
  echo "No .env at repo root — copy .env.example, fill it in (single-quote the password)." >&2
  exit 1
fi
set -a; source .env; set +a
for var in TRADEBENCH_INSTANCE TRADEBENCH_CAPTURE_DIR TRADEBENCH_EPICS \
           IG_DEMO_IDENTIFIER IG_DEMO_PASSWORD IG_DEMO_API_KEY IG_DEMO_ACCOUNT_ID; do
  [[ -n "${!var:-}" ]] || { echo "Missing $var in .env" >&2; exit 1; }
done
exec ./gradlew --console=plain -q :market-data-service:run
