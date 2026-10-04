-- Order reconciliation for one load-test run (docs/phases/P0.md §6.7 step 5).
-- The baseline schema has no seckill_event_id column, so SECKILL orders are matched by ref_id.
-- Usage: mysql --batch -e "SET @event_id=<id>; source loadtest/verify.sql" <db>
-- Output (TSV with header): orders, buyers, paid, failed, pending, duplicate_buyers
SELECT COUNT(*)                                    AS orders,
       COUNT(DISTINCT buyer_id)                    AS buyers,
       COALESCE(SUM(status = 'PAID'), 0)           AS paid,
       COALESCE(SUM(status = 'FAILED'), 0)         AS failed,
       COALESCE(SUM(status = 'PENDING'), 0)        AS pending,
       COUNT(*) - COUNT(DISTINCT buyer_id)         AS duplicate_buyers
  FROM orders
 WHERE source_type = 'SECKILL'
   AND ref_id = @event_id;
