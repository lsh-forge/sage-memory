package com.pa.sagememory.agent.mergeMemoryAgent;

import com.pa.sagememory.agent.AddAgentException;
import com.pa.sagememory.agent.AddAgentProperties;
import com.pa.sagememory.agent.AddDeadline;
import com.pa.sagememory.agent.AddLogSupport;
import com.pa.sagememory.agent.AgentRetryPolicy;
import com.pa.sagememory.agent.mergeMemoryTools.*;
import com.pa.sagememory.agent.middleware.AgentDebugLogProperties;
import com.pa.sagememory.agent.middleware.AgentDebugLoggingMiddleware;
import com.pa.sagememory.agent.middleware.AgentLogRedactor;
import com.pa.sagememory.agent.middleware.SingleRetryModel;
import com.pa.sagememory.agent.workspace.AddWorkspaceStore;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.GenerateReason;
import io.agentscope.core.message.Msg;
import io.agentscope.core.model.ExecutionConfig;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.core.tool.ToolkitConfig;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/** Add 小组完成后独立维护长期记忆，按需回查原文，最终 Lint 通过才推进水位。 */
@Service
public final class MergeMemoryAgentService {
    private static final Logger log = LoggerFactory.getLogger(MergeMemoryAgentService.class);
    private static final int MAX_ATTEMPTS = AgentRetryPolicy.MAX_ATTEMPTS;
    private final AddAgentProperties properties;
    private final AgentDebugLogProperties debugLog;
    private final AgentLogRedactor redactor;

    @Autowired
    public MergeMemoryAgentService(AddAgentProperties properties, AgentDebugLogProperties debugLog, AgentLogRedactor redactor) {
        this.properties = properties; this.debugLog = debugLog; this.redactor = redactor;
    }

    public MergeMemoryAgentService(AddAgentProperties properties) {
        this(properties, new AgentDebugLogProperties(), new AgentLogRedactor());
    }

    public void consolidate(Model model, AddWorkspaceStore store, AddWorkspaceStore.Workspace workspace,
            RuntimeContext runtime, AddDeadline deadline) throws IOException {
        deadline.check();
        String trace = AddLogSupport.trace(runtime);
        PendingMemorySaves pending = new PendingMemorySaves(workspace.user());
        Instant watermark = pending.watermark();
        Instant cutoff = Instant.now();
        PendingMemorySaves.Batch saves = pending.collect(watermark, cutoff);
        if (saves.count() == 0) {
            log.info("[Add记忆归并跳过] {}，水位={}，没有待归并Save，不调用模型", trace, watermark);
            return;
        }
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            deadline.check();
            MergeMemoryContext memory = new MergeMemoryContext(store, runtime.getUserId(), deadline::check,
                    properties.getConsolidationMaxChars());
            String original = memory.snapshot();
            long started = System.nanoTime();
            log.info("[Add归并Agent启动] {}，attempt={}/{}，待归并Save数={}，初始记忆快照字符数={}，Save字符数={}，最大轮次={}，单次模型上限={}秒，目标/上限字符数={}/{}；记忆快照与待合并Save分区输入，首轮开放全部工具，批量编辑后统一Lint",
                    trace, attempt, MAX_ATTEMPTS, saves.count(), original.length(), saves.content().length(),
                    properties.getConsolidationMaxIters(), properties.getConsolidationTimeoutSeconds(),
                    properties.getConsolidationTargetChars(), properties.getConsolidationMaxChars());
            try {
                // 用户锁内读取的同一份快照既用于模型初始比较，也用于失败恢复，不另读缓存。
                run(model, memory, runtime, deadline, original, saves.content(), attempt);
                // 文件由工具更新，此处不再从模型回复重写全文。最后检查后封闭，迟到写入被拒绝。
                String updated = memory.sealAfterLint();
                pending.advance(cutoff);
                log.info("[Add记忆归并完成] {}，attempt={}/{}，耗时={}ms，新记忆字符数={}，水位={}；每日台账及原始证据保持不变",
                        trace, attempt, MAX_ATTEMPTS, AddLogSupport.elapsedMillis(started), updated.length(), cutoff);
                return;
            } catch (IOException | RuntimeException error) {
                // 封闭与恢复在同一个工具锁内，后台工具不能在恢复完成后继续写坏文件。
                try { memory.rollback(original); }
                catch (IOException rollback) {
                    log.error("[Add归并恢复失败] {}，异常类型={}；未推进水位，请检查工作区文件", trace, rollback.getClass().getSimpleName());
                    throw new IOException("Could not restore memory after a failed merge.", rollback);
                }
                if (error instanceof IOException storage) throw storage;
                deadline.check();
                AddAgentException failure = failure((RuntimeException) error);
                if (attempt < MAX_ATTEMPTS && AgentRetryPolicy.retryable(error)) {
                    log.warn("[Add记忆归并重试] {}，{}；已恢复旧记忆，最多重试一次，仅重跑归并Agent", trace, AddLogSupport.failure(failure));
                    continue;
                }
                log.warn("[Add记忆归并失败] {}，{}；已恢复旧记忆，不推进水位、不标记整单完成", trace, AddLogSupport.failure(failure));
                throw failure;
            }
        }
    }

    private void run(Model model, MergeMemoryContext memory, RuntimeContext runtime, AddDeadline deadline,
            String memorySnapshot, String saves, int attempt) throws IOException {
        // 同轮操作严格按调用顺序执行，避免后面的 Lint 抢在写入前完成。
        Toolkit toolkit = new Toolkit(ToolkitConfig.builder().parallel(false).build());
        toolkit.registerTool(new GetMemoryTool(memory));
        toolkit.registerTool(new GetEvidenceTool(memory));
        toolkit.registerTool(new CreateTopicTool(memory));
        toolkit.registerTool(new AppendMemoryTool(memory));
        toolkit.registerTool(new EditMemoryTool(memory));
        toolkit.registerTool(new DeleteMemoryTool(memory));
        toolkit.registerTool(new LintMemoryTool(memory));
        var options = GenerateOptions.builder().stream(false).temperature(0.0);
        if (model.getModelName() != null && model.getModelName().toLowerCase(Locale.ROOT).startsWith("qwen"))
            options.additionalBodyParam("enable_thinking", false);
        String prompt = MergeMemoryPrompts.systemPrompt(properties.getConsolidationTargetChars(), properties.getConsolidationMaxChars());
        try (ReActAgent agent = ReActAgent.builder().name("merge-memory").sysPrompt(prompt)
                .model(new SingleRetryModel(model, Duration.ofSeconds(properties.getConsolidationTimeoutSeconds()),
                        deadline::remaining, "agent=merge-memory " + AddLogSupport.trace(runtime) + " agent_attempt=" + attempt + "/2", redactor))
                .toolkit(toolkit).generateOptions(options.build())
                .maxIters(properties.getConsolidationMaxIters())
                .modelExecutionConfig(ExecutionConfig.builder().timeout(deadline.limit(properties.getConsolidationTimeoutSeconds()))
                        .maxAttempts(1).build())
                .middleware(new MergeMemoryMiddleware(deadline, properties.getConsolidationMaxIters()))
                .middleware(new AgentDebugLoggingMiddleware(debugLog, redactor))
                .hook(new MergeMemoryCompletionHook(memory)).build()) {
            // 整个多轮归并仍受 Add 总预算约束，模型单轮 300 秒不会重置整单计时。
            Msg response = agent.call(MergeMemoryPrompts.userPrompt(memorySnapshot, saves), runtime).timeout(deadline.remaining()).block();
            deadline.check();
            if (response == null || response.getGenerateReason() != GenerateReason.MODEL_STOP
                    || !memory.isLintPassedForLatestMemory())
                throw new AddAgentException(HttpStatus.BAD_GATEWAY, "ADD_CONSOLIDATION_LINT_REQUIRED",
                        "Memory merge must finish normally after lint_memory passes for the latest file.");
        }
    }

    private static AddAgentException failure(RuntimeException error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof AddAgentException known) return known;
            if (cause instanceof TimeoutException) return new AddAgentException(HttpStatus.GATEWAY_TIMEOUT,
                    "ADD_CONSOLIDATION_TIMEOUT", "Memory merge exceeded its deadline. Retry with the same request_id.", error);
        }
        return new AddAgentException(HttpStatus.BAD_GATEWAY, "ADD_CONSOLIDATION_FAILED",
                "Memory merge failed. Retry with the same request_id to resume completed batches.", error);
    }
}
