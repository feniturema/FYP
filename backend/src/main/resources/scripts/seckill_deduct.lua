-- Atomic SecKill stock deduction with per-user duplicate-purchase guard.
--
-- KEYS[1] = seckill:stock:{eventId}   (string, integer remaining stock)
-- KEYS[2] = seckill:bought:{eventId}  (set of userIds who already succeeded)
-- ARGV[1] = userId
--
-- Returns:
--    1  -> success (stock decremented, user recorded)
--    0  -> sold out
--   -1  -> user already bought
--   -2  -> stock not initialised (event not warmed / unknown)

local stock = redis.call('GET', KEYS[1])
if stock == false then
    return -2
end

if redis.call('SISMEMBER', KEYS[2], ARGV[1]) == 1 then
    return -1
end

if tonumber(stock) <= 0 then
    return 0
end

redis.call('DECR', KEYS[1])
redis.call('SADD', KEYS[2], ARGV[1])
return 1
