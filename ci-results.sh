#!/usr/bin/env bash
# Downloads a pull request's CI results: the browser tests' screenshots and
# failures (e2e-results) and the Android screens (native-screenshots), which
# ci.yml attaches to the ci-pr-<number> pre-release (.github/pr-results.sh).
# Unlike `gh run download`, this works from a Claude Code cloud session.
#
#   ./ci-results.sh <pull request number> [folder]   (default ci-results/pr-<number>)
set -euo pipefail
pr=${1:?usage: ./ci-results.sh <pull request number> [folder]}
out=${2:-ci-results/pr-$pr}
# owner/name from the remote: git@github.com:o/n.git, https://github.com/o/n or a proxy's .../git/o/n
repo=$(git remote get-url origin | sed -E 's#^.*(github\.com[:/]|/git/)##; s#\.git$##')

assets=$(gh api "repos/$repo/releases/tags/ci-pr-$pr" -q '.assets[] | "\(.id) \(.name)"') || {
  echo "no CI results for #$pr yet (they appear once a job of its CI run finishes)" >&2
  exit 1
}
rm -rf "$out"
mkdir -p "$out"
head=$(git rev-parse HEAD)
while read -r id name; do
  [ -n "$id" ] || continue
  dir="$out/${name%.zip}"
  gh api -H 'Accept: application/octet-stream' "repos/$repo/releases/assets/$id" > "$out/$name"
  unzip -q -o "$out/$name" -d "$dir"
  rm "$out/$name"
  from=$(cat "$dir/COMMIT" 2>/dev/null || echo unknown)
  note=""
  [ "$from" = "$head" ] || note=" (not this checkout's HEAD, ${head:0:7}: CI may still be running)"
  echo "$dir: from ${from:0:7}$note"
done <<< "$assets"
