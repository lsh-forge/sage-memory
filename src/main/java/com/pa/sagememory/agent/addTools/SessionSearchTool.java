/*
 * Copyright 2024-2026 the original author or authors.
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
// Adapted from AgentScope Harness 2.0.4 SessionSearchTool (Apache-2.0).
package com.pa.sagememory.agent.addTools;

import com.pa.sagememory.agent.AddLogSupport;
import com.pa.sagememory.agent.workspace.AddWorkspaceStore;
import com.pa.sagememory.agent.workspace.WorkspaceIdentity;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import io.agentscope.harness.agent.memory.session.SessionEntry;
import io.agentscope.harness.agent.memory.session.SessionTree;
import io.agentscope.harness.agent.workspace.WorkspaceManager;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Add 专用原话回查：当前 Session 内定位消息并展开前后文，不读取长期记忆或原图。 */
public class SessionSearchTool {
    private static final Logger log = LoggerFactory.getLogger(SessionSearchTool.class);
    private static final JsonMapper JSON = JsonMapper.builder().build();
    public static final String SOURCE_UPPER_BOUND = "session_search_source_upper_bound";
    private static final int MAX_ANCHORS = 5;
    private static final int NEIGHBORS = 2;
    private static final int MAX_RESPONSE_CHARS = 24000;
    private final WorkspaceManager workspaceManager;

    public SessionSearchTool(WorkspaceManager workspaceManager) {
        this.workspaceManager = workspaceManager;
    }

    @Tool(name = "session_search", readOnly = true, description = """
            Look up original messages in the current session when context is missing or ambiguous.
            Search by a keyword or phrase, or locate known source IDs, with surrounding messages.
            """)
    public String sessionSearch(RuntimeContext runtimeContext,
            @ToolParam(name = "query", description = "Keyword or phrase to find in original text or image descriptions.",
                    required = false) String query,
            @ToolParam(name = "source_ids", description = "Known source IDs to inspect, up to 5. Provide query or source_ids.",
                    required = false) List<String> sourceIds) {
        String keyword = query == null ? "" : query.strip();
        Set<String> ids = new LinkedHashSet<>();
        if (sourceIds != null) {
            if (sourceIds.size() > MAX_ANCHORS) return "Error: provide at most 5 source_ids.";
            for (String id : sourceIds) {
                if (id == null || !id.matches("[1-9][0-9]*")) return "Error: source_ids must be positive integer IDs.";
                ids.add(id);
            }
        }
        if (keyword.isEmpty() && ids.isEmpty()) return "Error: provide query or source_ids.";
        if (keyword.length() > 256) return "Error: query must not exceed 256 characters.";
        // 会话和消息上限是宿主传入的隐藏上下文，模型不能选择其他用户、Session 或后续批次。
        if (runtimeContext == null || runtimeContext.getSessionId() == null
                || runtimeContext.getSessionId().isBlank()) return "Error: current session scope is unavailable.";
        Long upperBound = runtimeContext.get(SOURCE_UPPER_BOUND, Long.class);
        if (upperBound == null || upperBound < 1) return "Error: current batch source scope is unavailable.";
        long startedAt = System.nanoTime();
        try {
            List<SourceMessage> messages = loadMessages(runtimeContext, upperBound);
            Set<Integer> anchors = new LinkedHashSet<>();
            Set<String> foundIds = new LinkedHashSet<>();
            for (int i = 0; i < messages.size(); i++) {
                if (ids.contains(messages.get(i).id())) {
                    anchors.add(i);
                    foundIds.add(messages.get(i).id());
                }
            }
            // 关键词有多个命中时优先最近消息；最后仍按会话原始顺序输出，便于判断确认和指代。
            String lower = keyword.toLowerCase(Locale.ROOT);
            for (int i = messages.size() - 1; !lower.isEmpty() && i >= 0 && anchors.size() < MAX_ANCHORS; i--) {
                SourceMessage message = messages.get(i);
                if ((message.date() + "\n" + message.text()).toLowerCase(Locale.ROOT).contains(lower)) anchors.add(i);
            }
            Set<String> missing = new LinkedHashSet<>(ids);
            missing.removeAll(foundIds);
            if (anchors.isEmpty()) {
                log.info("[Add会话原话回查] {}，会话消息数={}，命中点=0，返回消息数=0，未找到ID数={}，耗时={}ms",
                        AddLogSupport.trace(runtimeContext), messages.size(), missing.size(), AddLogSupport.elapsedMillis(startedAt));
                return "No matching messages in the current session."
                        + (missing.isEmpty() ? "" : " Unavailable source_ids: " + String.join(",", missing));
            }

            Set<Integer> positions = new TreeSet<>();
            for (int anchor : anchors) for (int i = Math.max(0, anchor - NEIGHBORS);
                    i <= Math.min(messages.size() - 1, anchor + NEIGHBORS); i++) positions.add(i);
            // 先给命中原话预留预算，再填相邻上下文；完整消息放不下则明确列出 ID，不悄悄截断证据。
            Set<Integer> included = new TreeSet<>();
            Set<String> omitted = new LinkedHashSet<>();
            int chars = 0;
            List<Integer> priority = new ArrayList<>(anchors);
            positions.stream().filter(i -> !anchors.contains(i)).forEach(priority::add);
            for (int position : priority) {
                SourceMessage message = messages.get(position);
                int size = display(message, anchors.contains(position)).length() + 2;
                if (chars + size > MAX_RESPONSE_CHARS) omitted.add(message.id());
                else { included.add(position); chars += size; }
            }
            StringBuilder output = new StringBuilder();
            for (int position : included) output.append(display(messages.get(position), anchors.contains(position))).append("\n\n");
            if (!missing.isEmpty()) output.append("Unavailable source_ids in the current session/batch: ")
                    .append(String.join(",", missing)).append('\n');
            if (!omitted.isEmpty()) output.append("Response size limit; omitted complete messages: ")
                    .append(String.join(",", omitted)).append(". Inspect fewer source_ids.\n");
            log.info("[Add会话原话回查] {}，会话消息数={}，命中点={}，返回消息数={}，未找到ID数={}，预算省略数={}，耗时={}ms",
                    AddLogSupport.trace(runtimeContext), messages.size(), anchors.size(), included.size(), missing.size(),
                    omitted.size(), AddLogSupport.elapsedMillis(startedAt));
            return output.toString().stripTrailing();
        } catch (IOException | RuntimeException failure) {
            // 不能将读取失败伪装成没有历史；不打印原始文件内容、查询参数或异常文本。
            log.warn("[Add会话原话回查失败] {}，exception_type={}；未返回其他会话内容",
                    AddLogSupport.trace(runtimeContext), failure.getClass().getSimpleName());
            return "Error: could not read the current session transcript.";
        }
    }

    private List<SourceMessage> loadMessages(RuntimeContext context, long upperBound) throws IOException {
        String sessionId = WorkspaceIdentity.pathSegment(context.getSessionId());
        Path root = workspaceManager.getWorkspace().toAbsolutePath().normalize();
        Path transcript = workspaceManager.resolveSessionContextFile(context, AddWorkspaceStore.AGENT_NAME, sessionId)
                .toAbsolutePath().normalize();
        ensureLocalPath(root, transcript);
        ensureLocalPath(root, transcript.resolveSibling(sessionId + ".log.jsonl"));
        if (!Files.isRegularFile(transcript)) return List.of();
        SessionTree tree = new SessionTree(transcript, root, null);
        tree.load();
        List<SourceMessage> messages = new ArrayList<>();
        for (SessionEntry.MessageEntry entry : tree.getMessageEntries()) {
            JsonNode source = JSON.readTree(entry.getContent());
            String id = source.path("source_id").asString();
            if (!id.matches("[1-9][0-9]*") || !id.equals(entry.getId())
                    || !context.getSessionId().equals(source.path("session_id").asString()))
                throw new IOException("Session source identity is inconsistent.");
            // 重试时磁盘可能已存在后续组消息，但本组回查不得偷看尚未进入其抽取范围的内容。
            if (Long.parseLong(id) > upperBound) continue;
            String role = source.path("role").asString();
            if (!(role.equals("user") || role.equals("assistant")) || !role.equals(entry.getRole()))
                throw new IOException("Session speaker attribution is inconsistent.");
            messages.add(new SourceMessage(id, role, source.path("source_date").isString()
                    ? source.path("source_date").asString() : "unknown", text(source.path("content"))));
        }
        return messages;
    }

    private static void ensureLocalPath(Path root, Path file) throws IOException {
        if (!file.startsWith(root) || Files.isSymbolicLink(root)) throw new IOException("Session path is outside workspace.");
        Path current = root;
        for (Path segment : root.relativize(file)) {
            current = current.resolve(segment);
            if (Files.isSymbolicLink(current)) throw new IOException("Session symbolic links are not allowed.");
        }
    }

    private static String text(JsonNode content) throws IOException {
        if (content.isString()) return content.asString();
        if (!content.isArray()) throw new IOException("Session content must be text or content blocks.");
        List<String> texts = new ArrayList<>();
        for (JsonNode part : content) if ("text".equals(part.path("type").asString())) texts.add(part.path("text").asString());
        // 仅返回增强正文中的图片描述，绝不把原图 Base64、路径、模型配置送回 Agent。
        return String.join("\n", texts);
    }

    private static String display(SourceMessage message, boolean matched) {
        return "source_id: " + message.id() + (matched ? " [matched]" : " [context]")
                + "\nrole: " + message.role() + "\nsource_date: " + message.date() + "\ncontent: " + message.text();
    }

    private record SourceMessage(String id, String role, String date, String text) {}
}
