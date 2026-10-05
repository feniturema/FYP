#!/usr/bin/env bash
# One benchmark run of one configuration (docs/phases/P3.md §6.4, §7.2):
#   loadtest/bench/run_config.sh A|B|C <throughput|contention> <run.sh args...>
#     A  v0.4.2-baseline jar        --reject-status 200 --drain stream  db ftsm_bench_a  results <out-root>/A-baseline
#     B  current jar, SECKILL_MODE=sync   409 --drain none   db ftsm_bench_b  results <out-root>/B-sync
#     C  current jar, default async       409 --drain outbox db ftsm_bench_c  results <out-root>/C-async
# Needs infra_up.sh first. The backend runs on the host (port 8080) with the same JVM options for all
# configs. A warm-up throughput run (rate 50, 5 s ramp, 10 s steady) goes to <out-root>/<config>/warmup
# and never counts. Afterwards: stop the backend, and only when no k6 is running and the drain
# succeeded, reset the seckill data (loadtest/reset.sh). Exit code = the measured run.sh's exit code;
# 2 = usage / backend did not start.
set -Eeuo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/common.sh"

cfg=${1:-} scenario=${2:-}
[[ $cfg =~ ^[ABC]$ && ( $scenario == throughput || $scenario == contention ) ]] \
  || { sed -n '2,12p' "${BASH_SOURCE[0]}" >&2; exit 2; }
shift 2
out_root=loadtest/results
for ((i = 1; i <= $#; i++)); do
  [[ ${!i} == --out-root ]] && { j=$((i + 1)); out_root=${!j}; }
done

JVM_ARGS="-Xms2g -Xmx2g -XX:+UseG1GC"
case $cfg in
  A) name=A-baseline mode=baseline-v0.4.2 reject=200 drain=stream db=ftsm_bench_a ;;
  B) name=B-sync     mode=sync            reject=409 drain=none   db=ftsm_bench_b ;;
  C) name=C-async    mode=async           reject=409 drain=outbox db=ftsm_bench_c ;;
esac
[[ -f $BENCH_ENV ]] || { echo "run_config: $BENCH_ENV missing; run loadtest/bench/infra_up.sh first" >&2; exit 2; }
DB_PASSWORD=$(sed -n 's/^DB_PASSWORD=//p' "$BENCH_ENV")
JWT_SECRET=$(sed -n 's/^JWT_SECRET=//p' "$BENCH_ENV")
export DB_PASSWORD JWT_SECRET
export BACKEND_DB_PORT=33306 BACKEND_REDIS_PORT=36379 BACKEND_KAFKA=127.0.0.1:39092
export BACKEND_JVM_ARGS=$JVM_ARGS BACKEND_MODE=$mode
mysql_t=$(bench_target_mysql "$db") redis_t=$(bench_target_redis)
cd "$BENCH_REPO"

# 1. the jar under test
if [[ $cfg == A ]]; then
  base=$BENCH_TMP/baseline
  jar=$base/backend/target/ecommerce-0.0.1-SNAPSHOT.jar
  if [[ ! -f $jar ]]; then
    rm -rf "$base"
    git -C "$BENCH_REPO" worktree add --detach --force "$base" v0.4.2-baseline >/dev/null
    # This checkout's root Wrapper (reads .mvn/ next to itself); the baseline tag has no Wrapper of its own.
    if [[ -f "$base/pom.xml" ]]; then
      (cd "$base" && "$BENCH_REPO/mvnw" -B -q -pl backend -am -DskipTests package) > "$BENCH_TMP/build-A.log" 2>&1
    else
      (cd "$base/backend" && "$BENCH_REPO/mvnw" -B -q -DskipTests package) > "$BENCH_TMP/build-A.log" 2>&1
    fi || { echo "run_config: baseline build failed (see $BENCH_TMP/build-A.log)" >&2; exit 2; }
  fi
  unset SECKILL_MODE
else
  (cd "$BENCH_REPO" && ./mvnw -B -q -pl backend -am -DskipTests package) > "$BENCH_TMP/build-$cfg.log" 2>&1 \
    || { echo "run_config: build failed (see $BENCH_TMP/build-$cfg.log)" >&2; exit 2; }
  jar=$BENCH_REPO/backend/target/ecommerce-0.0.1-SNAPSHOT.jar
  if [[ $cfg == B ]]; then export SECKILL_MODE=sync; else unset SECKILL_MODE; fi
fi

# 2-3. backend on the host against the config's own database (created on first start)
log=$BENCH_TMP/backend-$name-$(date -u +%Y%m%dT%H%M%SZ).log
stop() { stop_backend; rm -f "$BENCH_TMP/backend.pid"; }
trap stop EXIT
if ! start_backend "$jar" "$db" 8080 "$log"; then
  echo "run_config: backend $name did not become healthy (log $log)" >&2; exit 2
fi
echo "$BACKEND_PID" > "$BENCH_TMP/backend.pid"
echo "run_config: $name backend up (pid $BACKEND_PID, $mode, $JVM_ARGS)" >&2

common=(--reject-status "$reject" --drain "$drain" --mysql "$mysql_t" --redis "$redis_t" --base http://127.0.0.1:8080)
# 4. warm-up (not counted)
warm_rc=0
loadtest/run.sh --config warmup --scenario throughput --rate 50 --ramp 5 --steady 10 --stock 100000 \
  "${common[@]}" --out-root "$out_root/$name" || warm_rc=$?
echo "run_config: warm-up rc=$warm_rc (not counted)" >&2

# 5. the measured run (remember which run dirs existed, so step 7 only looks at the one created now)
before=$(ls -d "$out_root/$name"/*-"$name"-"$scenario" 2>/dev/null || true)
rc=0
loadtest/run.sh --config "$name" --scenario "$scenario" "$@" "${common[@]}" || rc=$?

# 6. stop the backend
stop; trap - EXIT

# 7. reset only when nothing can still be consuming: no k6 process, and the drain of the run succeeded
run_dir=$(comm -13 <(printf '%s\n' "$before" | sort) <(ls -d "$out_root/$name"/*-"$name"-"$scenario" 2>/dev/null | sort) | tail -n 1)
drain_rc=$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1])).get("drain_rc"))' "$run_dir/run.json" 2>/dev/null || echo none)
if [[ -z $run_dir ]]; then
  echo "run_config: run.sh created no run directory; nothing to reset" >&2
elif pgrep -f 'k6[^ ]* run' >/dev/null; then
  echo "run_config: a k6 process is still running; NOT resetting" >&2
elif [[ $drain_rc != 0 ]]; then
  echo "run_config: drain of $run_dir did not succeed (drain_rc=$drain_rc); NOT resetting" >&2
else
  CONFIRM_RESET=yes loadtest/reset.sh --mysql "$mysql_t" --redis "$redis_t"
fi
echo "run_config: $name $scenario done: run.sh rc=$rc ($run_dir)" >&2
exit "$rc"
