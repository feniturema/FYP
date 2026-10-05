package my.edu.ukm.ftsm.ecommerce.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.modelcontextprotocol.spec.McpTransportException;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;

import java.net.ConnectException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Records which tools the model called, per conversation (docs/phases/P4b.md §6.4), for the dev debug endpoint and the
 * evaluation. Covers every tool, MCP and the local {@code my_orders}. A transport failure of an MCP tool call marks the
 * MCP connection broken. At most 1000 conversations (2 h after the last write) and 200 records each (oldest dropped).
 */
public class ToolCallRecorder {

    public record ToolCallRecord(String toolName, Instant at) {}

    static final int MAX_CONVERSATIONS = 1000;
    static final int MAX_RECORDS = 200;

    private final McpToolsProvider mcp;
    private final Clock clock;
    private final Cache<String, List<ToolCallRecord>> calls = Caffeine.newBuilder()
            .maximumSize(MAX_CONVERSATIONS).expireAfterWrite(Duration.ofHours(2)).build();

    public ToolCallRecorder(McpToolsProvider mcp) {
        this(mcp, Clock.systemUTC());
    }

    ToolCallRecorder(McpToolsProvider mcp, Clock clock) {
        this.mcp = mcp;
        this.clock = clock;
    }

    public List<ToolCallRecord> calls(String conversationId) {
        List<ToolCallRecord> list = calls.getIfPresent(conversationId);
        return list == null ? List.of() : List.copyOf(list);
    }

    public ToolCallback[] wrap(String conversationId, ToolCallback[] callbacks) {
        ToolCallback[] wrapped = new ToolCallback[callbacks.length];
        for (int i = 0; i < callbacks.length; i++) {
            wrapped[i] = new Recording(conversationId, callbacks[i]);
        }
        return wrapped;
    }

    private void record(String conversationId, String toolName) {
        ToolCallRecord r = new ToolCallRecord(toolName, clock.instant());
        calls.asMap().compute(conversationId, (k, old) -> {   // no I/O inside: only list copying
            List<ToolCallRecord> next = old == null ? new ArrayList<>() : new ArrayList<>(old);
            next.add(r);
            return next.size() > MAX_RECORDS ? new ArrayList<>(next.subList(next.size() - MAX_RECORDS, next.size())) : next;
        });
    }

    static boolean isTransportFailure(Throwable t) {
        for (Throwable c = t; c != null; c = c.getCause()) {
            if (c instanceof McpTransportException || c instanceof ConnectException) {
                return true;
            }
            if (c.getCause() == c) {
                break;
            }
        }
        return false;
    }

    private final class Recording implements ToolCallback {
        private final String conversationId;
        private final ToolCallback delegate;

        Recording(String conversationId, ToolCallback delegate) {
            this.conversationId = conversationId;
            this.delegate = delegate;
        }

        @Override
        public ToolDefinition getToolDefinition() {
            return delegate.getToolDefinition();
        }

        @Override
        public ToolMetadata getToolMetadata() {
            return delegate.getToolMetadata();
        }

        @Override
        public String call(String toolInput) {
            return call(toolInput, null);
        }

        @Override
        public String call(String toolInput, ToolContext toolContext) {
            record(conversationId, delegate.getToolDefinition().name());
            try {
                return toolContext == null ? delegate.call(toolInput) : delegate.call(toolInput, toolContext);
            } catch (RuntimeException e) {
                if (isTransportFailure(e)) {
                    mcp.markBroken(e);
                }
                throw e;
            }
        }
    }
}
