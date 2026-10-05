#!/usr/bin/env bash
# P4b acceptance A4 -> A10 in the isolated compose project ftsm-p4b-acc (docs/phases/P4b.md §7.6, §9).
#
#   scripts/p4b/acceptance.sh   # optional env: P4B_TMP (reused if set), P4B_EVIDENCE_DIR (default scripts/p4b/evidence),
#                               # ACCEPTANCE_STEPS (subset, default "A4 A5 A6 A7 A8 A9 A10"), TOKEN (for runs without A4)
#
# Steps run in order and stop at the first failure (each later step depends on the earlier ones); the EXIT trap stops
# the background backend-log follower. Without A10 the stack is left running; tear it down with
#   docker compose -p ftsm-p4b-acc --env-file "$P4B_TMP/compose.env" down -v
# Evidence holds HTTP statuses, assertion results and redacted fields only: no password, token or key.
set -Eeuo pipefail

REPO=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
cd "$REPO"
# shellcheck source=../lib/wait.sh
source "$REPO/scripts/lib/wait.sh"

E=${P4B_EVIDENCE_DIR:-$REPO/scripts/p4b/evidence}
mkdir -p "$E"
P4B_TMP=${P4B_TMP:-$(mktemp -d /tmp/ftsm-p4b-acc.XXXXXX)}
export P4B_TMP
ENV_FILE=$P4B_TMP/compose.env
# Throwaway values for a one-off local project only (never reuse them in production).
[[ -f $ENV_FILE ]] || cat > "$ENV_FILE" <<EOF
DB_PASSWORD=p4b-acc-db
JWT_SECRET=p4b-acceptance-secret-0123456789abcdef0123
MYSQL_HOST_PORT=53306
REDIS_HOST_PORT=56379
KAFKA_HOST_PORT=59092
BACKEND_HOST_PORT=58080
FRONTEND_HOST_PORT=58081
MCP_HOST_PORT=58082
MCP_DB_PASSWORD=$(openssl rand -hex 24)
LLM_PROVIDER=none
SPRING_PROFILES_ACTIVE=dev
MAIL_ENABLED=false
SEED_ENABLED=true
SEED_ADMIN_EMAIL=p4b-admin@ukm.edu.my
SEED_ADMIN_PASSWORD=p4b-local-admin-password
EOF
DC=(docker compose -p ftsm-p4b-acc --env-file "$ENV_FILE")
BASE=http://127.0.0.1:58080
FRONT=http://127.0.0.1:58081
MCP=http://127.0.0.1:58082/mcp
MCP_DB_PASSWORD=$(sed -n 's/^MCP_DB_PASSWORD=//p' "$ENV_FILE")
STEPS=${ACCEPTANCE_STEPS:-A4 A5 A6 A7 A8 A9 A10}
SUMMARY=$E/acceptance-summary.txt
EXPECTED_MCP='["get_product_detail", "get_stock", "list_flash_sales", "search_products", "search_secondhand_items"]'
EXPECTED_ALL='["get_product_detail", "get_stock", "list_flash_sales", "my_orders", "search_products", "search_secondhand_items"]'

LOGPID=
start_log_follower() {   # backend log -> $P4B_TMP/backend.log (append; --since avoids duplicates after a restart)
  stop_log_follower
  if [[ -n ${1:-} ]]; then
    "${DC[@]}" logs --no-color -f --since "$1" backend >> "$P4B_TMP/backend.log" 2>&1 &
  else
    "${DC[@]}" logs --no-color -f backend >> "$P4B_TMP/backend.log" 2>&1 &
  fi
  LOGPID=$!
}
stop_log_follower() { [[ -z $LOGPID ]] || kill "$LOGPID" 2>/dev/null || true; LOGPID=; }
trap stop_log_follower EXIT
now_utc() { date -u +%Y-%m-%dT%H:%M:%SZ; }
log() { echo "[$(now_utc)] $*" | tee -a "$SUMMARY"; }
fail() { log "FAIL $*"; exit 1; }
has_step() { [[ " $STEPS " == *" $1 "* ]]; }

need_token() {
  if [[ -z ${TOKEN:-} ]]; then
    echo "TOKEN is not set: run A4 first, or export TOKEN=\"\$(python3 scripts/p4b/get_test_token.py --base $BASE --email p4b-admin@ukm.edu.my --password p4b-local-admin-password)\"" >&2
    exit 2
  fi
}
fetch_token() {
  TOKEN="$(python3 scripts/p4b/get_test_token.py --base "$BASE" \
    --email p4b-admin@ukm.edu.my --password p4b-local-admin-password)"
  test -n "$TOKEN" || fail "login returned an empty token"
  export TOKEN
}
debug_tools() {   # prints the sorted tool-name JSON array, or exits non-zero
  curl -fsS --max-time 30 -H "Authorization: Bearer $TOKEN" "$BASE/api/assistant/debug/tools" \
    | python3 -c 'import json,sys; print(json.dumps(sorted(json.load(sys.stdin))))'
}
# Every service with a healthcheck is healthy and every other one is running.
all_healthy() {
  "${DC[@]}" ps --format json | python3 -c '
import json, sys
rows = [json.loads(l) for l in sys.stdin.read().splitlines() if l.strip()]
if len(rows) == 1 and isinstance(rows[0], list):
    rows = rows[0]
ok = len(rows) >= 6 and all(r["State"] == "running" and r.get("Health") in ("", "healthy") for r in rows)
sys.exit(0 if ok else 1)'
}
ps_summary() {
  "${DC[@]}" ps --format json | python3 -c '
import json, sys
rows = [json.loads(l) for l in sys.stdin.read().splitlines() if l.strip()]
if len(rows) == 1 and isinstance(rows[0], list):
    rows = rows[0]
for r in sorted(rows, key=lambda r: r["Service"]):
    print(r["Service"], r["State"], r.get("Health") or "-")'
}
wait_backend() { wait_http "$BASE/actuator/health" "${1:-300}"; }
# `acceptance.sh __all_healthy`: wait_cmd needs an executable, not a shell function.
if [[ ${1:-} == __all_healthy ]]; then all_healthy; exit; fi

echo "== P4b acceptance $(now_utc) steps: $STEPS (P4B_TMP=$P4B_TMP)" >> "$SUMMARY"

# ---------- A4: the whole stack starts without a key; /api/chat answers "not configured" ----------
if has_step A4; then
  "${DC[@]}" up -d --build > "$P4B_TMP/a4-up.log" 2>&1 || fail "A4 compose up (see $P4B_TMP/a4-up.log)"
  start_log_follower
  wait_backend 300 >> "$P4B_TMP/a4-up.log" 2>&1 || fail "A4 backend health (see $P4B_TMP/a4-up.log)"
  wait_cmd 300 "all services healthy" -- "$0" __all_healthy >> "$P4B_TMP/a4-up.log" 2>&1 || true
  all_healthy || { ps_summary > "$E/a4.txt"; fail "A4 not every service is healthy"; }
  fetch_token
  reply=$(curl -fsS -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
    -d '{"message":"hi"}' "$BASE/api/chat" | python3 -c 'import json,sys; print(json.load(sys.stdin)["reply"])')
  {
    echo "# A4 $(now_utc)"; echo "## docker compose ps (service state health)"; ps_summary
    echo "## login: token non-empty = true (length ${#TOKEN})"
    echo "## POST /api/chat reply: $reply"
  } > "$E/a4.txt"
  [[ $reply == *"not configured"* ]] || fail "A4 reply does not contain 'not configured'"
  log "PASS A4"
fi

# ---------- A5: MCP protocol: exactly 5 tools; get_stock returns totalStock ----------
if has_step A5; then
  python3 scripts/p4b/mcp_probe.py --url "$MCP" --call get_stock '{"productId":1}' > "$E/a5.json" \
    || fail "A5 mcp_probe failed"
  python3 - "$E/a5.json" "$EXPECTED_MCP" <<'PY' || fail "A5 assertions"
import json, sys
out, expected = json.load(open(sys.argv[1])), json.loads(sys.argv[2])
assert out["tools"] == expected, out["tools"]
assert not out["call"]["isError"] and "totalStock" in out["call"]["text"], out["call"]
PY
  log "PASS A5"
fi

# ---------- A6: the backend discovers the MCP tools plus my_orders ----------
if has_step A6; then
  need_token
  tools=$(debug_tools) || fail "A6 debug/tools request"
  echo "$tools" > "$E/a6.json"
  [[ $tools == "$EXPECTED_ALL" ]] || fail "A6 tools = $tools"
  log "PASS A6"
fi

# ---------- A7: MCP outage and recovery ----------
if has_step A7; then
  need_token
  {
    echo "# A7 $(now_utc)"
    "${DC[@]}" stop mcp-server
    since=$(now_utc)
    "${DC[@]}" restart backend
    start_log_follower "$since"
    wait_backend 300
    echo "backend healthy after restart without mcp-server: $(curl -fsS "$BASE/actuator/health")"
    down_tools=$(debug_tools)
    echo "tools while mcp-server is stopped: $down_tools"
    sleep 2
    warn=$("${DC[@]}" logs --no-color --since "$since" backend 2>&1 | /usr/bin/grep -c 'MCP server unavailable' || true)
    echo "backend log lines 'MCP server unavailable' since restart: $warn"
    "${DC[@]}" start mcp-server
    t0=$SECONDS; up_tools=
    while (( SECONDS - t0 < 90 )); do
      up_tools=$(debug_tools || true)
      [[ $up_tools == "$EXPECTED_ALL" ]] && break
      sleep 5
    done
    echo "tools after mcp-server start (+$(( SECONDS - t0 ))s): $up_tools"
  } > "$E/a7.txt" 2>&1 || fail "A7 command failed (see $E/a7.txt)"
  [[ $down_tools == '["my_orders"]' ]] || fail "A7 tools while down = $down_tools"
  (( warn >= 1 )) || fail "A7 no 'MCP server unavailable' log line"
  [[ $up_tools == "$EXPECTED_ALL" ]] || fail "A7 tools not restored within 90 s: $up_tools"
  log "PASS A7"
fi

# ---------- A8: streaming through nginx is not buffered (dev fake model) ----------
if has_step A8; then
  need_token
  /usr/bin/grep -q '^APP_ASSISTANT_FAKEMODEL=true$' "$ENV_FILE" || echo 'APP_ASSISTANT_FAKEMODEL=true' >> "$ENV_FILE"
  since=$(now_utc)
  # Recreate: a plain restart would keep the old environment.
  "${DC[@]}" up -d --no-deps backend > "$P4B_TMP/a8-up.log" 2>&1 || fail "A8 backend recreate (see $P4B_TMP/a8-up.log)"
  start_log_follower "$since"
  wait_backend 300 >> "$P4B_TMP/a8-up.log" 2>&1 || fail "A8 backend health (see $P4B_TMP/a8-up.log)"
  python3 scripts/p4b/sse_timing.py --url "$FRONT/api/assistant/stream" --token "$TOKEN" > "$E/a8.json" \
    || fail "A8 sse_timing: $(cat "$E/a8.json")"
  python3 - "$E/a8.json" <<'PY' || fail "A8 assertions: $(cat "$E/a8.json")"
import json, sys
o = json.load(open(sys.argv[1]))
assert o["count"] == 4 and o["done"], o
assert o["firstMs"] <= 500 and o["lastMs"] >= 900, o
PY
  log "PASS A8"
fi

# ---------- A9: the read-only account cannot write; a bad password is rejected by the init script ----------
if has_step A9; then
  set +e
  denied=$("${DC[@]}" exec -T mysql mysql -uftsm_ro -p"$MCP_DB_PASSWORD" ftsm_ecommerce \
    -e "DELETE FROM products WHERE id=-1" 2>&1 < /dev/null)
  denied_rc=$?
  "${DC[@]}" exec -T -e MCP_DB_PASSWORD="bad'pass" mysql bash /docker-entrypoint-initdb.d/01-readonly-user.sh \
    > "$P4B_TMP/a9-bad.log" 2>&1 < /dev/null
  bad_rc=$?
  set -e
  {
    echo "# A9 $(now_utc)"
    echo "DELETE as ftsm_ro: rc=$denied_rc"
    echo "$denied" | /usr/bin/grep -v 'Using a password on the command line' | sed "s/$MCP_DB_PASSWORD/<redacted>/g"
    echo "init script with MCP_DB_PASSWORD=\"bad'pass\": rc=$bad_rc"
    sed "s/$MCP_DB_PASSWORD/<redacted>/g" "$P4B_TMP/a9-bad.log"
  } > "$E/a9.txt"
  [[ $denied_rc -ne 0 && $denied == *"DELETE command denied"* ]] || fail "A9 DELETE was not denied"
  [[ $bad_rc -eq 1 ]] || fail "A9 bad password rc=$bad_rc (want 1)"
  log "PASS A9"
fi

# ---------- A10: cleanup of this project only ----------
if has_step A10; then
  stop_log_follower
  "${DC[@]}" down -v > "$P4B_TMP/a10-down.log" 2>&1 || fail "A10 down -v"
  log "PASS A10 (backend log kept at $P4B_TMP/backend.log)"
fi
