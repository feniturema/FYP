package my.edu.ukm.ftsm.ecommerce.consumer;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import my.edu.ukm.ftsm.ecommerce.dto.SeckillDtos.SeckillOrderMessage;
import my.edu.ukm.ftsm.ecommerce.service.SeckillOrderWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Consumes {@code seckill.orders} and persists each purchase exactly once (docs/phases/P2.md §6.7).
 * Offsets are committed per record after this method returns (ack-mode record). A constraint
 * violation counts as a duplicate delivery only when the order with this tracking token exists;
 * anything else is rethrown so the error handler retries it and finally dead-letters it.
 */
@Component
public class SeckillOrderListener {

    private static final Logger log = LoggerFactory.getLogger(SeckillOrderListener.class);

    private final ObjectMapper objectMapper;
    private final SeckillOrderWriter writer;

    public SeckillOrderListener(ObjectMapper objectMapper, SeckillOrderWriter writer) {
        this.objectMapper = objectMapper;
        this.writer = writer;
    }

    @KafkaListener(topics = "${app.seckill.topic}", concurrency = "${app.seckill.consumer-concurrency}")
    public void onMessage(String payload) throws JsonProcessingException {
        SeckillOrderMessage m = objectMapper.readValue(payload, SeckillOrderMessage.class); // parse error -> not retryable -> DLT
        try {
            writer.persist(m);
        } catch (DataIntegrityViolationException e) {
            if (writer.existsByTrackingToken(m.orderId())) {
                // INFO (spec §6.7 says debug): A8 counts these lines under the default log level.
                log.info("[SecKill] duplicate {}, skipped", m.orderId());
                return;
            }
            throw e; // retried, then DLT
        }
    }
}
