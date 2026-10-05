#!/usr/bin/env bash
# One load-test run = one result directory (docs/phases/P0.md §6.7).
#
# loadtest/run.sh --config <A-baseline|P0|...> --scenario <throughput|contention> [--base URL] \
#                 [--jwt-secret-env JWT_SECRET] --stock N [--buyers N --vus N | --rate N --ramp S --steady S] \
#                 --reject-status 200|409 --drain stream|outbox|none --mysql <target> --redis <target> \
#                 [--out-root loadtest/results/_smoke]
# Targets: temp:<db> / temp (scripts/db/lib.sh), or compose:<project>:<env-file>[:<db>] (P2+).
# --drain picks the code generation being measured (docs/phases/P3.md §6.4):
#   stream  pre-P2 code (baseline/P0/P1): verify_legacy.sql, keys seckill:stock:<id>, orders by ref_id
#   outbox  P2+ async: waits for outbox + Kafka (needs a compose MySQL target); verify_outbox.sql,
#           keys seckill:stock:{<id>}, orders by seckill_event_id
#   none    P2+ sync (SECKILL_MODE=sync): nothing to wait for; same verification as outbox plus
#           "no outbox row for the event" (sync never writes the outbox)
# Pre-P2 code must be run with --reject-status 200. Optional env recorded in run.json (P3):
# BACKEND_MODE (e.g. baseline-v0.4.2 / sync / async) and BACKEND_JVM_ARGS.
#
# Writes <out-root>/<config>/<RUN_ID>/{run.json,summary.json,k6.log,verify.tsv,persist.tsv,redis.txt}.
# Exit code: k6's exit code if non-zero (99 = thresholds failed), otherwise the first failure of
# 2 usage / existing run dir / unknown target, 3 drain timeout, 4 verification mismatch.
# The backend under test must already be running at --base against the --mysql/--redis targets.
set -Eeuo pipefail

REPO=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
# shellcheck source=../scripts/db/lib.sh
source "$REPO/scripts/db/lib.sh"

usage() { sed -n '2,21p' "${BASH_SOURCE[0]}" >&2; exit 2; }

config= scenario= base=http://127.0.0.1:8080 secret_env=JWT_SECRET stock= buyers= vus=
rate= ramp= steady= reject_status= drain= mysql_target= redis_target= out_root=loadtest/results/_smoke
while (( $# )); do
  [[ $# -ge 2 ]] || usage
  case $1 in
    --config) config=$2 ;;
    --scenario) scenario=$2 ;;
    --base) base=$2 ;;
    --jwt-secret-env) secret_env=$2 ;;
    --stock) stock=$2 ;;
    --buyers) buyers=$2 ;;
    --vus) vus=$2 ;;
    --rate) rate=$2 ;;
    --ramp) ramp=$2 ;;
    --steady) steady=$2 ;;
    --reject-status) reject_status=$2 ;;
    --drain) drain=$2 ;;
    --mysql) mysql_target=$2 ;;
    --redis) redis_target=$2 ;;
    --out-root) out_root=$2 ;;
    *) echo "run.sh: unknown option $1" >&2; usage ;;
  esac
  shift 2
done

is_int() { [[ $1 =~ ^[0-9]+$ ]]; }
[[ $config =~ ^[A-Za-z0-9._-]+$ ]] || { echo "run.sh: --config is required ([A-Za-z0-9._-])" >&2; exit 2; }
is_int "$stock" && (( stock > 0 )) || { echo "run.sh: --stock must be a positive integer" >&2; exit 2; }
[[ $reject_status == 200 || $reject_status == 409 ]] || { echo "run.sh: --reject-status must be 200 or 409" >&2; exit 2; }
[[ $drain == stream || $drain == outbox || $drain == none ]] || { echo "run.sh: --drain must be stream|outbox|none" >&2; exit 2; }
case $scenario in
  throughput)
    for v in rate ramp steady; do is_int "${!v}" && (( ${!v} > 0 )) || { echo "run.sh: --$v is required for throughput" >&2; exit 2; }; done ;;
  contention)
    is_int "$buyers" && (( buyers > 0 )) || { echo "run.sh: --buyers is required for contention" >&2; exit 2; }
    [[ -n $vus ]] || vus=$buyers
    is_int "$vus" && (( vus > 0 )) || { echo "run.sh: --vus must be a positive integer" >&2; exit 2; } ;;
  *) echo "run.sh: --scenario must be throughput or contention" >&2; exit 2 ;;
esac
secret=${!secret_env:-}
[[ -n $secret ]] || { echo "run.sh: \$$secret_env is empty (--jwt-secret-env)" >&2; exit 2; }

# Step 0: the verify/drain targets must be ones this phase knows how to reach.
mysql_target_cli "$mysql_target" -e 'SELECT 1' >/dev/null || { echo "run.sh: cannot use --mysql $mysql_target" >&2; exit 2; }
redis_target_cli "$redis_target" PING >/dev/null || { echo "run.sh: cannot use --redis $redis_target" >&2; exit 2; }

K6=${K6:-$REPO/.tools/k6-v2.3.0/k6}
[[ -x $K6 ]] || { echo "run.sh: k6 not found at $K6 (run scripts/tools/install_k6.sh)" >&2; exit 2; }
k6_version=$("$K6" version | head -n 1)

# Step 1: a fresh run directory; existing results are never reused.
run_id="$(date -u +%Y%m%dT%H%M%SZ)-$config-$scenario"
dir="$out_root/$config/$run_id"
[[ ! -e $dir ]] || { echo "run.sh: $dir already exists; refusing to reuse it" >&2; exit 2; }
mkdir -p "$out_root/$config"
mkdir "$dir"
today=${run_id%%T*}
run_seq=$(( $(find "$out_root" -mindepth 2 -maxdepth 2 -type d -name "${today}T*" | wc -l) ))   # (( )): BSD wc pads with spaces
user_base=$(( 1000000000 + run_seq * 100000000 ))
started_at=$(date -u +%Y-%m-%dT%H:%M:%SZ)
echo "run.sh: $dir (runSeq=$run_seq userBase=$user_base)" >&2

write_run_json() {
  python3 - "$dir/run.json" <<'PY'
import json, os, sys
e = os.environ
def num(k):
    v = e.get(k, "")
    return int(v) if v.lstrip("-").isdigit() else None
doc = {
    "format": 2,   # P3: persist.tsv, drainSeconds, backendMode, jvmArgs
    "runId": e["RJ_RUN_ID"], "gitSha": e["RJ_GIT_SHA"], "gitDirty": e["RJ_GIT_DIRTY"] == "1",
    "backendMode": e.get("BACKEND_MODE") or "unknown", "jvmArgs": e.get("BACKEND_JVM_ARGS") or "",
    "config": e["RJ_CONFIG"], "scenario": e["RJ_SCENARIO"],
    "eventId": num("RJ_EVENT_ID"), "productId": num("RJ_PRODUCT_ID"),
    "startedAt": e["RJ_STARTED_AT"], "finishedAt": e.get("RJ_FINISHED_AT") or None,
    "k6_rc": num("RJ_K6_RC"), "drain_rc": num("RJ_DRAIN_RC"), "drainSeconds": num("RJ_DRAIN_SECONDS"),
    "verify_ok": {"1": True, "0": False}.get(e.get("RJ_VERIFY_OK", ""), None),
    "verify_detail": e.get("RJ_VERIFY_DETAIL") or None,
    "exit_code": num("RJ_EXIT"),
    "params": json.loads(e["RJ_PARAMS"]),
    "environment": json.loads(e["RJ_ENV"]),
}
with open(sys.argv[1], "w") as f:
    json.dump(doc, f, indent=2)
    f.write("\n")
PY
}

git_dirty=0
[[ -z $(git -C "$REPO" status --porcelain -- . ':!loadtest/results') ]] || git_dirty=1
export RJ_RUN_ID=$run_id RJ_CONFIG=$config RJ_SCENARIO=$scenario RJ_STARTED_AT=$started_at
export RJ_GIT_SHA RJ_GIT_DIRTY=$git_dirty
RJ_GIT_SHA=$(git -C "$REPO" rev-parse HEAD)
RJ_PARAMS=$(python3 -c 'import json,sys; a=sys.argv[1:]; print(json.dumps(dict(zip(a[::2], a[1::2]))))' \
  base "$base" stock "$stock" buyers "${buyers:-}" vus "${vus:-}" rate "${rate:-}" ramp "${ramp:-}" \
  steady "${steady:-}" rejectStatus "$reject_status" drain "$drain" mysql "$mysql_target" \
  redis "$redis_target" userBase "$user_base" runSeq "$run_seq")
RJ_ENV=$(python3 -c 'import json,sys; a=sys.argv[1:]; print(json.dumps(dict(zip(a[::2], a[1::2]))))' \
  os "$(uname -sr)" arch "$(uname -m)" cpus "$(nproc 2>/dev/null || getconf _NPROCESSORS_ONLN)" \
  memTotalKb "$(awk '/MemTotal/{print $2}' /proc/meminfo 2>/dev/null || echo unknown)" \
  k6 "$k6_version" java "$(java -version 2>&1 | grep -m1 -E 'version' || echo unknown)" \
  mysql "$(mysql_target_cli "$mysql_target" --batch --skip-column-names -e 'SELECT VERSION()' 2>/dev/null || echo unknown)" \
  redis "$(redis_target_cli "$redis_target" INFO server 2>/dev/null | tr -d '\r' | awk -F: '/^redis_version/{print $2}')" \
  mysqlMode "${P0_MYSQL_MODE:-}")
export RJ_PARAMS RJ_ENV
write_run_json

# Step 2: a new event for every run.
setup_json=$(python3 "$REPO/loadtest/setup_event.py" --base "$base" --stock "$stock" --label "$run_id")
event_id=$(python3 -c 'import json,sys; print(json.loads(sys.argv[1])["eventId"])' "$setup_json")
product_id=$(python3 -c 'import json,sys; print(json.loads(sys.argv[1])["productId"])' "$setup_json")
export RJ_EVENT_ID=$event_id RJ_PRODUCT_ID=$product_id
write_run_json
echo "run.sh: event $event_id (product $product_id) is ACTIVE" >&2

# Step 3: k6.
k6_env=("BASE_URL=$base" "EVENT_ID=$event_id" "JWT_SECRET=$secret" "STOCK=$stock" "USER_BASE=$user_base"
        "REJECT_STATUS=$reject_status" "SUMMARY_PATH=$dir/summary.json" "RUN_ID=$run_id" "K6_VERSION=$k6_version")
if [[ $scenario == throughput ]]; then
  k6_env+=("RATE=$rate" "RAMP=$ramp" "STEADY=$steady")
else
  k6_env+=("BUYERS=$buyers" "VUS=$vus")
fi
k6_rc=0
env "${k6_env[@]}" "$K6" run --quiet --no-color "$REPO/loadtest/$scenario.js" > "$dir/k6.log" 2>&1 || k6_rc=$?
cat "$dir/k6.log" >&2
export RJ_K6_RC=$k6_rc

# Step 4: wait for the consumer to persist everything (drainSeconds = upper bound of persist latency).
drain_rc=0 drain_t0=$SECONDS
if [[ $drain == outbox ]]; then
  "$REPO/loadtest/drain.sh" outbox --event "$event_id" --mysql "$mysql_target" --timeout 180 || drain_rc=$?
else
  "$REPO/loadtest/drain.sh" "$drain" --redis "$redis_target" --timeout 120 || drain_rc=$?
fi
export RJ_DRAIN_RC=$drain_rc RJ_DRAIN_SECONDS=$(( SECONDS - drain_t0 ))

# Step 5: database and Redis evidence (P2 renamed the keys to seckill:<kind>:{<id>}).
if [[ $drain == stream ]]; then
  verify_sql=$REPO/loadtest/verify_legacy.sql stock_key="seckill:stock:$event_id" bought_key="seckill:bought:$event_id"
  event_column=ref_id
else
  verify_sql=$REPO/loadtest/verify_outbox.sql stock_key="seckill:stock:{$event_id}" bought_key="seckill:bought:{$event_id}"
  event_column=seckill_event_id
fi
{ echo "SET @event_id = $event_id;"; cat "$verify_sql"; } \
  | mysql_target_cli "$mysql_target" --batch > "$dir/verify.tsv"
{ echo "SET @event_id = $event_id;"; sed "s/__EVENT_COLUMN__/$event_column/" "$REPO/loadtest/persist.sql"; } \
  | mysql_target_cli "$mysql_target" --batch > "$dir/persist.tsv"
{
  printf 'key\tvalue\n'
  printf '%s\t%s\n' "$stock_key" "$(redis_target_cli "$redis_target" GET "$stock_key")"
  printf 'scard %s\t%s\n' "$bought_key" "$(redis_target_cli "$redis_target" SCARD "$bought_key")"
} > "$dir/redis.txt"

# Step 6: orders == buyers == accepted (whole test); contention also needs Redis stock 0.
verify_rc=0
verify_detail=$(python3 - "$dir" "$scenario" "$drain" <<'PY'
import csv, json, os, sys
d, scenario, drain = sys.argv[1], sys.argv[2], sys.argv[3]
problems = []
try:
    meta = json.load(open(os.path.join(d, "summary.json")))["meta"]
    accepted = int(meta["accepted"])
except Exception as e:
    print(f"summary.json unreadable: {e}"); sys.exit(1)
rows = list(csv.DictReader(open(os.path.join(d, "verify.tsv")), delimiter="\t"))
v = rows[0] if rows else {}
orders, buyers = int(v.get("orders", -1)), int(v.get("buyers", -1))
if not (orders == buyers == accepted):
    problems.append(f"orders={orders} buyers={buyers} accepted={accepted}")
if "sold_count" in v:   # verify_outbox.sql (P2+): MySQL-side counter and outbox must agree too
    if int(v["sold_count"]) != orders:
        problems.append(f"sold_count={v['sold_count']} orders={orders}")
    if int(v["outbox_new"]) != 0:
        problems.append(f"outbox_new={v['outbox_new']} (want 0)")
    if drain == "none" and int(v["outbox_total"]) != 0:   # sync mode never writes the outbox (P3 §6.4)
        problems.append(f"outbox_total={v['outbox_total']} (sync must not write the outbox)")
redis = dict(l.rstrip("\n").split("\t", 1) for l in open(os.path.join(d, "redis.txt")) if "\t" in l)
stock_key = next((k for k in redis if k.startswith("seckill:stock:")), None)
remaining = redis.get(stock_key, "")
if scenario == "contention" and remaining != "0":
    problems.append(f"redis remaining stock={remaining!r} (want 0)")
print("; ".join(problems) if problems else f"ok: orders={orders} buyers={buyers} accepted={accepted} redis_stock={remaining}")
sys.exit(1 if problems else 0)
PY
) || verify_rc=4
echo "run.sh: verify: $verify_detail" >&2
export RJ_VERIFY_OK=$(( verify_rc == 0 ? 1 : 0 )) RJ_VERIFY_DETAIL=$verify_detail

# Steps 7-8.
rc=$k6_rc
(( rc != 0 )) || rc=$drain_rc
(( rc != 0 )) || rc=$verify_rc
export RJ_FINISHED_AT RJ_EXIT=$rc
RJ_FINISHED_AT=$(date -u +%Y-%m-%dT%H:%M:%SZ)
write_run_json
echo "run.sh: $dir done: k6_rc=$k6_rc drain_rc=$drain_rc verify_ok=$RJ_VERIFY_OK exit=$rc" >&2
exit "$rc"
