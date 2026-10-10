#!/usr/bin/env bash
# Export one replay window as a scenario fixture (E1-T12): database → JSONL, one command.
# The format and the library: market-data-service/src/test/resources/replays/README.md.
#
#   scripts/export-replay.sh prototype|tradebench \
#     --anchor '2026-08-04 12:38:08.916+00' --before 360 --until 460 \
#     --name '2026-08-04 silent while connected' \
#     --recorded '<what the source recorded that the expectations anchor on>' \
#     --modelled '<what the harness supplies because the source did not record it>' \
#     --out market-data-service/src/test/resources/replays/<fixture>.jsonl
#
# prototype  — the prototype's igtrader_demo in its Docker container: set PROTOTYPE_PG_CONTAINER
#              (the container name; psql runs inside it as its own POSTGRES_USER). G1: the exporter
#              reads the per-market tick tables only — never strategy_events or the OMS tables.
# tradebench — Tradebench's own market_data: set TRADEBENCH_PSQL_URL (postgres://user:pass@host/db),
#              and optionally TRADEBENCH_USER_NAME / TRADEBENCH_SOURCE_NAME (default-user, ig-stream-demo).
# Nothing machine-specific is defaulted here (config discipline); the variables come from the shell.
set -euo pipefail
cd "$(dirname "$0")/.."
replays=market-data-service/src/test/resources/replays

source_kind="${1:-}"; shift || true
anchor=""; before=""; until_secs=""; name=""; recorded=""; modelled=""; out=""
while [[ $# -gt 0 ]]; do
  case "$1" in
    --anchor)   anchor="$2"; shift 2 ;;
    --before)   before="$2"; shift 2 ;;
    --until)    until_secs="$2"; shift 2 ;;
    --name)     name="$2"; shift 2 ;;
    --recorded) recorded="$2"; shift 2 ;;
    --modelled) modelled="$2"; shift 2 ;;
    --out)      out="$2"; shift 2 ;;
    *) echo "unknown argument: $1" >&2; exit 2 ;;
  esac
done
for v in source_kind anchor before until_secs name recorded modelled out; do
  [[ -n "${!v}" ]] || { echo "missing --${v//_secs/} (or the source kind)" >&2; exit 2; }
done
command -v jq >/dev/null || { echo "jq is needed to compact the lines" >&2; exit 1; }

# psql variables travel as environment variables and are read with \getenv, so no value is ever
# quoted into a shell command line.
preamble=$'\\getenv anchor R_ANCHOR\n\\getenv before_secs R_BEFORE\n\\getenv until_secs R_UNTIL\n\\getenv name R_NAME\n\\getenv recorded R_RECORDED\n\\getenv modelled R_MODELLED\n'
case "$source_kind" in
  prototype)
    [[ -n "${PROTOTYPE_PG_CONTAINER:-}" ]] || { echo "set PROTOTYPE_PG_CONTAINER to the prototype's Postgres container name" >&2; exit 1; }
    { printf '%s' "$preamble"; cat "$replays/export-prototype.sql"; } \
      | docker exec -i \
          -e R_ANCHOR="$anchor" -e R_BEFORE="$before" -e R_UNTIL="$until_secs" -e R_NAME="$name" \
          -e R_RECORDED="$recorded" -e R_MODELLED="$modelled" \
          "$PROTOTYPE_PG_CONTAINER" sh -c 'psql -U "$POSTGRES_USER" -d igtrader_demo -At -v ON_ERROR_STOP=1 -f -' \
      | jq -c . > "$out"
    ;;
  tradebench)
    [[ -n "${TRADEBENCH_PSQL_URL:-}" ]] || { echo "set TRADEBENCH_PSQL_URL (postgres://user:pass@host:port/db)" >&2; exit 1; }
    preamble+=$'\\getenv user_name R_USER_NAME\n\\getenv source_name R_SOURCE_NAME\n'
    { printf '%s' "$preamble"; cat "$replays/export-tradebench.sql"; } \
      | R_ANCHOR="$anchor" R_BEFORE="$before" R_UNTIL="$until_secs" R_NAME="$name" \
        R_RECORDED="$recorded" R_MODELLED="$modelled" \
        R_USER_NAME="${TRADEBENCH_USER_NAME:-default-user}" R_SOURCE_NAME="${TRADEBENCH_SOURCE_NAME:-ig-stream-demo}" \
        psql "$TRADEBENCH_PSQL_URL" -At -v ON_ERROR_STOP=1 -f - \
      | jq -c . > "$out"
    ;;
  *) echo "the source kind must be 'prototype' or 'tradebench'" >&2; exit 2 ;;
esac
lines=$(wc -l < "$out" | tr -d ' ')
echo "$out: $lines lines, $(du -h "$out" | cut -f1 | tr -d ' ') — header: $(head -c 400 "$out")"
