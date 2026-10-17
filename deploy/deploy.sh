#!/usr/bin/env bash
# Deploy a release tag (E1-T7, D34 R6): check out the repository's deploy files at that tag, pull the image
# GitHub Actions published for it, and restart the stack. The tag deployed before is kept, so
# `deploy/rollback.sh` re-runs this with it.   Usage: deploy/deploy.sh v0.1.0
set -euo pipefail
tag="${1:?usage: deploy.sh <tag, e.g. v0.1.0>}"
[[ "$tag" =~ ^v[0-9]+\.[0-9]+\.[0-9]+$ ]] || { echo "deploy: '$tag' is not a release tag (vX.Y.Z)" >&2; exit 2; }
repo=/opt/tradebench
git -C "$repo" fetch --quiet --tags origin
git -C "$repo" -c advice.detachedHead=false checkout --quiet "$tag"
cd "$repo/deploy"
[ -f /etc/tradebench/release ] && cp /etc/tradebench/release /etc/tradebench/release.previous
export TRADEBENCH_TAG="$tag"
docker compose -f compose.prod.yaml pull --quiet market-data
docker compose -f compose.prod.yaml up -d --remove-orphans
echo "$tag" > /etc/tradebench/release
echo "deploy: $tag running — watch it with: docker compose -f $repo/deploy/compose.prod.yaml logs -f market-data"
