package my.edu.ukm.ftsm.ecommerce.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Drives {@link OutboxPublisher}: up to 20 batches per round, stopping early on a short batch.
 * Never throws; a failed round leaves the rows NEW and the next round retries them.
 */
@Component
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);
    static final int MAX_BATCHES_PER_ROUND = 20;

    private final OutboxPublisher publisher;
    private final int batchSize;

    public OutboxRelay(OutboxPublisher publisher, @Value("${app.seckill.relay-batch-size}") int batchSize) {
        this.publisher = publisher;
        this.batchSize = batchSize;
    }

    @Scheduled(fixedDelayString = "${app.seckill.relay-interval-ms:100}")
    public void run() {
        try {
            for (int i = 0; i < MAX_BATCHES_PER_ROUND; i++) {
                if (publisher.publishBatch() < batchSize) {
                    return;
                }
            }
        } catch (Exception e) {
            log.warn("[SecKill] outbox relay round failed, rows stay NEW and will be retried: {}", e.toString());
        }
    }
}
