package my.edu.ukm.ftsm.ecommerce.controller;

import my.edu.ukm.ftsm.ecommerce.security.AuthPrincipal;
import my.edu.ukm.ftsm.ecommerce.service.McpToolsProvider;
import my.edu.ukm.ftsm.ecommerce.service.OrderTools;
import my.edu.ukm.ftsm.ecommerce.service.ToolCallRecorder;
import my.edu.ukm.ftsm.ecommerce.service.ToolCallRecorder.ToolCallRecord;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.stream.Stream;

/**
 * Dev-only assistant diagnostics (docs/phases/P4b.md §4, §6.4, §7.5): the tool names the model would be offered now,
 * and the tools called in one of the caller's own conversations. Not registered outside the {@code dev} profile.
 */
@Profile("dev")
@RestController
@RequestMapping("/api/assistant/debug")
public class AssistantDebugController {

    private final McpToolsProvider mcp;
    private final ToolCallRecorder recorder;
    private final ToolCallback[] localTools;

    public AssistantDebugController(McpToolsProvider mcp, ToolCallRecorder recorder, OrderTools orderTools) {
        this.mcp = mcp;
        this.recorder = recorder;
        this.localTools = ToolCallbacks.from(orderTools);
    }

    @GetMapping("/tools")
    public List<String> tools() {
        return Stream.concat(Stream.of(mcp.currentTools()), Stream.of(localTools))
                .map(cb -> cb.getToolDefinition().name()).sorted().toList();
    }

    /** {@code conversationId} is the server-side key {@code <userId>:<clientId>}; other users' keys are 404. */
    @GetMapping("/tool-calls")
    public ResponseEntity<List<ToolCallRecord>> toolCalls(@AuthenticationPrincipal AuthPrincipal principal,
                                                          @RequestParam String conversationId) {
        if (!conversationId.startsWith(principal.userId() + ":")) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(recorder.calls(conversationId));
    }
}
