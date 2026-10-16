/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
// Copied from AgentScope Harness 2.0.4 (Apache-2.0).
// Modified for sage-memory: relocated package; save to daily ledger only, consolidate MEMORY.md separately.
package com.pa.sagememory.agent.addTools;

import com.pa.sagememory.agent.AddLogSupport;
import com.pa.sagememory.agent.AddAgentException;
import com.pa.sagememory.agent.workspace.AddMemoryAttempt;
import com.pa.sagememory.agent.workspace.AddWorkspaceStore;
import com.pa.sagememory.agent.workspace.WorkspaceIdentity;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import io.agentscope.harness.agent.workspace.WorkspaceConstants;
import io.agentscope.harness.agent.workspace.WorkspaceManager;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import io.agentscope.harness.agent.memory.session.SessionTree;
import tools.jackson.databind.json.JsonMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;

/**
 * Dedicated tool for persisting user memories to today's append-only daily ledger
 * ({@code memory/YYYY-MM-DD.md}). {@code MEMORY.md} is updated separately by host consolidation.
 *
 * <p>This is the <b>only</b> write tool exposed to the Add agent. The program selects
 * the daily ledger path; the model supplies only the facts to save.
 *
 * <p>Writes are append-only; deduplication and size-trimming are handled by the
 * end-of-Add consolidation based on {@link io.agentscope.harness.agent.memory.MemoryConsolidator}.
 */
// 必要适配：Save 只追加台账，归并服务独占 MEMORY.md 的内容更新，避免双写重复输入。
public class MemorySaveTool {
    private static final Logger log = LoggerFactory.getLogger(MemorySaveTool.class);
    private static final JsonMapper JSON = JsonMapper.builder().build();
    // 仅由写入前校验返回；宿主可据此区分“尚未写入”和“写入失败”。
    public static final String VALIDATION_ERROR_PREFIX = "Error: validation: ";
    private final WorkspaceManager workspaceManager;
    private final AddMemoryAttempt attempt;

    public MemorySaveTool(WorkspaceManager workspaceManager) {
        this(workspaceManager, null);
    }

    public MemorySaveTool(WorkspaceManager workspaceManager, AddMemoryAttempt attempt) {
        this.workspaceManager = workspaceManager;
        this.attempt = attempt;
    }

    @Tool(
            name = "memory_save",
            description =
                    "Save supported facts, preferences, decisions, and events to the daily ledger only;"
                        + " it does not update MEMORY.md. Correct validation errors and retry.")
    public String memorySave(
            RuntimeContext runtimeContext,
            @ToolParam(
                            name = "content",
                            description =
                                    "One concise, self-contained fact per line; missing Markdown bullet markers are added automatically. End every fact"
                                            + " with (source_ids: 1,3). Copy original numeric IDs exactly; never add prefixes."
                                            + " source_date is optional; if provided, it must match the original messages."
                                            + " Example: - Finn can swim for hours (source_ids: 61, source_date: 2025-03-27).")
                    String content) {
        RuntimeContext rc = runtimeContext != null ? runtimeContext : RuntimeContext.empty();
        String normalized;
        try {
            // 整批通过后才追加；第二条错误不能造成第一条提前落盘。
            normalized = preparedContent(rc, content);
        } catch (IllegalArgumentException invalid) {
            log.warn("[Add保存引用校验拒绝] {}，原因={}；本次未写入，等待Agent修正", AddLogSupport.trace(rc), invalid.getMessage());
            return VALIDATION_ERROR_PREFIX + invalid.getMessage();
        } catch (IOException failure) {
            if (attempt != null) attempt.recordStorageFailure(failure);
            log.warn("[Add保存来源读取失败] {}，异常类型={}；本次未写入，不打印原消息或异常正文",
                    AddLogSupport.trace(rc), failure.getClass().getSimpleName());
            return VALIDATION_ERROR_PREFIX + "could not verify original sources; retry after checking session storage.";
        } catch (RuntimeException failure) {
            if (attempt != null) attempt.recordStorageFailure(new IOException("Could not access original sources.", failure));
            throw failure;
        }
        if (!normalized.equals(content.strip())) log.info("[Add来源标注规范化] {}，原字符数={}，规范后字符数={}；统一列表符号、source_ids并按原始消息核验或补齐日期，不打印正文、不推测来源",
                AddLogSupport.trace(rc), content.strip().length(), normalized.length());

        String today = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE);
        String dailyPath = WorkspaceConstants.MEMORY_DIR + "/" + today + ".md";
        String dailyEntry =
                String.format(
                        // Session 标识由宿主提供，放在标题内，不增加模型参数或额外证据条目。
                        "\n## Memory Save — %s | session_id=%s\n%s\n", Instant.now().toString(),
                        JSON.writeValueAsString(rc.getSessionId()), normalized);
        if (attempt == null) workspaceManager.appendUtf8WorkspaceRelative(rc, dailyPath, dailyEntry);
        else {
            try { attempt.append(rc, dailyPath, dailyEntry); }
            catch (IOException storage) { throw new AddAgentException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "ADD_STORAGE_FAILED", "Could not append the daily ledger safely.", storage); }
        }

        long count = normalized.lines().filter(l -> l.stripLeading().startsWith("-")).count();
        if (count == 0) {
            count = 1;
        }
        return "Saved " + count + " memor" + (count == 1 ? "y" : "ies") + " to " + dailyPath;
    }

    /** 宿主落盘验收使用同一规范正文，包含从原始消息补齐的日期，不能再比对模型原参数。 */
    public String preparedContent(RuntimeContext context, String content) throws IOException {
        if (content == null || content.isBlank()) throw new IllegalArgumentException("content is required.");
        Long upperBound = context.get(SessionSearchTool.SOURCE_UPPER_BOUND, Long.class);
        if (context.getSessionId() == null || context.getSessionId().isBlank() || upperBound == null || upperBound < 1)
            throw new IllegalArgumentException("current session/batch source scope is unavailable.");
        String session = WorkspaceIdentity.pathSegment(context.getSessionId());
        Path root = workspaceManager.getWorkspace().toAbsolutePath().normalize();
        Path transcript = workspaceManager.resolveSessionContextFile(context, AddWorkspaceStore.AGENT_NAME, session)
                .toAbsolutePath().normalize();
        ensureLocal(root, transcript);
        ensureLocal(root, transcript.resolveSibling(session + ".log.jsonl"));
        if (!Files.isRegularFile(transcript)) throw new IOException("Original session transcript is missing.");
        SessionTree tree = new SessionTree(transcript, root, null);
        Map<String, String> dates = new LinkedHashMap<>();
        try {
            tree.load();
            for (var entry : tree.getMessageEntries()) {
                var source = JSON.readTree(entry.getContent());
                String id = source.path("source_id").asString();
                if (!id.matches("[1-9][0-9]*") || !id.equals(entry.getId())
                        || !context.getSessionId().equals(source.path("session_id").asString()))
                    throw new IOException("Original source identity is inconsistent.");
                if (Long.parseLong(id) > upperBound) continue;
                String date = source.path("source_date").isString() ? source.path("source_date").asString() : "unknown";
                if (!date.equals("unknown")) LocalDate.parse(date);
                if (dates.putIfAbsent(id, date) != null) throw new IOException("Original source IDs are duplicated.");
            }
        } catch (RuntimeException invalid) {
            throw new IOException("Original source metadata cannot be read safely.");
        }
        return MemorySaveContentValidator.prepare(content, dates);
    }

    private static void ensureLocal(Path root, Path file) throws IOException {
        if (!file.startsWith(root) || Files.isSymbolicLink(root)) throw new IOException("Source is outside workspace.");
        Path cursor = root;
        for (Path part : root.relativize(file)) {
            cursor = cursor.resolve(part);
            if (Files.isSymbolicLink(cursor)) throw new IOException("Source symlinks are not allowed.");
        }
    }
}
