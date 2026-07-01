#!/usr/bin/env bash
set -euo pipefail

repo="${1:-$(pwd)}"
cd "$repo"

echo "== checkout =="
git branch --show-current
git status --short

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
