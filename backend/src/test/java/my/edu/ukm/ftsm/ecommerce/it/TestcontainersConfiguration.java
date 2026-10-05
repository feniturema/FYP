package my.edu.ukm.ftsm.ecommerce.it;

import com.redis.testcontainers.RedisContainer;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * The real infrastructure for integration tests (docs/phases/P6a.md §6.2). The images are pinned and later
 * phases must not change them; a phase that needs another container adds it in its own test configuration.
 * The containers are beans, so they live and die with the Spring test context (inferred {@code close()}).
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

    @Bean
    @ServiceConnection
    MySQLContainer<?> mysql() {
        return new MySQLContainer<>(DockerImageName.parse("mysql:8.0.46"));
    }

    @Bean
    @ServiceConnection
    KafkaContainer kafka() {
        return new KafkaContainer(DockerImageName.parse("apache/kafka:3.9.2"));
    }

    @Bean
    @ServiceConnection
    RedisContainer redis() {
        return new RedisContainer(DockerImageName.parse("redis:8.10.2"));
    }
}
