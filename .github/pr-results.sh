#!/usr/bin/env bash
# Attaches a CI job's results to its pull request's ci-pr-<number>
# pre-release (ci.yml), as NAME.zip with a COMMIT file saying which commit
# they're from. Artifacts download from a storage host that changes every
# run, which a sandboxed Claude Code session can't allow; release assets
# come from one host (release-assets.githubusercontent.com), so
# ./ci-results.sh can fetch them. android-preview-cleanup.yml deletes the
# release when the pull request closes.
#
#   .github/pr-results.sh NAME PATH...      (needs GH_TOKEN, PR and SHA)
set -euo pipefail
name=$1
shift
paths=()
for p in "$@"; do [ -e "$p" ] && paths+=("$p"); done
if [ ${#paths[@]} -eq 0 ]; then
  echo "nothing to attach as $name"
  exit 0
fi

zip="$RUNNER_TEMP/$name.zip"
rm -f "$zip"
zip -qr "$zip" "${paths[@]}"
echo "$SHA" > "$RUNNER_TEMP/COMMIT"
(cd "$RUNNER_TEMP" && zip -q "$zip" COMMIT)

tag="ci-pr-$PR"
if ! gh release view "$tag" >/dev/null 2>&1; then
  # The other job may make it at the same moment; the upload below tells.
  gh release create "$tag" --prerelease --latest=false --target "$SHA" \
    --title "CI results for #$PR" \
    --notes "Screenshots and test results from CI for #$PR, replaced on every push (each zip's COMMIT file says which commit). Fetch them with ./ci-results.sh $PR. Deleted when the pull request closes." \
    || true
fi
gh release upload "$tag" "$zip" --clobber
