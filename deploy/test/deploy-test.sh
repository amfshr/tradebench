#!/usr/bin/env bash
# deploy.sh's record keeping (E1-T7), driven against stubbed docker, git, install and systemctl in a temporary
# tree: v0.2.0 runs, v0.1.0 is the rollback target, v0.3.0 is deployed. A success records v0.3.0 and makes v0.2.0
# the target; a failed start leaves the record as it was and puts the checkout and deploy/.env back at v0.2.0;
# redeploying v0.2.0 keeps v0.1.0 the target.
# Run from the repository root: deploy/test/deploy-test.sh
set -u
deploy() { # <tag to deploy> <tag whose start fails, or none> → one line of the state afterwards
    local tag=$1 failing=$2 T; T=$(mktemp -d)
    mkdir -p "$T/opt/tradebench/deploy" "$T/etc/tradebench" "$T/bin"
    sed -e "s#/opt/tradebench#$T/opt/tradebench#g" -e "s#/etc/tradebench#$T/etc/tradebench#g" deploy/deploy.sh > "$T/deploy.sh"
    echo v0.2.0 > "$T/etc/tradebench/release"; echo v0.1.0 > "$T/etc/tradebench/release.previous"
    echo TRADEBENCH_TAG=v0.2.0 > "$T/opt/tradebench/deploy/.env"; echo v0.2.0 > "$T/checkout"
    printf '#!/usr/bin/env bash\nif [ "$1" = compose ] && grep -qx "TRADEBENCH_TAG=%s" "%s"; then exit 1; fi\n' \
        "$failing" "$T/opt/tradebench/deploy/.env" > "$T/bin/docker"
    printf '#!/usr/bin/env bash\nfor a in "$@"; do case "$a" in v*) echo "$a" > "%s";; esac; done\n' "$T/checkout" > "$T/bin/git"
    printf '#!/bin/sh\n' > "$T/bin/install"; printf '#!/bin/sh\n' > "$T/bin/systemctl"; chmod +x "$T/bin/"*
    PATH="$T/bin:$PATH" bash "$T/deploy.sh" "$tag" > /dev/null 2>&1
    echo "exit=$? release=$(cat "$T/etc/tradebench/release") previous=$(cat "$T/etc/tradebench/release.previous")" \
        "$(cat "$T/opt/tradebench/deploy/.env") checkout=$(cat "$T/checkout")"
    rm -rf "$T"
}
fail=0
expect() { [ "$2" = "$3" ] && echo "ok   $1" || { echo "FAIL $1: got '$2', want '$3'"; fail=1; }; }
expect "a deploy records its tag and keeps the one it replaced" "$(deploy v0.3.0 none)" \
    "exit=0 release=v0.3.0 previous=v0.2.0 TRADEBENCH_TAG=v0.3.0 checkout=v0.3.0"
expect "a failed start goes back to the running tag, the record untouched" "$(deploy v0.3.0 v0.3.0)" \
    "exit=1 release=v0.2.0 previous=v0.1.0 TRADEBENCH_TAG=v0.2.0 checkout=v0.2.0"
expect "redeploying the running tag keeps the rollback target" "$(deploy v0.2.0 none)" \
    "exit=0 release=v0.2.0 previous=v0.1.0 TRADEBENCH_TAG=v0.2.0 checkout=v0.2.0"
exit $fail
