#!/usr/bin/env bash
# Compare the schema fingerprints of two databases (docs/phases/P0.md §6.5).
# Usage: P0_MYSQL_MODE=… P0_TMP=… scripts/db/compare_schemas.sh DB_A DB_B
# Writes scripts/db/evidence/p0/<db>.fingerprint.tsv and diff-<A>-<B>.txt.
# Exit 0 = identical, 1 = differences (see the diff file), other = error.
set -Eeuo pipefail

REPO=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
EVIDENCE=${EVIDENCE_DIR:-$REPO/scripts/db/evidence/p0}

[[ $# -eq 2 && -n $1 && -n $2 ]] || { echo "usage: $0 DB_A DB_B" >&2; exit 2; }
a=$1 b=$2
mkdir -p "$EVIDENCE"
"$REPO/scripts/db/schema_fingerprint.sh" "$a" > "$EVIDENCE/$a.fingerprint.tsv"
"$REPO/scripts/db/schema_fingerprint.sh" "$b" > "$EVIDENCE/$b.fingerprint.tsv"

rc=0
(cd "$EVIDENCE" && diff -u "$a.fingerprint.tsv" "$b.fingerprint.tsv") > "$EVIDENCE/diff-$a-$b.txt" || rc=$?
case $rc in
  0) echo "compare_schemas: $a and $b are structurally identical" >&2 ;;
  1) echo "compare_schemas: $a and $b differ; see $EVIDENCE/diff-$a-$b.txt" >&2 ;;
  *) echo "compare_schemas: diff failed (rc=$rc)" >&2 ;;
esac
exit "$rc"
