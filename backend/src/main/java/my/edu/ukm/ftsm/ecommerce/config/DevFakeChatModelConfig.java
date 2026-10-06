package my.edu.ukm.ftsm.ecommerce.config;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.List;

/**
 * Dev-only fake model for checking that SSE tokens reach the browser unbuffered (docs/phases/P4b.md acceptance A8):
 * streams {@code "Hel","lo ","from ","fake"} 300 ms apart. It never calls tools. Enabled only with the {@code dev}
 * profile and {@code app.assistant.fake-model=true} (env {@code APP_ASSISTANT_FAKEMODEL=true}).
 */
@Profile("dev")
@Configuration
@ConditionalOnProperty(name = "app.assistant.fake-model", havingValue = "true")
public class DevFakeChatModelConfig {

    @Bean
    @Primary
    public ChatModel fakeStreamingChatModel() {
        return new FakeStreamingChatModel();
    }

    static final class FakeStreamingChatModel implements ChatModel {

        static final List<String> TOKENS = List.of("Hel", "lo ", "from ", "fake");
        static final Duration GAP = Duration.ofMillis(300);

        @Override
        public ChatResponse call(Prompt prompt) {
            return response(String.join("", TOKENS));
        }

        @Override
        public Flux<ChatResponse> stream(Prompt prompt) {
            return Flux.interval(Duration.ZERO, GAP).take(TOKENS.size())
                    .map(i -> response(TOKENS.get(i.intValue())));
        }

        @Override
        public ChatOptions getDefaultOptions() {
            return ToolCallingChatOptions.builder().build();
        }

        private static ChatResponse response(String text) {
            return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
        }
    }
}
