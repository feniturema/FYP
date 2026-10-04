package my.edu.ukm.ftsm.ecommerce.utils;

/** Centralised Redis key naming so producers/consumers/scripts stay in sync. */
public final class RedisKeys {

    private RedisKeys() {}

    /**
     * Remaining SecKill stock. The braces are a Redis Cluster hash tag: the stock and bought keys
     * of one event always hash to the same slot, so the Lua scripts can touch both atomically.
     */
    public static String seckillStock(Long eventId) {
        return "seckill:stock:{" + eventId + "}";
    }

    /** Set of userIds that already secured a unit of this event (same hash tag as the stock key). */
    public static String seckillBought(Long eventId) {
        return "seckill:bought:{" + eventId + "}";
    }

    public static String otp(String email) {
        return "otp:" + email.toLowerCase();
    }
}
