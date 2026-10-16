package com.pa.sagememory.agent.mergeMemoryTools;

import com.pa.sagememory.agent.AddLogSupport;
import com.pa.sagememory.agent.AddAgentProperties;
import com.pa.sagememory.agent.workspace.AddWorkspaceStore;
import com.pa.sagememory.agent.workspace.WorkspaceIdentity;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.json.JsonMapper;

/** 单次归并共享的文件上下文；用户路径由宿主绑定，模型无法选择文件。 */
public final class MergeMemoryContext {
    private static final Logger log = LoggerFactory.getLogger(MergeMemoryContext.class);
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Pattern LINE = Pattern.compile("([^\\r\\n]*)(\\r\\n|\\n|\\r|$)");
    static final Pattern HEADING = Pattern.compile("^ {0,3}(#{1,6})[ \\t]+(.+?)[ \\t]*#*[ \\t]*$");
    private static final Pattern FENCE = Pattern.compile("^ {0,3}(`{3,}|~{3,})(.*)$");
    private final String userId;
    private final Path userRoot;
    private final Path memory;
    private String lintedContent;
    private boolean closed;
    private final Runnable checkDeadline;
    private final int maxChars;
    private MergeEvidenceReader evidenceReader;

    public MergeMemoryContext(AddWorkspaceStore store, String userId) {
        this(store, userId, () -> {});
    }

    /** 截止检查在文件临界区内执行，超时取消后排队的工具不能继续写入。 */
    public MergeMemoryContext(AddWorkspaceStore store, String userId, Runnable checkDeadline) {
        this(store, userId, checkDeadline, AddAgentProperties.DEFAULT_CONSOLIDATION_MAX_CHARS);
    }

    public MergeMemoryContext(AddWorkspaceStore store, String userId, Runnable checkDeadline, int maxChars) {
        this.userId = Objects.requireNonNull(userId);
        this.userRoot = Objects.requireNonNull(store).userRoot(userId).toAbsolutePath().normalize();
        this.memory = userRoot.resolve("MEMORY.md");
        this.checkDeadline = Objects.requireNonNull(checkDeadline);
        if (maxChars < 1) throw new IllegalArgumentException("Memory character limit must be positive.");
        this.maxChars = maxChars;
    }

    public synchronized String snapshot() throws IOException { return read(); }

    /** 先封闭上下文，再推进归并水位；封闭后不接受任何迟到工具操作。 */
    public synchronized String sealAfterLint() throws IOException {
        checkDeadline.run();
        String current = read();
        if (lintedContent == null || !lintedContent.equals(current))
            throw new IllegalStateException("Latest memory has not passed lint_memory.");
        closed = true;
        return current;
    }

    /** 本轮失败时恢复原文，不能拿半成品继续对外宣称归并成功。 */
    public synchronized void rollback(String original) throws IOException {
        closed = true;
        if (!read().equals(original)) write(original);
    }

    /** 接入 Agent 时，宿主可在结束后确认通过检查的仍是最新文件，而不是修改前的版本。 */
    public synchronized boolean isLintPassedForLatestMemory() throws IOException {
        return lintedContent != null && lintedContent.equals(read());
    }

    ToolResultBlock getMemory() {
        return execute("get_memory", () -> {
            String content = read();
            return ToolResultBlock.text("Characters: " + content.length() + "/" + maxChars + "\n\n"
                    + (content.isEmpty() ? "MEMORY.md is empty. Use create_topic with a topic name and fact bullets to add new knowledge." : content));
        });
    }

    ToolResultBlock getEvidence(List<String> sourceIds) {
        return execute("get_evidence", () -> {
            List<String> ids = MergeEvidenceReader.sourceIds(sourceIds);
            // 与记忆文件不同，原始消息在 Add 用户锁内已固定；同一次归并只建一次只读索引。
            if (evidenceReader == null) evidenceReader = new MergeEvidenceReader(userRoot, this::checkPath, checkDeadline);
            MergeEvidenceReader.Result result = evidenceReader.retrieve(ids);
            log.info("[归并原始证据回查] user_id={}，返回消息数={}，图片描述数={}，缺失来源数={}，预算省略数={}；按同会话配对，不打印原文",
                    AddLogSupport.value(userId), result.messages(), result.images(), result.missing(), result.omitted());
            return result.messages() == 0 ? ToolResultBlock.error(result.text()) : ToolResultBlock.text(result.text());
        });
    }

    ToolResultBlock createTopic(String topic, String content) {
        return execute("create_topic", () -> {
            if (topic == null || topic.isBlank()) throw new IllegalArgumentException(
                    "topic must be a non-empty topic name without Markdown heading markers.");
            String name = topic.strip();
            var heading = HEADING.matcher("## " + name);
            if (name.codePoints().anyMatch(Character::isISOControl) || name.startsWith("#")
                    || !heading.matches() || !heading.group(2).strip().equals(name))
                throw new IllegalArgumentException("topic must be one plain topic name, without newlines or Markdown heading markers. The host generates ## automatically.");
            String body = topicBody(content);
            String current = read();
            List<Heading> existing = headings(lines(current)).stream().filter(h -> h.level() > 1
                    && (h.title().equalsIgnoreCase(name) || h.path().equalsIgnoreCase(name))).toList();
            if (!existing.isEmpty()) throw new IllegalArgumentException(existingTopicFeedback(current, existing));
            String newline = current.contains("\r\n") ? "\r\n" : "\n";
            if (current.isBlank()) current = "# MEMORY.md" + newline + newline;
            String separator = current.endsWith(newline + newline) ? "" : current.endsWith(newline) ? newline : newline + newline;
            int position = current.length();
            String inserted = separator + "## " + name + newline + normalizeNewlines(body, newline) + newline + newline;
            String updated = current + inserted;
            write(updated);
            return changeReceipt("Created topic '" + name + "'", updated, position, updated.length());
        });
    }

    ToolResultBlock appendMemory(String anchor, String content, String topic) {
        return execute("append_memory", () -> {
            if (content == null || content.isBlank()) throw new IllegalArgumentException(
                    "append_memory content must be non-empty. Put every new fact in content; anchor only locates existing text. For a normal append, supply topic and content and omit anchor. Example: " + appendExample(topic) + ". No changes made.");
            if (topic == null || topic.isBlank()) throw new IllegalArgumentException(
                    "append_memory requires an existing topic name or Parent > Child path. Supply topic and content; normally omit anchor. No changes made.");
            if (content.lines().anyMatch(line -> HEADING.matcher(line).matches()))
                throw new IllegalArgumentException("append_memory inserts text, not topic headings. Use create_topic with a plain topic name and fact bullets to create a topic; use edit_memory to rename one. No changes made.");
            String current = read();
            if (current.isBlank()) throw new IllegalArgumentException(
                    "MEMORY.md is empty; there is no topic to append to. Existing topics: (none). Use create_topic with a topic name and fact bullets. No changes made.");
            String topicName = topic.strip();
            Range scope = appendScope(current, topicName);
            boolean anchored = anchor != null;
            int position;
            if (anchored) {
                if (anchor.isBlank()) throw new IllegalArgumentException(appendAnchorFeedback(
                        "anchor must be non-empty when supplied; omit it for a normal topic append.", current, scope, topicName));
                String scopedContent = current.substring(scope.start(), scope.end());
                if (!scopedContent.contains(anchor)) throw new IllegalArgumentException(appendAnchorFeedback(
                        "Anchor not found. No exact match for the supplied anchor exists in this topic.", current, scope, topicName));
                try {
                    position = scope.start() + uniquePosition(scopedContent, anchor) + anchor.length();
                } catch (IllegalArgumentException invalid) {
                    throw new IllegalArgumentException(appendAnchorFeedback(invalid.getMessage(), current, scope, topicName));
                }
            } else {
                Heading selected = selectTopic(current, topicName);
                Range body = directTopicScope(current, selected.line().start() + 1);
                // 插在本主题最后一条非空行后、原分隔空行前，不落入末尾的子主题。
                position = body.start();
                for (Line line : lines(current.substring(body.start(), body.end())))
                    if (!line.text().isBlank()) position = body.start() + line.start() + line.text().length();
            }
            String newline = current.contains("\r\n") ? "\r\n" : "\n";
            String inserted = normalizeNewlines(content, newline);
            int skipped = 0;
            List<Line> incoming = lines(inserted);
            boolean factList = incoming.stream().filter(line -> !line.text().isBlank())
                    .allMatch(line -> line.text().matches("^\\s*[-*+]\\s+.+$"));
            if (!anchored && !factList) throw new IllegalArgumentException(
                    "Without anchor, content must contain self-contained Markdown fact bullet lines. For an inline addition, supply an existing exact anchor and the needed spaces. Example: "
                            + appendExample(topicName) + ". No changes made.");
            if (factList) {
                // 不允许自动换行把旧事实及其引用从中间拆开。
                int lineEnd = position;
                while (lineEnd < current.length() && current.charAt(lineEnd) != '\n' && current.charAt(lineEnd) != '\r') lineEnd++;
                if (!current.substring(position, lineEnd).isBlank()) throw new IllegalArgumentException(appendAnchorFeedback(
                        "Fact bullets require an anchor at the end of an existing line; the supplied anchor ends inside a line.", current, scope, topicName));
                // 只对完整事实行做字面去重；行内短句保持精确插入语义，不按来源 ID 或语义去重。
                Range factScope = directTopicScope(current, position);
                var existing = new LinkedHashSet<String>();
                lines(current.substring(factScope.start(), factScope.end())).forEach(line -> existing.add(line.text()));
                List<String> remaining = new ArrayList<>();
                for (Line line : incoming) {
                    if (line.text().isBlank()) continue;
                    if (!existing.add(line.text())) skipped++;
                    else remaining.add(line.text());
                }
                if (remaining.isEmpty()) return ToolResultBlock.text("No change needed; all submitted fact lines already exist in this topic. MEMORY.md is unchanged. Skipped identical fact lines: " + skipped + ".");
                String leading = position > 0 && current.charAt(position - 1) != '\n' && current.charAt(position - 1) != '\r' ? newline : "";
                String trailing = position == current.length() || current.charAt(position) != '\n' && current.charAt(position) != '\r' ? newline : "";
                inserted = leading + String.join(newline, remaining) + trailing;
            }
            String updated = current.substring(0, position) + inserted + current.substring(position);
            write(updated);
            return changeReceipt("Appended; skipped identical fact lines: " + skipped, updated, position, position + inserted.length());
        });
    }

    ToolResultBlock editMemory(String anchor, String replacement, String topic) {
        return execute("edit_memory", () -> {
            requireContent(replacement);
            String current = read();
            Range scope = scope(current, topic);
            int position = scope.start() + uniquePosition(current.substring(scope.start(), scope.end()), anchor);
            String updated = current.substring(0, position) + replacement + current.substring(position + anchor.length());
            if (updated.equals(current)) return ToolResultBlock.text("No change needed; MEMORY.md is unchanged.");
            write(updated);
            return changeReceipt("Updated", updated, position, position + replacement.length());
        });
    }

    ToolResultBlock deleteMemory(String targetType, String target, String topic) {
        return execute("delete_memory", () -> {
            if (target == null || target.isBlank()) throw new IllegalArgumentException("target must not be empty.");
            String current = read();
            List<Line> lines = lines(current);
            int start;
            int end;
            if ("topic".equals(targetType)) {
                if (topic != null && !topic.isEmpty()) throw new IllegalArgumentException("topic scope is used only for line deletion.");
                Range selected = topicRange(current, selectTopic(current, target));
                start = selected.start();
                end = selected.end();
            } else if ("line".equals(targetType)) {
                if (target.contains("\n") || target.contains("\r")) throw new IllegalArgumentException("line target must be a single-line text fragment without newlines.");
                Range scope = scope(current, topic);
                List<Line> candidates = lines.stream().filter(line -> line.start() >= scope.start() && line.start() < scope.end()).toList();
                List<Line> matches = candidates.stream().filter(line -> line.text().equals(target)).toList();
                // 优先完整行精确匹配；否则允许省略列表符号、引用或提供独特原文片段，但不做语义模糊匹配。
                if (matches.isEmpty()) matches = candidates.stream().filter(line -> line.text().contains(target.strip())).toList();
                if (matches.isEmpty()) throw new IllegalArgumentException("Line not found. No deletion was performed. Copy a distinctive text fragment from get_memory; the bullet marker and source annotations may be omitted. Example: target_type=line, target=unique fact text.");
                Line selected = matches.get(0);
                var heading = HEADING.matcher(selected.text());
                if (heading.matches()) throw new IllegalArgumentException("Use topic deletion for headings; the document root cannot be deleted.");
                start = selected.start();
                end = selected.end();
                String deletedAt = location(current, start);
                String updated = current.substring(0, start) + current.substring(end);
                write(updated);
                return changeReceipt("Deleted first matching line at " + deletedAt + "; matched lines: " + matches.size()
                        + "; removed: 1; remaining matching lines: " + (matches.size() - 1), updated, start, start);
            } else throw new IllegalArgumentException("target_type must be topic or line.");
            String updated = current.substring(0, start) + current.substring(end);
            write(updated);
            return changeReceipt("Deleted " + targetType, updated, start, start);
        });
    }

    ToolResultBlock lintMemory() {
        return execute("lint_memory", () -> {
            lintedContent = null;
            String current = read();
            List<String> issues = MemoryLintChecker.check(current, sourceDates());
            if (current.length() > maxChars) issues.add(0, "- Memory has " + current.length() + " characters, exceeding " + maxChars
                    + ". Merge repetition and trim low-value or stale details while preserving important facts and references.");
            if (!issues.isEmpty()) {
                log.warn("[归并记忆检查未通过] user_id={}，问题数={}；返回行号与原因，不修改文件", AddLogSupport.value(userId), issues.size());
                return ToolResultBlock.error("Lint failed:\n" + String.join("\n", issues.stream().limit(50).toList())
                        + (issues.size() > 50 ? "\nAdditional issues: " + (issues.size() - 50) : ""));
            }
            lintedContent = current;
            return ToolResultBlock.text("Lint passed: structure, exact duplicates, source IDs and dates checked. Characters: "
                    + current.length() + "/" + maxChars + ". This check does not verify semantic truth or whether pending saves were fully considered."
                    + (current.isBlank() ? " Memory is empty; this is valid only if no supported, durable facts need saving." : ""));
        });
    }

    /** 同一 Agent 即使同轮发出多个工具调用，也只能串行执行读改写，避免丢失更新。 */
    private synchronized ToolResultBlock execute(String tool, Operation operation) {
        long started = System.nanoTime();
        try {
            if (closed) return ToolResultBlock.error("This memory merge is closed; no further operations are allowed.");
            checkDeadline.run();
            ToolResultBlock result = operation.run();
            // SDK 的 text() 默认是 RUNNING；同步工具返回时显式结束，错误回执保持 ERROR。
            if (result.getState() != ToolResultState.ERROR) result = result.withState(ToolResultState.SUCCESS);
            log.info("[归并工具执行完成] user_id={}，tool={}，状态={}，耗时={}ms；不打印记忆正文",
                    AddLogSupport.value(userId), tool, result.getState(), AddLogSupport.elapsedMillis(started));
            return result;
        } catch (IllegalArgumentException invalid) {
            log.warn("[归并工具参数拒绝] user_id={}，tool={}；未执行目标修改", AddLogSupport.value(userId), tool);
            return ToolResultBlock.error(invalid.getMessage());
        } catch (IOException | RuntimeException failure) {
            log.warn("[归并工具存储失败] user_id={}，tool={}，异常类型={}；不打印文件内容或异常原文",
                    AddLogSupport.value(userId), tool, failure.getClass().getSimpleName());
            return ToolResultBlock.error("Memory storage operation failed; check workspace files and retry.");
        }
    }

    private String read() throws IOException {
        checkPath(memory);
        if (!Files.exists(memory, LinkOption.NOFOLLOW_LINKS)) return "";
        if (!Files.isRegularFile(memory, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Memory must be a regular file.");
        return Files.readString(memory, StandardCharsets.UTF_8);
    }

    private void write(String content) throws IOException {
        checkPath(memory);
        Files.createDirectories(userRoot);
        Path temporary = Files.createTempFile(userRoot, ".merge-memory-", ".tmp");
        try {
            Files.writeString(temporary, content, StandardCharsets.UTF_8);
            try {
                Files.move(temporary, memory, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, memory, StandardCopyOption.REPLACE_EXISTING);
            }
            // 每次成功修改后立即失效，不允许拿旧版本的 Lint 结果作为完成依据。
            lintedContent = null;
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private Map<String, String> sourceDates() throws IOException {
        Path sessions = userRoot.resolve("agents/" + AddWorkspaceStore.AGENT_NAME + "/sessions");
        checkPath(sessions);
        Map<String, String> dates = new LinkedHashMap<>();
        if (!Files.exists(sessions, LinkOption.NOFOLLOW_LINKS)) return dates;
        try (var files = Files.list(sessions)) {
            for (Path file : files.filter(path -> path.getFileName().toString().endsWith(".jsonl")
                    && !path.getFileName().toString().endsWith(".log.jsonl")).toList()) {
                checkPath(file);
                if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Session must be a regular file.");
                String sessionKey = file.getFileName().toString().replaceFirst("\\.jsonl$", "");
                try (var reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        if (line.isBlank()) continue;
                        var entry = JSON.readTree(line);
                        if (!"message".equals(entry.path("type").asString())) continue;
                        var source = JSON.readTree(entry.path("content").asString());
                        String id = source.path("source_id").asString();
                        if (!id.matches("[1-9][0-9]*") || !id.equals(entry.path("id").asString())
                                || !sessionKey.equals(WorkspaceIdentity.pathSegment(source.path("session_id").asString())))
                            throw new IOException("Source identity is inconsistent.");
                        String date = source.path("source_date").isString() ? source.path("source_date").asString() : "unknown";
                        if (!date.equals("unknown")) LocalDate.parse(date);
                        if (dates.putIfAbsent(id, date) != null) throw new IOException("Source IDs are duplicated.");
                    }
                }
            }
        }
        return dates;
    }

    private void checkPath(Path file) throws IOException {
        if (!file.toAbsolutePath().normalize().startsWith(userRoot)) throw new IOException("File is outside user workspace.");
        Path cursor = file.getRoot();
        for (Path part : file) {
            cursor = cursor.resolve(part);
            if (Files.isSymbolicLink(cursor)) throw new IOException("Workspace symlinks are not allowed.");
        }
    }

    private static int uniquePosition(String content, String anchor) {
        if (anchor == null || anchor.isBlank()) throw new IllegalArgumentException("anchor must be non-empty exact text.");
        int position = content.indexOf(anchor);
        if (position < 0) throw new IllegalArgumentException("Anchor not found. No changes made. Copy exact text from the latest memory or write receipt; if the topic is new, use create_topic instead.");
        // 从下一字符继续查，连重叠匹配也不能误判成唯一锚点。
        if (content.indexOf(anchor, position + 1) >= 0) throw new IllegalArgumentException("Anchor has multiple matches. No changes made. Include more surrounding text or supply an exact topic scope.");
        return position;
    }

    private static void requireContent(String content) {
        if (content == null || content.isBlank()) throw new IllegalArgumentException("Content must be non-empty; use delete_memory for deletion.");
    }

    /** 只校验新主题的内容形态，引用和日期仍由最终 Lint 统一检查。 */
    private static String topicBody(String content) {
        requireContent(content);
        String body = content.strip();
        boolean seenBullet = false;
        for (String line : body.lines().toList()) {
            if (line.isBlank()) continue;
            if (HEADING.matcher(line).matches() || FENCE.matcher(line).matches())
                throw new IllegalArgumentException("create_topic content must contain Markdown fact bullets, without headings or code fences. Put the topic name in topic; the host generates its heading. No changes made.");
            if (line.matches("^\\s*[-*+]\\s+.+$")) seenBullet = true;
            else if (!seenBullet || !(line.startsWith(" ") || line.startsWith("\t")))
                throw new IllegalArgumentException("create_topic content must use Markdown fact bullets, such as '- Fact (source_ids: 1, source_date: YYYY-MM-DD)'. No changes made.");
        }
        return body;
    }

    private static String normalizeNewlines(String content, String newline) {
        return content.replace("\r\n", "\n").replace('\r', '\n').replace("\n", newline);
    }

    private static List<Line> lines(String content) {
        List<Line> lines = new ArrayList<>();
        var matcher = LINE.matcher(content);
        while (matcher.find() && matcher.start() < content.length())
            lines.add(new Line(matcher.group(1), matcher.start(), matcher.end()));
        return lines;
    }

    private static List<Heading> headings(List<Line> lines) {
        List<Heading> result = new ArrayList<>();
        List<Heading> parents = new ArrayList<>();
        char fence = 0;
        int fenceLength = 0;
        for (Line line : lines) {
            var marker = FENCE.matcher(line.text());
            if (marker.matches()) {
                if (fence == 0) { fence = marker.group(1).charAt(0); fenceLength = marker.group(1).length(); }
                else if (marker.group(1).charAt(0) == fence && marker.group(1).length() >= fenceLength && marker.group(2).isBlank()) fence = 0;
                continue;
            }
            var heading = HEADING.matcher(line.text());
            if (fence == 0 && heading.matches()) {
                int level = heading.group(1).length();
                String title = heading.group(2).strip();
                parents.removeIf(parent -> parent.level() >= level);
                String path = String.join(" > ", parents.stream().filter(parent -> parent.level() > 1).map(Heading::title).toList());
                Heading item = new Heading(line, level, title, path.isEmpty() ? title : path + " > " + title);
                result.add(item); parents.add(item);
            }
        }
        return result;
    }

    private static Heading selectTopic(String current, String topic) {
        if (topic == null || topic.isBlank()) throw new IllegalArgumentException("topic must be a non-empty exact name or path.");
        List<Heading> matches = headings(lines(current)).stream().filter(h -> h.level() > 1
                && (h.title().equals(topic) || h.path().equals(topic))).toList();
        if (matches.isEmpty()) throw new IllegalArgumentException("Topic not found. No changes made. Copy an existing topic name or Parent > Child path from get_memory; for a new topic use create_topic with its name and fact bullets.");
        if (matches.size() > 1) throw new IllegalArgumentException("Topic name has multiple matches. Use an exact path: "
                + String.join(", ", matches.stream().limit(8).map(Heading::path).toList()));
        return matches.get(0);
    }

    private static Range topicRange(String current, Heading selected) {
        int end = headings(lines(current)).stream().filter(h -> h.line().start() > selected.line().start()
                && h.level() <= selected.level()).mapToInt(h -> h.line().start()).findFirst().orElse(current.length());
        return new Range(selected.line().start(), end);
    }

    /** 未传 topic 时也只在插入位置所属的直接主题内去重，不跨其他主题或子主题吞掉事实。 */
    private static Range directTopicScope(String current, int position) {
        List<Heading> topics = headings(lines(current));
        int start = topics.stream().filter(h -> h.line().start() < position)
                .mapToInt(h -> h.line().start()).max().orElse(0);
        int end = topics.stream().filter(h -> h.line().start() >= position)
                .mapToInt(h -> h.line().start()).min().orElse(current.length());
        return new Range(start, end);
    }

    /** 重名创建不写文件；返回最新主题原文，使模型可直接复制锚点并判断追加、修订或跳过。 */
    private static String existingTopicFeedback(String current, List<Heading> existing) {
        StringBuilder feedback = new StringBuilder("Topic already exists: ")
                .append(String.join(", ", existing.stream().limit(8).map(Heading::path).toList()))
                .append(". No changes made.\n\nCurrent topic content:\n");
        for (Heading heading : existing.stream().limit(8).toList()) {
            Range range = topicRange(current, heading);
            feedback.append("Topic: ").append(heading.path()).append('\n')
                    .append(current, range.start(), range.end()).append('\n');
        }
        if (existing.size() > 8) feedback.append("Additional matching topics: ").append(existing.size() - 8)
                .append(". Use get_memory to inspect them.\n");
        feedback.append("If the submitted content adds new facts, call append_memory with the exact existing topic and all new facts in content; normally omit anchor to append to the topic body end. ")
                .append("If it revises an existing fact, use edit_memory. If it is already covered, skip it. ")
                .append("If multiple topics are shown, choose the intended one and supply its exact Topic path as topic. Do not call create_topic again for an existing topic.");
        return feedback.toString();
    }

    private static Range scope(String current, String topic) {
        return topic == null || topic.isEmpty() ? new Range(0, current.length()) : topicRange(current, selectTopic(current, topic));
    }

    /** append 传错主题时展示完整主题目录，不把新事实当成文件锚点，也不自动创建或改写主题。 */
    private static Range appendScope(String current, String topic) {
        if (topic == null || topic.isBlank()) throw new IllegalArgumentException("append_memory requires an existing topic. No changes made.");
        List<Heading> topics = headings(lines(current)).stream().filter(h -> h.level() > 1).toList();
        if (topics.stream().noneMatch(h -> h.title().equals(topic) || h.path().equals(topic))) {
            String catalog = topics.isEmpty() ? "(none)" : String.join("\n", topics.stream().map(h -> "- " + h.path()).toList());
            throw new IllegalArgumentException("Topic not found: " + topic + ". No changes made.\nExisting topics:\n"
                    + catalog + "\nUse an exact existing topic name or Parent > Child path as topic. For a new topic, call create_topic with its name and fact bullets.");
        }
        return topicRange(current, selectTopic(current, topic));
    }

    private static String appendExample(String topic) {
        return JSON.writeValueAsString(Map.of("topic", topic == null || topic.isBlank() ? "Existing topic" : topic.strip(),
                "content", "- New fact (source_ids: 1, source_date: YYYY-MM-DD)"));
    }

    /** 显式错误锚点绝不降级写入；给出当前原文以及省略锚点的正常调用示例。 */
    private static String appendAnchorFeedback(String reason, String current, Range scope, String topic) {
        return reason + " No changes made.\n\nCurrent topic content:\nTopic: " + topic + "\n"
                + current.substring(scope.start(), scope.end())
                + "\nFor a normal append, supply topic and content and omit anchor. Put every new fact in content, not in anchor. "
                + "For a specific position, copy unique exact existing text from above, not a new fact from pending saves. "
                + "Example (format only; use actual source references): " + appendExample(topic)
                + ". Do not retry unchanged failed arguments.";
    }

    /** 小范围回执帮助模型观察结构，不返回整篇文件、不截断真实写入内容。 */
    private static ToolResultBlock changeReceipt(String action, String current, int start, int end) {
        List<Line> all = lines(current);
        int first = lineIndex(all, Math.min(start, current.length()));
        int last = lineIndex(all, Math.min(end, current.length()));
        StringBuilder preview = new StringBuilder(action).append(" in MEMORY.md. Characters: ").append(current.length())
                .append(". Change near lines ").append(first + 1).append('–').append(last + 1).append(".\nNearby content:\n");
        int previous = -1;
        for (int index = Math.max(0, first - 2); index < Math.min(all.size(), last + 3); index++) {
            if (index > first + 2 && index < last - 2) continue;
            if (previous >= 0 && index > previous + 1) preview.append("...\n");
            String line = all.get(index).text();
            preview.append(index + 1).append(": ").append(line.length() > 240 ? line.substring(0, 240) + "..." : line).append('\n');
            previous = index;
        }
        return ToolResultBlock.text(preview.toString());
    }

    private static int lineIndex(List<Line> lines, int position) {
        for (int index = 0; index < lines.size(); index++) if (position < lines.get(index).end()) return index;
        return Math.max(0, lines.size() - 1);
    }

    private static String location(String current, int position) {
        String topic = headings(lines(current)).stream().filter(h -> h.line().start() < position && h.level() > 1)
                .reduce((a, b) -> b).map(Heading::path).orElse("document root");
        return "line " + (lineIndex(lines(current), position) + 1) + " (" + topic + ")";
    }

    @FunctionalInterface private interface Operation { ToolResultBlock run() throws IOException; }
    private record Line(String text, int start, int end) {}
    private record Heading(Line line, int level, String title, String path) {}
    private record Range(int start, int end) {}
}
