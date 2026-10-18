#!/usr/bin/env bash
# Deploy a release tag (E1-T7, D34 R6). In order, so a failure part-way leaves the running release and its
# rollback target as they were: pull the image GitHub Actions published for the tag (fails before anything
# changes if it is not published yet); check the repository's deploy files out at the tag; refresh the systemd
# units; restart the stack at the tag; and only once it is up, record the tag — the one it replaced becomes
# the rollback target. A failure after the pull goes back to the running tag (restore, below). The script runs
# as the checkout held it before the deploy: a change to it takes effect on the deploy after the one shipping it. The tag is also written to deploy/.env (gitignored), which compose reads by itself, so
# every later compose command — the nightly backup's, the runbook's — resolves the same image.
#   Usage: deploy/deploy.sh v0.1.0
set -euo pipefail
tag="${1:?usage: deploy.sh <tag, e.g. v0.1.0>}"
[[ "$tag" =~ ^v[0-9]+\.[0-9]+\.[0-9]+$ ]] || { echo "deploy: '$tag' is not a release tag (vX.Y.Z)" >&2; exit 2; }
repo=/opt/tradebench
image="ghcr.io/amfshr/tradebench-market-data:$tag"
running=$(cat /etc/tradebench/release 2>/dev/null || true)

docker pull --quiet "$image" > /dev/null || { echo "deploy: $image is not pullable — is the release build finished?" >&2; exit 1; }

# From here a failure puts the checkout and deploy/.env back at the running tag and starts it again, so the box
# is never left split — the record naming one tag while the next compose command would start another.
restore() {
    trap - ERR
    [ -n "$running" ] || { echo "deploy: $tag failed and no earlier release is recorded to go back to" >&2; exit 1; }
    git -C "$repo" -c advice.detachedHead=false checkout --quiet "$running" || true
    echo "TRADEBENCH_TAG=$running" > "$repo/deploy/.env"
    (cd "$repo/deploy" && docker compose -f compose.prod.yaml up -d --remove-orphans) || true
    echo "deploy: $tag failed — the box is back on $running (check it: §2 of the runbook)" >&2
    exit 1
}
trap restore ERR
git -C "$repo" fetch --quiet --tags origin
git -C "$repo" -c advice.detachedHead=false checkout --quiet "$tag"
install -m 0644 "$repo"/deploy/systemd/tradebench-backup.{service,timer} /etc/systemd/system/
systemctl daemon-reload

cd "$repo/deploy"
echo "TRADEBENCH_TAG=$tag" > .env
docker compose -f compose.prod.yaml up -d --remove-orphans

if [ -n "$running" ] && [ "$running" != "$tag" ]; then
    echo "$running" > /etc/tradebench/release.previous
fi
echo "$tag" > /etc/tradebench/release
echo "deploy: $tag running${running:+ (replacing $running)} — watch it: docker compose -f $repo/deploy/compose.prod.yaml logs -f market-data"
