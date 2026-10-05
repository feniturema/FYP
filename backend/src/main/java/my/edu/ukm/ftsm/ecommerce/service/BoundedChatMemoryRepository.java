package my.edu.ukm.ftsm.ecommerce.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Ticker;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.messages.Message;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Bounded, in-process conversation store for the assistant (docs/phases/P4b.md §6.4): at most 1000 conversations,
 * each dropped 2 h after its last access. Wrapped by a 10-message window in {@code AssistantConfig}. Lost on restart and
 * not shared between replicas (documented limitation).
 */
public class BoundedChatMemoryRepository implements ChatMemoryRepository {

    static final int MAX_CONVERSATIONS = 1000;
    static final Duration EXPIRE_AFTER_ACCESS = Duration.ofHours(2);

    private final Cache<String, List<Message>> store;

    public BoundedChatMemoryRepository() {
        this(Ticker.systemTicker());
    }

    BoundedChatMemoryRepository(Ticker ticker) {
        this.store = Caffeine.newBuilder().maximumSize(MAX_CONVERSATIONS).expireAfterAccess(EXPIRE_AFTER_ACCESS)
                .ticker(ticker).executor(Runnable::run).build();
    }

    @Override
    public List<String> findConversationIds() {
        return List.copyOf(store.asMap().keySet());
    }

    @Override
    public List<Message> findByConversationId(String conversationId) {
        List<Message> messages = store.getIfPresent(conversationId);
        return messages == null ? List.of() : List.copyOf(messages);
    }

    @Override
    public void saveAll(String conversationId, List<Message> messages) {
        store.put(conversationId, new ArrayList<>(messages));
    }

    @Override
    public void deleteByConversationId(String conversationId) {
        store.invalidate(conversationId);
    }

    /** Runs pending evictions now (tests). */
    void cleanUp() {
        store.cleanUp();
    }
}
