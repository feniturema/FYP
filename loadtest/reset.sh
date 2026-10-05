#!/usr/bin/env bash
# Remove SecKill test data between benchmark runs (docs/phases/P3.md §7.2 step 7):
#   CONFIRM_RESET=yes loadtest/reset.sh --mysql <target:db> --redis <target>
# Deletes only: SECKILL orders, order_outbox rows (if the table exists), and the Redis keys
# seckill:stock:* / seckill:bought:* (both key formats). Events, users, products and the legacy
# stream are kept. Call it only after the run's drain succeeded and no k6 is running: deleting while a
# consumer is still writing would corrupt the next measurement. Exit 2 without CONFIRM_RESET=yes;
# exit 1 if a seckill key survives the deletion.
set -Eeuo pipefail
REPO=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
# shellcheck source=../scripts/db/lib.sh
source "$REPO/scripts/db/lib.sh"
mysql_t= redis_t=
while (( $# )); do
  [[ $# -ge 2 ]] || { echo "reset: $1 needs a value" >&2; exit 2; }
  case $1 in
    --mysql) mysql_t=$2 ;;
    --redis) redis_t=$2 ;;
    *) echo "reset: unknown option $1" >&2; exit 2 ;;
  esac
  shift 2
done
[[ ${CONFIRM_RESET:-} == yes ]] || { echo "reset: set CONFIRM_RESET=yes to delete seckill test data" >&2; exit 2; }
[[ -n $mysql_t && -n $redis_t ]] || { echo "reset: --mysql and --redis are required" >&2; exit 2; }

has_outbox=$(mysql_target_cli "$mysql_t" --batch --skip-column-names -e \
  "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name = 'order_outbox'")
sql="DELETE FROM orders WHERE source_type = 'SECKILL';"
[[ $has_outbox == 0 ]] || sql+=" DELETE FROM order_outbox;"
mysql_target_cli "$mysql_t" -e "$sql"
# Collect the keys first, then DEL with stdin closed: a compose target runs `docker compose exec`,
# which would otherwise swallow the rest of the --scan output and leave keys behind.
scan_keys() {
  local pattern key
  for pattern in 'seckill:stock:*' 'seckill:bought:*'; do
    while IFS= read -r key; do
      key=${key%$'\r'}
      [[ -z $key ]] || printf '%s\n' "$key"
    done < <(redis_target_cli "$redis_t" --scan --pattern "$pattern" < /dev/null)
  done
}
keys=()
while IFS= read -r key; do keys+=("$key"); done < <(scan_keys)
deleted=${#keys[@]}
for ((i = 0; i < deleted; i += 100)); do
  redis_target_cli "$redis_t" DEL "${keys[@]:i:100}" < /dev/null > /dev/null
done
left=$(scan_keys | wc -l | tr -d ' ')
[[ $left == 0 ]] || { echo "reset: $left seckill redis keys are still present after DEL" >&2; exit 1; }
echo "reset: deleted SECKILL orders$([[ $has_outbox == 0 ]] || echo ', outbox rows') and $deleted redis keys" >&2
