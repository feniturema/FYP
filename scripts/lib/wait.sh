# shellcheck shell=bash
# Readiness helpers shared by every upgrade phase (docs/phases/P0.md §6.1).
# Source it:  source scripts/lib/wait.sh
# Self-test:  bash scripts/lib/wait.sh

# wait_http URL TIMEOUT_S [PID] [LOG]
# return 0 = ready, 1 = timeout, 2 = watched process exited. Prints last 80 log lines on failure.
wait_http() {
  local url=$1 timeout=$2 pid=${3:-} log=${4:-}
  local deadline=$(( SECONDS + timeout ))
  while (( SECONDS < deadline )); do
    if [[ -n $pid ]] && ! kill -0 "$pid" 2>/dev/null; then
      echo "wait_http: process $pid exited before $url was ready" >&2
      [[ -n $log && -f $log ]] && tail -n 80 "$log" >&2
      return 2
    fi
    if curl -fsS -o /dev/null --max-time 5 "$url"; then return 0; fi
    sleep 2
  done
  echo "wait_http: timeout after ${timeout}s waiting for $url" >&2
  [[ -n $log && -f $log ]] && tail -n 80 "$log" >&2
  return 1
}

# wait_cmd TIMEOUT_S DESCRIPTION -- CMD [ARGS...]   (retries CMD until exit 0)
wait_cmd() {
  local timeout=$1 desc=$2; shift 2
  [[ ${1:-} == -- ]] || { echo "wait_cmd: missing -- before command: $desc" >&2; return 2; }
  shift
  (( timeout > 0 )) || { echo "wait_cmd: timeout must be positive: $desc" >&2; return 2; }
  (( $# > 0 )) || { echo "wait_cmd: empty command: $desc" >&2; return 2; }
  local deadline=$(( SECONDS + timeout ))
  while :; do
    local remaining=$(( deadline - SECONDS ))
    (( remaining > 0 )) || { echo "wait_cmd: timeout after ${timeout}s: $desc" >&2; return 1; }
    # A retry must not block past the overall deadline. `timeout` is required
    # because mysqladmin/docker commands can otherwise hang indefinitely.
    if timeout "$remaining" "$@" >/dev/null 2>&1; then return 0; fi
    remaining=$(( deadline - SECONDS ))
    (( remaining > 0 )) || { echo "wait_cmd: timeout after ${timeout}s: $desc" >&2; return 1; }
    sleep "$(( remaining < 2 ? remaining : 2 ))"
  done
}

# Self-test when executed directly (docs/phases/P0.md §8). No side effects.
if [[ ${BASH_SOURCE[0]} == "$0" ]]; then
  set -u
  fail=0
  check() { if [[ $2 == "$3" ]]; then echo "ok   $1 (rc=$2)"; else echo "FAIL $1 (rc=$2, want $3)"; fail=1; fi; }

  s=$SECONDS; wait_http http://127.0.0.1:9/ 5 2>/dev/null; rc=$?; e=$(( SECONDS - s ))
  check "wait_http timeout" "$rc" 1
  (( e >= 5 && e < 10 )) && echo "ok   wait_http elapsed ${e}s" || { echo "FAIL wait_http elapsed ${e}s"; fail=1; }

  sh -c 'exit 0' & dead=$!; wait "$dead"
  wait_http http://127.0.0.1:9/ 5 "$dead" 2>/dev/null; check "wait_http exited pid" "$?" 2

  s=$SECONDS; wait_cmd 2 blocked -- sh -c "sleep 30" 2>/dev/null; rc=$?; e=$(( SECONDS - s ))
  check "wait_cmd blocked" "$rc" 1
  (( e >= 2 && e < 5 )) && echo "ok   wait_cmd elapsed ${e}s" || { echo "FAIL wait_cmd elapsed ${e}s"; fail=1; }

  wait_cmd 5 ok -- true; check "wait_cmd success" "$?" 0
  wait_cmd 2 malformed 2>/dev/null; check "wait_cmd missing --" "$?" 2
  wait_cmd 2 empty -- 2>/dev/null; check "wait_cmd empty command" "$?" 2
  wait_cmd 0 zero -- true 2>/dev/null; check "wait_cmd zero timeout" "$?" 2
  wait_cmd -1 negative -- true 2>/dev/null; check "wait_cmd negative timeout" "$?" 2
  exit "$fail"
fi
