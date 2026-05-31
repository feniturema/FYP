package my.edu.ukm.ftsm.ecommerce.utils;

/** Centralised Redis key naming so producers/consumers/scripts stay in sync. */
public final class RedisKeys {

    private RedisKeys() {}

    public static String seckillStock(Long eventId) {
        return "seckill:stock:" + eventId;
    }

    public static String seckillBought(Long eventId) {
        return "seckill:bought:" + eventId;
    }

    /** Redis Stream key carrying confirmed seckill orders awaiting persistence. */
    public static String seckillOrdersStream() {
        return "seckill:orders";
    }

    public static String seckillConsumerGroup() {
        return "seckill-order-consumers";
    }

    public static String otp(String email) {
        return "otp:" + email.toLowerCase();
    }
}
