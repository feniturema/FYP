package my.edu.ukm.ftsm.ecommerce.config;

import com.fasterxml.jackson.core.JsonParseException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.ListenerExecutionFailedException;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.kafka.support.SendResult;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Drives the real error handler bean with mocked Kafka collaborators (no broker). */
@SuppressWarnings({"unchecked", "rawtypes"})
class KafkaConfigTest {

    private static final String DLT = "seckill.orders.DLT";

    private final KafkaTemplate<String, String> template = mock(KafkaTemplate.class);
    private final Consumer<?, ?> consumer = mock(Consumer.class);
    private final MessageListenerContainer container = mock(MessageListenerContainer.class);
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final ConsumerRecord<String, String> record =
            new ConsumerRecord<>("seckill.orders", 2, 41L, "7", "{\"orderId\":\"x\"}");
    private CommonErrorHandler handler;

    @BeforeEach
    void setUp() {
        handler = new KafkaConfig("seckill.orders", DLT, 6, 1).kafkaErrorHandler(template, meters);
    }

    @Test
    void topicsAreDeclaredWithConfiguredPartitions() {
        KafkaConfig config = new KafkaConfig("seckill.orders", DLT, 6, 1);
        assertThat(config.seckillOrdersTopic().numPartitions()).isEqualTo(6);
        assertThat(config.seckillOrdersDltTopic().name()).isEqualTo(DLT);
        assertThat(config.seckillOrdersDltTopic().numPartitions()).isEqualTo(1);
    }

    @Test
    void jsonErrorsAreNotRetriedAndGoToTheDltWithPartitionLeftToTheProducer() {
        sendSucceeds();

        handle(new JsonParseException(null, "bad json"));

        ProducerRecord<Object, Object> sent = sentRecord();
        assertThat(sent.topic()).isEqualTo(DLT);
        assertThat(sent.partition()).as("TopicPartition(dlt, -1) => no explicit partition").isNull();
    }

    @Test
    void otherErrorsAreRetriedThreeTimesBeforeTheDlt() {
        sendSucceeds();
        RuntimeException boom = new IllegalStateException("incrementSold returned 0");

        for (int attempt = 1; attempt <= 3; attempt++) {
            // not recovered yet: the handler seeks back and signals the container to redeliver
            assertThatThrownBy(() -> handle(boom)).isInstanceOf(RuntimeException.class)
                    .hasMessage("Record in retry and not yet recovered");
            verify(template, never()).send(any(ProducerRecord.class));
        }
        handle(boom);

        verify(template, times(1)).send(any(ProducerRecord.class));
        assertThat(sentRecord().topic()).isEqualTo(DLT);
    }

    @Test
    void failedDltPublishIsCountedAndTheRecordIsNotCommitted() {
        when(template.send(any(ProducerRecord.class)))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException("DLT down")));

        assertThatThrownBy(() -> handle(new JsonParseException(null, "bad json"))).isInstanceOf(RuntimeException.class);

        assertThat(meters.counter("seckill.dlt.publish.failed").count()).isEqualTo(1.0);
    }

    private void handle(Exception cause) {
        handler.handleRemaining(new ListenerExecutionFailedException("listener failed", cause),
                List.of((ConsumerRecord) record), consumer, container);
    }

    private void sendSucceeds() {
        when(template.send(any(ProducerRecord.class))).thenAnswer(inv -> {
            ProducerRecord<Object, Object> pr = inv.getArgument(0);
            RecordMetadata md = new RecordMetadata(new TopicPartition(pr.topic(), 0), 0, 0, 0, 0, 0);
            return CompletableFuture.completedFuture(new SendResult<>(pr, md));
        });
    }

    private ProducerRecord<Object, Object> sentRecord() {
        ArgumentCaptor<ProducerRecord> captor = ArgumentCaptor.forClass(ProducerRecord.class);
        verify(template).send(captor.capture());
        return captor.getValue();
    }
}
