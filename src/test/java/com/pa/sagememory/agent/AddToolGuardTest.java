package com.pa.sagememory.agent;

import com.pa.sagememory.agent.middleware.AddToolGuardMiddleware;
import com.pa.sagememory.agent.workspace.AddWorkspaceStore;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.middleware.ActingInput;
import io.agentscope.core.message.ToolUseBlock;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AddToolGuardTest {
    @Test
    void blocksSessionAndFileSelectors() {
        rejected("session_search", Map.of("query", "Milo", "session_id", "other-session"));
        rejected("session_search", Map.of("query", "Milo", "path", "assets/images/photo.png"));
    }

    @Test
    void blocksSessionAgentTraversal() {
        rejected("session_search", Map.of("query", "Milo", "agentId", "../../other-user"));
    }

    @Test
    void checksSerializedArgumentsAsWellAsParsedInput() {
        var guard = new AddToolGuardMiddleware();
        ToolUseBlock raw = new ToolUseBlock("raw", "session_search", Map.of(),
                AddWorkspaceStore.json(Map.of("agentId", "../../other-user", "query", "Milo")), Map.of());
        assertThatThrownBy(() -> guard.onActing(null, RuntimeContext.empty(),
                new ActingInput(List.of(raw)), ignored -> Flux.empty()).blockLast())
                .isInstanceOf(AddAgentException.class);
    }

    private void rejected(String name, Map<String, Object> args) {
        var guard = new AddToolGuardMiddleware();
        ToolUseBlock call = new ToolUseBlock("test", name, args);
        assertThatThrownBy(() -> guard.onActing(null, RuntimeContext.empty(),
                new ActingInput(List.of(call)), ignored -> Flux.empty()).blockLast())
                .isInstanceOf(AddAgentException.class);
    }
}
