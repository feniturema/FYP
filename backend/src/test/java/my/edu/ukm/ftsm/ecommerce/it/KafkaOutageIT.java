package my.edu.ukm.ftsm.ecommerce.it;

import com.github.dockerjava.api.DockerClient;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.annotation.DirtiesContext;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.kafka.KafkaContainer;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/** Kafka paused for 10 s: buys are still accepted into the outbox, and every order arrives after unpause (§6.4). */
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class KafkaOutageIT extends AbstractIntegrationTest {

    private static final long USER_BASE = 40_000_000L;

    @Autowired
    KafkaContainer kafka;

    @Test
    void buysDuringAKafkaPauseAreDeliveredAfterwards() throws Exception {
        long eventId = createActiveEvent(createProduct(20, PRICE), 20);
        DockerClient docker = DockerClientFactory.instance().client();
        String kafkaId = kafka.getContainerId();

        boolean paused = false;
        try {
            docker.pauseContainerCmd(kafkaId).exec();
            paused = true;
            long pausedAt = System.nanoTime();

            for (int i = 0; i < 20; i++) {
                assertThat(buy(tokenFor(USER_BASE + i), eventId).statusCode()).isEqualTo(202);
            }
            assertThat(outboxPending(eventId)).as("outbox rows wait while Kafka is paused").isPositive();

            long left = Duration.ofSeconds(10).toNanos() - (System.nanoTime() - pausedAt);
            if (left > 0) {
                Thread.sleep(Duration.ofNanos(left));   // the spec's 10 s outage
            }
        } finally {
            if (paused) {
                docker.unpauseContainerCmd(kafkaId).exec();
            }
        }

        awaitOrders(eventId, 20, Duration.ofSeconds(60));
    }
}
