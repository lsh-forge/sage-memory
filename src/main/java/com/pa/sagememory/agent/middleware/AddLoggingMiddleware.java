package com.pa.sagememory.agent.middleware;

import com.pa.sagememory.agent.AddLogSupport;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.ToolResultEndEvent;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.middleware.ActingInput;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.middleware.ModelCallInput;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;

/** 只观测模型/工具生命周期，不打印思考、提示词、工具参数或回忆片段，不改原生工具。 */
public final class AddLoggingMiddleware implements MiddlewareBase {
    private static final Logger log = LoggerFactory.getLogger(AddLoggingMiddleware.class);
    private final AtomicInteger modelRounds = new AtomicInteger();

    @Override
    public Flux<AgentEvent> onModelCall(Agent agent, RuntimeContext context, ModelCallInput input,
            Function<ModelCallInput, Flux<AgentEvent>> next) {
        return Flux.defer(() -> {
            String trace = AddLogSupport.trace(context);
            int round = modelRounds.incrementAndGet();
            long startedAt = System.nanoTime();
            log.info("[Add模型调用开始] {}，模型调用轮次={}", trace, round);
            return next.apply(input)
                    .doOnComplete(() -> log.info("[Add模型调用结束] {}，轮次={}，耗时={}ms", trace, round,
                            AddLogSupport.elapsedMillis(startedAt)))
                    .doOnError(error -> log.warn("[Add模型调用失败] {}，轮次={}，耗时={}ms，{}", trace, round,
                            AddLogSupport.elapsedMillis(startedAt), AddLogSupport.failure(error)))
                    .doOnCancel(() -> log.warn("[Add模型调用取消] {}，轮次={}，耗时={}ms；请查看批次/整体超时日志", trace,
                            round, AddLogSupport.elapsedMillis(startedAt)));
        });
    }

    @Override
    public Flux<AgentEvent> onActing(Agent agent, RuntimeContext context, ActingInput input,
            Function<ActingInput, Flux<AgentEvent>> next) {
        return Flux.defer(() -> {
            String trace = AddLogSupport.trace(context);
            long startedAt = System.nanoTime();
            input.toolCalls().forEach(call -> log.info("[Add工具调用] {}，tool={}，tool_call_id={}", trace,
                    AddLogSupport.value(call.getName()), AddLogSupport.value(call.getId())));
            return next.apply(input)
                    .doOnNext(event -> {
                        if (event instanceof ToolResultEndEvent result) {
                            if (result.getState() == ToolResultState.SUCCESS) {
                                log.info("[Add工具回执] {}，tool={}，tool_call_id={}，SDK状态={}；写入仍需落盘校验", trace,
                                        AddLogSupport.value(result.getToolCallName()), AddLogSupport.value(result.getToolCallId()), result.getState());
                            } else {
                                log.warn("[Add工具回执异常] {}，tool={}，tool_call_id={}，SDK状态={}", trace,
                                        AddLogSupport.value(result.getToolCallName()), AddLogSupport.value(result.getToolCallId()), result.getState());
                            }
                        }
                    })
                    .doOnComplete(() -> log.info("[Add工具执行结束] {}，调用数量={}，耗时={}ms", trace,
                            input.toolCalls().size(), AddLogSupport.elapsedMillis(startedAt)))
                    .doOnError(error -> log.warn("[Add工具执行失败] {}，耗时={}ms，{}", trace,
                            AddLogSupport.elapsedMillis(startedAt), AddLogSupport.failure(error)));
        });
    }
}
