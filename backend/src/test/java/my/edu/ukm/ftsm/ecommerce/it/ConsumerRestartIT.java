package my.edu.ukm.ftsm.ecommerce.it;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.test.annotation.DirtiesContext;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The Kafka listeners are stopped while buys are in flight and restarted 2 s later: no accepted order is lost.
 * This proves a GRACEFUL stop only. A process crash (SIGKILL) is covered by P2 acceptance A6, not by this test.
 */
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ConsumerRestartIT extends AbstractIntegrationTest {

    private static final long USER_BASE = 30_000_000L;

    @Autowired
    KafkaListenerEndpointRegistry registry;

    @Test
    void listenersStoppedAndRestartedMidRunLoseNoOrder() throws Exception {
        long eventId = createActiveEvent(createProduct(200, PRICE), 200);
        AtomicInteger returned = new AtomicInteger();
        CountDownLatch stopped = new CountDownLatch(1);

        List<Integer> statuses = new ArrayList<>();
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<Integer>> futures = new ArrayList<>();
            try {
                for (int i = 0; i < 200; i++) {
                    long user = USER_BASE + i;
                    futures.add(pool.submit(() -> {
                        int status = buy(tokenFor(user), eventId).statusCode();
                        if (returned.incrementAndGet() == 50) {   // the 50th response stops every listener
                            registry.getListenerContainers().forEach(MessageListenerContainer::stop);
                            stopped.countDown();
                        }
                        return status;
                    }));
                }
                assertThat(stopped.await(60, TimeUnit.SECONDS)).as("listeners stopped at the 50th response").isTrue();
                Thread.sleep(2_000);   // the spec's 2 s stop window
            } finally {
                registry.getListenerContainers().forEach(c -> {
                    if (!c.isRunning()) {
                        c.start();
                    }
                });
            }
            for (Future<Integer> f : futures) {
                statuses.add(f.get());
            }
        }

        long accepted = statuses.stream().filter(s -> s == 202).count();
        assertThat(accepted).isEqualTo(200);
        awaitOrders(eventId, accepted, Duration.ofSeconds(60));
    }
}
