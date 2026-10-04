-- P2 (v0.7.0): transactional outbox for SecKill purchase intents, per-buyer/event uniqueness,
-- MySQL-side sold counter and reconciliation flag (docs/phases/P2.md §6.3).
-- MySQL DDL is not transactional: run scripts/p2/precheck_cutover.py and take a backup first.
-- Immutable once merged; the next migration is V3 (P5a).

CREATE TABLE order_outbox (
  id          BIGINT AUTO_INCREMENT PRIMARY KEY,
  order_id    CHAR(36)    NOT NULL,
  user_id     BIGINT      NOT NULL,
  event_id    BIGINT      NOT NULL,
  payload     JSON        NOT NULL,
  status      TINYINT     NOT NULL DEFAULT 0,          -- 0 NEW, 1 SENT
  created_at  DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  sent_at     DATETIME(3) NULL,
  UNIQUE KEY uk_outbox_order_id (order_id),
  KEY idx_outbox_status_id (status, id),
  KEY idx_outbox_event_status (event_id, status)
);

-- Historical SecKill orders: backfill seckill_event_id from ref_id.
ALTER TABLE orders ADD COLUMN seckill_event_id BIGINT NULL;
UPDATE orders SET seckill_event_id = ref_id WHERE source_type = 'SECKILL';
ALTER TABLE orders ADD UNIQUE KEY uk_orders_buyer_seckill (buyer_id, seckill_event_id);

-- Historical events: sold_count from order count; ended events count as reconciled;
-- events that have not started are re-warmed under the new hash-tagged Redis keys.
ALTER TABLE seckill_events
  ADD COLUMN sold_count INT NOT NULL DEFAULT 0,
  ADD COLUMN reconciled BIT(1) NOT NULL DEFAULT b'0';
UPDATE seckill_events e
   SET sold_count = (SELECT COUNT(*) FROM orders o WHERE o.source_type = 'SECKILL' AND o.ref_id = e.id);
UPDATE seckill_events SET reconciled = b'1' WHERE status = 'ENDED' OR end_time < CURRENT_TIMESTAMP(6);
UPDATE seckill_events SET stock_warmed = b'0' WHERE status = 'PENDING' AND start_time > CURRENT_TIMESTAMP(6);
