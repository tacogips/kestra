#!/usr/bin/env bash
set -euo pipefail

repo="${1:-$(pwd)}"
cd "$repo"

echo "== checkout =="
git branch --show-current
git status --short

echo
echo "== fork diff baseline =="
git rev-parse --verify main-freeze-before-fork >/dev/null 2>&1 && {
  echo "baseline: main-freeze-before-fork ($(git rev-parse --short main-freeze-before-fork))"
  echo
  git diff --stat main-freeze-before-fork..main
  echo
  git diff --name-status main-freeze-before-fork..main
} || {
  echo "main-freeze-before-fork branch is not available in this checkout"
}

echo
echo "== tacogips worker routing commits =="
git log --all \
  --author='tacogips' \
  --grep='worker\|routing\|group\|queue' \
  --regexp-ignore-case \
  --date=short \
  --pretty=format:'%h %ad %an <%ae> %s' \
  --max-count=40

echo
echo
echo "== feature commit stat =="
git show --stat --oneline --no-renames f64927b52 -- || true

echo
echo "== main source anchors =="
rg -n "workerGroupId|WorkerGroups|WorkerRoutingConfiguration|ConfiguredWorkerQueue|workerSelector|QueueSubscription" \
  core worker worker-controller executor docs README.md
