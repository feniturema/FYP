package my.edu.ukm.ftsm.ecommerce.service;

import my.edu.ukm.ftsm.ecommerce.dto.SeckillDtos.SeckillOrderMessage;
import my.edu.ukm.ftsm.ecommerce.model.Order;
import my.edu.ukm.ftsm.ecommerce.model.SeckillEvent;
import my.edu.ukm.ftsm.ecommerce.repository.OrderRepository;
import my.edu.ukm.ftsm.ecommerce.repository.SeckillEventRepository;
import my.edu.ukm.ftsm.ecommerce.service.strategy.FakeWalletStrategy;
import my.edu.ukm.ftsm.ecommerce.service.strategy.MockFpxStrategy;
import my.edu.ukm.ftsm.ecommerce.service.strategy.PaymentStrategyFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Real transactions (no test-managed rollback) so commit/rollback behaviour is what production sees. */
@DataJpaTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:seckill-writer;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.flyway.enabled=false"
})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import({SeckillOrderWriter.class, OrderService.class, PaymentStrategyFactory.class,
        FakeWalletStrategy.class, MockFpxStrategy.class})
class SeckillOrderWriterH2Test {

    @Autowired
    private SeckillOrderWriter writer;
    @Autowired
    private OrderRepository orders;
    @Autowired
    private SeckillEventRepository events;

    @AfterEach
    void cleanUp() {
        orders.deleteAll();
        events.deleteAll();
    }

    @Test
    void firstDeliveryCreatesAPaidOrderAndBumpsSoldCount() {
        SeckillEvent e = event(2);
        SeckillOrderMessage m = message(e, 5L, UUID.randomUUID().toString());

        Order o = writer.persist(m);

        assertThat(o.getStatus()).isEqualTo(Order.Status.PAID);
        assertThat(o.getSeckillEventId()).isEqualTo(e.getId());
        assertThat(o.getRefId()).isEqualTo(e.getId());
        assertThat(o.getPaymentMethod()).isEqualTo(SeckillOrderWriter.SECKILL_PAYMENT);
        assertThat(events.findById(e.getId()).orElseThrow().getSoldCount()).isEqualTo(1);
        assertThat(writer.existsByTrackingToken(m.orderId())).isTrue();
    }

    @Test
    void redeliveryOfTheSameMessageViolatesAConstraintAndIsRecognisedAsDuplicate() {
        SeckillEvent e = event(2);
        SeckillOrderMessage m = message(e, 5L, UUID.randomUUID().toString());
        writer.persist(m);

        assertThatThrownBy(() -> writer.persist(m)).isInstanceOf(DataIntegrityViolationException.class);

        assertThat(writer.existsByTrackingToken(m.orderId())).isTrue();
        assertThat(orders.count()).isEqualTo(1);
        assertThat(events.findById(e.getId()).orElseThrow().getSoldCount()).isEqualTo(1);
    }

    @Test
    void sameBuyerSameEventDifferentTokenIsNotADuplicate() {
        SeckillEvent e = event(2);
        writer.persist(message(e, 5L, UUID.randomUUID().toString()));
        SeckillOrderMessage other = message(e, 5L, UUID.randomUUID().toString());

        assertThatThrownBy(() -> writer.persist(other)).isInstanceOf(DataIntegrityViolationException.class);

        assertThat(writer.existsByTrackingToken(other.orderId())).isFalse();
        assertThat(orders.count()).isEqualTo(1);
    }

    @Test
    void soldCountGuardRejectsAndLeavesNoOrder() {
        SeckillEvent e = event(1);
        writer.persist(message(e, 5L, UUID.randomUUID().toString()));
        SeckillOrderMessage overflow = message(e, 6L, UUID.randomUUID().toString());

        assertThatThrownBy(() -> writer.persist(overflow)).isInstanceOf(IllegalStateException.class);

        assertThat(writer.existsByTrackingToken(overflow.orderId())).isFalse();
        assertThat(orders.count()).isEqualTo(1);
        assertThat(events.findById(e.getId()).orElseThrow().getSoldCount()).isEqualTo(1);
    }

    private SeckillEvent event(int stock) {
        Instant now = Instant.now();
        return events.save(SeckillEvent.builder().productId(10L).seckillPrice(new BigDecimal("9.90"))
                .seckillStock(stock).startTime(now.minusSeconds(60)).endTime(now.plusSeconds(600))
                .status(SeckillEvent.Status.ACTIVE).stockWarmed(true).build());
    }

    private static SeckillOrderMessage message(SeckillEvent e, long userId, String orderId) {
        return new SeckillOrderMessage(orderId, userId, e.getId(), e.getProductId(), e.getSeckillPrice(), Instant.now());
    }
}
