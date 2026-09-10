#!/usr/bin/env bash
# Single source of truth for the Android versionCode.
#
# Why not ${{ github.run_number }}: every workflow keeps its own counter, so the same commit
# produced 8 (verification), 50 (ci) and ~46 (release). A versionCode has to be a monotonic
# property of the commit itself, hence `git rev-list --count HEAD`.
#
# Callers must use `fetch-depth: 0` and, for pull_request events, check out the real head SHA
# (github.event.pull_request.head.sha) rather than the temporary merge ref.
set -euo pipefail

MIN_VERSION_CODE=185
MAX_VERSION_CODE=2100000000

if ! git rev-parse --git-dir >/dev/null 2>&1; then
  echo "version-code: not inside a git repository" >&2
  exit 1
fi

count="$(git rev-list --count HEAD)"
case "$count" in
  ''|*[!0-9]*)
    echo "version-code: git rev-list --count HEAD returned '$count'" >&2
    exit 1
    ;;
esac

if [ "$count" -lt "$MIN_VERSION_CODE" ]; then
  echo "version-code: $count is below the floor of $MIN_VERSION_CODE (shallow clone?)" >&2
  exit 1
fi
if [ "$count" -gt "$MAX_VERSION_CODE" ]; then
  echo "version-code: $count exceeds the Android limit of $MAX_VERSION_CODE" >&2
  exit 1
fi

echo "$count"
