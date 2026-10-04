# shellcheck shell=bash
# Shared settings for loadtest/bench/*.sh (docs/phases/P3.md §7). Sourced, not executed.
#   BENCH_TMP  scratch dir (baseline jar cache, backend logs, bench.env); default /tmp/ftsm-p3-bench
#   BENCH_ENV  compose env-file of project ftsm-p3-bench; created on first use
BENCH_REPO=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
BENCH_PROJECT=ftsm-p3-bench
BENCH_TMP=${BENCH_TMP:-/tmp/ftsm-p3-bench}
BENCH_ENV=${BENCH_ENV:-$BENCH_TMP/bench.env}
export BENCH_TMP BENCH_ENV
# shellcheck source=../../scripts/db/lib.sh
source "$BENCH_REPO/scripts/db/lib.sh"

bench_env_init() {
  mkdir -p "$BENCH_TMP"
  [[ -f $BENCH_ENV ]] && return 0
  # Ports per CHANGE_SPEC §0.11; the JWT secret is random per bench directory and never committed.
  cat > "$BENCH_ENV" <<ENV
DB_PASSWORD=bench
JWT_SECRET=$(openssl rand -hex 32)
MYSQL_HOST_PORT=33306
REDIS_HOST_PORT=36379
KAFKA_HOST_PORT=39092
BACKEND_HOST_PORT=38080
FRONTEND_HOST_PORT=38081
ENV
}

bench_dc() { docker compose -f "$BENCH_REPO/docker-compose.yml" -p "$BENCH_PROJECT" --env-file "$BENCH_ENV" "$@"; }

bench_healthy() {   # bench_healthy SERVICE TIMEOUT
  local cid; cid=$(bench_dc ps -q "$1")
  [[ -n $cid ]] || { echo "bench: no container for $1" >&2; return 1; }
  wait_cmd "$2" "$1 healthy" -- sh -c '[ "$(docker inspect --format "{{.State.Health.Status}}" "$0")" = healthy ]' "$cid"
}

bench_target_mysql() { echo "compose:$BENCH_PROJECT:$BENCH_ENV:$1"; }
bench_target_redis() { echo "compose:$BENCH_PROJECT:$BENCH_ENV"; }
