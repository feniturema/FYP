package my.edu.ukm.ftsm.ecommerce.service;

import my.edu.ukm.ftsm.ecommerce.repository.OutboxDao;
import my.edu.ukm.ftsm.ecommerce.repository.OutboxDao.OutboxRow;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SuppressWarnings("unchecked")
class OutboxPublisherTest {

    private final OutboxDao dao = mock(OutboxDao.class);
    private final KafkaTemplate<String, String> kafka = mock(KafkaTemplate.class);
    private final OutboxPublisher publisher = new OutboxPublisher(dao, kafka, "seckill.orders", 500);

    @Test
    void allAcksMarkTheBatchSent() {
        when(dao.lockBatch(500)).thenReturn(List.of(row(1, 7), row(2, 8)));
        when(kafka.send(anyString(), anyString(), anyString()))
                .thenReturn(CompletableFuture.completedFuture(mock(SendResult.class)));

        assertThat(publisher.publishBatch()).isEqualTo(2);

        verify(kafka).send("seckill.orders", "7", "p1");
        verify(kafka).send("seckill.orders", "8", "p2");
        verify(dao).markSent(List.of(1L, 2L));
    }

    @Test
    void oneFailedSendFailsTheWholeBatchWithoutMarkingSent() {
        when(dao.lockBatch(500)).thenReturn(List.of(row(1, 7), row(2, 8)));
        when(kafka.send("seckill.orders", "7", "p1"))
                .thenReturn(CompletableFuture.completedFuture(mock(SendResult.class)));
        when(kafka.send("seckill.orders", "8", "p2"))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException("broker unavailable")));

        assertThatThrownBy(publisher::publishBatch).isInstanceOf(IllegalStateException.class);

        verify(dao, never()).markSent(anyList());
    }

    @Test
    void synchronousSendErrorIsWrappedToo() {
        when(dao.lockBatch(500)).thenReturn(List.of(row(1, 7)));
        when(kafka.send(anyString(), anyString(), anyString()))
                .thenThrow(new org.apache.kafka.common.KafkaException("metadata timeout"));

        assertThatThrownBy(publisher::publishBatch).isInstanceOf(IllegalStateException.class);
        verify(dao, never()).markSent(anyList());
    }

    @Test
    void emptyBatchReturnsZeroAndSendsNothing() {
        when(dao.lockBatch(500)).thenReturn(List.of());

        assertThat(publisher.publishBatch()).isZero();

        verify(kafka, never()).send(anyString(), anyString(), anyString());
        verify(dao, never()).markSent(any());
    }

    private static OutboxRow row(long id, long eventId) {
        return new OutboxRow(id, "order-" + id, eventId, "p" + id);
    }
}
