#!/usr/bin/env bash
# Destroy the benchmark infrastructure: `down -v` of project ftsm-p3-bench only (its volumes hold the
# ftsm_bench_* databases), then remove $BENCH_TMP. Refuses while a backend from run_config.sh runs.
set -Eeuo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/common.sh"
if [[ -f $BENCH_TMP/backend.pid ]] && kill -0 "$(cat "$BENCH_TMP/backend.pid")" 2>/dev/null; then
  echo "infra_down: a bench backend (pid $(cat "$BENCH_TMP/backend.pid")) is still running" >&2; exit 2
fi
[[ -f $BENCH_ENV ]] || { echo "infra_down: $BENCH_ENV missing, nothing to tear down" >&2; exit 0; }
bench_dc down -v
case $BENCH_TMP in
  /tmp/ftsm-p3*|/private/tmp/ftsm-p3*) rm -rf "$BENCH_TMP"; git -C "$BENCH_REPO" worktree prune ;;
  *) echo "infra_down: leaving BENCH_TMP=$BENCH_TMP in place (not under /tmp/ftsm-p3*)" >&2 ;;
esac
echo "infra_down: $BENCH_PROJECT removed" >&2
