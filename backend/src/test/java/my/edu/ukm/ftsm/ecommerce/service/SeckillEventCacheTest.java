package my.edu.ukm.ftsm.ecommerce.service;

import my.edu.ukm.ftsm.ecommerce.model.SeckillEvent;
import my.edu.ukm.ftsm.ecommerce.repository.SeckillEventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SeckillEventCacheTest {

    private static final Instant START = Instant.parse("2026-10-04T10:00:00Z");
    private static final Instant END = Instant.parse("2026-10-04T10:10:00Z");

    private SeckillEventRepository repo;
    private SeckillEventCache cache;

    @BeforeEach
    void setUp() {
        repo = mock(SeckillEventRepository.class);
        cache = new SeckillEventCache(repo, Duration.ofMinutes(5));
    }

    @Test
    void hitDoesNotQueryTheRepositoryAgain() {
        when(repo.findById(1L)).thenReturn(Optional.of(event(1L)));

        assertThat(cache.get(1L)).get().extracting(SeckillEventCache.EventWindow::productId).isEqualTo(10L);
        assertThat(cache.get(1L)).isPresent();

        verify(repo, times(1)).findById(1L);
    }

    @Test
    void invalidateForcesAReload() {
        when(repo.findById(1L)).thenReturn(Optional.of(event(1L)));

        cache.get(1L);
        cache.invalidate(1L);
        cache.get(1L);

        verify(repo, times(2)).findById(1L);
    }

    @Test
    void unknownIdIsCachedAsEmpty() {
        when(repo.findById(99L)).thenReturn(Optional.empty());

        assertThat(cache.get(99L)).isEmpty();
        assertThat(cache.get(99L)).isEmpty();

        verify(repo, times(1)).findById(99L);
    }

    @Test
    void windowIsInclusiveAtBothEnds() {
        var w = new SeckillEventCache.EventWindow(1L, 10L, BigDecimal.ONE, START, END);
        assertThat(w.isActive(START)).isTrue();
        assertThat(w.isActive(END)).isTrue();
        assertThat(w.isActive(START.minusMillis(1))).isFalse();
        assertThat(w.isActive(END.plusMillis(1))).isFalse();
    }

    private static SeckillEvent event(Long id) {
        return SeckillEvent.builder().id(id).productId(10L).seckillPrice(new BigDecimal("9.90"))
                .seckillStock(5).startTime(START).endTime(END).build();
    }
}
