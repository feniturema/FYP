package my.edu.ukm.ftsm.ecommerce.service;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.InMemoryChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** docs/phases/P4b.md §6.4 / §8: NOT_CONFIGURED without a model; the system text with and without MCP. */
class AssistantServiceTest {

    private final McpToolsProvider mcp = mock(McpToolsProvider.class);
    private final ChatMemory memory = MessageWindowChatMemory.builder()
            .chatMemoryRepository(new InMemoryChatMemoryRepository()).maxMessages(10).build();
    private final OrderTools orderTools = new OrderTools(mock(OrderService.class));

    /** Records every prompt and answers "ok" in two chunks. */
    static final class RecordingChatModel implements ChatModel {
        final List<Prompt> prompts = new CopyOnWriteArrayList<>();

        @Override
        public ChatResponse call(Prompt prompt) {
            prompts.add(prompt);
            return new ChatResponse(List.of(new Generation(new AssistantMessage("ok"))));
        }

        @Override
        public Flux<ChatResponse> stream(Prompt prompt) {
            prompts.add(prompt);
            return Flux.just("o", "k").map(t -> new ChatResponse(List.of(new Generation(new AssistantMessage(t)))));
        }

        @Override
        public ChatOptions getDefaultOptions() {
            return ToolCallingChatOptions.builder().build();
        }
    }

    private static ObjectProvider<ChatModel> provider(ChatModel model) {
        StaticListableBeanFactory bf = new StaticListableBeanFactory();
        if (model != null) {
            bf.addBean("chatModel", model);
        }
        return bf.getBeanProvider(ChatModel.class);
    }

    private AssistantService service(ChatModel model) {
        return new AssistantService(provider(model), memory, mcp, new ToolCallRecorder(mcp), orderTools);
    }

    @Test
    void withoutAChatModelAnswersNotConfigured() {
        assertThat(service(null).stream(1L, "conv1", "hi").collectList().block())
                .containsExactly(AssistantService.NOT_CONFIGURED);
    }

    @Test
    void mcpDownAppendsTheNoteToTheBaseSystemText() {
        when(mcp.currentTools()).thenReturn(new ToolCallback[0]);
        when(mcp.available()).thenReturn(false);
        RecordingChatModel model = new RecordingChatModel();

        assertThat(String.join("", service(model).stream(1L, "conv1", "find a laptop").collectList().block()))
                .isEqualTo("ok");

        String system = model.prompts.get(0).getSystemMessage().getText();
        assertThat(system).isEqualTo(AssistantService.BASE_SYSTEM + AssistantService.MCP_DOWN_NOTE);
        assertThat(system).startsWith("You are the FTSM Marketplace Assistant");
    }

    @Test
    void mcpUpUsesTheBaseSystemTextOnly() {
        when(mcp.currentTools()).thenReturn(new ToolCallback[0]);
        when(mcp.available()).thenReturn(true);
        RecordingChatModel model = new RecordingChatModel();

        service(model).stream(1L, null, "hi").blockLast();

        Prompt prompt = model.prompts.get(0);
        assertThat(prompt.getSystemMessage().getText()).isEqualTo(AssistantService.BASE_SYSTEM);
        assertThat(prompt.getOptions()).isInstanceOf(ToolCallingChatOptions.class);
        assertThat(((ToolCallingChatOptions) prompt.getOptions()).getToolCallbacks())
                .extracting(cb -> cb.getToolDefinition().name()).contains("my_orders");
        assertThat(((ToolCallingChatOptions) prompt.getOptions()).getToolContext())
                .containsEntry("userId", 1L).containsEntry("conversationId", "1:default");
    }

    @Test
    void conversationMemoryIsKeyedByServerSideUserPrefix() {
        when(mcp.currentTools()).thenReturn(new ToolCallback[0]);
        when(mcp.available()).thenReturn(true);
        service(new RecordingChatModel()).stream(5L, "conv1", "hi").blockLast();

        assertThat(memory.get("5:conv1")).isNotEmpty();
        assertThat(memory.get("conv1")).isEmpty();
    }
}
