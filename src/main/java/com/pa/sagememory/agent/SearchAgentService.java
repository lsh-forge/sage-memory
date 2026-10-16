package com.pa.sagememory.agent;

import com.pa.sagememory.agent.middleware.SearchAgentMiddleware;
import com.pa.sagememory.agent.middleware.SearchFinishHook;
import com.pa.sagememory.agent.middleware.AgentDebugLogProperties;
import com.pa.sagememory.agent.middleware.AgentLogRedactor;
import com.pa.sagememory.agent.middleware.AgentDebugLoggingMiddleware;
import com.pa.sagememory.agent.middleware.SingleRetryModel;
import com.pa.sagememory.agent.searchTools.*;
import com.pa.sagememory.agent.multimodal.SearchImageQueryService;
import com.pa.sagememory.agent.workspace.WorkspaceIdentity;
import com.pa.sagememory.controller.ApiRequestValidator;
import com.pa.sagememory.dto.SearchRequest;
import com.pa.sagememory.dto.SearchResponse;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.GenerateReason;
import io.agentscope.core.message.Msg;
import io.agentscope.core.model.ExecutionConfig;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.tool.Toolkit;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.time.Duration;
import java.util.Locale;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 每次 Search 独立 Agent 和快照；只读召回，finish 成功后由宿主组装结果。 */
@Service
public final class SearchAgentService implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(SearchAgentService.class);
    private final SearchAgentProperties properties;
    private final SearchEvidenceStore store;
    private final SearchEvidenceAssembler assembler;
    private final Supplier<Model> models;
    private final ApiRequestValidator validator = new ApiRequestValidator();
    private final ThreadPoolExecutor workers;
    private final AgentDebugLogProperties debugLog;
    private final AgentLogRedactor logRedactor;
    private final SearchImageQueryService queryImages;

    @Autowired
    public SearchAgentService(SearchAgentProperties properties, SearchEvidenceStore store, SearchEvidenceAssembler assembler,
            @Qualifier("searchModel") ObjectProvider<Model> models, AddAgentService addAgent,
            AgentDebugLogProperties debugLog, AgentLogRedactor logRedactor, SearchImageQueryService queryImages) {
        this(properties, store, assembler, () -> {
            Model supplied = models.getIfAvailable();
            if (supplied != null) return supplied;
            try { return addAgent.model(); }
            catch (AddAgentException invalid) { throw new SearchAgentException(invalid.getStatus(),
                    invalid.getCode().replace("ADD_", "SEARCH_"), "Configure the shared text model or supply a searchModel bean.", invalid); }
        }, debugLog, logRedactor, queryImages);
    }
    public SearchAgentService(SearchAgentProperties properties, SearchEvidenceStore store, SearchEvidenceAssembler assembler, Model model) {
        this(properties, store, assembler, () -> model, new AgentDebugLogProperties(), new AgentLogRedactor(), new SearchImageQueryService(null));
    }
    public SearchAgentService(SearchAgentProperties properties, SearchEvidenceStore store, SearchEvidenceAssembler assembler,
            Model model, AgentDebugLogProperties debugLog, AgentLogRedactor logRedactor) {
        this(properties, store, assembler, () -> model, debugLog, logRedactor, new SearchImageQueryService(null));
    }
    /** 离线验收可分别注入检索模型和视觉服务，不访问真实模型接口。 */
    public SearchAgentService(SearchAgentProperties properties, SearchEvidenceStore store, SearchEvidenceAssembler assembler,
            Model model, SearchImageQueryService queryImages) {
        this(properties, store, assembler, () -> model, new AgentDebugLogProperties(), new AgentLogRedactor(), queryImages);
    }
    private SearchAgentService(SearchAgentProperties properties, SearchEvidenceStore store, SearchEvidenceAssembler assembler,
            Supplier<Model> models, AgentDebugLogProperties debugLog, AgentLogRedactor logRedactor, SearchImageQueryService queryImages) {
        properties.validate(); this.properties = properties; this.store = store; this.assembler = assembler; this.models = models;
        this.debugLog = debugLog; this.logRedactor = logRedactor;
        this.queryImages = queryImages;
        AtomicInteger sequence = new AtomicInteger();
        workers = new ThreadPoolExecutor(properties.getWorkerThreads(), properties.getWorkerThreads(), 0, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(properties.getQueueCapacity()), task -> {
                    Thread thread = new Thread(task, "search-worker-" + sequence.incrementAndGet()); thread.setDaemon(true); return thread;
                }, new ThreadPoolExecutor.AbortPolicy());
    }
    public SearchResponse search(SearchRequest request) {
        try { return searchAsync(request).join(); }
        catch (CompletionException error) { if (error.getCause() instanceof RuntimeException cause) throw cause; throw error; }
    }
    public CompletableFuture<SearchResponse> searchAsync(SearchRequest request) {
        validator.validate(request);
        try { WorkspaceIdentity.pathSegment(request.userId()); }
        catch (IllegalArgumentException invalid) { throw new SearchAgentException(HttpStatus.UNPROCESSABLE_CONTENT,
                "SEARCH_INVALID_IDENTIFIER", "user_id must map to a safe workspace directory name."); }
        long expiresAt = System.nanoTime() + Duration.ofSeconds(properties.getRequestTimeoutSeconds()).toNanos();
        try { return CompletableFuture.supplyAsync(() -> run(request, expiresAt), workers); }
        catch (RejectedExecutionException busy) { throw new SearchAgentException(HttpStatus.SERVICE_UNAVAILABLE, "SEARCH_BUSY", "Search workers are busy; retry later."); }
    }
    @SuppressWarnings("removal")
    private SearchResponse run(SearchRequest request, long expiresAt) {
        long startedAt = System.nanoTime();
        String user = AddLogSupport.value(request.userId());
        try {
            SearchAgentMiddleware.remaining(expiresAt);
            String query = queryImages.prepare(request, expiresAt);
            SearchEvidenceContext snapshot = store.open(request.userId());
            // 新用户也通过 finish_search 确认空结果，不拿模型配置失败或工具失败伪装成空召回。
            Model model = models.get();
            if (model == null) throw new SearchAgentException(HttpStatus.SERVICE_UNAVAILABLE, "SEARCH_MODEL_NOT_CONFIGURED", "Search model is not configured.");
            Selection selection = select(request, query, snapshot, model, expiresAt);
            SearchEvidenceContext evidence = selection.evidence();
            SearchAgentMiddleware.remaining(expiresAt);
            SearchResponse response = assembler.assemble(evidence, request.topK());
            SearchAgentMiddleware.remaining(expiresAt);
            log.info("[Search处理完成] user_id={}，最终 ID 数={}，响应证据数={}，top_k={}，结束原因={}，总耗时={}ms；不生成答案",
                    user, evidence.selectedIds().size(), response.data().size(), request.topK(), selection.result().getGenerateReason(), AddLogSupport.elapsedMillis(startedAt));
            return response;
        } catch (IOException storage) {
            throw new SearchAgentException(HttpStatus.INTERNAL_SERVER_ERROR, "SEARCH_STORAGE_FAILED", "Search evidence could not be read safely.", storage);
        } catch (RuntimeException error) {
            log.warn("[Search处理失败] user_id={}，异常类型={}，耗时={}ms，错误信息={}；完整脱敏堆栈见接口错误日志，不返回伪成功空结果", user,
                    error.getClass().getSimpleName(), AddLogSupport.elapsedMillis(startedAt), logRedactor.text(error.getMessage()));
            if (error instanceof SearchAgentException known) throw known;
            for (Throwable cause = error; cause != null; cause = cause.getCause()) {
                if (cause instanceof SearchAgentException known) throw known;
                if (cause instanceof TimeoutException) throw new SearchAgentException(HttpStatus.GATEWAY_TIMEOUT, "SEARCH_TIMEOUT", "Search model or request timed out.", error);
            }
            throw new SearchAgentException(HttpStatus.BAD_GATEWAY, "SEARCH_MODEL_FAILED", "The retrieval model failed.", error);
        }
    }

    /** 复用只读候选和查询描述；每次尝试单独持有已展示证据、分页、最终选择及轮次状态。 */
    @SuppressWarnings("removal")
    private Selection select(SearchRequest request, String query, SearchEvidenceContext snapshot, Model model, long expiresAt) {
        for (int attempt = 1; attempt <= AgentRetryPolicy.MAX_ATTEMPTS; attempt++) {
            SearchAgentMiddleware.remaining(expiresAt);
            SearchEvidenceContext evidence = new SearchEvidenceContext(snapshot.userId(), snapshot.candidates());
            Msg input = SearchAgentPrompts.userMessage(evidence, request, query);
            Toolkit toolkit = new Toolkit();
            toolkit.registerTool(new SearchEvidenceTool(evidence));
            toolkit.registerTool(new SearchImagesTool(evidence));
            toolkit.registerTool(new GetEvidenceTool(evidence));
            toolkit.registerTool(new GetTopicEvidenceTool(evidence));
            toolkit.registerTool(new FinishSearchTool(evidence));
            var options = GenerateOptions.builder().stream(false).temperature(0.0).maxTokens(properties.getMaxOutputTokens());
            if (model.getModelName() != null && model.getModelName().toLowerCase(Locale.ROOT).startsWith("qwen")) options.additionalBodyParam("enable_thinking", false);
            RuntimeContext runtime = RuntimeContext.builder().userId(request.userId()).build();
            String trace = AddLogSupport.trace(runtime) + " agent_attempt=" + attempt + "/2";
            log.info("[Search Agent启动] {}，主题数={}，快照片段数={}，模型={}，最大轮次={}，单次模型上限={}秒，整单上限={}秒",
                    trace, evidence.memoryTopics().size(), evidence.candidates().size(), AddLogSupport.value(model.getModelName()),
                    properties.getMaxIters(), properties.getModelTimeoutSeconds(), properties.getRequestTimeoutSeconds());
            try (ReActAgent agent = ReActAgent.builder().name("search-memory").sysPrompt(SearchAgentPrompts.SYSTEM_PROMPT)
                    .model(new SingleRetryModel(model, Duration.ofSeconds(properties.getModelTimeoutSeconds()),
                            () -> SearchAgentMiddleware.remaining(expiresAt), "agent=search-memory " + trace, logRedactor))
                    .toolkit(toolkit).generateOptions(options.build()).maxIters(properties.getMaxIters())
                    .modelExecutionConfig(ExecutionConfig.builder().timeout(Duration.ofSeconds(properties.getModelTimeoutSeconds())).maxAttempts(1).build())
                    .middleware(new SearchAgentMiddleware(evidence, expiresAt, properties.getModelTimeoutSeconds()))
                    .middleware(new AgentDebugLoggingMiddleware(debugLog, logRedactor))
                    .hook(new SearchFinishHook(evidence)).build()) {
                Msg result = agent.call(java.util.List.of(input), runtime).timeout(SearchAgentMiddleware.remaining(expiresAt)).block();
                if (!evidence.isFinished() || result == null || !(result.getGenerateReason() == GenerateReason.ACTING_STOP_REQUESTED
                        || result.getGenerateReason() == GenerateReason.TOOL_RETURN_DIRECT)) throw new SearchAgentException(HttpStatus.BAD_GATEWAY,
                                "SEARCH_FINISH_REQUIRED", "The retrieval agent ended without a successful finish_search call.");
                return new Selection(evidence, result);
            } catch (RuntimeException error) {
                SearchAgentMiddleware.remaining(expiresAt);
                if (attempt == AgentRetryPolicy.MAX_ATTEMPTS || !AgentRetryPolicy.retryable(error)) throw error;
                log.warn("[Search Agent级重试] {}，已丢弃本次检索运行状态；复用查询描述和证据快照，不重新识图；异常类型={}",
                        trace, error.getClass().getSimpleName());
            }
        }
        throw new IllegalStateException("Search Agent attempts exhausted.");
    }

    private record Selection(SearchEvidenceContext evidence, Msg result) {}
    @Override @PreDestroy public void close() { workers.shutdownNow(); }
}
