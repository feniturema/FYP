package my.edu.ukm.ftsm.ecommerce.it;

import com.fasterxml.jackson.databind.ObjectMapper;
import my.edu.ukm.ftsm.ecommerce.service.AssistantService;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.test.context.TestPropertySource;

import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * docs/phases/P4b.md §6.2 / §8: the full application starts for every keyless LLM_PROVIDER setting, has no
 * {@link ChatModel} bean, and {@code POST /api/chat} answers "not configured".
 */
class AssistantStartupIT {

    private static final long USER = 120_000_000L;

    abstract static class KeylessChecks extends AbstractIntegrationTest {

        @Autowired
        ApplicationContext context;
        @Autowired
        Environment environment;

        @Test
        void noChatModelBean() {
            assertThat(context.getBeanNamesForType(ChatModel.class)).isEmpty();
            assertThat(environment.getProperty("spring.ai.model.chat")).isEqualTo("none");
        }

        @Test
        void chatAnswersNotConfigured() throws Exception {
            HttpResponse<String> r = postJson("/api/chat", tokenFor(USER), "{\"message\":\"hi\"}");
            assertThat(r.statusCode()).isEqualTo(200);
            assertThat(new ObjectMapper().readTree(r.body()).path("reply").asText())
                    .isEqualTo(AssistantService.NOT_CONFIGURED).contains("not configured");
        }
    }

    /** Same expression as application.yml with LLM_PROVIDER absent from the environment. */
    @Nested
    @TestPropertySource(properties = "spring.ai.model.chat=${LLM_PROVIDER:none}")
    class ProviderUnset extends KeylessChecks {

        @Test
        void llmProviderIsReallyUnset() {
            assertThat(System.getenv("LLM_PROVIDER")).as("this case needs LLM_PROVIDER unset").isNull();
        }
    }

    @Nested
    @TestPropertySource(properties = "spring.ai.model.chat=none")
    class ProviderNone extends KeylessChecks {
    }

    /** The EPP overrides deepseek-without-a-key to none before any auto-configuration runs. */
    @Nested
    @TestPropertySource(properties = {"spring.ai.model.chat=deepseek", "spring.ai.deepseek.api-key="})
    class DeepseekWithoutKey extends KeylessChecks {
    }
}
