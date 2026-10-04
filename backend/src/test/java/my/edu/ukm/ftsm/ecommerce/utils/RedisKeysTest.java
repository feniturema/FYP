package my.edu.ukm.ftsm.ecommerce.utils;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RedisKeysTest {

    @Test
    void seckillKeysCarryTheEventIdAsHashTag() {
        assertThat(RedisKeys.seckillStock(42L)).isEqualTo("seckill:stock:{42}");
        assertThat(RedisKeys.seckillBought(42L)).isEqualTo("seckill:bought:{42}");
    }

    @Test
    void stockAndBoughtKeysShareTheSameHashTag() {
        String stock = RedisKeys.seckillStock(7L);
        String bought = RedisKeys.seckillBought(7L);
        assertThat(hashTag(stock)).isEqualTo("7").isEqualTo(hashTag(bought));
    }

    private static String hashTag(String key) {
        return key.substring(key.indexOf('{') + 1, key.indexOf('}'));
    }
}
