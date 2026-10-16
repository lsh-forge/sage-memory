package com.pa.sagememory.agent.mergeMemoryAgent;

import com.pa.sagememory.agent.AddAgentException;
import com.pa.sagememory.agent.AddDeadline;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.middleware.ActingInput;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.middleware.ModelCallInput;
import java.util.function.Function;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.http.HttpStatus;
import reactor.core.publisher.Flux;

/** 归并轮次与整单截止检查；单轮模型计时及重试由 SingleRetryModel 负责。 */
final class MergeMemoryMiddleware implements MiddlewareBase {
    private final AddDeadline deadline;
    private final int maxRounds;
    private final AtomicInteger rounds = new AtomicInteger();
    MergeMemoryMiddleware(AddDeadline deadline, int maxRounds) {
        this.deadline = deadline; this.maxRounds = maxRounds;
    }
    @Override public Flux<AgentEvent> onModelCall(Agent agent, RuntimeContext context, ModelCallInput input,
            Function<ModelCallInput, Flux<AgentEvent>> next) {
        return Flux.defer(() -> {
            // SDK gotoReasoning 不消耗常规 acting 迭代，必须把补查轮次也计入上限。
            if (input.tools().isEmpty() || rounds.incrementAndGet() > maxRounds) return Flux.error(new AddAgentException(HttpStatus.BAD_GATEWAY,
                    "ADD_CONSOLIDATION_ITERATION_LIMIT", "Memory merge exhausted its tool-enabled iterations."));
            // 初始记忆快照已随用户输入提供，从首轮起保留全部工具，不再强制先调用 get_memory。
            deadline.check();
            return next.apply(input);
        });
    }
    @Override public Flux<AgentEvent> onActing(Agent agent, RuntimeContext context, ActingInput input,
            Function<ActingInput, Flux<AgentEvent>> next) {
        return Flux.defer(() -> { deadline.check(); return next.apply(input); });
    }
}
