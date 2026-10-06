package my.edu.ukm.ftsm.ecommerce.mcp;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/**
 * Read-only MCP server (streamable HTTP, {@code /mcp}) exposing catalogue tools to the assistant
 * (docs/phases/P4b.md §6.5). It shares catalog-core with backend; the schema is migrated by backend only.
 */
@SpringBootApplication(scanBasePackages = {"my.edu.ukm.ftsm.ecommerce.mcp", "my.edu.ukm.ftsm.ecommerce.search"})
@EntityScan("my.edu.ukm.ftsm.ecommerce.model")
@EnableJpaRepositories("my.edu.ukm.ftsm.ecommerce.repository")
public class McpServerApplication {

    public static void main(String[] args) {
        SpringApplication.run(McpServerApplication.class, args);
    }
}
