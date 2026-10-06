package my.edu.ukm.ftsm.ecommerce.config;

import org.apache.commons.logging.Log;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.boot.logging.DeferredLogFactory;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

import java.util.Locale;
import java.util.Map;

/**
 * LLM provider contract (docs/phases/P4b.md §6.2). Runs after the config files are loaded (registered in
 * META-INF/spring.factories, default order), so it sees {@code spring.ai.model.chat} (= {@code LLM_PROVIDER}) and the
 * DeepSeek key from yml and the environment:
 * <ul>
 *   <li>none / unset → no ChatModel; INFO {@code assistant disabled (LLM_PROVIDER=none)};</li>
 *   <li>deepseek with an empty key → overridden to none with the highest precedence, WARN — instead of the
 *       DeepSeek autoconfiguration failing startup;</li>
 *   <li>deepseek with a key → unchanged;</li>
 *   <li>anything else → {@link IllegalStateException}: fail fast rather than silently disable a misspelt provider.</li>
 * </ul>
 */
public class LlmProviderEnvironmentPostProcessor implements EnvironmentPostProcessor {

    static final String CHAT_MODEL = "spring.ai.model.chat";
    static final String API_KEY = "spring.ai.deepseek.api-key";
    static final String OVERRIDE_SOURCE = "llmProviderOverride";

    private final Log log;

    public LlmProviderEnvironmentPostProcessor(DeferredLogFactory logFactory) {
        this.log = logFactory.getLog(LlmProviderEnvironmentPostProcessor.class);
    }

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment env, SpringApplication application) {
        String provider = env.getProperty(CHAT_MODEL, "none").strip().toLowerCase(Locale.ROOT);
        switch (provider) {
            case "", "none" -> log.info("assistant disabled (LLM_PROVIDER=none)");
            case "deepseek" -> {
                String key = env.getProperty(API_KEY, "");
                if (key.isBlank()) {
                    env.getPropertySources().addFirst(new MapPropertySource(OVERRIDE_SOURCE, Map.of(CHAT_MODEL, "none")));
                    log.warn("LLM_PROVIDER=deepseek but LLM_API_KEY is empty; assistant disabled");
                }
            }
            default -> throw new IllegalStateException("Unsupported LLM_PROVIDER: " + provider + "; allowed: none, deepseek");
        }
    }
}
