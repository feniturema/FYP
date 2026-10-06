package my.edu.ukm.ftsm.ecommerce.service;

import com.github.benmanes.caffeine.cache.Ticker;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/** docs/phases/P4b.md §6.4 / §8. */
class BoundedChatMemoryRepositoryTest {

    private final AtomicLong nanos = new AtomicLong();
    private final Ticker ticker = nanos::get;
    private final BoundedChatMemoryRepository repo = new BoundedChatMemoryRepository(ticker);

    private static List<Message> messages(String text) {
        return List.of(new UserMessage(text), new AssistantMessage("re: " + text));
    }

    @Test
    void readsAndWritesByConversationId() {
        repo.saveAll("1:a", messages("hello"));
        repo.saveAll("2:a", messages("other"));

        assertThat(repo.findByConversationId("1:a")).extracting(Message::getText).containsExactly("hello", "re: hello");
        assertThat(repo.findByConversationId("3:a")).isEmpty();
        assertThat(repo.findConversationIds()).containsExactlyInAnyOrder("1:a", "2:a");

        repo.deleteByConversationId("1:a");
        assertThat(repo.findByConversationId("1:a")).isEmpty();
        assertThat(repo.findByConversationId("2:a")).hasSize(2);
    }

    @Test
    void evictsBeyond1000Conversations() {
        for (int i = 0; i < BoundedChatMemoryRepository.MAX_CONVERSATIONS + 50; i++) {
            repo.saveAll("u:" + i, messages("m" + i));
        }
        repo.cleanUp();
        assertThat(repo.findConversationIds()).hasSize(BoundedChatMemoryRepository.MAX_CONVERSATIONS);
    }

    @Test
    void expiresTwoHoursAfterTheLastAccess() {
        repo.saveAll("1:a", messages("hello"));
        repo.saveAll("1:b", messages("idle"));

        nanos.addAndGet(Duration.ofMinutes(90).toNanos());
        assertThat(repo.findByConversationId("1:a")).hasSize(2);      // access resets 1:a's clock

        nanos.addAndGet(Duration.ofMinutes(31).toNanos());
        repo.cleanUp();
        assertThat(repo.findByConversationId("1:b")).isEmpty();      // 121 min idle
        assertThat(repo.findByConversationId("1:a")).hasSize(2);     // 31 min idle

        nanos.addAndGet(Duration.ofHours(2).plusNanos(1).toNanos());
        repo.cleanUp();
        assertThat(repo.findByConversationId("1:a")).isEmpty();
    }
}
