#!/usr/bin/env bash
# G1's scanners proven red (E0-T4): every rule in .gitleaks.toml catches its planted violations, an innocent file
# passes, and the env value check and the pre-commit hook refuse what they exist to refuse. The violations are made
# here at run time, from random values, so none of them is ever in the repository for the real scan to trip on.
# Run from the repository root: scripts/guardrails/guardrails-test.sh (needs gitleaks, openssl, python3, git)
set -u
root=$(pwd)
T=$(mktemp -d)
trap 'rm -rf "$T"' EXIT
failures=0 n=0
fail() { echo "FAIL: $*"; failures=$((failures + 1)); }

caught() { # <rule id> <relative path> <content> — gitleaks must report that rule for the file
    local rule=$1 path=$2 dir="$T/scan-$((n += 1))"
    mkdir -p "$(dirname "$dir/$path")"
    printf '%s\n' "$3" > "$dir/$path"
    gitleaks dir "$dir" --config "$root/.gitleaks.toml" --no-banner --log-level error \
        --report-format json --report-path "$dir.json" > /dev/null 2>&1
    python3 -c "import json,sys; sys.exit(0 if '$rule' in {f['RuleID'] for f in json.load(open('$dir.json'))} else 1)" \
        || fail "$rule did not catch its planted violation in $path"
}

hex() { openssl rand -hex "$1"; }
b64() { openssl rand -base64 "$1" | tr -d '\n=' | tr -c 'A-Za-z0-9+/' 'A'; }
uuid() { python3 -c 'import uuid; print(uuid.uuid4())'; }
marker() { printf '%s: %s' tradebench private; } # never written whole here: the real scan would flag this file

caught ig-api-key config.properties "IG_DEMO_API_KEY=$(hex 20)"
caught ig-api-key request.json "{\"X-IG-API-KEY\": \"$(hex 20)\"}"
caught ig-session-token request.http "CST: $(hex 24)"
caught ig-session-token stream.log "password=CST-$(hex 24)|XST-$(hex 24)"
caught s3-access-key-id deploy.env "TRADEBENCH_ARCHIVE_ACCESS_KEY_ID=$(hex 16)"
caught s3-access-key-id rclone.env "RCLONE_CONFIG_R2_ACCESS_KEY_ID=$(hex 16)"
caught s3-secret-access-key deploy.env "TRADEBENCH_ARCHIVE_SECRET_ACCESS_KEY=$(hex 32)"
caught healthchecks-ping-url ping.env "TRADEBENCH_PING_URL=https://hc-ping.com/$(uuid)"
caught healthchecks-ping-url ping.md "https://hc-ping.com/$(uuid | tr 'a-z' 'A-Z')"
caught otlp-basic-auth otel.env "OTEL_EXPORTER_OTLP_HEADERS=Authorization=Basic%20$(b64 48)"
caught otlp-basic-auth curl.sh "curl -H \"Authorization: Basic $(b64 48)\" https://example.invalid"
caught grafana-cloud-api-token grafana.env "GRAFANA_TOKEN=glc_$(b64 48)"
caught private-key id.pem "$(openssl genpkey -algorithm ed25519 2> /dev/null)"
caught strategy-private-path strategies/private/pattern.txt "an ordinary line"
caught strategy-private-marker pattern.dsl "$(printf '# %s\nentry when close > open' "$(marker)")"
caught strategy-private-marker windows.dsl "$(printf '# %s\r\nentry when close > open\r' "$(marker)")"
caught strategy-private-marker pattern.html "$(printf '<!-- %s -->\n<p>rules</p>' "$(marker)")"

# an innocent file: the marker named inside a sentence, the ping host with no key, a 40-character word
mkdir -p "$T/innocent"
printf 'Real strategy files start with the line `%s`.\nThe ping goes to hc-ping.com.\napikey: %s\n' \
    "$(marker)" "$(printf 'x%.0s' {1..40})" > "$T/innocent/notes.md"
gitleaks dir "$T/innocent" --config "$root/.gitleaks.toml" --no-banner --log-level error > /dev/null 2>&1 \
    || fail "an innocent file was flagged"

# the env value check, in a scratch repository with its own copy of the guardrails
repo="$T/repo"; mkdir -p "$repo/deploy/env" "$repo/scripts/guardrails" "$repo/.githooks"
secret="acct-$(hex 6)" commented="user-$(hex 6)" bare="inst-$(hex 6)"
printf 'IG_DEMO_ACCOUNT_ID=%s\nTRADEBENCH_USER=platform\nRCLONE_CONFIG_R2RO_PROVIDER=Cloudflare\n' "$secret" > "$T/personal.env"
printf "IG_DEMO_IDENTIFIER='%s' # the demo login\nTRADEBENCH_INSTANCE=%s # this machine\n" "$commented" "$bare" >> "$T/personal.env"
printf 'TRADEBENCH_USER=platform\n' > "$repo/.env.example"
printf 'RCLONE_CONFIG_R2_PROVIDER=Cloudflare\n' > "$repo/deploy/env/backup.env.example"
cp "$root/scripts/guardrails/env-value-check.py" "$repo/scripts/guardrails/"
cp "$root/.githooks/pre-commit" "$repo/.githooks/"; cp "$root/.gitleaks.toml" "$repo/"
(cd "$repo" && git init -q && git config user.email t@example.invalid && git config user.name t \
    && git add -A && git commit -qm init)
check() { (cd "$repo" && TRADEBENCH_ENV_FILES="$T/personal.env" python3 scripts/guardrails/env-value-check.py "$@") 2>&1; }
staged() { (cd "$repo" && git reset -q && git checkout -q -- . && git clean -qfd && printf "$2" "$3" >> "$1" && git add "$1"); }

staged leak.txt 'the account is %s\n' "$secret"; out=$(check --staged); status=$?
[ "$status" = 1 ] || fail "a staged env value was not refused (exit $status)"
grep -q "IG_DEMO_ACCOUNT_ID (from personal.env) appears in leak.txt" <<< "$out" || fail "the refusal does not name the variable: $out"
for v in "$secret" "$commented" "$bare"; do grep -qF "$v" <<< "$out" && fail "the refusal printed an env value"; done

refused() { # <variable> <what> — the staged change must be refused, naming the variable
    out=$(check --staged); status=$?
    [ "$status" = 1 ] && grep -q "^env-value-check: $1 (from" <<< "$out" || fail "$2 (exit $status): $out"
}
staged leak.txt 'the login is %s\n' "$commented"; refused IG_DEMO_IDENTIFIER "a quoted value with a trailing comment was not refused"
staged leak.txt 'the instance is %s\n' "$bare"; refused TRADEBENCH_INSTANCE "a bare value with a trailing comment was not refused"

staged deploy/env/new.env.example 'IG_DEMO_ACCOUNT_ID=%s\n' "$secret"; refused IG_DEMO_ACCOUNT_ID "a value staged into a new example made itself public"
staged .env.example 'IG_DEMO_ACCOUNT_ID=%s\n' "$secret"; refused IG_DEMO_ACCOUNT_ID "a value staged into the committed example made itself public"

live="live-$(hex 6)"
staged leak.txt 'the live account is %s\n' "$live"; printf 'IG_LIVE_ACCOUNT_ID=%s\n' "$live" > "$repo/.env.live"
(cd "$repo" && python3 scripts/guardrails/env-value-check.py --staged > /dev/null 2>&1) \
    && fail "a value from another root .env* file (.env.live) was not refused"

staged ok.txt 'owned by %s, stored at Cloudflare\n' platform
check --staged > /dev/null || fail "a value a committed example publishes was refused"

staged leak.txt 'the account is %s\n' "$secret"
(cd "$repo" && git commit -qm "the account is $secret" && git rm -q leak.txt && git commit -qm "the leak redacted")
redaction=$(cd "$repo" && git rev-parse --short=7 HEAD)
hist=$(check --history)
grep -q "IG_DEMO_ACCOUNT_ID (from personal.env) appears in .* (message)" <<< "$hist" \
    || fail "the history sweep missed a value in a commit message"
grep -q "appears in $redaction" <<< "$hist" && fail "the history sweep blamed the redaction commit: $hist"

# the pre-commit hook: refuses a leak, refuses without gitleaks, lets a clean commit through
hook() { (cd "$repo" && TRADEBENCH_ENV_FILES="$T/personal.env" PATH="$1" git -c core.hooksPath=.githooks commit -qm t) > /dev/null 2>&1; }
nogitleaks="$T/bin"; mkdir -p "$nogitleaks"
for tool in bash git python3 env; do ln -s "$(command -v "$tool")" "$nogitleaks/$tool"; done
staged leak.txt 'the account is %s\n' "$secret"; hook "$PATH" && fail "the hook let a staged env value through"
staged ok.txt 'nothing personal %s\n' here; hook "$nogitleaks" && fail "the hook committed without gitleaks"
staged ok.txt 'nothing personal %s\n' here; hook "$PATH" || fail "the hook refused a clean commit"

[ "$failures" = 0 ] && echo "guardrails: every planted violation caught, the innocent ones passed" || exit 1
