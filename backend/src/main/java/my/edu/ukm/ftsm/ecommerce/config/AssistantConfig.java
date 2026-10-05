package my.edu.ukm.ftsm.ecommerce.config;

import my.edu.ukm.ftsm.ecommerce.service.BoundedChatMemoryRepository;
import my.edu.ukm.ftsm.ecommerce.service.McpToolsProvider;
import my.edu.ukm.ftsm.ecommerce.service.ToolCallRecorder;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Assistant memory and tool-call recording (docs/phases/P4b.md §6.4). Both are in-process only. */
@Configuration
public class AssistantConfig {

    static final int MEMORY_WINDOW = 10;

    @Bean
    public ChatMemory assistantChatMemory() {
        return MessageWindowChatMemory.builder().chatMemoryRepository(new BoundedChatMemoryRepository())
                .maxMessages(MEMORY_WINDOW).build();
    }

    @Bean
    public ToolCallRecorder toolCallRecorder(McpToolsProvider mcp) {
        return new ToolCallRecorder(mcp);
    }
}
