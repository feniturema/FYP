package my.edu.ukm.ftsm.ecommerce.config;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.scripting.support.ResourceScriptSource;

@Configuration
public class RedisConfig {

    @Bean
    public StringRedisTemplate stringRedisTemplate(RedisConnectionFactory factory) {
        return new StringRedisTemplate(factory);
    }

    /**
     * Atomic SecKill deduction script.
     * Returns 1 = success, 0 = sold out, -1 = already bought, -2 = not warmed.
     */
    @Bean
    @Qualifier("seckillDeductScript")
    public RedisScript<Long> seckillDeductScript() {
        return script("scripts/seckill_deduct.lua");
    }

    /** Idempotent compensation: returns 1 if the user was removed and the unit given back, else 0. */
    @Bean
    @Qualifier("seckillRollbackScript")
    public RedisScript<Long> seckillRollbackScript() {
        return script("scripts/seckill_rollback.lua");
    }

    private static RedisScript<Long> script(String classpathLocation) {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setScriptSource(new ResourceScriptSource(new ClassPathResource(classpathLocation)));
        script.setResultType(Long.class);
        return script;
    }
}
