package my.edu.ukm.ftsm.ecommerce.consumer;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import my.edu.ukm.ftsm.ecommerce.dto.SeckillDtos.SeckillOrderMessage;
import my.edu.ukm.ftsm.ecommerce.service.SeckillOrderWriter;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SeckillOrderListenerTest {

    private static final String ORDER_ID = "3f2b8c1e-5d6a-4e7b-9c0d-1a2b3c4d5e6f";

    private final ObjectMapper mapper = JsonMapper.builder().findAndAddModules().build();
    private final SeckillOrderWriter writer = mock(SeckillOrderWriter.class);
    private final SeckillOrderListener listener = new SeckillOrderListener(mapper, writer);

    @Test
    void validMessageIsPersisted() throws Exception {
        listener.onMessage(payload());

        verify(writer).persist(message());
    }

    @Test
    void duplicateDeliveryOfAnExistingOrderIsSkipped() throws Exception {
        when(writer.persist(any())).thenThrow(new DataIntegrityViolationException("Duplicate entry for tracking_token"));
        when(writer.existsByTrackingToken(ORDER_ID)).thenReturn(true);

        assertThatCode(() -> listener.onMessage(payload())).doesNotThrowAnyException();
    }

    @Test
    void constraintViolationWithoutThatOrderIsRethrown() throws Exception {
        DataIntegrityViolationException conflict =
                new DataIntegrityViolationException("Duplicate entry for uk_orders_buyer_seckill");
        when(writer.persist(any())).thenThrow(conflict);
        when(writer.existsByTrackingToken(ORDER_ID)).thenReturn(false);

        assertThatThrownBy(() -> listener.onMessage(payload())).isSameAs(conflict);
    }

    @Test
    void malformedJsonThrowsJsonProcessingException() {
        assertThatThrownBy(() -> listener.onMessage("{not json")).isInstanceOf(JsonProcessingException.class);
        verify(writer, never()).persist(any());
    }

    private SeckillOrderMessage message() {
        return new SeckillOrderMessage(ORDER_ID, 5L, 7L, 10L, new BigDecimal("9.90"),
                Instant.parse("2026-10-04T10:00:00Z"));
    }

    private String payload() throws JsonProcessingException {
        return mapper.writeValueAsString(message());
    }
}
