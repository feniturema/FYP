package my.edu.ukm.ftsm.ecommerce.mcp;

import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Registers the {@link ShopTools} methods as MCP tools (docs/phases/P4b.md §6.5). The bean is named
 * {@code shopToolsProvider}: the spec's {@code shopTools} would clash with the {@code @Component ShopTools} bean.
 */
@Configuration
public class ToolsConfig {

    @Bean
    ToolCallbackProvider shopToolsProvider(ShopTools tools) {
        return MethodToolCallbackProvider.builder().toolObjects(tools).build();
    }
}
