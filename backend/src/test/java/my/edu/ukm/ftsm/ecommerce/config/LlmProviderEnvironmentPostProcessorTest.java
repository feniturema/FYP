package my.edu.ukm.ftsm.ecommerce.config;

import org.apache.commons.logging.Log;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/** docs/phases/P4b.md §6.2 / §8: the four LLM_PROVIDER cases. */
class LlmProviderEnvironmentPostProcessorTest {

    private final Log log = mock(Log.class);
    private final LlmProviderEnvironmentPostProcessor epp = new LlmProviderEnvironmentPostProcessor(supplier -> log);

    @Test
    void unsetMeansNone() {
        MockEnvironment env = new MockEnvironment();
        epp.postProcessEnvironment(env, null);
        assertThat(env.getProperty("spring.ai.model.chat", "none")).isEqualTo("none");
        verify(log).info("assistant disabled (LLM_PROVIDER=none)");
    }

    @Test
    void noneStaysNone() {
        MockEnvironment env = new MockEnvironment().withProperty("spring.ai.model.chat", "none");
        epp.postProcessEnvironment(env, null);
        assertThat(env.getProperty("spring.ai.model.chat")).isEqualTo("none");
        assertThat(env.getPropertySources().contains(LlmProviderEnvironmentPostProcessor.OVERRIDE_SOURCE)).isFalse();
    }

    @Test
    void deepseekWithoutKeyIsOverriddenToNoneWithAWarning() {
        MockEnvironment env = new MockEnvironment().withProperty("spring.ai.model.chat", "deepseek")
                .withProperty("spring.ai.deepseek.api-key", "   ");
        epp.postProcessEnvironment(env, null);
        assertThat(env.getProperty("spring.ai.model.chat")).isEqualTo("none");
        assertThat(env.getPropertySources().iterator().next().getName())
                .isEqualTo(LlmProviderEnvironmentPostProcessor.OVERRIDE_SOURCE);
        verify(log).warn("LLM_PROVIDER=deepseek but LLM_API_KEY is empty; assistant disabled");
    }

    @Test
    void deepseekWithKeyStaysDeepseek() {
        MockEnvironment env = new MockEnvironment().withProperty("spring.ai.model.chat", "deepseek")
                .withProperty("spring.ai.deepseek.api-key", "test-key-not-real");
        epp.postProcessEnvironment(env, null);
        assertThat(env.getProperty("spring.ai.model.chat")).isEqualTo("deepseek");
        verify(log, never()).warn(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void unknownProviderFailsFast() {
        MockEnvironment env = new MockEnvironment().withProperty("spring.ai.model.chat", "foo");
        assertThatThrownBy(() -> epp.postProcessEnvironment(env, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Unsupported LLM_PROVIDER: foo; allowed: none, deepseek");
    }
}
