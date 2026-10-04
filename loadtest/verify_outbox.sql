-- Order reconciliation for one load-test run on P2+ code (--drain outbox; docs/phases/P2.md §9 A5/A12).
-- SECKILL orders are matched by seckill_event_id (added by V2); sold_count and the outbox are reported too.
-- Usage: mysql --batch -e "SET @event_id=<id>; source loadtest/verify_outbox.sql" <db>
-- Output (TSV with header): orders, buyers, paid, failed, pending, duplicate_buyers, sold_count,
--                           outbox_new, outbox_total
SELECT COUNT(*)                                    AS orders,
       COUNT(DISTINCT o.buyer_id)                  AS buyers,
       COALESCE(SUM(o.status = 'PAID'), 0)         AS paid,
       COALESCE(SUM(o.status = 'FAILED'), 0)       AS failed,
       COALESCE(SUM(o.status = 'PENDING'), 0)      AS pending,
       COUNT(*) - COUNT(DISTINCT o.buyer_id)       AS duplicate_buyers,
       (SELECT sold_count FROM seckill_events WHERE id = @event_id)                     AS sold_count,
       (SELECT COUNT(*) FROM order_outbox WHERE event_id = @event_id AND status = 0)    AS outbox_new,
       (SELECT COUNT(*) FROM order_outbox WHERE event_id = @event_id)                   AS outbox_total
  FROM orders o
 WHERE o.source_type = 'SECKILL'
   AND o.seckill_event_id = @event_id;
