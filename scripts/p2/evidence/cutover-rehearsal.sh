#!/usr/bin/env bash
# A13 (docs/phases/P2.md §9, §10.1): cutover rehearsal on a copy that holds P1 history.
# Kept with the evidence as the exact command record; output goes to scripts/p2/evidence/cutover.txt.
#
#   P2_JAR=backend/target/ecommerce-0.0.1-SNAPSHOT.jar scripts/p2/evidence/cutover-rehearsal.sh
#
# 1. Build the P1 jar from origin/main (merged P1) in a temporary worktree.
# 2. Temporary MySQL 3307 / Redis 6380 (scripts/db/lib.sh, docker mode), database ftsm_p2_cutover.
# 3. P1 backend: e2e (history: orders + Redis Stream entries) and one future event (warmed by P1
#    under the old key name). Precheck while the e2e event is live must FAIL (control).
# 4. After the e2e event has ended: precheck must PASS. Stop P1, take the backup the precheck prints.
# 5. Start the P2 jar on the same database: Flyway runs V2. Check backfill, reconciled, re-warm,
#    then archive the legacy stream (RENAME, not delete).
# Kafka is not attached in this rehearsal (the pipeline is covered by A4–A12); only the DB/Redis
# cutover is rehearsed. Cleans up everything it started.
set -Eeuo pipefail

REPO=$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)
cd "$REPO"
P2_TMP=${P2_TMP:?set P2_TMP}
P2_JAR=${P2_JAR:-backend/target/ecommerce-0.0.1-SNAPSHOT.jar}
OUT=$REPO/scripts/p2/evidence/cutover.txt
export P0_MYSQL_MODE=docker P0_TMP=$P2_TMP DB_PASSWORD=root
export JWT_SECRET=p2-cutover-secret-0123456789abcdef0123456789
source scripts/db/lib.sh
DB=ftsm_p2_cutover PORT=18095 BASE=http://127.0.0.1:18095
WT=$P2_TMP/wt-p1
LOG1=$P2_TMP/cutover-p1.log LOG2=$P2_TMP/cutover-p2.log

cleanup() {
  stop_backend || true
  stop_temp_mysql || true
  stop_temp_redis || true
  git worktree remove --force "$WT" 2>/dev/null || true
  # start_temp_mysql rewrites this tracked P0 evidence file; it is not P2 evidence.
  git checkout -- scripts/db/evidence/p0/mysql-version.txt 2>/dev/null || true
}
trap cleanup EXIT

say() { echo "$*" | tee -a "$OUT"; }
run() { echo "\$ $*" >> "$OUT"; "$@" 2>&1 | tee -a "$OUT"; return "${PIPESTATUS[0]}"; }
q() { mysql_cli "$DB" --batch -e "$1"; }

: > "$OUT"
say "A13 cutover rehearsal at $(date -u +%FT%TZ); P2 commit $(git rev-parse --short HEAD); P1 = origin/main $(git rev-parse --short origin/main)"

# 1. P1 jar
git worktree add --detach "$WT" origin/main > /dev/null
(cd "$WT/backend" && ./mvnw -B -q -DskipTests package) > "$P2_TMP/cutover-p1-build.log" 2>&1
P1_JAR=$WT/backend/target/ecommerce-0.0.1-SNAPSHOT.jar

# 2. temporary instances
start_temp_mysql
start_temp_redis
mysql_cli -e "DROP DATABASE IF EXISTS $DB; CREATE DATABASE $DB"
redis_cli FLUSHALL > /dev/null

# 3. history on P1
start_backend "$P1_JAR" "$DB" "$PORT" "$LOG1"
say "## P1 backend up; e2e on P1 (history)"
python3 scripts/e2e_test.py --base "$BASE" --log "$LOG1" --users 30 --stock 10 | tee -a "$OUT"
FUTURE=$(python3 - "$BASE" <<'PY'
import datetime as d, json, sys, urllib.request
base = sys.argv[1]
def call(m, p, body=None, tok=None):
    r = urllib.request.Request(base + p, data=json.dumps(body).encode() if body else None, method=m,
                               headers={"Content-Type": "application/json", **({"Authorization": "Bearer " + tok} if tok else {})})
    return json.load(urllib.request.urlopen(r, timeout=10))
tok = call("POST", "/api/auth/login", {"email": "admin@ukm.edu.my", "password": "Admin@123"})["token"]
pid = call("POST", "/api/admin/products", {"name": "future event product", "price": 10, "totalStock": 30, "category": "Test"}, tok)["id"]
now = d.datetime.now(d.timezone.utc)
iso = lambda t: t.isoformat().replace("+00:00", "Z")
print(call("POST", "/api/admin/seckill-events", {"productId": pid, "seckillPrice": 1, "seckillStock": 7,
           "startTime": iso(now + d.timedelta(hours=2)), "endTime": iso(now + d.timedelta(hours=3))}, tok)["id"])
PY
)
wait_cmd 30 "P1 warms the future event" -- sh -c '[ -n "$(docker exec ftsm-p0-redis redis-cli GET "seckill:stock:$0")" ]' "$FUTURE"
E2E_EVENT=$(q "SELECT id FROM seckill_events WHERE id <> $FUTURE ORDER BY id DESC LIMIT 1" | tail -n 1)
say "history: e2e event $E2E_EVENT (ends 10 min after creation), future event $FUTURE"
run q "SELECT id, status, seckill_stock, stock_warmed+0 AS stock_warmed, start_time, end_time FROM seckill_events"
run q "SELECT source_type, status, COUNT(*) FROM orders GROUP BY 1,2"
say "legacy keys: seckill:stock:$FUTURE=$(redis_cli GET "seckill:stock:$FUTURE")  XLEN seckill:orders=$(redis_cli XLEN seckill:orders)"

say "## precheck while the e2e event is still live (control: must FAIL check 1)"
rc=0; run python3 scripts/p2/precheck_cutover.py --mysql "temp:$DB" --redis temp || rc=$?
say "precheck rc=$rc (expected 1)"
[[ $rc == 1 ]]

say "## wait until the e2e event has ended"
end_s=$(q "SELECT GREATEST(0, TIMESTAMPDIFF(SECOND, NOW(6), end_time)) FROM seckill_events WHERE id=$E2E_EVENT" | tail -n 1)
wait_cmd $(( end_s + 60 )) "e2e event ended" -- sh -c '[ "$(docker exec -e MYSQL_PWD=root ftsm-p0-mysql mysql -uroot -N -e "SELECT COUNT(*) FROM seckill_events WHERE end_time >= NOW(6) AND start_time <= NOW(6) + INTERVAL 30 MINUTE" '"$DB"')" = 0 ]'

say "## precheck after the event ended (must PASS)"
run python3 scripts/p2/precheck_cutover.py --mysql "temp:$DB" --redis temp

say "## cutover: stop P1, backup, deploy P2 (Flyway V2)"
stop_backend
mysqldump_cli --single-transaction "$DB" > "$P2_TMP/backup-$DB.sql"
say "backup: $(wc -c < "$P2_TMP/backup-$DB.sql") bytes (kept in \$P2_TMP, not committed)"
KAFKA_BOOTSTRAP=127.0.0.1:1 start_backend "$P2_JAR" "$DB" "$PORT" "$LOG2"
grep -E 'Migrating schema|Successfully applied|Started FtsmEcommerceApplication' "$LOG2" | sed -E 's/^.* : //' | tee -a "$OUT"
run q "SELECT version, description, type, success FROM flyway_schema_history ORDER BY installed_rank"
say "## after V2"
run q "SELECT source_type, COUNT(*) AS orders, SUM(seckill_event_id IS NOT NULL) AS with_event_id, SUM(source_type='SECKILL' AND seckill_event_id <> ref_id) AS mismatched FROM orders GROUP BY source_type"
run q "SELECT e.id, e.status, e.seckill_stock, e.sold_count, (SELECT COUNT(*) FROM orders o WHERE o.source_type='SECKILL' AND o.ref_id=e.id) AS orders, e.reconciled+0 AS reconciled, e.stock_warmed+0 AS stock_warmed FROM seckill_events e"
wait_cmd 30 "P2 re-warms the future event under the hash-tagged key" -- \
  sh -c '[ -n "$(docker exec ftsm-p0-redis redis-cli GET "seckill:stock:{$0}")" ]' "$FUTURE"
say "re-warm: seckill:stock:{$FUTURE}=$(redis_cli GET "seckill:stock:{$FUTURE}") (old key seckill:stock:$FUTURE=$(redis_cli GET "seckill:stock:$FUTURE") is no longer read)"
say "## archive the legacy stream (§10.1 step 4)"
ARCHIVE=seckill:orders:archive:$(date -u +%Y%m%d)
run redis_cli RENAME seckill:orders "$ARCHIVE"
say "EXISTS seckill:orders=$(redis_cli EXISTS seckill:orders) XLEN $ARCHIVE=$(redis_cli XLEN "$ARCHIVE")"

# Assertions (A13): V2 success, backfill complete, ended events reconciled, future event re-warmed.
v2=$(q "SELECT success FROM flyway_schema_history WHERE version='2'" | tail -n 1)
bad_backfill=$(q "SELECT COUNT(*) FROM orders WHERE source_type='SECKILL' AND (seckill_event_id IS NULL OR seckill_event_id <> ref_id)" | tail -n 1)
unreconciled=$(q "SELECT COUNT(*) FROM seckill_events WHERE end_time < NOW(6) AND reconciled = b'0'" | tail -n 1)
sold_ok=$(q "SELECT COUNT(*) FROM seckill_events e WHERE e.sold_count <> (SELECT COUNT(*) FROM orders o WHERE o.source_type='SECKILL' AND o.ref_id=e.id)" | tail -n 1)
say "A13 checks: v2_success=$v2 seckill_orders_not_backfilled=$bad_backfill ended_not_reconciled=$unreconciled sold_count_mismatch=$sold_ok"
[[ $v2 == 1 && $bad_backfill == 0 && $unreconciled == 0 && $sold_ok == 0 ]]
say "A13: PASS"
