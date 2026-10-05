package my.edu.ukm.ftsm.ecommerce.service;

import io.modelcontextprotocol.client.McpSyncClient;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/** docs/phases/P4b.md §6.3 / §8. */
class McpToolsProviderTest {

    private static final Duration RETRY = Duration.ofSeconds(30);
    private static final Duration TTL = Duration.ofMinutes(5);

    private final MutableClock clock = new MutableClock(Instant.parse("2026-10-01T00:00:00Z"));

    /** Counts connections; fails or succeeds on demand; returns one mock callback per listing. */
    private static final class CountingConnector implements McpToolsProvider.Connector {
        final AtomicInteger connects = new AtomicInteger();
        final AtomicInteger listings = new AtomicInteger();
        final List<McpSyncClient> clients = new ArrayList<>();
        volatile boolean fail;
        volatile long connectMillis;
        volatile CountDownLatch listingStarted;
        volatile CountDownLatch releaseListing;

        @Override
        public McpSyncClient connect(String url) {
            connects.incrementAndGet();
            if (connectMillis > 0) {
                try {
                    Thread.sleep(connectMillis);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            if (fail) {
                throw new IllegalStateException("connection refused");
            }
            McpSyncClient c = mock(McpSyncClient.class);
            synchronized (clients) {
                clients.add(c);
            }
            return c;
        }

        @Override
        public ToolCallback[] tools(McpSyncClient client) {
            listings.incrementAndGet();
            CountDownLatch started = listingStarted;
            CountDownLatch release = releaseListing;
            if (started != null && release != null) {
                started.countDown();
                try {
                    release.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            return new ToolCallback[] {mock(ToolCallback.class)};
        }
    }

    private McpToolsProvider provider(CountingConnector connector) {
        return new McpToolsProvider("http://mcp.test", true, RETRY, TTL, connector, clock);
    }

    @Test
    void unreachableServerReturnsEmptyWithoutThrowing() {
        McpToolsProvider p = new McpToolsProvider("http://127.0.0.1:1", true, RETRY, TTL);
        assertThat(p.currentTools()).isEmpty();
        assertThat(p.available()).isFalse();
        p.destroy();
    }

    @Test
    void noReconnectWithinTheRetryInterval() {
        CountingConnector c = new CountingConnector();
        c.fail = true;
        McpToolsProvider p = provider(c);

        assertThat(p.currentTools()).isEmpty();
        clock.advance(RETRY.minusSeconds(1));
        assertThat(p.currentTools()).isEmpty();
        assertThat(c.connects).hasValue(1);

        clock.advance(Duration.ofSeconds(1));
        c.fail = false;
        assertThat(p.currentTools()).hasSize(1);
        assertThat(c.connects).hasValue(2);
        assertThat(p.available()).isTrue();
    }

    @Test
    void twentyConcurrentVirtualThreadsConnectOnce() throws InterruptedException {
        CountingConnector c = new CountingConnector();
        c.connectMillis = 200;
        McpToolsProvider p = provider(c);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger withTools = new AtomicInteger();
        List<Thread> threads = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            threads.add(Thread.ofVirtual().start(() -> {
                try {
                    start.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                if (p.currentTools().length == 1) {
                    withTools.incrementAndGet();
                }
            }));
        }
        start.countDown();
        for (Thread t : threads) {
            t.join();
        }
        assertThat(c.connects).hasValue(1);
        assertThat(withTools).hasValue(20);
    }

    @Test
    void cachedToolsAreRelistedAfterTheTtlWithoutReconnecting() {
        CountingConnector c = new CountingConnector();
        McpToolsProvider p = provider(c);
        p.currentTools();
        clock.advance(TTL.minusSeconds(1));
        p.currentTools();
        assertThat(c.listings).hasValue(1);
        clock.advance(Duration.ofSeconds(1));
        p.currentTools();
        assertThat(c.listings).hasValue(2);
        assertThat(c.connects).hasValue(1);
    }

    @Test
    void markBrokenClearsTheCacheAndClosesTheClient() {
        CountingConnector c = new CountingConnector();
        McpToolsProvider p = provider(c);
        assertThat(p.currentTools()).hasSize(1);

        p.markBroken(new IllegalStateException("transport closed"));

        assertThat(p.available()).isFalse();
        assertThat(p.currentTools()).isEmpty();
        verify(c.clients.get(0)).closeGracefully();
        assertThat(c.connects).hasValue(1);   // still inside the retry interval
    }

    @Test
    void markBrokenCannotBeOverwrittenByAnInFlightRefresh() throws InterruptedException {
        CountingConnector c = new CountingConnector();
        McpToolsProvider p = provider(c);
        p.currentTools();
        clock.advance(TTL);
        c.listingStarted = new CountDownLatch(1);
        c.releaseListing = new CountDownLatch(1);

        Thread refresh = Thread.startVirtualThread(p::currentTools);
        assertThat(c.listingStarted.await(1, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        Thread broken = Thread.startVirtualThread(() -> p.markBroken(new IllegalStateException("transport closed")));
        Thread.sleep(50);
        c.releaseListing.countDown();
        refresh.join();
        broken.join();

        assertThat(p.available()).isFalse();
        assertThat(p.currentTools()).isEmpty();
    }

    @Test
    void destroyClosesTheClientGracefully() {
        CountingConnector c = new CountingConnector();
        McpToolsProvider p = provider(c);
        p.currentTools();
        p.destroy();
        verify(c.clients.get(0)).closeGracefully();
        assertThat(p.available()).isFalse();
    }

    @Test
    void disabledNeverConnects() {
        CountingConnector c = new CountingConnector();
        McpToolsProvider p = new McpToolsProvider("http://mcp.test", false, RETRY, TTL, c, clock);
        assertThat(p.currentTools()).isEmpty();
        assertThat(p.available()).isFalse();
        assertThat(c.connects).hasValue(0);
    }
}
