package my.edu.ukm.ftsm.ecommerce.service;

import io.modelcontextprotocol.spec.McpTransportException;
import my.edu.ukm.ftsm.ecommerce.service.ToolCallRecorder.ToolCallRecord;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.net.ConnectException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** docs/phases/P4b.md §6.4 / §8. */
class ToolCallRecorderTest {

    private static final Instant T0 = Instant.parse("2026-10-01T00:00:00Z");

    private final McpToolsProvider mcp = mock(McpToolsProvider.class);
    private final MutableClock clock = new MutableClock(T0);
    private final ToolCallRecorder recorder = new ToolCallRecorder(mcp, clock);

    private static ToolCallback tool(String name) {
        ToolCallback cb = mock(ToolCallback.class);
        when(cb.getToolDefinition()).thenReturn(ToolDefinition.builder().name(name).description(name)
                .inputSchema("{\"type\":\"object\",\"properties\":{}}").build());
        when(cb.call(anyString())).thenReturn("[]");
        when(cb.call(anyString(), any())).thenReturn("[]");
        return cb;
    }

    @Test
    void recordsMcpAndLocalTools() {
        OrderService orders = mock(OrderService.class);
        when(orders.listForBuyer(7L)).thenReturn(List.of());
        ToolCallback[] local = ToolCallbacks.from(new OrderTools(orders));
        ToolCallback[] wrapped = recorder.wrap("7:conv1", new ToolCallback[] {tool("get_stock"), local[0]});

        wrapped[0].call("{\"productId\":1}", new ToolContext(Map.of("userId", 7L)));
        clock.advance(Duration.ofSeconds(1));
        wrapped[1].call("{}", new ToolContext(Map.of("userId", 7L)));

        assertThat(recorder.calls("7:conv1")).containsExactly(
                new ToolCallRecord("get_stock", T0), new ToolCallRecord("my_orders", T0.plusSeconds(1)));
        assertThat(wrapped[1].getToolDefinition().name()).isEqualTo("my_orders");
        verify(orders).listForBuyer(7L);
    }

    @Test
    void conversationsAreIsolated() {
        recorder.wrap("1:a", new ToolCallback[] {tool("search_products")})[0].call("{}");
        assertThat(recorder.calls("1:a")).hasSize(1);
        assertThat(recorder.calls("2:a")).isEmpty();
        assertThat(recorder.calls("1:b")).isEmpty();
    }

    @Test
    void keepsOnlyTheNewest200Records() {
        ToolCallback wrapped = recorder.wrap("1:a", new ToolCallback[] {tool("get_stock")})[0];
        for (int i = 0; i < 205; i++) {
            wrapped.call("{}");
            clock.advance(Duration.ofSeconds(1));
        }
        List<ToolCallRecord> calls = recorder.calls("1:a");
        assertThat(calls).hasSize(ToolCallRecorder.MAX_RECORDS);
        assertThat(calls.get(0).at()).isEqualTo(T0.plusSeconds(5));
        assertThat(calls.get(199).at()).isEqualTo(T0.plusSeconds(204));
    }

    @Test
    void transportFailureMarksMcpBrokenAndIsRethrown() {
        ToolCallback broken = tool("get_stock");
        McpTransportException cause = new McpTransportException("stream closed");
        when(broken.call(anyString(), any())).thenThrow(new IllegalStateException("tool failed", cause));
        ToolCallback wrapped = recorder.wrap("1:a", new ToolCallback[] {broken})[0];

        assertThatThrownBy(() -> wrapped.call("{}", new ToolContext(Map.of()))).hasCause(cause);
        verify(mcp).markBroken(any());
        assertThat(recorder.calls("1:a")).hasSize(1);
    }

    @Test
    void connectExceptionCountsAsTransportFailure() {
        assertThat(ToolCallRecorder.isTransportFailure(new RuntimeException(new ConnectException("refused")))).isTrue();
    }

    @Test
    void ordinaryToolErrorsDoNotMarkMcpBroken() {
        ToolCallback failing = tool("get_stock");
        when(failing.call(anyString())).thenThrow(new IllegalArgumentException("unknown product"));
        ToolCallback wrapped = recorder.wrap("1:a", new ToolCallback[] {failing})[0];

        assertThatThrownBy(() -> wrapped.call("{}")).isInstanceOf(IllegalArgumentException.class);
        verify(mcp, never()).markBroken(any());
    }
}
