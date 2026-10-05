package my.edu.ukm.ftsm.ecommerce.it;

import io.micrometer.core.instrument.MeterRegistry;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.config.ConfigResource;
import org.apache.kafka.common.errors.TopicExistsException;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.testcontainers.kafka.KafkaContainer;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * P2 §6.1, marked [unverified] there: when publishing to the DLT fails, the failed record's offset is NOT committed
 * (it is redelivered, blocking its partition) and {@code seckill.dlt.publish.failed} counts the failure; once the DLT
 * exists again the record lands there and the offset moves past it. The poison is a non-JSON payload, which the
 * listener does not retry, so it goes straight to the recoverer.
 * <p>
 * Deviation (maintainer decision 2026-10-06): this class's broker runs with {@code auto.create.topics.enable=false}.
 * With the broker default (true, as in docker-compose), deleting the DLT does not make the DLT publish fail: the
 * producer's metadata request recreates the topic and the record is published, so the failure path never runs.
 * The shared {@link TestcontainersConfiguration} is unchanged; only this class's own context uses the setting.
 */
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class DltPublishFailureIT extends AbstractIntegrationTest {

    private static final long USER_BASE = 70_000_000L;   // reserved by §6.4; this test sends no buys
    private static final String GROUP = "seckill-order-writer";

    @Autowired
    KafkaTemplate<String, String> kafkaTemplate;
    @Autowired
    KafkaAdmin kafkaAdmin;
    @Autowired
    MeterRegistry meterRegistry;
    @Value("${app.seckill.topic}")
    String topic;
    @Value("${app.seckill.dlt-topic}")
    String dltTopic;

    /** Adds the env var before Boot's Testcontainers lifecycle starts the container (after initialization). */
    @TestConfiguration(proxyBeanMethods = false)
    static class NoTopicAutoCreation {
        @Bean
        static BeanPostProcessor kafkaWithoutTopicAutoCreation() {
            return new BeanPostProcessor() {
                @Override
                public Object postProcessBeforeInitialization(Object bean, String beanName) {
                    if (bean instanceof KafkaContainer kafka) {
                        kafka.withEnv("KAFKA_AUTO_CREATE_TOPICS_ENABLE", "false");
                    }
                    return bean;
                }
            };
        }
    }

    @Test
    void dltPublishFailureKeepsTheRecordUntilTheDltIsBack() throws Exception {
        try (AdminClient admin = AdminClient.create(kafkaAdmin.getConfigurationProperties())) {
            String brokerId = String.valueOf(admin.describeCluster().nodes().get(10, TimeUnit.SECONDS).iterator().next().id());
            ConfigResource broker = new ConfigResource(ConfigResource.Type.BROKER, brokerId);
            assertThat(admin.describeConfigs(List.of(broker)).all().get(10, TimeUnit.SECONDS).get(broker)
                    .get("auto.create.topics.enable").value()).as("precondition: no topic auto-creation").isEqualTo("false");

            double before = failed();
            String poison = "not-json-" + UUID.randomUUID();
            RecordMetadata md;
            TopicPartition tp;
            try {
                admin.deleteTopics(List.of(dltTopic)).all().get(30, TimeUnit.SECONDS);
                await().atMost(Duration.ofSeconds(30)).until(() -> !admin.listTopics().names().get().contains(dltTopic));

                md = kafkaTemplate.send(topic, poison).get(10, TimeUnit.SECONDS).getRecordMetadata();
                tp = new TopicPartition(topic, md.partition());

                await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> assertThat(failed() - before).isGreaterThanOrEqualTo(1));
                Long committed = committed(admin, tp);
                assertThat(committed == null || committed <= md.offset())
                        .as("offset not committed past the poison record (committed=%s, poison=%s)", committed, md.offset())
                        .isTrue();
            } finally {
                try {
                    admin.createTopics(List.of(new NewTopic(dltTopic, 1, (short) 1))).all().get(30, TimeUnit.SECONDS);
                } catch (ExecutionException e) {
                    if (!(e.getCause() instanceof TopicExistsException)) {
                        throw e;
                    }
                }
            }

            await().atMost(Duration.ofSeconds(60)).pollInterval(Duration.ofSeconds(1))
                    .untilAsserted(() -> assertThat(dltContains(poison)).as("poison record in the DLT").isTrue());
            await().atMost(Duration.ofSeconds(60)).untilAsserted(() -> {
                Long committed = committed(admin, tp);
                assertThat(committed).isNotNull().isGreaterThan(md.offset());
            });
        }
    }

    private double failed() {
        return meterRegistry.counter("seckill.dlt.publish.failed").count();
    }

    private static Long committed(AdminClient admin, TopicPartition tp) throws Exception {
        Map<TopicPartition, OffsetAndMetadata> offsets =
                admin.listConsumerGroupOffsets(GROUP).partitionsToOffsetAndMetadata().get(10, TimeUnit.SECONDS);
        OffsetAndMetadata o = offsets.get(tp);
        return o == null ? null : o.offset();
    }

    private boolean dltContains(String payload) {
        Map<String, Object> props = Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafkaAdmin.getConfigurationProperties().get("bootstrap.servers"),
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props)) {
            TopicPartition dlt = new TopicPartition(dltTopic, 0);
            consumer.assign(List.of(dlt));
            consumer.seekToBeginning(List.of(dlt));
            for (int i = 0; i < 5; i++) {
                for (ConsumerRecord<String, String> r : consumer.poll(Duration.ofSeconds(1))) {
                    if (payload.equals(r.value())) {
                        return true;
                    }
                }
            }
            return false;
        }
    }
}
