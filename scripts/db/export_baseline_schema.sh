#!/usr/bin/env bash
# Export backend/src/main/resources/db/migration/V1__baseline.sql from the schema that the
# baseline jar (v0.4.2, Hibernate ddl-auto=update) creates on a real MySQL 8.0.x.
#
# Usage: BASELINE_JAR=… P0_MYSQL_MODE=… P0_TMP=… scripts/db/export_baseline_schema.sh [--force-overwrite-untracked]
# Exit codes: 1 schema assertion failed, 3 V1 already merged (never overwritten),
#             4 V1 exists uncommitted (needs --force-overwrite-untracked), other = backend wait code.
# See docs/phases/P0.md §6.4.
set -Eeuo pipefail

REPO=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
# shellcheck source=lib.sh
source "$REPO/scripts/db/lib.sh"

DB=ftsm_p0_export
REL_OUT=backend/src/main/resources/db/migration/V1__baseline.sql
OUT=$REPO/$REL_OUT
EXPORT_PORT=18080
EXPECTED_TABLES="items orders products reviews seckill_events users"

force=0
case ${1:-} in
  "") ;;
  --force-overwrite-untracked) force=1 ;;
  *) echo "usage: $0 [--force-overwrite-untracked]" >&2; exit 2 ;;
esac

tmp=
pid=
cleanup() {
  if [[ -n $pid ]] && kill -0 "$pid" 2>/dev/null; then kill "$pid" 2>/dev/null || true; wait "$pid" 2>/dev/null || true; fi
  [[ -z $tmp ]] || rm -rf "$tmp"
}
trap cleanup EXIT

main() {
  : "${BASELINE_JAR:?BASELINE_JAR is required}" "${P0_MYSQL_MODE:?P0_MYSQL_MODE is required}" "${P0_TMP:?P0_TMP is required}"
  [[ -f $BASELINE_JAR ]] || { echo "export: BASELINE_JAR not found: $BASELINE_JAR" >&2; return 2; }
  tmp=$(mktemp -d -p "$P0_TMP")

  # Output protection: a merged V1 is immutable; an uncommitted one needs an explicit flag.
  if git -C "$REPO" cat-file -e "origin/main:$REL_OUT" 2>/dev/null; then
    echo "export: $REL_OUT is already merged on origin/main; refusing to overwrite" >&2
    return 3
  fi
  if [[ -e $OUT ]] && (( ! force )); then
    echo "export: $REL_OUT exists but is not merged; pass --force-overwrite-untracked to regenerate" >&2
    return 4
  fi

  # Recreate the scratch database. Only ftsm_p0_* databases may ever be dropped.
  [[ $DB == ftsm_p0_* ]] || { echo "export: refusing to drop non-P0 database $DB" >&2; return 1; }
  mysql_cli -e "DROP DATABASE IF EXISTS \`$DB\`; CREATE DATABASE \`$DB\`;"

  local log="$P0_TMP/export-backend.log" rc=0
  local -a envs=(
    "DB_URL=jdbc:mysql://127.0.0.1:3307/$DB?createDatabaseIfNotExist=true&useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC"
    DB_USERNAME=root "DB_PASSWORD=$DB_PASSWORD" REDIS_HOST=127.0.0.1 REDIS_PORT=6380
    SEED_ENABLED=false "SERVER_PORT=$EXPORT_PORT" MAIL_ENABLED=false LLM_API_KEY=
  )
  env "${envs[@]}" java -jar "$BASELINE_JAR" > "$log" 2>&1 &
  pid=$!
  wait_http "http://127.0.0.1:$EXPORT_PORT/actuator/health" 180 "$pid" "$log" || rc=$?
  kill "$pid" 2>/dev/null || true
  wait "$pid" 2>/dev/null || true
  pid=
  (( rc == 0 )) || { echo "export: baseline backend did not become healthy (rc=$rc)" >&2; return "$rc"; }

  mysqldump_cli --no-data --compact --skip-add-drop-table --skip-comments --skip-set-charset "$DB" > "$tmp/raw.sql"
  local version
  version=$(mysql_cli --batch --skip-column-names -e 'SELECT VERSION()')

  python3 - "$tmp/raw.sql" "$tmp/V1.sql" "$version" "$EXPECTED_TABLES" <<'PY'
import re, sys
raw, out, version, expected = sys.argv[1], sys.argv[2], sys.argv[3], set(sys.argv[4].split())
lines = []
for line in open(raw, encoding="utf-8").read().splitlines():
    if line.startswith("/*!") or line.startswith("SET "):
        continue
    lines.append(re.sub(r" AUTO_INCREMENT=\d+", "", line))
body = "\n".join(lines).strip() + "\n"
tables = re.findall(r"^CREATE TABLE `([^`]+)`", body, flags=re.M)
if len(tables) != 6 or set(tables) != expected:
    sys.exit(f"export: expected exactly 6 tables {sorted(expected)}, got {tables}")
header = (f"-- V1 baseline: exported from Hibernate DDL at 5f5fae4 (v0.4.2) on MySQL {version}. "
          "DO NOT EDIT after merge.\n")
with open(out, "w", encoding="utf-8") as f:
    f.write(header + body)
PY
  mkdir -p "$(dirname "$OUT")"
  # Atomic replace: write next to the target, then rename.
  local staged
  staged=$(mktemp -p "$(dirname "$OUT")" .V1.XXXXXX)
  cp "$tmp/V1.sql" "$staged"
  chmod 0644 "$staged"
  mv -f "$staged" "$OUT"
  echo "export: wrote $REL_OUT ($(grep -c '^CREATE TABLE' "$OUT") tables, MySQL $version)" >&2
}

main "$@"
