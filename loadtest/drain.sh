#!/usr/bin/env bash
# Wait until the backend has consumed every seckill message for this run.
# Usage: loadtest/drain.sh <stream|outbox|none> [--redis temp] [--timeout 120]
#   stream: XPENDING count of seckill:orders / seckill-order-consumers is 0 AND the
#           group's lag (XINFO GROUPS) is 0.
#   outbox: added in P2 (exit 2 until then).  none: no-op.
# Exit codes: 0 drained, 2 usage/unsupported, 3 timeout.
set -Eeuo pipefail

REPO=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
# shellcheck source=../scripts/db/lib.sh
source "$REPO/scripts/db/lib.sh"

STREAM=seckill:orders
GROUP=seckill-order-consumers

mode=${1:-}; shift || true
redis_target=temp
timeout_s=120
while (( $# )); do
  case $1 in
    --redis) redis_target=$2; shift 2 ;;
    --timeout) timeout_s=$2; shift 2 ;;
    *) echo "drain: unknown argument $1" >&2; exit 2 ;;
  esac
done

# Prints "<pending> <lag>" for the consumer group; lag is empty if unknown.
stream_state() {
  local pending lag
  pending=$(redis_target_cli "$redis_target" XPENDING "$STREAM" "$GROUP" | awk 'NR==1')
  lag=$(redis_target_cli "$redis_target" XINFO GROUPS "$STREAM" | python3 -c '
import sys
lines = [l.rstrip("\n") for l in sys.stdin]
# redis-cli prints each group as a flat list of alternating field names and values.
groups, cur = [], None
for i in range(0, len(lines) - 1, 2):
    if lines[i] == "name":
        cur = {}
        groups.append(cur)
    if cur is not None:
        cur[lines[i]] = lines[i + 1]
print(next((g.get("lag", "") for g in groups if g.get("name") == sys.argv[1]), ""))
' "$GROUP")
  echo "$pending $lag"
}

case $mode in
  none) exit 0 ;;
  stream) ;;
  outbox) echo "drain: outbox mode is introduced in P2" >&2; exit 2 ;;
  *) echo "usage: $0 <stream|outbox|none> [--redis temp] [--timeout 120]" >&2; exit 2 ;;
esac

redis_target_cli "$redis_target" PING >/dev/null || exit 2
deadline=$(( SECONDS + timeout_s ))
while :; do
  read -r pending lag < <(stream_state) || true
  if [[ ${pending:-x} == 0 && ${lag:-x} == 0 ]]; then
    echo "drain: stream drained (pending=0 lag=0)" >&2
    exit 0
  fi
  if (( SECONDS >= deadline )); then
    echo "drain: timeout after ${timeout_s}s (pending=${pending:-?} lag=${lag:-?})" >&2
    exit 3
  fi
  sleep 1
done
