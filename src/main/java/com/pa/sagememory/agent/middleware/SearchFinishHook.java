package com.pa.sagememory.agent.middleware;

import com.pa.sagememory.agent.searchTools.SearchEvidenceContext;
import io.agentscope.core.hook.Hook;
import io.agentscope.core.hook.HookEvent;
import io.agentscope.core.hook.PostActingEvent;
import io.agentscope.core.message.ToolResultState;
import reactor.core.publisher.Mono;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 2.0.4 的显式停止回调：混合工具批次也在 finish 成功后停止，不再发起模型调用。 */
@SuppressWarnings("removal")
public final class SearchFinishHook implements Hook {
    private static final Logger log = LoggerFactory.getLogger(SearchFinishHook.class);
    private final SearchEvidenceContext context;
    public SearchFinishHook(SearchEvidenceContext context) { this.context = context; }
    @Override public <T extends HookEvent> Mono<T> onEvent(T event) {
        // Middleware 的 onActing 没有 stopAgent API；当前 SDK 提供的显式停止能力位于此回调。
        // 不取消同轮已经执行的只读工具，避免 SDK 留下无回执的 tool_use。
        if (event instanceof PostActingEvent acting && acting.getToolUse() != null
                && "finish_search".equals(acting.getToolUse().getName()) && acting.getToolResult() != null
                && acting.getToolResult().getState() == ToolResultState.SUCCESS && context.isFinished()) {
            acting.stopAgent();
            log.info("[Search停止推理] user_id={}，finish_search 已成功，最终 ID 数={}；不再进入下一轮模型推理", context.userId(), context.selectedIds().size());
        }
        return Mono.just(event);
    }
}
