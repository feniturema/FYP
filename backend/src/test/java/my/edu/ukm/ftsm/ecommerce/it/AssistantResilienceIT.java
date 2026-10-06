package my.edu.ukm.ftsm.ecommerce.it;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RateLimiterRegistry;
import my.edu.ukm.ftsm.ecommerce.service.AssistantService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * docs/phases/P4b.md §8: rate limiter, circuit breaker and the 30 s stream timeout around a controllable stub
 * {@link ChatModel} (modes: normal / throw / 35 s delay), observed through {@code POST /api/chat} on a real Tomcat.
 */
class AssistantResilienceIT extends AbstractIntegrationTest {

    private static final long USER = 130_000_000L;
    private static final ObjectMapper JSON = new ObjectMapper();

    enum Mode { NORMAL, THROW, DELAY_35S }

    static final class StubChatModel implements ChatModel {
        final AtomicReference<Mode> mode = new AtomicReference<>(Mode.NORMAL);
        final AtomicInteger streams = new AtomicInteger();

        @Override
        public ChatResponse call(Prompt prompt) {
            throw new UnsupportedOperationException("the assistant only streams");
        }

        @Override
        public Flux<ChatResponse> stream(Prompt prompt) {
            streams.incrementAndGet();
            return switch (mode.get()) {
                case NORMAL -> Flux.just(text("stub "), text("answer"));
                case THROW -> Flux.error(new IllegalStateException("stub model failure"));
                case DELAY_35S -> Mono.delay(Duration.ofSeconds(35)).thenMany(Flux.just(text("too late")));
            };
        }

        @Override
        public ChatOptions getDefaultOptions() {
            return ToolCallingChatOptions.builder().build();
        }

        private static ChatResponse text(String t) {
            return new ChatResponse(List.of(new Generation(new AssistantMessage(t))));
        }
    }

    @TestConfiguration
    static class StubModelConfig {
        @Bean
        StubChatModel stubChatModel() {
            return new StubChatModel();
        }
    }

    @Autowired
    StubChatModel model;
    @Autowired
    CircuitBreakerRegistry circuitBreakers;
    @Autowired
    RateLimiterRegistry rateLimiters;

    private String token;

    /** Every test starts CLOSED and at the start of a fresh 20-permit cycle, whatever ran before it. */
    @BeforeEach
    void reset() throws InterruptedException {
        model.mode.set(Mode.NORMAL);
        circuitBreakers.circuitBreaker("llm").reset();
        token = tokenFor(USER);
        awaitFreshRateLimiterCycle();
    }

    private String reply(String conv) {
        HttpResponse<String> r = postJson("/api/chat", token, "{\"message\":\"hi\",\"conversationId\":\"" + conv + "\"}");
        assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
        try {
            return JSON.readTree(r.body()).path("reply").asText();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void normalModeAnswersThroughTheModel() {
        assertThat(reply("normal")).isEqualTo("stub answer");
    }

    @Test
    void tenConsecutiveFailuresOpenTheBreakerAndTheNextCallFailsFast() throws Exception {
        model.mode.set(Mode.THROW);
        int initial = model.streams.get();
        for (int i = 0; i < 10; i++) {
            assertThat(reply("cb")).isEqualTo(AssistantService.UNAVAILABLE_TEXT);
        }
        assertThat(model.streams.get() - initial).as("all 10 failures must come from the model").isEqualTo(10);

        HttpRequest req = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/actuator/circuitbreakers"))
                .header("Authorization", "Bearer " + token).timeout(Duration.ofSeconds(10)).GET().build();
        HttpResponse<String> cb = http.send(req, HttpResponse.BodyHandlers.ofString());
        assertThat(cb.statusCode()).isEqualTo(200);
        JsonNode llm = JSON.readTree(cb.body()).path("circuitBreakers").path("llm");
        assertThat(llm.path("state").asText()).as(cb.body()).isEqualTo("OPEN");

        int before = model.streams.get();
        long start = System.nanoTime();
        String fallback = reply("cb");
        long millis = Duration.ofNanos(System.nanoTime() - start).toMillis();
        assertThat(fallback).isEqualTo(AssistantService.UNAVAILABLE_TEXT);
        assertThat(millis).isLessThan(500);
        assertThat(model.streams.get()).as("an open breaker must not reach the model").isEqualTo(before);
    }

    @Test
    void twentyFiveConcurrentRequestsWithinOneSecondAreRateLimited() throws Exception {
        // reset() just entered a fresh 1 s refresh period, so all 25 fire inside one period
        CountDownLatch go = new CountDownLatch(1);
        List<Thread> threads = new ArrayList<>();
        List<String> replies = java.util.Collections.synchronizedList(new ArrayList<>());
        for (int i = 0; i < 25; i++) {
            String conv = "rl-" + i;
            threads.add(Thread.ofVirtual().start(() -> {
                try {
                    go.await();
                    replies.add(reply(conv));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }));
        }
        long start = System.nanoTime();
        go.countDown();
        for (Thread t : threads) {
            t.join();
        }
        long fallbacks = replies.stream().filter(AssistantService.UNAVAILABLE_TEXT::equals).count();
        assertThat(replies).hasSize(25);
        assertThat(fallbacks).as("replies: %s, elapsed %d ms", replies,
                Duration.ofNanos(System.nanoTime() - start).toMillis()).isGreaterThanOrEqualTo(5);
        assertThat(replies).filteredOn("stub answer"::equals).hasSize(25 - (int) fallbacks);
    }

    @Test
    void aStreamSlowerThanThirtySecondsTimesOutIntoTheFallback() {
        model.mode.set(Mode.DELAY_35S);
        long start = System.nanoTime();
        String reply = reply("slow");
        long seconds = Duration.ofNanos(System.nanoTime() - start).toSeconds();
        assertThat(reply).isEqualTo(AssistantService.UNAVAILABLE_TEXT);
        assertThat(seconds).isBetween(29L, 34L);
    }

    /** Waits until the llm limiter enters a new refresh cycle, so the burst cannot straddle two cycles. */
    private void awaitFreshRateLimiterCycle() throws InterruptedException {
        RateLimiter limiter = rateLimiters.rateLimiter("llm");
        var metrics = (io.github.resilience4j.ratelimiter.internal.AtomicRateLimiter.AtomicRateLimiterMetrics)
                ((io.github.resilience4j.ratelimiter.internal.AtomicRateLimiter) limiter).getDetailedMetrics();
        long cycle = metrics.getCycle();
        long deadline = System.nanoTime() + Duration.ofSeconds(3).toNanos();
        while (metrics.getCycle() == cycle && System.nanoTime() < deadline) {
            Thread.sleep(1);
        }
        assertThat(metrics.getCycle()).as("rate limiter cycle did not advance").isNotEqualTo(cycle);
    }
}
