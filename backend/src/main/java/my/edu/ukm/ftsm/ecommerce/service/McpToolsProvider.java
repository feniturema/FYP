package my.edu.ukm.ftsm.ecommerce.service;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.mcp.McpToolNamePrefixGenerator;
import org.springframework.ai.mcp.SyncMcpToolCallbackProvider;
import org.springframework.ai.mcp.ToolContextToMcpMetaConverter;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Lazily connected MCP client for the catalogue tools served by mcp-server (docs/phases/P4b.md §6.3). Never connects at
 * startup and never throws: when the server is unreachable the assistant runs with its local tools only, and a new
 * connection is attempted at most once per retry interval.
 */
@Component
public class McpToolsProvider implements DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(McpToolsProvider.class);
    private static final ToolCallback[] NONE = new ToolCallback[0];
    private static final long LOCK_WAIT_SECONDS = 5;

    /** Opens an initialized client and lists its tools; replaced by a counting stub in tests. */
    interface Connector {
        McpSyncClient connect(String url);

        ToolCallback[] tools(McpSyncClient client);
    }

    private final ReentrantLock lock = new ReentrantLock();   // not synchronized: virtual threads would pin on JDK 21
    private final String mcpUrl;
    private final boolean enabled;
    private final Duration retryInterval;
    private final Duration toolsTtl;
    private final Connector connector;
    private final Clock clock;

    private volatile McpSyncClient client;                    // non-null while connected
    private volatile ToolCallback[] cached = NONE;
    private volatile Instant cachedAt = Instant.EPOCH;
    private volatile Instant lastFailure = Instant.EPOCH;

    @Autowired
    public McpToolsProvider(@Value("${app.assistant.mcp-url}") String mcpUrl,
                            @Value("${app.assistant.mcp-enabled:true}") boolean enabled,
                            @Value("${app.assistant.mcp-retry-interval:30s}") Duration retryInterval,
                            @Value("${app.assistant.mcp-tools-ttl:5m}") Duration toolsTtl) {
        this(mcpUrl, enabled, retryInterval, toolsTtl, new HttpConnector(), Clock.systemUTC());
    }

    McpToolsProvider(String mcpUrl, boolean enabled, Duration retryInterval, Duration toolsTtl,
                     Connector connector, Clock clock) {
        this.mcpUrl = mcpUrl;
        this.enabled = enabled;
        this.retryInterval = retryInterval;
        this.toolsTtl = toolsTtl;
        this.connector = connector;
        this.clock = clock;
    }

    /** Never throws. Never blocks startup. Returns cached MCP callbacks, or [] when unavailable. */
    public ToolCallback[] currentTools() {
        if (!enabled) {
            return NONE;
        }
        if (!needsWork(clock.instant())) {
            return cached;
        }
        try {
            if (!lock.tryLock(LOCK_WAIT_SECONDS, TimeUnit.SECONDS)) {
                return cached;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return cached;
        }
        try {
            Instant now = clock.instant();
            if (needsWork(now)) {
                refresh(now);
            }
            return cached;
        } finally {
            lock.unlock();
        }
    }

    public boolean available() {
        return enabled && client != null;
    }

    /** Called when a tool call fails at the transport level: drop the connection and wait out the retry interval. */
    public void markBroken(Throwable t) {
        lock.lock();
        try {
            fail(t);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void destroy() {
        lock.lock();
        try {
            McpSyncClient c = client;
            client = null;
            cached = NONE;
            close(c);
        } finally {
            lock.unlock();
        }
    }

    private boolean needsWork(Instant now) {
        if (client != null) {
            return !now.isBefore(cachedAt.plus(toolsTtl));
        }
        return !now.isBefore(lastFailure.plus(retryInterval));
    }

    private void refresh(Instant now) {
        McpSyncClient c = client;
        try {
            if (c == null) {
                c = connector.connect(mcpUrl);
            }
            ToolCallback[] tools = connector.tools(c);
            cached = tools;
            cachedAt = now;
            client = c;
        } catch (RuntimeException e) {
            if (client == null) {
                close(c);              // connected but listing failed: the half-open client is not published
            }
            fail(e);
        }
    }

    private void fail(Throwable t) {
        McpSyncClient c = client;
        client = null;
        cached = NONE;
        lastFailure = clock.instant();
        close(c);
        log.warn("MCP server unavailable at {}: {}", mcpUrl, t.toString());
    }

    private static void close(McpSyncClient c) {
        if (c == null) {
            return;
        }
        try {
            c.closeGracefully();
        } catch (RuntimeException e) {
            log.debug("Ignoring MCP close failure: {}", e.toString());
        }
    }

    /** Streamable HTTP transport against {@code <mcpUrl>/mcp}, timeouts per §6.3. */
    static final class HttpConnector implements Connector {

        @Override
        public McpSyncClient connect(String url) {
            HttpClientStreamableHttpTransport transport = HttpClientStreamableHttpTransport.builder(url)
                    .endpoint("/mcp").connectTimeout(Duration.ofSeconds(3)).build();
            McpSyncClient c = McpClient.sync(transport).requestTimeout(Duration.ofSeconds(20))
                    .initializationTimeout(Duration.ofSeconds(5)).build();
            try {
                c.initialize();
                return c;
            } catch (RuntimeException e) {
                close(c);
                throw e;
            }
        }

        @Override
        public ToolCallback[] tools(McpSyncClient c) {
            // Stable tool names (no per-connection prefix); the tool context (userId) is not forwarded to mcp-server.
            return SyncMcpToolCallbackProvider.builder().mcpClients(c)
                    .toolNamePrefixGenerator(McpToolNamePrefixGenerator.noPrefix())
                    .toolContextToMcpMetaConverter(ToolContextToMcpMetaConverter.noOp())
                    .build().getToolCallbacks();
        }
    }
}
