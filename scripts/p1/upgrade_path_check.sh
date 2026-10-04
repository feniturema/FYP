#!/usr/bin/env bash
# P1 schema upgrade-path check (docs/phases/P1.md §6.5).
#
#   legacy: baseline jar (v0.4.2, Hibernate ddl-auto=update, seed on) -> P0 jar (Flyway baseline)
#           -> P1 jar, all on database ftsm_p1_legacy
#   fresh:  P1 jar only, on database ftsm_p1_fresh
#   then:   compare_schemas.sh ftsm_p1_fresh ftsm_p1_legacy  (evidence in scripts/db/evidence/p1/)
#
# Usage: P0_MYSQL_MODE=docker|local [P1_TMP=…] [P0_COMMIT=…] [P1_JAR=…] scripts/p1/upgrade_path_check.sh
#   P0_COMMIT defaults to `git merge-base HEAD origin/main` (main with P0 merged).
#   P1_JAR defaults to a fresh `./mvnw clean package -DskipTests` of the working tree.
# Exit code: that of the first failing step (each failure prints the last 80 log lines).
set -Eeuo pipefail

REPO=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
P1_TMP=${P1_TMP:-$(mktemp -d /tmp/ftsm-p1.XXXXXX)}
export P0_TMP=${P0_TMP:-$P1_TMP}
export JWT_SECRET=${JWT_SECRET:-p1-upgrade-check-secret-0123456789abcdef}
# shellcheck source=../db/lib.sh
source "$REPO/scripts/db/lib.sh"

EVIDENCE=$REPO/scripts/db/evidence/p1
BASELINE_REF=v0.4.2-baseline
P0_COMMIT=${P0_COMMIT:-$(git -C "$REPO" merge-base HEAD origin/main)}
PORT=8080
LEGACY=ftsm_p1_legacy
FRESH=ftsm_p1_fresh

worktrees=()
cleanup() {
  stop_backend
  local wt
  for wt in "${worktrees[@]}"; do git -C "$REPO" worktree remove --force "$wt" >/dev/null 2>&1 || true; done
}
trap cleanup EXIT

step() { echo "== $*" >&2; }

# build_jar REF NAME: builds REF in a throwaway worktree; sets BUILT_JAR (no subshell, so the
# worktree is registered for cleanup).
BUILT_JAR=
build_jar() {
  local ref=$1 name=$2 wt="$P1_TMP/wt-$2" log="$P1_TMP/build-$2.log" rc=0
  git -C "$REPO" worktree add --detach --force "$wt" "$ref" >/dev/null 2>&1 || { echo "worktree add $ref failed" >&2; return 1; }
  worktrees+=("$wt")
  (cd "$wt/backend" && "$REPO/backend/mvnw" -B -q -DskipTests package) > "$log" 2>&1 || rc=$?
  if (( rc != 0 )); then tail -n 80 "$log" >&2; return "$rc"; fi
  mkdir -p "$P1_TMP/jars"
  cp "$wt/backend/target/ecommerce-0.0.1-SNAPSHOT.jar" "$P1_TMP/jars/$name.jar"
  BUILT_JAR=$P1_TMP/jars/$name.jar
}

# boot JAR DB NAME: start, wait for health, stop. Returns start_backend's code.
boot() {
  local jar=$1 db=$2 name=$3 log="$P1_TMP/$2-$3.log" rc=0
  step "boot $name jar on $db"
  start_backend "$jar" "$db" "$PORT" "$log" || rc=$?
  stop_backend
  if (( rc != 0 )); then
    echo "upgrade_path_check: $name jar failed on $db (rc=$rc); last 80 lines of $log:" >&2
    tail -n 80 "$log" >&2
    return "$rc"
  fi
  grep -hE 'Successfully (applied|baselined|validated)|Migrating schema|is up to date|Schema-validation' "$log" \
    | sed -E 's/^.*(Flyway|DbMigrate|DbValidate|DbBaseline|JdbcTableSchemaHistory|SchemaHistory)[^:]*: */   /' >&2 || true
}

main() {
  mkdir -p "$EVIDENCE"
  step "build baseline jar ($BASELINE_REF) and P0 jar ($P0_COMMIT)"
  local baseline_jar p0_jar p1_jar
  build_jar "$BASELINE_REF" baseline; baseline_jar=$BUILT_JAR
  build_jar "$P0_COMMIT" p0; p0_jar=$BUILT_JAR
  if [[ -n ${P1_JAR:-} ]]; then
    p1_jar=$P1_JAR
  else
    step "build P1 jar (working tree)"
    (cd "$REPO/backend" && ./mvnw -B -q -DskipTests clean package) > "$P1_TMP/build-p1.log" 2>&1 \
      || { local rc=$?; tail -n 80 "$P1_TMP/build-p1.log" >&2; return "$rc"; }
    p1_jar=$REPO/backend/target/ecommerce-0.0.1-SNAPSHOT.jar
  fi

  step "start temporary MySQL / Redis ($P0_MYSQL_MODE)"
  start_temp_mysql
  start_temp_redis
  local db
  for db in "$LEGACY" "$FRESH"; do
    [[ $db == ftsm_p1_* ]] || { echo "refusing to drop $db" >&2; return 1; }
    mysql_cli -e "DROP DATABASE IF EXISTS \`$db\`"
  done

  boot "$baseline_jar" "$LEGACY" baseline
  boot "$p0_jar" "$LEGACY" p0
  boot "$p1_jar" "$LEGACY" p1
  boot "$p1_jar" "$FRESH" p1

  mysql_cli "$FRESH" < "$REPO/scripts/db/verify_schema.sql" > "$EVIDENCE/verify-fresh.txt"
  mysql_cli "$LEGACY" < "$REPO/scripts/db/verify_schema.sql" > "$EVIDENCE/verify-legacy.txt"
  step "flyway history (version|type|script|success)"
  echo "   fresh:  $(grep -E '^[0-9.]+\|' "$EVIDENCE/verify-fresh.txt" | paste -sd ' ')" >&2
  echo "   legacy: $(grep -E '^[0-9.]+\|' "$EVIDENCE/verify-legacy.txt" | paste -sd ' ')" >&2

  step "compare_schemas.sh $FRESH $LEGACY"
  EVIDENCE_DIR=$EVIDENCE "$REPO/scripts/db/compare_schemas.sh" "$FRESH" "$LEGACY"
}

main "$@"
