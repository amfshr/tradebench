#!/usr/bin/env bash
# G1's scanners proven red (E0-T4): every rule in .gitleaks.toml, and the env value check, each catch a planted
# violation, and an innocent file passes. The violations are made here at run time, from random values, so none
# of them is ever in the repository for the real scan to trip on.
# Run from the repository root: scripts/guardrails/guardrails-test.sh (needs gitleaks, openssl, python3)
set -u
root=$(pwd)
T=$(mktemp -d)
trap 'rm -rf "$T"' EXIT
failures=0
fail() { echo "FAIL: $*"; failures=$((failures + 1)); }

caught() { # <rule id> <relative path> <content> — gitleaks must report exactly that rule for the file
    local rule=$1 path=$2 dir="$T/scan-$1"
    mkdir -p "$(dirname "$dir/$path")"
    printf '%s\n' "$3" > "$dir/$path"
    gitleaks dir "$dir" --config "$root/.gitleaks.toml" --no-banner --log-level error \
        --report-format json --report-path "$T/$rule.json" > /dev/null 2>&1
    python3 -c "import json,sys; sys.exit(0 if '$rule' in {f['RuleID'] for f in json.load(open('$T/$rule.json'))} else 1)" \
        || fail "$rule did not catch its planted violation"
}

hex() { openssl rand -hex "$1"; }
b64() { openssl rand -base64 "$1" | tr -d '\n=' | tr -c 'A-Za-z0-9+/' 'A'; }

caught ig-api-key config.properties "IG_DEMO_API_KEY=$(hex 20)"
caught ig-session-token request.http "CST: $(hex 24)"
caught s3-access-key-id deploy.env "TRADEBENCH_ARCHIVE_ACCESS_KEY_ID=$(hex 16)"
caught s3-secret-access-key deploy.env "TRADEBENCH_ARCHIVE_SECRET_ACCESS_KEY=$(hex 32)"
caught healthchecks-ping-url ping.env "TRADEBENCH_PING_URL=https://hc-ping.com/$(uuidgen | tr 'A-Z' 'a-z')"
caught otlp-basic-auth otel.env "OTEL_EXPORTER_OTLP_HEADERS=Authorization=Basic%20$(b64 48)"
caught grafana-cloud-api-token grafana.env "GRAFANA_TOKEN=glc_$(b64 48)"
caught private-key id.pem "$(openssl genpkey -algorithm ed25519 2> /dev/null)"
caught strategy-private-path strategies/private/pattern.txt "an ordinary line"
caught strategy-private-marker pattern.dsl "$(printf '# %s: %s\nentry when close > open' tradebench private)"

# an innocent file: the marker named inside a sentence, the ping host with no key, a 40-character word
mkdir -p "$T/innocent"
printf 'Real strategy files start with the line `%s: %s`.\nThe ping goes to hc-ping.com.\napikey: %s\n' \
    tradebench private "$(printf 'x%.0s' {1..40})" > "$T/innocent/notes.md"
gitleaks dir "$T/innocent" --config "$root/.gitleaks.toml" --no-banner --log-level error > /dev/null 2>&1 \
    || fail "an innocent file was flagged"

# the env value check: a personal value staged is refused by name, never shown; a public one passes
repo="$T/repo"; mkdir -p "$repo"
secret="acct-$(hex 6)"
printf 'IG_DEMO_ACCOUNT_ID=%s\nTRADEBENCH_USER=platform\n' "$secret" > "$T/personal.env"
printf 'TRADEBENCH_USER=platform\n' > "$repo/.env.example"
(cd "$repo" && git init -q && git config user.email t@example.invalid && git config user.name t \
    && git add .env.example && git commit -qm init)
check() { (cd "$repo" && TRADEBENCH_ENV_FILES="$T/personal.env" python3 "$root/scripts/guardrails/env-value-check.py" "$@") 2>&1; }

printf 'the account is %s\n' "$secret" > "$repo/leak.txt"; (cd "$repo" && git add leak.txt)
out=$(check --staged); status=$?
[ "$status" = 1 ] || fail "a staged env value was not refused (exit $status)"
grep -q "IG_DEMO_ACCOUNT_ID (from personal.env) appears in leak.txt" <<< "$out" || fail "the refusal does not name the variable: $out"
grep -qF "$secret" <<< "$out" && fail "the refusal printed the value itself"

(cd "$repo" && git rm -q --cached leak.txt && rm leak.txt && printf 'owned by platform\n' > ok.txt && git add ok.txt)
check --staged > /dev/null || fail "a value .env.example publishes was refused"

(cd "$repo" && git commit -qm "the account is $secret")
check --history | grep -q "IG_DEMO_ACCOUNT_ID (from personal.env) appears in .* (message)" \
    || fail "the history sweep missed a value in a commit message"

[ "$failures" = 0 ] && echo "guardrails: every planted violation caught, the innocent ones passed" || exit 1
