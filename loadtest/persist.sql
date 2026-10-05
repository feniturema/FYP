-- Final persistence for one load-test run (docs/phases/P3.md §6.3): orders of the event and the span
-- between the first and the last order, for persistedRps = orders / span_s.
-- __EVENT_COLUMN__ is replaced by run.sh: `ref_id` for pre-P2 code (--drain stream), `seckill_event_id`
-- otherwise (the column only exists from V2).
-- Usage: mysql --batch -e "SET @event_id=<id>; <this file with the column substituted>" <db>
SELECT COUNT(*) AS orders,
       TIMESTAMPDIFF(MICROSECOND, MIN(created_at), MAX(created_at)) / 1e6 AS span_s
FROM orders WHERE source_type = 'SECKILL' AND __EVENT_COLUMN__ = @event_id;
