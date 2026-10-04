package my.edu.ukm.ftsm.ecommerce.config;

import com.fasterxml.jackson.core.JsonProcessingException;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.TopicPartition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.listener.RetryListener;
import org.springframework.util.backoff.FixedBackOff;

import java.time.Duration;

/**
 * SecKill order topics and the listener error handling (docs/phases/P2.md §6.7):
 * 3 retries 1 s apart, then the record goes to the DLT; JSON parse errors skip the retries.
 * Boot wires the {@link CommonErrorHandler} bean into the default listener container factory.
 */
@Configuration
public class KafkaConfig {

    private static final Logger log = LoggerFactory.getLogger(KafkaConfig.class);

    private final String topic;
    private final String dltTopic;
    private final int partitions;
    private final int replicas;

    public KafkaConfig(@Value("${app.seckill.topic}") String topic,
                       @Value("${app.seckill.dlt-topic}") String dltTopic,
                       @Value("${app.seckill.partitions}") int partitions,
                       @Value("${app.seckill.replicas}") int replicas) {
        this.topic = topic;
        this.dltTopic = dltTopic;
        this.partitions = partitions;
        this.replicas = replicas;
    }

    @Bean
    NewTopic seckillOrdersTopic() {
        return TopicBuilder.name(topic).partitions(partitions).replicas(replicas).build();
    }

    @Bean
    NewTopic seckillOrdersDltTopic() {
        return TopicBuilder.name(dltTopic).partitions(1).replicas(replicas).build();
    }

    @Bean
    CommonErrorHandler kafkaErrorHandler(KafkaTemplate<String, String> t, MeterRegistry m) {
        var rec = new DeadLetterPublishingRecoverer(t, (r, ex) -> new TopicPartition(dltTopic, -1));
        rec.setFailIfSendResultIsError(true);
        rec.setWaitForSendResultTimeout(Duration.ofSeconds(10));
        var h = new DefaultErrorHandler(rec, new FixedBackOff(1_000L, 3));
        h.addNotRetryableExceptions(JsonProcessingException.class);
        h.setRetryListeners(new RetryListener() {
            @Override
            public void failedDelivery(ConsumerRecord<?, ?> r, Exception e, int attempt) {
            }

            @Override
            public void recoveryFailed(ConsumerRecord<?, ?> r, Exception original, Exception failure) {
                m.counter("seckill.dlt.publish.failed").increment();
                log.error("[SecKill] DLT publish failed for offset {}", r.offset(), failure);
            }
        });
        return h;
    }
}
