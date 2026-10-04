package my.edu.ukm.ftsm.ecommerce.service;

import my.edu.ukm.ftsm.ecommerce.repository.OutboxDao;
import my.edu.ukm.ftsm.ecommerce.repository.OutboxDao.OutboxRow;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * T2 of the SecKill pipeline (docs/phases/P2.md §6.1, §6.7): claim NEW outbox rows with
 * FOR UPDATE SKIP LOCKED, send them all, wait for every ack, then mark them SENT in the same
 * transaction. Any send failure rolls the whole batch back, so rows stay NEW and are re-sent
 * later (duplicates are removed by the consumer's unique key). Separate bean so the
 * {@code @Transactional} proxy applies when {@link OutboxRelay} calls it.
 * <p>READ COMMITTED: under MySQL's default REPEATABLE READ the locking scan also takes a gap lock
 * after the last NEW row, which blocks the hot path's outbox INSERT for as long as the batch waits
 * for Kafka (up to 15 s). Row locks and SKIP LOCKED behave the same at both levels
 * (scripts/p2/evidence/gaplock-isolation.txt).
 */
@Service
public class OutboxPublisher {

    static final long SEND_TIMEOUT_SECONDS = 15;

    private final OutboxDao outboxDao;
    private final KafkaTemplate<String, String> kafka;
    private final String topic;
    private final int batchSize;

    public OutboxPublisher(OutboxDao outboxDao, KafkaTemplate<String, String> kafka,
                           @Value("${app.seckill.topic}") String topic,
                           @Value("${app.seckill.relay-batch-size}") int batchSize) {
        this.outboxDao = outboxDao;
        this.kafka = kafka;
        this.topic = topic;
        this.batchSize = batchSize;
    }

    /** @return number of rows published in this batch (0 when nothing was pending). */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public int publishBatch() {
        List<OutboxRow> rows = outboxDao.lockBatch(batchSize);
        if (rows.isEmpty()) {
            return 0;
        }
        try {
            CompletableFuture<?>[] acks = rows.stream()
                    .map(r -> kafka.send(topic, String.valueOf(r.eventId()), r.payload()))
                    .toArray(CompletableFuture[]::new);
            CompletableFuture.allOf(acks).get(SEND_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("outbox publish interrupted; batch rolled back", e);
        } catch (Exception e) {
            throw new IllegalStateException("outbox publish failed for " + rows.size() + " rows; batch rolled back", e);
        }
        outboxDao.markSent(rows.stream().map(OutboxRow::id).toList());
        return rows.size();
    }
}
