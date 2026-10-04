#!/usr/bin/env bash
# Remove SecKill test data between benchmark runs (docs/phases/P3.md §7.2 step 7):
#   CONFIRM_RESET=yes loadtest/reset.sh --mysql <target:db> --redis <target>
# Deletes only: SECKILL orders, order_outbox rows (if the table exists), and the Redis keys
# seckill:stock:* / seckill:bought:* (both key formats). Events, users, products and the legacy
# stream are kept. Call it only after the run's drain succeeded and no k6 is running: deleting while a
# consumer is still writing would corrupt the next measurement. Exit 2 without CONFIRM_RESET=yes.
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
deleted=0
for pattern in 'seckill:stock:*' 'seckill:bought:*'; do
  while IFS= read -r key; do
    key=${key%$'\r'}
    [[ -n $key ]] || continue
    redis_target_cli "$redis_t" DEL "$key" > /dev/null
    deleted=$((deleted + 1))
  done < <(redis_target_cli "$redis_t" --scan --pattern "$pattern")
done
echo "reset: deleted SECKILL orders$([[ $has_outbox == 0 ]] || echo ', outbox rows') and $deleted redis keys" >&2
