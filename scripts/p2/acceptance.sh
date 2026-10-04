#!/usr/bin/env bash
# P2 acceptance A2 -> A12 in the isolated compose project ftsm-p2-acc (docs/phases/P2.md §7.4, §9).
#
#   scripts/p2/acceptance.sh            # optional env: P2_TMP (reused if set), K6 (k6 2.3.0 binary)
#
# Every scenario runs fail-fast in its own subshell and writes its evidence to scripts/p2/evidence/.
# A failing scenario does not stop the run: the EXIT trap always restores what a scenario broke
# (unpause kafka, start mysql/backend) and stops the background log follower, and the script exits
# with the first failing scenario's code. The stack is left running for A13/A14; tear it down with
#   docker compose -p ftsm-p2-acc --env-file "$P2_TMP/compose.env" down -v
# Fault injection uses compose commands only. No secret, password, OTP or token is written to evidence.
set -Eeuo pipefail

REPO=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
cd "$REPO"
# shellcheck source=../db/lib.sh
source "$REPO/scripts/db/lib.sh"

E=$REPO/scripts/p2/evidence
mkdir -p "$E"
P2_TMP=${P2_TMP:-$(mktemp -d /tmp/ftsm-p2.XXXXXX)}
export P2_TMP
[[ -f $P2_TMP/compose.env ]] || cat > "$P2_TMP/compose.env" <<EOF
DB_PASSWORD=p2-acc-db
JWT_SECRET=p2-acceptance-secret-0123456789abcdef0123
MYSQL_HOST_PORT=23306
REDIS_HOST_PORT=26379
KAFKA_HOST_PORT=29092
BACKEND_HOST_PORT=28080
FRONTEND_HOST_PORT=28081
EOF
DC=(docker compose -p ftsm-p2-acc --env-file "$P2_TMP/compose.env")
BASE=http://127.0.0.1:28080
MYSQL_T=compose:ftsm-p2-acc:$P2_TMP/compose.env:ftsm_ecommerce
REDIS_T=compose:ftsm-p2-acc:$P2_TMP/compose.env
JWT_SECRET=$(sed -n 's/^JWT_SECRET=//p' "$P2_TMP/compose.env")
export JWT_SECRET
K6=${K6:-$REPO/.tools/k6-v2.3.0/k6}
export K6
STATE=$P2_TMP/state
LOGS=$P2_TMP/logs
mkdir -p "$STATE" "$LOGS"
SUMMARY=$E/acceptance-summary.txt

# ---------- helpers ----------
q() { mysql_target_cli "$MYSQL_T" --batch --skip-column-names -e "$1"; }
# `acceptance.sh __query SQL`: wait_cmd needs an executable, not a shell function.
if [[ ${1:-} == __query ]]; then q "$2"; exit; fi
health() { wait_http "$BASE/actuator/health" "${1:-300}"; }
# Docker marks a paused container unhealthy at once and keeps it unhealthy after unpause until the
# next passing probe; `compose start backend` refuses a dependency in that state (and still exits 0).
wait_healthy() {
  local cid; cid=$("${DC[@]}" ps -q "$1")
  [[ -n $cid ]] || { echo "wait_healthy: no container for $1" >&2; return 1; }
  wait_cmd "${2:-120}" "$1 healthy" -- sh -c '[ "$(docker inspect --format "{{.State.Health.Status}}" "$0")" = healthy ]' "$cid"
}
unpause_kafka() { "${DC[@]}" unpause kafka; rm -f "$STATE/paused_kafka"; wait_healthy kafka; }
kafka_groups() {
  "${DC[@]}" exec -T kafka /opt/kafka/bin/kafka-consumer-groups.sh --bootstrap-server localhost:9092 "$@"
}

# The backend log is streamed to $P2_TMP/backend.log (e2e and race_product read OTPs from it).
# After a backend restart the follower is restarted with --since so lines are not duplicated.
start_log_follower() {
  local since=${1:-}
  local -a args=(logs --no-color -f)
  [[ -z $since ]] || args+=(--since "$since")
  "${DC[@]}" "${args[@]}" backend >> "$P2_TMP/backend.log" 2>&1 &
  echo $! > "$STATE/logpid"
}
stop_log_follower() {
  [[ -f $STATE/logpid ]] || return 0
  kill "$(cat "$STATE/logpid")" 2>/dev/null || true
  rm -f "$STATE/logpid"
}
restart_backend_log() { stop_log_follower; start_log_follower "$1"; }
now_utc() { date -u +%Y-%m-%dT%H:%M:%SZ; }

jwt_for() {   # HS256 token for a synthetic user id (same claims as loadtest/lib/jwt.js)
  python3 - "$JWT_SECRET" "$1" <<'PY'
import base64, hashlib, hmac, json, sys, time
secret, uid = sys.argv[1].encode(), sys.argv[2]
b64 = lambda b: base64.urlsafe_b64encode(b).rstrip(b"=")
now = int(time.time())
head = b64(json.dumps({"alg": "HS256", "typ": "JWT"}).encode())
body = b64(json.dumps({"sub": uid, "email": f"lt{uid}@siswa.ukm.edu.my", "role": "STUDENT",
                       "name": f"acceptance {uid}", "iat": now, "exp": now + 3600}).encode())
sig = b64(hmac.new(secret, head + b"." + body, hashlib.sha256).digest())
print((head + b"." + body + b"." + sig).decode())
PY
}

buy_as() {   # buy_as <eventId> <userId> -> prints "<http status> <result>"
  python3 - "$BASE" "$1" "$(jwt_for "$2")" <<'PY'
import json, sys, urllib.error, urllib.request
base, eid, tok = sys.argv[1:4]
req = urllib.request.Request(f"{base}/api/seckill/{eid}/buy", method="POST",
                             headers={"Authorization": f"Bearer {tok}"})
try:
    with urllib.request.urlopen(req, timeout=150) as r:
        st, raw = r.status, r.read()
except urllib.error.HTTPError as e:
    st, raw = e.code, e.read()
except Exception as e:
    print(f"0 ERROR:{type(e).__name__}"); sys.exit(0)
try:
    res = json.loads(raw).get("result")
except Exception:
    res = "UNPARSEABLE"
print(f"{st} {res}")
PY
}

new_event() {  # new_event <stock> <label> [duration-min] -> prints eventId
  python3 loadtest/setup_event.py --base "$BASE" --stock "$1" --label "$2" --duration-min "${3:-120}" \
    | python3 -c 'import json,sys; print(json.loads(sys.stdin.read())["eventId"])'
}

verify_event() {  # verify_event <eventId> -> verify_outbox.sql TSV (header + 1 row)
  { echo "SET @event_id = $1;"; cat loadtest/verify_outbox.sql; } | mysql_target_cli "$MYSQL_T" --batch
}

tsv_get() {  # tsv_get <file> <column>
  python3 - "$1" "$2" <<'PY'
import csv, sys
rows = list(csv.DictReader(open(sys.argv[1]), delimiter="\t"))
print(rows[0][sys.argv[2]] if rows else "")
PY
}

k6_contention() {  # k6_contention <eventId> <stock> <buyers> <vus> <userBase> <summary.json> <log>
  env BASE_URL="$BASE" EVENT_ID="$1" JWT_SECRET="$JWT_SECRET" STOCK="$2" BUYERS="$3" VUS="$4" \
      USER_BASE="$5" REJECT_STATUS=409 SUMMARY_PATH="$6" RUN_ID="acc-$(date +%s)" \
      K6_VERSION="$("$K6" version | head -n 1)" \
      "$K6" run --quiet --no-color "$REPO/loadtest/contention.js" > "$7" 2>&1
}

# ---------- scenarios ----------
a2() {
  "${DC[@]}" up -d --build mysql redis kafka backend > "$LOGS/A2-up.log" 2>&1
  if ! health 300; then "${DC[@]}" logs --tail 200 backend >&2; return 1; fi
  : > "$P2_TMP/backend.log"
  start_log_follower
  wait_cmd 90 "both topics exist" -- sh -c '"$@" --list | grep -qx seckill.orders.DLT' _ \
    "${DC[@]}" exec -T kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092
  {
    echo "\$ kafka-topics.sh --bootstrap-server localhost:9092 --list"
    "${DC[@]}" exec -T kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 --list
    echo "\$ kafka-topics.sh --describe (partition counts)"
    "${DC[@]}" exec -T kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 --describe \
      | grep -E '^Topic:' | sed -E 's/TopicId: [^[:space:]]+[[:space:]]*//'
    echo "\$ GET /actuator/health"
    curl -fsS "$BASE/actuator/health"; echo
  } > "$E/topics.txt"
  grep -qx 'seckill.orders' "$E/topics.txt"
  grep -qx 'seckill.orders.DLT' "$E/topics.txt"
  grep -q '"status":"UP"' "$E/topics.txt"
}

a3() {
  { echo "version	success"; q "SELECT version, success FROM flyway_schema_history ORDER BY installed_rank"; } \
    > "$E/flyway.txt"
  cat "$E/flyway.txt"
  grep -qx '1	1' "$E/flyway.txt"
  grep -qx '2	1' "$E/flyway.txt"
}

a4() {
  local rc=0
  python3 scripts/e2e_test.py --base "$BASE" --log "$P2_TMP/backend.log" --users 200 --stock 50 \
    > "$E/e2e.txt" 2>&1 || rc=$?
  cat "$E/e2e.txt"
  sed -n 's/.*seckill event #\([0-9][0-9]*\).*/\1/p' "$E/e2e.txt" | head -n 1 > "$STATE/a4_event"
  echo "e2e rc=$rc" >> "$E/e2e.txt"
  return "$rc"
}

a5() {
  local eid; eid=$(cat "$STATE/a4_event")
  [[ -n $eid ]]
  loadtest/drain.sh outbox --event "$eid" --mysql "$MYSQL_T" --timeout 180
  verify_event "$eid" > "$E/verify-a5.tsv"
  local remaining; remaining=$(redis_target_cli "$REDIS_T" GET "seckill:stock:{$eid}" | tr -d '\r')
  printf 'event_id\t%s\nredis_remaining\t%s\n' "$eid" "$remaining" >> "$E/verify-a5.tsv"
  cat "$E/verify-a5.tsv"
  local orders buyers sold new
  orders=$(tsv_get "$E/verify-a5.tsv" orders); buyers=$(tsv_get "$E/verify-a5.tsv" buyers)
  sold=$(tsv_get "$E/verify-a5.tsv" sold_count); new=$(tsv_get "$E/verify-a5.tsv" outbox_new)
  [[ $orders == 50 && $buyers == 50 && $sold == 50 && $new == 0 && $remaining == 0 ]]
}

a6() {
  local eid; eid=$(new_event 50 A6-crash)
  echo "A6 event $eid"
  local summary=$P2_TMP/a6-summary.json k6log=$LOGS/A6-k6.log
  # 200 buyers on ONE VU: ~200 sequential requests (~1.5-2.5 s at local latency), so the kill
  # reliably lands while k6 is still sending. Kill as soon as the first unit is taken; poll Redis
  # in a tight loop (wait_cmd's 2 s retry interval is longer than the whole run).
  k6_contention "$eid" 50 200 1 2000000000 "$summary" "$k6log" &
  local k6pid=$! deadline=$(( SECONDS + 60 )) r=
  until r=$(redis_target_cli "$REDIS_T" GET "seckill:stock:{$eid}" | tr -d '\r') && [[ $r =~ ^[0-9]+$ ]] && (( r < 50 )); do
    (( SECONDS < deadline )) || { echo "A6: no ACCEPTED within 60s" >&2; return 1; }
    sleep 0.05
  done
  local running=no; kill -0 "$k6pid" 2>/dev/null && running=yes
  local killed_at; killed_at=$(now_utc)
  "${DC[@]}" kill -s SIGKILL backend
  echo backend > "$STATE/stopped_backend"
  sleep 3
  "${DC[@]}" start backend
  rm -f "$STATE/stopped_backend"
  wait "$k6pid" || true          # connection errors on the k6 side are allowed
  health 300
  restart_backend_log "$killed_at"
  loadtest/drain.sh outbox --event "$eid" --mysql "$MYSQL_T" --timeout 180
  verify_event "$eid" > "$P2_TMP/a6-verify.tsv"
  local accepted; accepted=$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["meta"]["accepted"])' "$summary")
  local orders buyers dups
  orders=$(tsv_get "$P2_TMP/a6-verify.tsv" orders); buyers=$(tsv_get "$P2_TMP/a6-verify.tsv" buyers)
  dups=$(tsv_get "$P2_TMP/a6-verify.tsv" duplicate_buyers)
  {
    printf 'event_id\tk6_running_at_kill\tkilled_at\taccepted_202\n%s\t%s\t%s\t%s\n' "$eid" "$running" "$killed_at" "$accepted"
    cat "$P2_TMP/a6-verify.tsv"
    echo "k6 result counts: $(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["meta"]["results"])' "$summary")"
  } > "$E/crash.tsv"
  cat "$E/crash.tsv"
  [[ $running == yes && $orders == "$accepted" && $orders -le 50 && $orders == "$buyers" && $dups == 0 ]]
}

a7() {
  local out=$P2_TMP/runs rc=0
  "${DC[@]}" pause kafka
  echo kafka > "$STATE/paused_kafka"
  local paused_at; paused_at=$(now_utc)
  ( sleep 30; "${DC[@]}" unpause kafka && now_utc > "$STATE/unpaused_at" && rm -f "$STATE/paused_kafka" ) \
    > "$LOGS/A7-timer.log" 2>&1 &
  ( while [[ -f $STATE/paused_kafka ]]; do
      echo "$(now_utc) $(q 'SELECT COUNT(*) FROM order_outbox WHERE status=0' 2>/dev/null)"; sleep 1
    done ) > "$P2_TMP/a7-new-samples.txt" 2>&1 &
  loadtest/run.sh --config P2-kafka-pause --scenario contention --stock 20 --buyers 20 --vus 20 \
    --reject-status 409 --drain outbox --mysql "$MYSQL_T" --redis "$REDIS_T" --base "$BASE" \
    --out-root "$out" 2> >(while IFS= read -r l; do echo "$(now_utc) $l"; done > "$LOGS/A7-run.log") || rc=$?
  wait_cmd 60 "kafka unpaused" -- test ! -f "$STATE/paused_kafka"
  wait_healthy kafka
  local dir; dir=$(ls -d "$out"/P2-kafka-pause/* | tail -n 1)
  local max_new; max_new=$(awk '{ if ($2+0 > m) m = $2+0 } END { print m+0 }' "$P2_TMP/a7-new-samples.txt")
  local accepted; accepted=$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["meta"]["accepted"])' "$dir/summary.json")
  # run.sh echoes k6's report right after k6 exits; its first line ends with "(contention)".
  local k6_done; k6_done=$(grep -m1 '(contention)$' "$LOGS/A7-run.log" | cut -d' ' -f1 || true)
  local unpaused_at; unpaused_at=$(cat "$STATE/unpaused_at" 2>/dev/null || true)
  {
    printf 'paused_at\tunpaused_at\tk6_finished_before\tmax_outbox_new_while_paused\taccepted_202\trun_rc\n'
    printf '%s\t%s\t%s\t%s\t%s\t%s\n' "$paused_at" "$unpaused_at" "$k6_done" "$max_new" "$accepted" "$rc"
    echo "run dir: ${dir#"$P2_TMP"/}"
    cat "$dir/verify.tsv"; cat "$dir/redis.txt"
    grep -E 'drain:|verify:' "$LOGS/A7-run.log" || true
  } > "$E/kafka-pause.tsv"
  cat "$E/kafka-pause.tsv"
  [[ $rc == 0 && $accepted == 20 && $max_new -gt 0 && -n $k6_done && $k6_done < $unpaused_at ]]
}

a8() {
  local eid; eid=$(cat "$STATE/a4_event")
  local before dups_before; before=$(q 'SELECT COUNT(*) FROM orders')
  dups_before=$(grep -cE 'duplicate .* skipped' "$P2_TMP/backend.log" || true)
  "${DC[@]}" stop backend
  echo backend > "$STATE/stopped_backend"
  wait_cmd 60 "group seckill-order-writer Empty" -- sh -c '"$@" | grep -qw Empty' _ \
    "${DC[@]}" exec -T kafka /opt/kafka/bin/kafka-consumer-groups.sh --bootstrap-server localhost:9092 \
      --group seckill-order-writer --describe --state
  {
    echo "orders before replay: $before"
    echo "\$ kafka-consumer-groups.sh --group seckill-order-writer --describe --state"
    kafka_groups --group seckill-order-writer --describe --state
    echo "\$ kafka-consumer-groups.sh --group seckill-order-writer --reset-offsets --to-earliest --all-topics --execute"
    kafka_groups --group seckill-order-writer --reset-offsets --to-earliest --all-topics --execute
  } > "$E/replay.txt"
  local restarted_at; restarted_at=$(now_utc)
  "${DC[@]}" start backend
  rm -f "$STATE/stopped_backend"
  health 300
  restart_backend_log "$restarted_at"
  loadtest/drain.sh outbox --event "$eid" --mysql "$MYSQL_T" --timeout 180
  sleep 2
  local after dups_after; after=$(q 'SELECT COUNT(*) FROM orders')
  dups_after=$(grep -cE 'duplicate .* skipped' "$P2_TMP/backend.log" || true)
  {
    echo "orders after replay: $after"
    echo "'duplicate .* skipped' log lines: before=$dups_before after=$dups_after (replayed duplicates: $(( dups_after - dups_before )))"
    echo "\$ kafka-consumer-groups.sh --group seckill-order-writer --describe"
    kafka_groups --group seckill-order-writer --describe | sed -E 's/consumer-[^ ]+/<consumer>/; s/\/[0-9.]+/\/<ip>/'
  } >> "$E/replay.txt"
  cat "$E/replay.txt"
  [[ $after == "$before" && $(( dups_after - dups_before )) -ge 1 ]]
}

a9() {
  local eid; eid=$(new_event 5 A9-uncertain)
  local warm test_user=3000000002
  warm=$(buy_as "$eid" 3000000001)        # caches the event window (TTL 5 s) before MySQL goes away
  local t0=$SECONDS stop_s
  "${DC[@]}" stop mysql
  echo mysql > "$STATE/stopped_mysql"
  stop_s=$(( SECONDS - t0 ))
  local res; res=$(buy_as "$eid" "$test_user")
  local took=$(( SECONDS - t0 ))
  local member; member=$(redis_target_cli "$REDIS_T" SISMEMBER "seckill:bought:{$eid}" "$test_user" | tr -d '\r')
  sleep 3
  local uncertain; uncertain=$(grep -E 'UNCERTAIN orderId=.* userId='"$test_user"' eventId='"$eid" "$P2_TMP/backend.log" \
    | sed -E 's/^backend-1 +\| //' | cut -c1-220 || true)
  "${DC[@]}" start mysql
  rm -f "$STATE/stopped_mysql"
  wait_healthy mysql
  health 300
  {
    echo "event $eid; warm-up buy by user 3000000001: $warm"
    echo "\$ docker compose -p ftsm-p2-acc stop mysql   (took ${stop_s}s)"
    echo "buy by user $test_user while MySQL is stopped: $res  (answered ${took}s after the stop began)"
    echo "SISMEMBER seckill:bought:{$eid} $test_user = $member   (1 = not compensated, as §6.1 requires when unconfirmable)"
    echo "UNCERTAIN log line:"; echo "${uncertain:-<none>}"
  } > "$E/uncertain.txt"
  cat "$E/uncertain.txt"
  [[ $res == "503 UNAVAILABLE" && $member == 1 && -n $uncertain ]]
}

a10() {
  local rc=0
  python3 scripts/p2/race_product.py --base "$BASE" --concurrency 20 --log "$P2_TMP/backend.log" \
    > "$E/race.txt" 2>&1 || rc=$?
  cat "$E/race.txt"
  return "$rc"
}

a11() {
  local eid; eid=$(new_event 5 A11-reconcile 1)
  local u results=""
  for u in 4000000001 4000000002 4000000003 4000000004 4000000005; do
    results+="$(buy_as "$eid" "$u"); "
  done
  loadtest/drain.sh outbox --event "$eid" --mysql "$MYSQL_T" --timeout 180
  local end; end=$(q "SELECT DATE_FORMAT(end_time, '%Y-%m-%dT%H:%i:%sZ') FROM seckill_events WHERE id=$eid")
  # end + 2 min settle delay + one 60 s reconciler tick; wait at most 5 min after the end.
  local waited=0 reconciled=0 budget
  budget=$(( $(python3 -c 'import datetime as d,sys; e=d.datetime.strptime(sys.argv[1],"%Y-%m-%dT%H:%M:%SZ").replace(tzinfo=d.timezone.utc); print(int((e-d.datetime.now(d.timezone.utc)).total_seconds()))' "$end") + 300 ))
  wait_cmd "$budget" "event $eid reconciled" -- sh -c '[ "$("$@")" = 1 ]' _ \
    "$REPO/scripts/p2/acceptance.sh" __query "SELECT reconciled+0 FROM seckill_events WHERE id=$eid" && reconciled=1
  local line; line=$(grep -E "reconcile event $eid: " "$P2_TMP/backend.log" | sed -E 's/^backend-1 +\| //' | cut -c1-260 || true)
  {
    echo "event $eid (1-minute window, end_time $end); buys: $results"
    { echo "SET @event_id = $eid;"; cat loadtest/verify_outbox.sql; } | mysql_target_cli "$MYSQL_T" --batch
    echo "reconciled: $(q "SELECT reconciled+0 FROM seckill_events WHERE id=$eid")"
    echo "reconciler log:"; echo "${line:-<none>}"
  } > "$E/reconcile.txt"
  cat "$E/reconcile.txt"
  [[ $reconciled == 1 ]] && grep -q "INFO .*reconcile event $eid: consistent" <<< "$line"
}

a12() {
  loadtest/run.sh --config P2 --scenario contention --stock 5 --buyers 20 --vus 20 --reject-status 409 \
    --drain outbox --mysql "compose:ftsm-p2-acc:$P2_TMP/compose.env:ftsm_ecommerce" \
    --redis "compose:ftsm-p2-acc:$P2_TMP/compose.env" --base "$BASE" --out-root loadtest/results/_smoke
}

# ---------- runner ----------
restore() {
  [[ -f $STATE/paused_kafka ]] && { unpause_kafka || true; }
  [[ -f $STATE/stopped_mysql ]] && { "${DC[@]}" start mysql || true; rm -f "$STATE/stopped_mysql"; wait_healthy mysql || true; }
  [[ -f $STATE/stopped_backend ]] && { "${DC[@]}" start backend || true; rm -f "$STATE/stopped_backend"; }
  stop_log_follower
}
trap restore EXIT

first_rc=0
run_step() {
  local id=$1 fn=$2 rc=0 t0=$SECONDS
  echo "=== $id start $(now_utc)" >&2
  set +e
  ( set -Eeuo pipefail; "$fn" ) > "$LOGS/$id.log" 2>&1
  rc=$?
  set -e
  restore_after_step
  printf '%s\t%s\trc=%s\t%ss\n' "$id" "$([[ $rc == 0 ]] && echo PASS || echo FAIL)" "$rc" "$(( SECONDS - t0 ))" \
    | tee -a "$SUMMARY" >&2
  (( rc == 0 || first_rc != 0 )) || first_rc=$rc
}
# Between steps, put back anything a failed scenario left broken (the follower keeps running).
restore_after_step() {
  [[ -f $STATE/paused_kafka ]] && { unpause_kafka || true; }
  [[ -f $STATE/stopped_mysql ]] && { "${DC[@]}" start mysql || true; rm -f "$STATE/stopped_mysql"; wait_healthy mysql || true; }
  if [[ -f $STATE/stopped_backend ]]; then
    "${DC[@]}" start backend || true; rm -f "$STATE/stopped_backend"
    health 300 || true; restart_backend_log "$(now_utc)"
  fi
  return 0
}

: > "$SUMMARY"
echo "acceptance: commit $(git rev-parse HEAD) dirty=$([[ -z $(git status --porcelain) ]] && echo no || echo yes) at $(now_utc)" \
  | tee -a "$SUMMARY" >&2
# ACCEPTANCE_STEPS (e.g. "a8 a9") reruns a subset against a stack a previous run left up.
for step in ${ACCEPTANCE_STEPS:-a2 a3 a4 a5 a6 a7 a8 a9 a10 a11 a12}; do
  id=$(echo "$step" | tr a A)
  run_step "$id" "$step"
  if [[ $id == A2 && $first_rc != 0 ]]; then
    echo "acceptance: environment did not come up; skipping A3-A12" | tee -a "$SUMMARY" >&2
    break
  fi
done
echo "acceptance: done, first failure rc=$first_rc (logs in $LOGS)" | tee -a "$SUMMARY" >&2
exit "$first_rc"
