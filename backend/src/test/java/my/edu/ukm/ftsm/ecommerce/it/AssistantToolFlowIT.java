package my.edu.ukm.ftsm.ecommerce.it;

import com.fasterxml.jackson.databind.ObjectMapper;
import my.edu.ukm.ftsm.ecommerce.service.OrderService;
import my.edu.ukm.ftsm.ecommerce.service.ToolCallRecorder;
import my.edu.ukm.ftsm.ecommerce.service.ToolCallRecorder.ToolCallRecord;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.net.http.HttpResponse;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * docs/phases/P4b.md §8: a stub model asks for {@code my_orders} on its first turn and answers with text on the second.
 * Like the real DeepSeek model it executes the tool call through Spring AI's {@link ToolCallingManager}, so the call
 * goes through the recorder wrapper and receives the ToolContext that AssistantService built from the JWT.
 */
class AssistantToolFlowIT extends AbstractIntegrationTest {

    private static final long USER_A = 140_000_001L;
    private static final long USER_B = 140_000_002L;

    static final class ToolCallingStubModel implements ChatModel {
        private final ToolCallingManager manager = ToolCallingManager.builder().build();
        final List<String> turns = new CopyOnWriteArrayList<>();
        final List<Message> toolResults = new CopyOnWriteArrayList<>();

        @Override
        public ChatResponse call(Prompt prompt) {
            throw new UnsupportedOperationException("the assistant only streams");
        }

        @Override
        public Flux<ChatResponse> stream(Prompt prompt) {
            turns.add("tool-call");
            ChatResponse toolCall = new ChatResponse(List.of(new Generation(AssistantMessage.builder().content("")
                    .toolCalls(List.of(new AssistantMessage.ToolCall("call-1", "function", "my_orders", "{}")))
                    .build())));
            return Mono.fromCallable(() -> manager.executeToolCalls(prompt, toolCall))
                    .subscribeOn(Schedulers.boundedElastic())
                    .flatMapMany((ToolExecutionResult result) -> {
                        toolResults.add(result.conversationHistory().get(result.conversationHistory().size() - 1));
                        turns.add("text");
                        return Flux.just(new ChatResponse(List.of(new Generation(
                                new AssistantMessage("Here are your orders.")))));
                    });
        }

        @Override
        public ChatOptions getDefaultOptions() {
            return ToolCallingChatOptions.builder().build();
        }
    }

    @TestConfiguration
    static class StubModelConfig {
        @Bean
        ToolCallingStubModel toolCallingStubModel() {
            return new ToolCallingStubModel();
        }
    }

    @Autowired
    ToolCallingStubModel model;
    @Autowired
    ToolCallRecorder recorder;
    @MockitoSpyBean
    OrderService orderService;

    @Test
    void myOrdersRunsForTheTokenUserAndIsRecordedUnderTheirConversation() throws Exception {
        HttpResponse<String> r = postJson("/api/chat", tokenFor(USER_A),
                "{\"message\":\"what did I order?\",\"conversationId\":\"conv1\"}");

        assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
        assertThat(new ObjectMapper().readTree(r.body()).path("reply").asText()).isEqualTo("Here are your orders.");
        assertThat(model.turns).containsExactly("tool-call", "text");
        assertThat(model.toolResults).singleElement().isInstanceOf(ToolResponseMessage.class);

        verify(orderService).listForBuyer(USER_A);
        verify(orderService, never()).listForBuyer(eq(USER_B));
        assertThat(recorder.calls(USER_A + ":conv1")).extracting(ToolCallRecord::toolName).containsExactly("my_orders");
        assertThat(recorder.calls(USER_B + ":conv1")).isEmpty();
        assertThat(recorder.calls("conv1")).isEmpty();
    }
}
