#!/usr/bin/env bash
# Print a normalised schema fingerprint (TSV) of database DB to stdout (docs/phases/P0.md §6.5).
# Usage: P0_MYSQL_MODE=… P0_TMP=… scripts/db/schema_fingerprint.sh DB
# flyway_schema_history is excluded so a migrated and a baselined database compare equal.
set -Eeuo pipefail

REPO=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
# shellcheck source=lib.sh
source "$REPO/scripts/db/lib.sh"

[[ $# -eq 1 && -n $1 ]] || { echo "usage: $0 DB" >&2; exit 2; }
db=$1

mysql_cli --batch --skip-column-names "$db" <<'SQL'
SELECT 'T', table_name, engine, table_collation, create_options
  FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name<>'flyway_schema_history' ORDER BY 2;
SELECT 'C', table_name, ordinal_position, column_name, column_type, is_nullable, IFNULL(column_default,'<NULL>'),
       extra, IFNULL(character_set_name,'-'), IFNULL(collation_name,'-')
  FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name<>'flyway_schema_history' ORDER BY 2,3;
SELECT 'I', table_name, index_name, non_unique, seq_in_index, column_name, IFNULL(sub_part,'-'), index_type
  FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name<>'flyway_schema_history' ORDER BY 2,3,5;
SELECT 'K', tc.table_name, tc.constraint_name, tc.constraint_type
  FROM information_schema.table_constraints tc WHERE tc.table_schema=DATABASE() AND tc.table_name<>'flyway_schema_history' ORDER BY 2,3;
SELECT 'CK', cc.constraint_name, cc.check_clause
  FROM information_schema.check_constraints cc WHERE cc.constraint_schema=DATABASE() ORDER BY 2;
SQL
