#!/bin/sh
# Pushes the current branch and waits for the CI run of exactly this commit.
# Exits non-zero when the run fails or never appears.
set -eu
repo=sharkusmanch/immich-wallpaper
cd "$(dirname "$0")/.."
git push -u origin HEAD
sha=$(git rev-parse HEAD)
id=""
for _ in $(seq 1 30); do
  id=$(gh run list --repo "$repo" --commit "$sha" --limit 1 --json databaseId -q '.[0].databaseId')
  [ -n "$id" ] && break
  sleep 5
done
[ -n "$id" ] || { echo "no CI run appeared for $sha" >&2; exit 1; }
gh run watch "$id" --repo "$repo" --exit-status
