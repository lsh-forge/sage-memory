package com.pa.sagememory.agent.middleware;

import com.pa.sagememory.agent.SearchAgentException;
import com.pa.sagememory.agent.SearchAgentPrompts;
import com.pa.sagememory.agent.searchTools.SearchEvidenceContext;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.ToolResultEndEvent;
import io.agentscope.core.middleware.ActingInput;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.middleware.ModelCallInput;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.springframework.http.HttpStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;

/** 单轮计时、检索收尾及中文诊断；禁止 SDK 在步数耗尽后额外调用总结模型。 */
public final class SearchAgentMiddleware implements MiddlewareBase {
    private static final Logger log = LoggerFactory.getLogger(SearchAgentMiddleware.class);
    private static final int RETRIEVAL_STEPS = 10;
    static final String FINISH_REMINDER_NAME = "search-finish-reminder";
    private final SearchEvidenceContext evidence;
    private final long expiresAt;
    private final int modelSeconds;
    private final AtomicInteger rounds = new AtomicInteger();
    public SearchAgentMiddleware(SearchEvidenceContext evidence, long expiresAt, int modelSeconds) {
        this.evidence = evidence; this.expiresAt = expiresAt; this.modelSeconds = modelSeconds;
    }
    public static Duration remaining(long expiresAt) {
        long nanos = expiresAt - System.nanoTime();
        if (nanos <= 0) throw new SearchAgentException(HttpStatus.GATEWAY_TIMEOUT, "SEARCH_REQUEST_TIMEOUT", "Search request deadline exceeded.");
        return Duration.ofNanos(nanos);
    }
    @Override public Flux<AgentEvent> onModelCall(Agent agent, RuntimeContext context, ModelCallInput input,
            Function<ModelCallInput, Flux<AgentEvent>> next) {
        return Flux.defer(() -> {
            if (evidence.isFinished() || input.tools().isEmpty()) return Flux.error(new SearchAgentException(
                    HttpStatus.BAD_GATEWAY, "SEARCH_FINISH_REQUIRED", "Search must end through finish_search; answer summarization is disabled."));
            int round = rounds.incrementAndGet();
            long started = System.nanoTime();
            Duration remaining = remaining(expiresAt);
            Duration timeout = remaining.compareTo(Duration.ofSeconds(modelSeconds)) < 0 ? remaining : Duration.ofSeconds(modelSeconds);
            ModelCallInput effective = input;
            if (round > RETRIEVAL_STEPS) {
                var finishTools = input.tools().stream().filter(tool -> "finish_search".equals(tool.getName())).toList();
                if (finishTools.size() != 1) return Flux.error(new SearchAgentException(HttpStatus.BAD_GATEWAY,
                        "SEARCH_FINISH_REQUIRED", "The final selection tool is not available."));
                // 前 10 步保持原始输入；第 11 步开始仅允许最终选择，不额外开启一次 Agent 调用。
                // 提示仅注入本轮请求，不修改累计上下文；第 12 步修正 ID 时仍带一条收尾提示，不重复累积。
                var messages = new ArrayList<>(input.messages());
                messages.add(Msg.builder().role(MsgRole.USER).name(FINISH_REMINDER_NAME)
                        .content(TextBlock.builder().text(SearchAgentPrompts.FINISH_REMINDER).build())
                        .metadata(Map.of(Msg.METADATA_SYNTHETIC, true)).build());
                effective = new ModelCallInput(messages, finishTools, input.options(), input.model());
                log.info("[Search进入收尾] user_id={}，轮次={}，已完成检索步数={}；追加收尾用户提示，仅开放 finish_search，由模型选择和排序已有证据",
                        context.getUserId(), round, RETRIEVAL_STEPS);
            }
            log.info("[Search模型调用开始] user_id={}，轮次={}，本次预算={}ms；不打印提示词或查询", context.getUserId(), round, timeout.toMillis());
            // SingleRetryModel 对每次完整生成独立计时；重试不重入本中间件，不增加检索步数。
            return next.apply(effective)
                    .doOnComplete(() -> log.info("[Search模型调用完成] user_id={}，轮次={}，耗时={}ms", context.getUserId(), round, (System.nanoTime() - started) / 1_000_000));
        });
    }
    @Override public Flux<AgentEvent> onActing(Agent agent, RuntimeContext context, ActingInput input,
            Function<ActingInput, Flux<AgentEvent>> next) {
        return Flux.defer(() -> {
            remaining(expiresAt);
            // 除模型侧只展示收尾工具外，再阻止幻觉调用历史检索工具，避免收尾后继续读取候选。
            if (rounds.get() > RETRIEVAL_STEPS && input.toolCalls().stream()
                    .anyMatch(call -> !"finish_search".equals(call.getName()))) {
                log.warn("[Search收尾调用拒绝] user_id={}，轮次={}；只允许 finish_search，不执行检索工具", context.getUserId(), rounds.get());
                return Flux.error(new SearchAgentException(HttpStatus.BAD_GATEWAY, "SEARCH_FINISH_REQUIRED",
                        "Retrieval is closed; call only finish_search with previously returned evidence IDs."));
            }
            input.toolCalls().forEach(call -> log.info("[Search工具调用] user_id={}，tool={}", context.getUserId(), call.getName()));
            return next.apply(input).doOnNext(event -> {
                if (event instanceof ToolResultEndEvent result) log.info("[Search工具回执] user_id={}，tool={}，状态={}", context.getUserId(), result.getToolCallName(), result.getState());
            });
        });
    }
}
