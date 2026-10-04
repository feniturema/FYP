-- Human-readable schema check (docs/phases/P0.md §9 A7).
-- Usage: mysql_cli <db> < scripts/db/verify_schema.sql
-- Run with the default (tabular) or --batch output; each block is labelled.

SELECT '== tables' AS section;
SELECT table_name, engine, table_collation
  FROM information_schema.tables
 WHERE table_schema = DATABASE()
 ORDER BY table_name;

SELECT '== enum columns' AS section;
SELECT table_name, column_name, column_type
  FROM information_schema.columns
 WHERE table_schema = DATABASE() AND data_type = 'enum'
 ORDER BY table_name, column_name;

SELECT '== indexes' AS section;
SELECT table_name, index_name, non_unique, GROUP_CONCAT(column_name ORDER BY seq_in_index) AS columns
  FROM information_schema.statistics
 WHERE table_schema = DATABASE() AND table_name <> 'flyway_schema_history'
 GROUP BY table_name, index_name, non_unique
 ORDER BY table_name, index_name;

SELECT '== key columns' AS section;
SELECT table_name, column_name, column_type, is_nullable
  FROM information_schema.columns
 WHERE table_schema = DATABASE()
   AND (table_name, column_name) IN (('items', 'item_condition'), ('orders', 'ref_id'), ('orders', 'source_type'),
                                     ('orders', 'tracking_token'), ('seckill_events', 'stock_warmed'),
                                     ('users', 'email'))
 ORDER BY table_name, column_name;

SELECT '== row counts' AS section;
SELECT 'users' AS t, COUNT(*) AS n FROM users UNION ALL
SELECT 'products', COUNT(*) FROM products UNION ALL
SELECT 'seckill_events', COUNT(*) FROM seckill_events UNION ALL
SELECT 'orders', COUNT(*) FROM orders;

SELECT '== flyway_schema_history (version|type|script|success)' AS section;
SELECT CONCAT_WS('|', version, type, script, success) AS history
  FROM flyway_schema_history
 ORDER BY installed_rank;
