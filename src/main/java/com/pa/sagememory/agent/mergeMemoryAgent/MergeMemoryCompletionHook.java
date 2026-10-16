package com.pa.sagememory.agent.mergeMemoryAgent;

import com.pa.sagememory.agent.AddAgentException;
import com.pa.sagememory.agent.mergeMemoryTools.MergeMemoryContext;
import io.agentscope.core.hook.Hook;
import io.agentscope.core.hook.HookEvent;
import io.agentscope.core.hook.PostReasoningEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolUseBlock;
import java.io.IOException;
import org.springframework.http.HttpStatus;
import reactor.core.publisher.Mono;

/** 只在模型试图结束却未检查最新文件时继续推理，不要求每次编辑后检查。 */
@SuppressWarnings("removal")
final class MergeMemoryCompletionHook implements Hook {
    private final MergeMemoryContext memory;
    MergeMemoryCompletionHook(MergeMemoryContext memory) { this.memory = memory; }

    @Override public <T extends HookEvent> Mono<T> onEvent(T event) {
        if (event instanceof PostReasoningEvent reasoning && reasoning.getReasoningMessage() != null
                && !reasoning.getReasoningMessage().hasContentBlocks(ToolUseBlock.class)) {
            try {
                if (!memory.isLintPassedForLatestMemory()) reasoning.gotoReasoning(Msg.builder()
                        .role(MsgRole.USER).content(TextBlock.builder().text(
                                "The latest memory has not passed lint_memory. Finish any corrections, then call lint_memory. "
                                + "If it reports errors, fix them and rerun it before completing.").build()).build());
            } catch (IOException failure) {
                return Mono.error(new AddAgentException(HttpStatus.INTERNAL_SERVER_ERROR,
                        "ADD_CONSOLIDATION_STORAGE_FAILED", "Could not verify the latest memory lint result.", failure));
            }
        }
        return Mono.just(event);
    }
}
