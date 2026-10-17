package com.pa.sagememory.agent;

import com.pa.sagememory.agent.addTools.MemorySaveTool;
import com.pa.sagememory.agent.addTools.SessionSearchTool;
import com.pa.sagememory.agent.mergeMemoryAgent.MergeMemoryAgentService;
import com.pa.sagememory.agent.middleware.AddToolGuardMiddleware;
import com.pa.sagememory.agent.middleware.AddDeadlineMiddleware;
import com.pa.sagememory.agent.middleware.AddLoggingMiddleware;
import com.pa.sagememory.agent.middleware.AgentDebugLogProperties;
import com.pa.sagememory.agent.middleware.AgentLogRedactor;
import com.pa.sagememory.agent.middleware.AgentDebugLoggingMiddleware;
import com.pa.sagememory.agent.middleware.SingleRetryModel;
import com.pa.sagememory.agent.workspace.AddWorkspaceStore;
import com.pa.sagememory.agent.workspace.AddMemoryAttempt;
import com.pa.sagememory.agent.workspace.MemoryProvenanceNormalizer;
import com.pa.sagememory.agent.workspace.WorkspaceIdentity;
import com.pa.sagememory.agent.workspace.KeyedLocks;
import com.pa.sagememory.agent.multimodal.ImageIngestionService;
import com.pa.sagememory.agent.multimodal.VisionProperties;
import com.pa.sagememory.controller.ApiRequestValidator;
import com.pa.sagememory.dto.AddMessage;
import com.pa.sagememory.dto.AddRequest;
import com.pa.sagememory.dto.AddResponse;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.GenerateReason;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ExecutionConfig;
import io.agentscope.core.model.Model;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.extensions.model.openai.OpenAIChatModel;
import java.io.IOException;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 逐组治理并写检查点，全部小组完成后由独立归并 Agent 维护 MEMORY.md，最后才标记整单成功。 */
@Service
public class AddAgentService implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(AddAgentService.class);
    private static final tools.jackson.databind.json.JsonMapper JSON = tools.jackson.databind.json.JsonMapper.builder().build();
    private final AddAgentProperties properties;
    private final Supplier<Model> suppliedModel;
    private final AddWorkspaceStore store;
    private final ApiRequestValidator validator;
    private final AddTaskExecutor tasks;
    private final MergeMemoryAgentService consolidation;
    private final ImageIngestionService images;
    private final AgentDebugLogProperties debugLog;
    private final AgentLogRedactor logRedactor;
    // 同用户串行，不同用户不因哈希碰撞互相等待；最后一个持有者/等待者退出后回收锁。
    private final KeyedLocks userLocks = new KeyedLocks();
    private volatile Model configuredModel;

    @Autowired
    public AddAgentService(AddAgentProperties properties, ObjectProvider<Model> models,
            AddWorkspaceStore store, ApiRequestValidator validator, MergeMemoryAgentService consolidation,
            ImageIngestionService images, AgentDebugLogProperties debugLog, AgentLogRedactor logRedactor) {
        this(properties, models::getIfAvailable, store, validator, consolidation, images, debugLog, logRedactor);
    }

    /** 支持注入离线测试模型，不依赖真实模型接口。 */
    public AddAgentService(AddAgentProperties properties, Model model, AddWorkspaceStore store) {
        this(properties, model, store, new ImageIngestionService(new VisionProperties(), model));
    }

    public AddAgentService(AddAgentProperties properties, Model model, AddWorkspaceStore store,
            ImageIngestionService images) {
        this(properties, () -> model, store, new ApiRequestValidator(), new MergeMemoryAgentService(properties), images,
                new AgentDebugLogProperties(), new AgentLogRedactor());
    }

    private AddAgentService(AddAgentProperties properties, Supplier<Model> suppliedModel,
            AddWorkspaceStore store, ApiRequestValidator validator, MergeMemoryAgentService consolidation,
            ImageIngestionService images, AgentDebugLogProperties debugLog, AgentLogRedactor logRedactor) {
        this.properties = properties;
        this.suppliedModel = suppliedModel;
        this.store = store;
        this.validator = validator;
        this.tasks = new AddTaskExecutor(properties);
        this.consolidation = consolidation;
        this.images = images;
        this.debugLog = debugLog;
        this.logRedactor = logRedactor;
    }

    public AddResponse add(AddRequest request) {
        // 同步入口只等待共享任务；HTTP 接口使用 addAsync，不占用 Servlet 线程。
        try {
            return addAsync(request).join();
        } catch (CompletionException e) {
            if (e.getCause() instanceof RuntimeException cause) throw cause;
            throw e;
        }
    }

    public CompletableFuture<AddResponse> addAsync(AddRequest request) {
        validator.validate(request);
        properties.validate();
        validateIdentifiers(request);
        images.validate(request);
        // 先检查纯文字预算；含图消息必须先识图再确定真实分组，不能拿 Base64 长度拆组。
        split(images.textMessages(request));
        // 先校验整单并构建模型；不发起推理，失败时不创建工作区。
        Model model = model();
        images.ensureConfigured(request);
        return tasks.submit(request, deadline -> processRequest(request, model, deadline));
    }

    private AddResponse processRequest(AddRequest request, Model model, AddDeadline deadline) {
        try (var lease = userLocks.retain(request.userId())) {
            return processLockedRequest(request, model, deadline, lease.lock());
        }
    }

    private AddResponse processLockedRequest(AddRequest request, Model model, AddDeadline deadline, ReentrantLock lock) {
        String trace = AddLogSupport.trace(request);
        long lockStartedAt = System.nanoTime();
        log.info("[Add等待用户锁] {}，锁等待上限={}秒", trace, properties.getQueueTimeoutSeconds());
        try {
            if (!lock.tryLock(deadline.limit(properties.getQueueTimeoutSeconds()).toNanos(), TimeUnit.NANOSECONDS)) {
                deadline.check();
                log.warn("[Add用户锁超时] {}，等待耗时={}ms", trace, AddLogSupport.elapsedMillis(lockStartedAt));
                throw new AddAgentException(HttpStatus.GATEWAY_TIMEOUT, "ADD_USER_LOCK_TIMEOUT",
                        "Another Add is still governing this user's memory. Retry with the same request_id.");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AddAgentException(HttpStatus.SERVICE_UNAVAILABLE, "ADD_INTERRUPTED", "Add execution was interrupted.", e);
        }
        log.info("[Add取得用户锁] {}，等待耗时={}ms", trace, AddLogSupport.elapsedMillis(lockStartedAt));
        try (AddWorkspaceStore.Workspace workspace = store.open(request)) {
            deadline.check();
            store.checkRequest(workspace, request, properties.getBatchSize(), properties.getMaxBatchChars());
            if (store.isComplete(workspace)) {
                log.info("[Add整单幂等命中] {}，此前已完成，直接返回成功，不重复推理", trace);
                return success(request);
            }
            // 识图前固定整单来源编号并落检查点；尚未写入会话的编号也不被其他请求占用。
            List<Map<String, Object>> allSources = store.sourceMessages(workspace, request);
            // 图片并行理解后回填；JSONL 与 Agent 共用这份增强正文，原始接口消息不被改写。
            images.enrich(request, allSources, store, workspace, deadline);
            List<AddMessage> readableMessages = allSources.stream().map(source -> new AddMessage(
                    source.get("role").toString(), (Long) source.get("timestamp"),
                    (tools.jackson.databind.JsonNode) source.get("content"))).toList();
            List<List<AddMessage>> groups = split(readableMessages);
            log.info("[Add分组规划] {}，消息数={}，小组数={}，目标每组消息数={}，单组字符上限={}，模型={}",
                    trace, readableMessages.size(), groups.size(), properties.getBatchSize(),
                    properties.getMaxBatchChars(), AddLogSupport.value(model.getModelName()));
            int start = 0;
            for (int index = 0; index < groups.size(); index++) {
                deadline.check();
                List<Map<String, Object>> sources = allSources.subList(start, start + groups.get(index).size());
                String fingerprint = AddWorkspaceStore.hash(AddWorkspaceStore.json(sources));
                if (!store.isBatchComplete(workspace, index, fingerprint)) {
                    log.info("[Add批次开始] {}，batch={}/{}，本组消息数={}，消息范围=[{},{}]", trace,
                            index + 1, groups.size(), sources.size(), start, start + sources.size() - 1);
                    RuntimeContext context = RuntimeContext.builder()
                            .userId(request.userId()).sessionId(request.sessionId())
                            .put("request_id", request.requestId()).put("batch_index", index)
                            .put(SessionSearchTool.SOURCE_UPPER_BOUND,
                                    Long.parseLong(sources.get(sources.size() - 1).get("source_id").toString())).build();
                    store.appendSources(workspace, request, context, sources);
                    List<String> recentSaves = store.recentSaves(workspace, context, properties.getRecentSaveMaxChars());
                    deadline.check();
                    processBatch(model, workspace, context, request, index, groups.size(), sources, recentSaves, deadline);
                    deadline.check();
                    store.completeBatch(workspace, index, fingerprint);
                    log.info("[Add批次检查点完成] {}，batch={}/{}，已允许后续重试跳过本组", trace, index + 1, groups.size());
                } else {
                    log.info("[Add跳过已完成批次] {}，batch={}/{}，消息数={}", trace, index + 1, groups.size(), sources.size());
                }
                start += groups.get(index).size();
            }
            deadline.check();
            // 仍在同用户锁内：全部小组成功后独立归并，再允许 Add 返回成功。
            RuntimeContext consolidationContext = RuntimeContext.builder().userId(request.userId())
                    .sessionId(request.sessionId()).put("request_id", request.requestId()).build();
            consolidation.consolidate(model, store, workspace, consolidationContext, deadline);
            deadline.check();
            store.completeRequest(workspace);
            log.info("[Add整单落盘完成] {}，小组数={}，记忆文件={}", trace, groups.size(), workspace.user().resolve("MEMORY.md"));
            return success(request);
        } catch (IOException e) {
            log.error("[Add存储失败] {}，{}；原始消息、归并记忆或检查点落盘失败，请使用原 request_id 重试", trace, AddLogSupport.failure(e));
            log.debug("[Add存储异常位置] {}，{}", trace, AddLogSupport.positions(e));
            throw new AddAgentException(HttpStatus.INTERNAL_SERVER_ERROR, "ADD_STORAGE_FAILED",
                    "Add workspace or source persistence failed. Retry with the same request_id.", e);
        } finally {
            lock.unlock();
            log.debug("[Add释放用户锁] {}", trace);
        }
    }

    private void processBatch(Model model, AddWorkspaceStore.Workspace workspace, RuntimeContext context,
            AddRequest request, int index, int total, List<Map<String, Object>> sources,
            List<String> recentSaves, AddDeadline deadline) throws IOException {
        for (int attempt = 1; attempt <= AgentRetryPolicy.MAX_ATTEMPTS; attempt++) {
            deadline.check();
            long expiresAt = System.nanoTime() + deadline.limit(properties.getTimeoutSeconds()).toNanos();
            AddMemoryAttempt writes = new AddMemoryAttempt(workspace.manager(), () -> checkBatchDeadline(deadline, expiresAt));
            try {
                processBatchAttempt(model, workspace, context, request, index, total, sources, recentSaves, deadline, writes, expiresAt, attempt);
                writes.seal();
                return;
            } catch (IOException | RuntimeException error) {
                try { writes.rollback(); }
                catch (IOException rollback) {
                    rollback.addSuppressed(error);
                    throw new IOException("Could not restore the failed Add attempt's daily ledgers; no retry is safe.", rollback);
                }
                writes.checkHealthy();
                if (error instanceof IOException storage) throw storage;
                deadline.check();
                if (attempt == AgentRetryPolicy.MAX_ATTEMPTS || !AgentRetryPolicy.retryable(error)) throw (RuntimeException) error;
                log.warn("[Add Agent级重试] {}，agent_attempt=1/2失败，已恢复本次台账写入；仅重跑当前小组，输入及来源ID不变；{}",
                        AddLogSupport.trace(context), AddLogSupport.failure(error));
            }
        }
        throw new IllegalStateException("Add Agent attempts exhausted.");
    }

    private static void checkBatchDeadline(AddDeadline deadline, long expiresAt) {
        deadline.check();
        if (System.nanoTime() >= expiresAt) throw new AddAgentException(HttpStatus.GATEWAY_TIMEOUT,
                "ADD_BATCH_TIMEOUT", "Add batch exceeded its deadline.");
    }

    private void processBatchAttempt(Model model, AddWorkspaceStore.Workspace workspace, RuntimeContext context,
            AddRequest request, int index, int total, List<Map<String, Object>> sources,
            List<String> recentSaves, AddDeadline deadline, AddMemoryAttempt writes, long batchExpiresAt, int attempt) {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("request_id", request.requestId());
        input.put("user_id", request.userId());
        input.put("session_id", request.sessionId());
        input.put("batch_index", index);
        input.put("total_batches", total);
        input.put("read_only_recent_saves", recentSaves);
        input.put("new_messages", ImageIngestionService.agentSources(sources));
        checkBatchDeadline(deadline, batchExpiresAt);
        Duration timeout = Duration.ofNanos(Math.max(1, batchExpiresAt - System.nanoTime()));
        Duration modelTimeout = deadline.limit(properties.getModelTimeoutSeconds());
        if (modelTimeout.compareTo(timeout) > 0) modelTimeout = timeout;
        LocalDate before = LocalDate.now();
        String trace = AddLogSupport.trace(context) + " agent_attempt=" + attempt + "/2";
        long batchStartedAt = System.nanoTime();
        // 每组独立上下文，只带同 Session 最近 Save 摘要，不注入长期记忆或通用工作区介绍。
        log.info("[Add Agent启动] {}，新消息数={}，只读Save摘要数={}，本组实际预算={}ms，模型单次预算={}ms，最大迭代次数={}",
                trace, sources.size(), recentSaves.size(), timeout.toMillis(), modelTimeout.toMillis(), properties.getMaxIters());
        try (ReActAgent agent = ReActAgent.builder()
                .name(AddWorkspaceStore.AGENT_NAME)
                .sysPrompt(AddAgentPrompts.SYSTEM_PROMPT)
                .model(new SingleRetryModel(model, Duration.ofSeconds(properties.getModelTimeoutSeconds()), () -> {
                    writes.checkHealthy();
                    Duration requestRemaining = deadline.remaining();
                    long batchRemaining = batchExpiresAt - System.nanoTime();
                    if (batchRemaining <= 0) throw new AddAgentException(HttpStatus.GATEWAY_TIMEOUT,
                            "ADD_BATCH_TIMEOUT", "Add batch exceeded its deadline.");
                    Duration batchBudget = Duration.ofNanos(batchRemaining);
                    return requestRemaining.compareTo(batchBudget) < 0 ? requestRemaining : batchBudget;
                }, "agent=add-memory " + trace, logRedactor)).toolkit(toolkit(workspace, writes))
                .maxIters(properties.getMaxIters())
                .modelExecutionConfig(ExecutionConfig.builder().timeout(modelTimeout).maxAttempts(1).build())
                .middleware(new AddToolGuardMiddleware())
                .middleware(new AddDeadlineMiddleware(deadline))
                .middleware(new AddLoggingMiddleware())
                .middleware(new AgentDebugLoggingMiddleware(debugLog, logRedactor))
                .build()) {
            Msg response;
            try {
                response = agent.call(AddWorkspaceStore.json(input), context).timeout(timeout).block();
            } catch (RuntimeException e) {
                log.warn("[Add批次推理失败] {}，耗时={}ms，{}；不会写本组完成检查点", trace,
                        AddLogSupport.elapsedMillis(batchStartedAt), AddLogSupport.failure(e));
                log.debug("[Add推理异常位置] {}，{}", trace, AddLogSupport.positions(e));
                agent.interrupt(context);
                deadline.check();
                if (e instanceof AddAgentException known) throw known;
                if (isTimeout(e)) {
                    boolean batchExpired = System.nanoTime() >= batchExpiresAt;
                    throw new AddAgentException(HttpStatus.GATEWAY_TIMEOUT,
                            batchExpired ? "ADD_BATCH_TIMEOUT" : "ADD_MODEL_TIMEOUT",
                            "Add batch or model call exceeded its deadline. Retry with the same request_id.", e);
                }
                throw new AddAgentException(HttpStatus.BAD_GATEWAY, "ADD_AGENT_FAILED",
                        "Add batch " + (index + 1) + " failed. Completed batches are checkpointed; retry with the same request_id.", e);
            }
            log.info("[Add Agent返回] {}，结束原因={}，回复字符数={}；开始校验工具回执与文件", trace,
                    response == null ? "null" : response.getGenerateReason(), response == null ? 0 : response.getTextContent().length());
            writes.checkHealthy();
            verifyCompletion(agent, context, workspace, response, before);
            deadline.check();
            log.info("[Add批次治理完成] {}，耗时={}ms，落盘校验已通过", trace, AddLogSupport.elapsedMillis(batchStartedAt));
        }
    }

    private Toolkit toolkit(AddWorkspaceStore.Workspace workspace, AddMemoryAttempt writes) {
        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(new MemorySaveTool(workspace.manager(), writes));
        toolkit.registerTool(new SessionSearchTool(workspace.manager()));
        return toolkit;
    }

    private void verifyCompletion(ReActAgent agent, RuntimeContext context, AddWorkspaceStore.Workspace workspace,
            Msg response, LocalDate before) {
        String trace = AddLogSupport.trace(context);
        // 只相信正常结束、真实工具回执与磁盘内容，不能凭 Agent 口头确认写完成检查点。
        if (response == null || response.getGenerateReason() != GenerateReason.MODEL_STOP) {
            throw incomplete("Agent did not finish normally; no completion checkpoint was written.");
        }
        Map<String, ToolUseBlock> calls = new HashMap<>();
        Map<String, Integer> callRounds = new HashMap<>();
        List<ToolResultBlock> results = new ArrayList<>();
        int round = 0;
        for (Msg message : agent.getAgentState(context).getContext()) {
            List<ToolUseBlock> messageCalls = message.getContentBlocks(ToolUseBlock.class);
            if (!messageCalls.isEmpty()) round++;
            for (ToolUseBlock call : messageCalls) {
                calls.put(call.getId(), call);
                callRounds.put(call.getId(), round);
            }
            results.addAll(message.getContentBlocks(ToolResultBlock.class));
        }
        boolean saved = false;
        boolean attemptedSave = false;
        Set<String> unresolvedWrites = new HashSet<>();
        Set<String> returnedCalls = new HashSet<>();
        int verifiedSaves = 0;
        int rejectedValidationRound = -1;
        int successfulSaveRound = -1;
        for (ToolResultBlock result : results) {
            ToolUseBlock call = calls.get(result.getId());
            String name = call == null ? result.getName() : call.getName();
            if (!"memory_save".equals(name)) continue;
            returnedCalls.add(result.getId());
            Map<String, Object> arguments = call == null ? Map.of() : arguments(call);
            String operation = saveIdentity(arguments);
            String text = resultText(result);
            attemptedSave = true;
            // 校验拒绝发生在落盘前，修正后的正文会变化，不能把旧参数哈希永久当成悬挂写入。
            // 全部被拒绝仍会因 saved=false 而失败，不能用 NO_REPLY 掩盖未完成保存。
            if (call != null && result.getState() == ToolResultState.SUCCESS
                    && text.startsWith(MemorySaveTool.VALIDATION_ERROR_PREFIX)) {
                rejectedValidationRound = Math.max(rejectedValidationRound, callRounds.get(call.getId()));
                continue;
            }
            if (call == null || result.getState() != ToolResultState.SUCCESS || !text.startsWith("Saved ")) {
                log.warn("[Add写入回执不合格] {}，tool={}，tool_call_id={}，SDK状态={}，原生返回错误={}；不打印回执正文", trace,
                        AddLogSupport.value(name), AddLogSupport.value(result.getId()), result.getState(), text.startsWith("Error:"));
                unresolvedWrites.add(operation);
                continue;
            }
            // 仅同一写入意图的成功重试能够抵消失败，其他成功写入不能掩盖它。
            unresolvedWrites.remove(operation);
            Object supplied = arguments.get("content");
            if (!(supplied instanceof String content) || content.isBlank()) {
                unresolvedWrites.add(operation);
                continue;
            }
            // 使用和工具相同的规范化内容，不能拿别名格式的原参数去匹配实际落盘正文。
            String normalized;
            try {
                normalized = new MemorySaveTool(workspace.manager()).preparedContent(context, content);
            } catch (IOException | IllegalArgumentException invalid) {
                throw incomplete("The saved source references could not be verified.");
            }
            String section = "\n" + normalized + "\n";
            boolean daily = workspace.manager().readManagedWorkspaceFileUtf8(context, "memory/" + before + ".md").contains(section)
                    || workspace.manager().readManagedWorkspaceFileUtf8(context, "memory/" + LocalDate.now() + ".md").contains(section);
            if (!daily) {
                log.warn("[Add台账校验失败] {}，tool_call_id={}，每日台账命中={}，保存内容字符数={}", trace,
                        AddLogSupport.value(call.getId()), daily, content.length());
                throw incomplete("memory_save returned but the daily ledger write could not be verified.");
            }
            saved = true;
            successfulSaveRound = Math.max(successfulSaveRound, callRounds.get(call.getId()));
            verifiedSaves++;
            log.info("[Add记忆台账校验通过] {}，tool_call_id={}，保存内容字符数={}", trace,
                    AddLogSupport.value(call.getId()), content.length());
        }
        if (!unresolvedWrites.isEmpty() || calls.values().stream()
                .anyMatch(call -> "memory_save".equals(call.getName()) && !returnedCalls.contains(call.getId()))) {
            log.warn("[Add完成校验拒绝] {}，未解决写入数={}，仍可能存在缺失回执；请保持原 request_id 续跑", trace, unresolvedWrites.size());
            throw incomplete("Agent left an unresolved or unacknowledged write; no completion checkpoint was written.");
        }
        // 同一轮并行提交的另一个成功 Save 不能掩盖错误；必须在拿到错误之后的新一轮完成修正。
        if (rejectedValidationRound >= 0 && successfulSaveRound <= rejectedValidationRound)
            throw incomplete("Agent left unresolved memory_save validation errors; correct them and save again.");
        if (!saved && (attemptedSave || !"NO_REPLY".equals(response.getTextContent().strip()))) {
            log.warn("[Add完成校验拒绝] {}，尝试保存={}，有效保存次数={}，既未落盘也非有效 NO_REPLY", trace, attemptedSave, verifiedSaves);
            throw incomplete("Agent must persist memory through memory_save or explicitly return NO_REPLY.");
        }
        log.info("[Add完成校验通过] {}，工具调用数={}，工具回执数={}，有效保存次数={}，无新增记忆={}", trace,
                calls.size(), results.size(), verifiedSaves, !saved);
    }

    private Map<String, Object> arguments(ToolUseBlock call) {
        if (call.getContent() == null || call.getContent().isBlank()) return call.getInput();
        return JSON.readValue(call.getContent(), new tools.jackson.core.type.TypeReference<Map<String, Object>>() {});
    }

    private String saveIdentity(Map<String, Object> arguments) {
        if (arguments.get("content") instanceof String content)
            return AddWorkspaceStore.hash(MemoryProvenanceNormalizer.normalize(content).strip());
        return AddWorkspaceStore.hash(AddWorkspaceStore.json(arguments));
    }

    private String resultText(ToolResultBlock result) {
        String text = result.getOutput().stream()
                .filter(block -> block instanceof io.agentscope.core.message.TextBlock)
                .map(block -> ((io.agentscope.core.message.TextBlock) block).getText()).reduce("", String::concat);
        // 原生 SDK 会把 String 回执再编码成 JSON 字符串，需先解码才能识别 Saved/Error 前缀。
        if (text.startsWith("\"")) {
            try { return JSON.readTree(text).asString(); } catch (RuntimeException ignored) { /* Not a valid receipt. */ }
        }
        return text;
    }

    private boolean isTimeout(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof TimeoutException) return true;
        }
        return false;
    }

    List<List<AddMessage>> split(List<AddMessage> messages) {
        List<List<AddMessage>> groups = new ArrayList<>();
        int start = 0;
        while (start < messages.size()) {
            int end = start;
            int chars = 0;
            while (end < messages.size() && end - start < properties.getBatchSize()) {
                int size = messages.get(end).content().toString().length() + 128;
                if (size > properties.getMaxBatchChars()) throw oversized();
                if (chars + size > properties.getMaxBatchChars()) break;
                chars += size;
                end++;
            }
            // 5 条是软目标；预算允许时带上相邻助手回复，尽量不拆开问答。
            if (end < messages.size() && "user".equals(messages.get(end - 1).role())
                    && "assistant".equals(messages.get(end).role())
                    && chars + messages.get(end).content().toString().length() + 128 <= properties.getMaxBatchChars()) {
                end++;
            }
            groups.add(List.copyOf(messages.subList(start, end)));
            start = end;
        }
        return groups;
    }

    private void validateIdentifiers(AddRequest request) {
        for (String id : List.of(request.requestId(), request.userId(), request.sessionId())) {
            try {
                // 不改写接口 ID；进入模型调用和落盘之前校验其安全路径映射。
                WorkspaceIdentity.pathSegment(id);
            } catch (IllegalArgumentException invalid) {
                throw new AddAgentException(HttpStatus.UNPROCESSABLE_CONTENT, "ADD_INVALID_IDENTIFIER",
                        invalid.getMessage());
            }
        }
    }

    // Search 默认复用同一文本模型配置；只返回客户端，不在此发起推理。
    Model model() {
        Model provided = suppliedModel.get();
        if (provided != null) return provided;
        if (properties.getApiKey() == null || properties.getApiKey().isBlank()) {
            throw new AddAgentException(HttpStatus.SERVICE_UNAVAILABLE, "ADD_MODEL_NOT_CONFIGURED",
                    "Configure sage.memory.add.api-key / OPENAI_API_KEY or supply an AgentScope Model bean.");
        }
        if (properties.getBaseUrl() != null && !properties.getBaseUrl().isBlank()) {
            try {
                var uri = java.net.URI.create(properties.getBaseUrl());
                if (!("https".equals(uri.getScheme()) || "http".equals(uri.getScheme())) || uri.getHost() == null) {
                    throw new IllegalArgumentException("Invalid model base URL");
                }
            } catch (IllegalArgumentException e) {
                throw new AddAgentException(HttpStatus.SERVICE_UNAVAILABLE, "ADD_MODEL_CONFIGURATION_INVALID",
                        "sage.memory.add.base-url must be an absolute HTTP(S) URL.", e);
            }
        }
        if (configuredModel == null) {
            synchronized (this) {
                if (configuredModel == null) {
                    var builder = OpenAIChatModel.builder().apiKey(properties.getApiKey())
                            .modelName(properties.getModelName()).stream(false);
                    if (properties.getBaseUrl() != null && !properties.getBaseUrl().isBlank()) builder.baseUrl(properties.getBaseUrl());
                    configuredModel = builder.build();
                    log.info("[Add模型初始化] model={}，已创建兼容 OpenAI 的客户端；不打印密钥或完整地址",
                            AddLogSupport.value(properties.getModelName()));
                }
            }
        }
        return configuredModel;
    }

    private AddResponse success(AddRequest request) {
        return new AddResponse(true, request.requestId(), request.userId(), request.sessionId());
    }

    private AddAgentException incomplete(String message) {
        return new AddAgentException(HttpStatus.BAD_GATEWAY, "ADD_BATCH_INCOMPLETE", message);
    }

    private AddAgentException oversized() {
        return new AddAgentException(HttpStatus.UNPROCESSABLE_CONTENT, "ADD_MESSAGE_TOO_LARGE",
                "A source message exceeds sage.memory.add.max-batch-chars; it was not truncated.");
    }

    @PreDestroy
    public void close() { tasks.close(); images.close(); }
}
