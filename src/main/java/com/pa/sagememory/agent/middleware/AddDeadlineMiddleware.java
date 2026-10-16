package com.pa.sagememory.agent.middleware;

import com.pa.sagememory.agent.AddDeadline;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.middleware.ActingInput;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.middleware.ModelCallInput;
import java.util.function.Function;
import reactor.core.publisher.Flux;

/** 检查整单截止时间；单次模型计时和重试由 SingleRetryModel 负责，不能在外层提前取消重试。 */
public final class AddDeadlineMiddleware implements MiddlewareBase {
    private final AddDeadline deadline;

    public AddDeadlineMiddleware(AddDeadline deadline) {
        this.deadline = deadline;
    }

    @Override
    public Flux<AgentEvent> onModelCall(Agent agent, RuntimeContext context, ModelCallInput input,
            Function<ModelCallInput, Flux<AgentEvent>> next) {
        return Flux.defer(() -> { deadline.check(); return next.apply(input); });
    }

    @Override
    public Flux<AgentEvent> onActing(Agent agent, RuntimeContext context, ActingInput input,
            Function<ActingInput, Flux<AgentEvent>> next) {
        return Flux.defer(() -> {
            // 工具真正开始前再检查预算，避免已经超时的任务继续开启新写入。
            deadline.check();
            return next.apply(input);
        });
    }
}
