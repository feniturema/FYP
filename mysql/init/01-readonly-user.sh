#!/bin/bash
# Creates the read-only MySQL account used by mcp-server (docs/phases/P4b.md §6.8).
# Runs automatically only when the mysql data volume is first initialised. For an existing volume run once:
#   docker compose exec mysql bash /docker-entrypoint-initdb.d/01-readonly-user.sh
# The character whitelist keeps the password inside the SQL string literal; the root password is passed
# through MYSQL_PWD so it never appears in the process arguments.
set -euo pipefail
: "${MCP_DB_PASSWORD:?required}"
[[ "$MCP_DB_PASSWORD" =~ ^[A-Za-z0-9._~-]{24,128}$ ]] || { echo "MCP_DB_PASSWORD must match ^[A-Za-z0-9._~-]{24,128}\$" >&2; exit 1; }
[[ "$MYSQL_DATABASE" =~ ^[A-Za-z0-9_]{1,64}$ ]] || { echo "bad MYSQL_DATABASE" >&2; exit 1; }
MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql -uroot <<SQL
CREATE USER IF NOT EXISTS 'ftsm_ro'@'%' IDENTIFIED BY '${MCP_DB_PASSWORD}';
ALTER USER 'ftsm_ro'@'%' IDENTIFIED BY '${MCP_DB_PASSWORD}';
GRANT SELECT ON \`${MYSQL_DATABASE}\`.* TO 'ftsm_ro'@'%';
SQL
