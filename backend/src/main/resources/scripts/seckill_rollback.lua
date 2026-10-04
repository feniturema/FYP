-- Compensation for a SecKill deduction whose outbox INSERT is known NOT to have been written
-- (docs/phases/P2.md §6.1 / §6.4).
-- KEYS[1]=stock KEYS[2]=bought ARGV[1]=userId ; idempotent: INCR only if SREM removed the user
if redis.call('SREM', KEYS[2], ARGV[1]) == 1 then redis.call('INCR', KEYS[1]); return 1 end
return 0
