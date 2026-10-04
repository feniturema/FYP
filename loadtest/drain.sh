#!/usr/bin/env bash
# Wait until the backend has consumed every seckill message for this run.
# Usage: loadtest/drain.sh stream [--redis temp] [--timeout 120]
#        loadtest/drain.sh outbox --event <id> --mysql compose:<project>:<env-file>:<db> [--timeout 180]
#        loadtest/drain.sh none
#   stream (pre-P2): XPENDING count of seckill:orders / seckill-order-consumers is 0 AND the
#           group's lag (XINFO GROUPS) is 0.
#   outbox (P2+, docs/phases/P2.md §7.4): all three hold at the same time
#           1. no NEW order_outbox row for the event;
#           2. the summed LAG of consumer group seckill-order-writer is 0 ("-" ignored), read with
#              kafka-consumer-groups.sh inside the kafka service of the same compose project;
#           3. the event's SECKILL orders == its outbox rows (countAllForEvent).
#   none: no-op.
# Exit codes: 0 drained, 2 usage/unsupported, 3 timeout.
set -Eeuo pipefail

REPO=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
# shellcheck source=../scripts/db/lib.sh
source "$REPO/scripts/db/lib.sh"

STREAM=seckill:orders
GROUP=seckill-order-consumers

mode=${1:-}; shift || true
redis_target=temp
mysql_target=
event_id=
timeout_s=
while (( $# )); do
  [[ $# -ge 2 ]] || { echo "drain: $1 needs a value" >&2; exit 2; }
  case $1 in
    --redis) redis_target=$2; shift 2 ;;
    --mysql) mysql_target=$2; shift 2 ;;
    --event) event_id=$2; shift 2 ;;
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

# Prints "<outbox_new> <lag_sum> <orders> <outbox_total>"; lag_sum is empty when it cannot be read.
outbox_state() {
  local counts lag
  counts=$(mysql_target_cli "$mysql_target" --batch --skip-column-names -e "
    SELECT (SELECT COUNT(*) FROM order_outbox WHERE event_id=$event_id AND status=0),
           (SELECT COUNT(*) FROM orders WHERE source_type='SECKILL' AND seckill_event_id=$event_id),
           (SELECT COUNT(*) FROM order_outbox WHERE event_id=$event_id)" 2>/dev/null | tr '\t' ' ') || counts=
  lag=$(compose_target_exec "$mysql_target" kafka /opt/kafka/bin/kafka-consumer-groups.sh \
          --bootstrap-server localhost:9092 --describe --group seckill-order-writer 2>/dev/null | python3 -c '
import sys
total, col, rows = 0, None, 0
for line in sys.stdin:
    f = line.split()
    if "LAG" in f and "GROUP" in f:
        col = f.index("LAG"); continue
    if col is None or len(f) <= col:
        continue
    v = f[col]
    if v == "-":
        continue
    if not v.isdigit():
        continue
    total += int(v); rows += 1
print(total if rows else "")
') || lag=
  set -- $counts
  echo "${1:-x} ${lag:-x} ${2:-x} ${3:-x}"
}

case $mode in
  none) exit 0 ;;
  stream) timeout_s=${timeout_s:-120} ;;
  outbox)
    timeout_s=${timeout_s:-180}
    [[ $event_id =~ ^[0-9]+$ ]] || { echo "drain: outbox needs --event <id>" >&2; exit 2; }
    [[ $mysql_target == compose:* ]] || { echo "drain: outbox needs --mysql compose:<project>:<env-file>:<db>" >&2; exit 2; }
    mysql_target_cli "$mysql_target" -e 'SELECT 1' >/dev/null || exit 2
    deadline=$(( SECONDS + timeout_s ))
    while :; do
      read -r new lag orders total < <(outbox_state) || true
      if [[ $new == 0 && $lag == 0 && $orders != x && $orders == "$total" ]]; then
        echo "drain: outbox drained for event $event_id (new=0 lag=0 orders=$orders outbox=$total)" >&2
        exit 0
      fi
      if (( SECONDS >= deadline )); then
        echo "drain: timeout after ${timeout_s}s (event=$event_id new=$new lag=$lag orders=$orders outbox=$total)" >&2
        exit 3
      fi
      sleep 1
    done ;;
  *) echo "usage: $0 <stream|outbox|none> ... (see header)" >&2; exit 2 ;;
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
