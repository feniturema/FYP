package my.edu.ukm.ftsm.ecommerce.it;

import my.edu.ukm.ftsm.ecommerce.service.SeckillOrderWriter;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.ConsumerGroupDescription;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.ConsumerGroupState;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.TopicPartitionInfo;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;

/**
 * All 30 messages are consumed again after the group offsets are reset to 0: every replay is recognised as a
 * duplicate through existsByTrackingToken and the order count stays 30 (§6.4, P2 §6.7).
 */
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ReplayIT extends AbstractIntegrationTest {

    private static final long USER_BASE = 50_000_000L;
    private static final String GROUP = "seckill-order-writer";

    @MockitoSpyBean
    SeckillOrderWriter writer;
    @Autowired
    KafkaListenerEndpointRegistry registry;
    @Autowired
    KafkaAdmin kafkaAdmin;
    @Value("${app.seckill.topic}")
    String topic;

    @Test
    void replayFromOffsetZeroCreatesNoNewOrder() throws Exception {
        long eventId = createActiveEvent(createProduct(30, PRICE), 30);
        for (int i = 0; i < 30; i++) {
            assertThat(buy(tokenFor(USER_BASE + i), eventId).statusCode()).isEqualTo(202);
        }
        awaitOrders(eventId, 30, Duration.ofSeconds(60));
        Mockito.clearInvocations(writer);

        try (AdminClient admin = AdminClient.create(kafkaAdmin.getConfigurationProperties())) {
            try {
                registry.getListenerContainers().forEach(MessageListenerContainer::stop);
                await().atMost(Duration.ofSeconds(60)).pollInterval(Duration.ofMillis(500)).until(() -> {
                    ConsumerGroupDescription d = admin.describeConsumerGroups(List.of(GROUP)).all().get().get(GROUP);
                    return d.state() == ConsumerGroupState.EMPTY;
                });
                List<TopicPartitionInfo> partitions =
                        admin.describeTopics(List.of(topic)).allTopicNames().get().get(topic).partitions();
                Map<TopicPartition, OffsetAndMetadata> zero = new HashMap<>();
                partitions.forEach(p -> zero.put(new TopicPartition(topic, p.partition()), new OffsetAndMetadata(0)));
                admin.alterConsumerGroupOffsets(GROUP, zero).all().get();
            } finally {
                registry.getListenerContainers().forEach(c -> {
                    if (!c.isRunning()) {
                        c.start();
                    }
                });
            }
        }

        // Wait until all 30 replays went through the duplicate path, then the count must still be 30.
        await().atMost(Duration.ofSeconds(60)).until(() -> Mockito.mockingDetails(writer).getInvocations().stream()
                .filter(inv -> inv.getMethod().getName().equals("existsByTrackingToken")).count() >= 30);
        assertThat(countOrders(eventId)).isEqualTo(30);
        verify(writer, atLeastOnce()).existsByTrackingToken(anyString());
    }
}
