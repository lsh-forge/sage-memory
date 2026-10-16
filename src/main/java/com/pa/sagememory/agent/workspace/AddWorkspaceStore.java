package com.pa.sagememory.agent.workspace;

import com.pa.sagememory.agent.AddAgentException;
import com.pa.sagememory.agent.AddAgentProperties;
import com.pa.sagememory.agent.AddLogSupport;
import com.pa.sagememory.dto.AddMessage;
import com.pa.sagememory.dto.AddRequest;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.filesystem.local.LocalFilesystem;
import io.agentscope.harness.agent.memory.session.SessionEntry;
import io.agentscope.harness.agent.memory.session.SessionTree;
import io.agentscope.harness.agent.workspace.WorkspaceManager;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 同用户共享记忆、会话级原始消息、宿主独占检查点；检查点位于 Agent 文件工具作用域外。 */
@Component
public class AddWorkspaceStore {
    private static final Logger log = LoggerFactory.getLogger(AddWorkspaceStore.class);
    public static final String AGENT_NAME = "add-memory";
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final Path root;
    private final Path stateRoot;

    public AddWorkspaceStore(AddAgentProperties properties) {
        Path configured = Path.of(properties.getWorkspaceRoot());
        root = (configured.isAbsolute() ? configured : projectRoot().resolve(configured))
                .toAbsolutePath().normalize();
        stateRoot = root.resolveSibling(root.getFileName().toString().equals("workspace")
                ? "add-state" : root.getFileName() + "-add-state");
        log.info("[Add工作区配置] 记忆根目录={}，检查点根目录={}；业务 ID 保持原值，路径中的冒号映射为###，原始#转义为#23", root, stateRoot);
    }

    private static Path projectRoot() {
        Path current = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        if (Files.isRegularFile(current.resolve("pom.xml"))) return current;
        if (Files.isRegularFile(current.resolve("sage-memory/pom.xml"))) return current.resolve("sage-memory");
        return current;
    }

    public Path userRoot(String userId) {
        return root.resolve(WorkspaceIdentity.pathSegment(userId));
    }

    /** 宿主检查点始终位于用户的 Agent 文件工具作用域之外。 */
    public Path requestState(String userId, String requestId) {
        return stateRoot.resolve(WorkspaceIdentity.pathSegment(userId)).resolve(WorkspaceIdentity.pathSegment(requestId));
    }

    public Workspace open(AddRequest request) throws IOException {
        // 必须在同用户锁内调用；普通名称保持原路径，不自动迁移任何历史目录或文件。
        Path user = userRoot(request.userId());
        Path state = requestState(request.userId(), request.requestId());
        ensureDirectory(user);
        ensureDirectory(state);
        for (String directory : List.of("memory", "agents/" + AGENT_NAME + "/sessions")) {
            ensureDirectory(user.resolve(directory));
        }
        // 首次保持真正空文件；归并成功后才生成文档标题及长期知识，不写占位标题。
        createIfAbsent(user.resolve("MEMORY.md"), "");
        LocalFilesystem filesystem = new LocalFilesystem(user, true, 10);
        WorkspaceManager manager = new WorkspaceManager(user, filesystem, null);
        log.info("[Add工作区就绪] {}，用户目录={}，检查点目录={}", AddLogSupport.trace(request), user, state);
        return new Workspace(user, state, filesystem, manager);
    }

    public void checkRequest(Workspace workspace, AddRequest request, int batchSize, int maxBatchChars)
            throws IOException {
        Path source = workspace.state().resolve("request.json");
        String content = JSON.writeValueAsString(request);
        if (Files.exists(source)) {
            if (!Files.readString(source).equals(content)) {
                log.warn("[Add落盘请求冲突] {}，原请求记录与本次内容不一致", AddLogSupport.trace(request));
                throw conflict("request_id was reused with different source messages.");
            }
            log.debug("[Add原请求复用] {}，原请求记录已存在", AddLogSupport.trace(request));
        } else {
            atomicWrite(source, content);
            log.info("[Add原请求落盘] {}，文件={}，序列化字符数={}", AddLogSupport.trace(request), source, content.length());
        }
        Path settings = workspace.state().resolve("grouping.txt");
        String grouping = batchSize + ":" + maxBatchChars;
        if (Files.exists(settings) && !Files.readString(settings).equals(grouping)
                && !isComplete(workspace)) {
            log.warn("[Add分组配置冲突] {}，未完成请求的分组配置发生变化，拒绝继续", AddLogSupport.trace(request));
            throw conflict("Batch grouping configuration changed during an unfinished request.");
        }
        if (!Files.exists(settings)) atomicWrite(settings, grouping);
    }

    public boolean isComplete(Workspace workspace) {
        return Files.isRegularFile(workspace.state().resolve("complete"));
    }

    public boolean isBatchComplete(Workspace workspace, int index, String fingerprint) throws IOException {
        Path marker = batchMarker(workspace, index);
        if (!Files.exists(marker)) return false;
        if (!Files.readString(marker).equals(fingerprint)) throw conflict("Completed batch sources do not match this request.");
        return true;
    }

    public void completeBatch(Workspace workspace, int index, String fingerprint) throws IOException {
        // 原子替换标记文件；只在 Agent 正常结束且真实记忆落盘校验通过后调用。
        atomicWrite(batchMarker(workspace, index), fingerprint);
    }

    public void completeRequest(Workspace workspace) throws IOException {
        atomicWrite(workspace.state().resolve("complete"), Instant.now().toString());
    }

    /** 原图按图片 ID 存储；同 ID 重试校验内容，不能覆盖另一张图。模型调用不在锁内，仍并行。 */
    public synchronized String saveImage(Workspace workspace, String imageId, byte[] bytes, String mediaType) throws IOException {
        Path directory = workspace.user().resolve("assets/images");
        ensureDirectory(directory);
        String relative = ImageIdentity.relativePath(imageId, mediaType);
        Path image = workspace.user().resolve(relative);
        // 同一图片 ID 只能对应一种格式，避免 p1-1.jpg / p1-1.png 指向不同内容。
        for (String type : List.of("image/jpeg", "image/png", "image/webp")) {
            Path candidate = workspace.user().resolve(ImageIdentity.relativePath(imageId, type));
            if (Files.isSymbolicLink(candidate)) throw new IOException("Image symbolic links are not allowed.");
            if (!candidate.equals(image) && Files.exists(candidate)) throw new IOException("Image ID already exists with a different media type.");
        }
        if (Files.isSymbolicLink(image)) throw new IOException("Image symbolic links are not allowed.");
        if (Files.exists(image)) {
            if (!Arrays.equals(Files.readAllBytes(image), bytes)) throw new IOException("Stored image content mismatch.");
        } else {
            Path temporary = Files.createTempFile(directory, ".image-", ".tmp");
            try {
                Files.write(temporary, bytes);
                try {
                    Files.move(temporary, image, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                } catch (AtomicMoveNotSupportedException unavailable) {
                    Files.move(temporary, image, StandardCopyOption.REPLACE_EXISTING);
                }
            } finally {
                Files.deleteIfExists(temporary);
            }
        }
        return relative;
    }

    /** 每张图成功后立即保存描述；失败重试只识别尚未完成的图，不重新生成已成功描述。 */
    public Map<String, String> loadImageCaption(Workspace workspace, String key, String hash) throws IOException {
        Path directory = workspace.state().resolve("image-captions");
        ensureDirectory(directory);
        Path path = directory.resolve(key + ".json");
        if (Files.isSymbolicLink(path)) throw new IOException("Image caption symbolic links are not allowed.");
        if (!Files.exists(path)) return null;
        var cached = JSON.readTree(Files.readString(path));
        String description = cached.path("description").asString();
        String model = cached.path("model").asString();
        if (!hash.equals(cached.path("sha256").asString()) || description.isBlank() || model.isBlank()) {
            throw new IOException("Stored image caption is inconsistent.");
        }
        return Map.of("description", description, "model", model);
    }

    public void saveImageCaption(Workspace workspace, String key, String hash, String description, String model)
            throws IOException {
        Path directory = workspace.state().resolve("image-captions");
        ensureDirectory(directory);
        atomicWrite(directory.resolve(key + ".json"), json(Map.of(
                "sha256", hash, "description", description, "model", model)));
    }

    private Path batchMarker(Workspace workspace, int index) {
        return workspace.state().resolve("batch-%04d.complete".formatted(index));
    }

    /** 识图前一次性持久化整单编号；同用户的新请求也必须跳过尚未写入原始会话的预留编号。 */
    public synchronized List<Map<String, Object>> sourceMessages(Workspace workspace, AddRequest request) throws IOException {
        long maximum = 0;
        Map<Integer, String> existingIds = new LinkedHashMap<>();
        Map<String, String> owners = new LinkedHashMap<>();
        Path sessions = workspace.user().resolve("agents/" + AGENT_NAME + "/sessions");
        try (var files = Files.list(sessions)) {
            // .log.jsonl 是同一会话的操作日志，交给 SessionTree 加载，不重复扫描计数。
            for (Path transcript : files.filter(path -> path.getFileName().toString().endsWith(".jsonl")
                    && !path.getFileName().toString().endsWith(".log.jsonl")).toList()) {
                if (Files.isSymbolicLink(transcript)) throw new IOException("Session symbolic links are not allowed.");
                SessionTree tree = new SessionTree(transcript, workspace.user(), null);
                tree.load();
                for (SessionEntry.MessageEntry entry : tree.getMessageEntries()) {
                    long number;
                    try {
                        number = Long.parseLong(entry.getId());
                        if (number < 1) throw new NumberFormatException("Non-positive source ID.");
                    } catch (NumberFormatException invalid) {
                        // 本阶段只支持新的顺序编号，不为旧测试数据添加兼容或迁移逻辑。
                        throw new IOException("Source IDs must be positive integers; clear old test data before retrying.", invalid);
                    }
                    maximum = Math.max(maximum, number);
                    var source = JSON.readTree(entry.getContent());
                    if (owners.putIfAbsent(entry.getId(), source.path("request_id").asString()) != null)
                        throw new IOException("Source IDs are duplicated across sessions.");
                    if (!request.requestId().equals(source.path("request_id").asString())) continue;
                    int index = source.path("message_index").asInt(-1);
                    if (!request.sessionId().equals(source.path("session_id").asString())
                            || index < 0 || index >= request.messages().size()
                            || existingIds.putIfAbsent(index, entry.getId()) != null) {
                        throw new IOException("Persisted request source identities are inconsistent.");
                    }
                }
            }
        }
        Path plan = workspace.state().resolve("source-ids.json");
        Map<Path, SourceReservation> reservations = new LinkedHashMap<>();
        Set<String> reserved = new HashSet<>();
        // 一份请求级预留文件就是分配提交点；不引入另一个计数器，避免双文件提交窗口。
        try (var requests = Files.list(workspace.state().getParent())) {
            for (Path directory : requests.toList()) {
                if (Files.isSymbolicLink(directory)) throw new IOException("Request state symbolic links are not allowed.");
                if (!Files.isDirectory(directory)) continue;
                Path file = directory.resolve("source-ids.json");
                if (!Files.exists(file)) continue;
                SourceReservation reservation = readReservation(file);
                reservations.put(file, reservation);
                for (String id : reservation.ids()) {
                    if (!reserved.add(id)) throw new IOException("Source ID reservations overlap.");
                    maximum = Math.max(maximum, Long.parseLong(id));
                }
            }
        }
        SourceReservation allocation = reservations.get(plan);
        boolean reused = allocation != null;
        if (!reused) {
            // 不迁移旧请求数据：已写原文却没有预留文件，需要清理旧测试数据重新测试。
            if (!existingIds.isEmpty()) throw new IOException("Persisted request has no source ID reservation; clear old test data.");
            List<String> ids = new ArrayList<>();
            long next = maximum;
            for (int i = 0; i < request.messages().size(); i++) {
                try { next = Math.incrementExact(next); }
                catch (ArithmeticException exhausted) { throw new IOException("Source ID sequence is exhausted.", exhausted); }
                ids.add(Long.toString(next));
            }
            allocation = new SourceReservation(request.sessionId(), List.copyOf(ids));
            atomicWrite(plan, json(Map.of("session_id", allocation.sessionId(), "source_ids", allocation.ids())));
            if (!readReservation(plan).equals(allocation)) throw new IOException("Source ID reservation was not persisted.");
        }
        if (!allocation.sessionId().equals(request.sessionId()) || allocation.ids().size() != request.messages().size())
            throw new IOException("Source ID reservation does not match this request.");
        List<Map<String, Object>> result = new ArrayList<>();
        for (int i = 0; i < request.messages().size(); i++) {
            AddMessage message = request.messages().get(i);
            String id = allocation.ids().get(i);
            if (existingIds.containsKey(i) && !id.equals(existingIds.get(i))) throw new IOException("Persisted source ID differs from its reservation.");
            if (owners.containsKey(id) && !request.requestId().equals(owners.get(id))) throw new IOException("Reserved source ID belongs to another request.");
            Map<String, Object> source = new LinkedHashMap<>();
            source.put("source_id", id);
            source.put("message_index", i);
            source.put("role", message.role());
            source.put("timestamp", message.timestamp());
            // 直接从原始 Unix 毫秒转换，统一 UTC，不使用服务器当前日期或默认时区。
            // 随来源消息一起入上下文及会话文件，日期不受摘要加载策略影响。
            source.put("source_date", message.timestamp() == null ? null
                    : Instant.ofEpochMilli(message.timestamp()).atOffset(ZoneOffset.UTC).toLocalDate().toString());
            source.put("content", message.content());
            result.add(source);
        }
        log.info("[Add来源编号规划] {}，已占用/预留最大编号={}，整单编号数={}，复用预留={}，已落盘消息数={}，本单编号范围={}～{}；识图前已持久化，失败重试不换号",
                AddLogSupport.trace(request), maximum, result.size(), reused, existingIds.size(),
                allocation.ids().get(0), allocation.ids().get(allocation.ids().size() - 1));
        return result;
    }

    private static SourceReservation readReservation(Path path) throws IOException {
        if (Files.isSymbolicLink(path) || !Files.isRegularFile(path)) throw new IOException("Source reservation must be a regular file.");
        var node = JSON.readTree(Files.readString(path));
        String sessionId = node.path("session_id").asString();
        if (sessionId.isBlank() || !node.path("source_ids").isArray() || node.path("source_ids").isEmpty())
            throw new IOException("Source reservation is invalid.");
        List<String> ids = new ArrayList<>();
        long previous = 0;
        for (var value : node.path("source_ids")) {
            String id = value.asString();
            long number;
            try { number = Long.parseLong(id); }
            catch (NumberFormatException invalid) { throw new IOException("Source reservation ID is invalid.", invalid); }
            if (!id.matches("[1-9][0-9]*") || number <= previous) throw new IOException("Source reservation IDs must be increasing positive integers.");
            ids.add(id); previous = number;
        }
        return new SourceReservation(sessionId, List.copyOf(ids));
    }

    private record SourceReservation(String sessionId, List<String> ids) {}

    public void appendSources(Workspace workspace, AddRequest request,
            RuntimeContext context, List<Map<String, Object>> sources) throws IOException {
        String sessionKey = WorkspaceIdentity.pathSegment(request.sessionId());
        Path transcript = workspace.manager().resolveSessionContextFile(context, AGENT_NAME, sessionKey);
        SessionTree tree = new SessionTree(transcript, workspace.user(), null);
        tree.load();
        Set<String> currentIds = new HashSet<>();
        sources.forEach(source -> currentIds.add(source.get("source_id").toString()));
        Set<String> existing = new HashSet<>();
        tree.getAllEntries().forEach(entry -> existing.add(entry.getId()));
        String parent = tree.getAllEntries().isEmpty() ? null
                : tree.getAllEntries().get(tree.getAllEntries().size() - 1).getId();
        int appended = 0;
        for (Map<String, Object> source : sources) {
            String id = source.get("source_id").toString();
            if (existing.contains(id)) continue;
            Map<String, Object> transcriptSource = new LinkedHashMap<>(source);
            transcriptSource.put("request_id", request.requestId());
            transcriptSource.put("session_id", request.sessionId());
            Long timestamp = (Long) source.get("timestamp");
            tree.append(new SessionEntry.MessageEntry(id, parent,
                    timestamp == null ? null : Instant.ofEpochMilli(timestamp),
                    source.get("role").toString(), JSON.writeValueAsString(transcriptSource), null,
                    source.containsKey("images") ? List.of("text", "image") : List.of("text")));
            parent = id;
            appended++;
        }
        tree.flush();
        // 同 Harness 一样维护会话索引；原始用户/会话标识仍保留在消息来源中。
        workspace.manager().updateSessionIndex(context, AGENT_NAME, sessionKey, "历史消息导入：" + request.sessionId());
        if (!Files.isRegularFile(transcript)) throw new IOException("Source transcript was not persisted.");
        SessionTree persisted = new SessionTree(transcript, workspace.user(), null);
        persisted.load();
        Set<String> persistedIds = new HashSet<>();
        persisted.getAllEntries().forEach(entry -> persistedIds.add(entry.getId()));
        if (!persistedIds.containsAll(currentIds)
                || !Files.isRegularFile(transcript.resolveSibling(sessionKey + ".log.jsonl"))) {
            throw new IOException("Source transcript was not fully persisted.");
        }
        log.info("[Add原始消息落盘] {}，本组源消息数={}，实际新增={}，已有源消息跳过={}，未知时间消息数={}，可读来源日期时区=UTC，会话文件={}",
                AddLogSupport.trace(context), sources.size(), appended, sources.size() - appended,
                sources.stream().filter(source -> source.get("timestamp") == null).count(), transcript);
    }

    /** 本组 Save 前读取；跨日期文件选择当前 Session 最近两段，正文保持完整并按先后顺序呈现。 */
    public List<String> recentSaves(Workspace workspace, RuntimeContext context, int maxChars) throws IOException {
        if (maxChars == 0) {
            log.info("[Add近期Save摘要关闭] {}，字符预算为0", AddLogSupport.trace(context));
            return List.of();
        }
        long startedAt = System.nanoTime();
        List<SaveExcerpt> newest = new ArrayList<>();
        try (var files = Files.list(workspace.user().resolve("memory"))) {
            List<Path> dailyFiles = files.filter(file -> file.getFileName().toString().matches("\\d{4}-\\d{2}-\\d{2}\\.md"))
                    .sorted(Comparator.comparing((Path file) -> file.getFileName().toString()).reversed()).toList();
            for (Path file : dailyFiles) {
                if (Files.isSymbolicLink(file)) throw new IOException("Daily ledger symbolic links are not allowed.");
                if (!Files.isRegularFile(file)) continue;
                List<SaveExcerpt> excerpts = readRecentSaves(file, context.getSessionId(), maxChars);
                for (int index = excerpts.size() - 1; index >= 0 && newest.size() < 2; index--) newest.add(excerpts.get(index));
                if (newest.size() == 2) break;
            }
        }
        List<String> summaries = new ArrayList<>();
        int remaining = maxChars;
        int omitted = 0;
        for (SaveExcerpt excerpt : newest) {
            if (excerpt.oversized() || excerpt.content().length() > remaining) { omitted++; continue; }
            summaries.add(0, excerpt.content());
            remaining -= excerpt.content().length();
        }
        log.info("[Add近期Save摘要加载完成] {}，同Session候选段数={}，附带段数={}，字符数={}，字符上限={}，超限省略段数={}，耗时={}ms；不加载长期记忆或原始前文",
                AddLogSupport.trace(context), newest.size(), summaries.size(), maxChars - remaining, maxChars, omitted,
                AddLogSupport.elapsedMillis(startedAt));
        return List.copyOf(summaries);
    }

    private static List<SaveExcerpt> readRecentSaves(Path file, String sessionId, int maxChars) throws IOException {
        Deque<SaveExcerpt> excerpts = new ArrayDeque<>();
        StringBuilder body = new StringBuilder();
        boolean belongs = false;
        boolean oversized = false;
        String marker = " | session_id=";
        try (var reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("## Memory Save — ")) {
                    if (belongs) rememberSave(excerpts, body, oversized, maxChars);
                    body.setLength(0); oversized = false; belongs = false;
                    int metadata = line.indexOf(marker);
                    if (metadata < 0) continue; // 只读取新格式，不推测或迁移无 Session 标识的旧记录。
                    try {
                        var storedSession = JSON.readTree(line.substring(metadata + marker.length()));
                        belongs = storedSession.isString() && storedSession.asString().equals(sessionId);
                    } catch (RuntimeException invalid) {
                        throw new IOException("Daily ledger Save session metadata is invalid.");
                    }
                } else if (belongs && !oversized) {
                    if ((long) body.length() + line.length() + 1 > (long) maxChars + 2) {
                        body.setLength(0); oversized = true;
                    } else body.append(line).append('\n');
                }
            }
        }
        if (belongs) rememberSave(excerpts, body, oversized, maxChars);
        return List.copyOf(excerpts);
    }

    private static void rememberSave(Deque<SaveExcerpt> excerpts, StringBuilder body, boolean oversized, int maxChars) {
        String content = body.toString().strip();
        if (!oversized && content.isBlank()) return;
        // 超长段保留一个占位，不能拿更早的记录冒充“最近两段”，也不截断成半条事实。
        excerpts.addLast(new SaveExcerpt(content, oversized || content.length() > maxChars));
        if (excerpts.size() > 2) excerpts.removeFirst();
    }

    private record SaveExcerpt(String content, boolean oversized) {}

    public static String json(Object value) { return JSON.writeValueAsString(value); }

    public static String hash(String value) {
        return hash(value.getBytes(StandardCharsets.UTF_8));
    }

    public static String hash(byte[] value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static void ensureDirectory(Path path) throws IOException {
        for (Path check = path; check != null; check = check.getParent()) {
            if (Files.isSymbolicLink(check)) throw new IOException("Workspace symbolic links are not allowed.");
        }
        Files.createDirectories(path);
    }

    private static void createIfAbsent(Path path, String content) throws IOException {
        if (Files.isSymbolicLink(path)) throw new IOException("Workspace symbolic links are not allowed.");
        if (!Files.exists(path)) Files.writeString(path, content, StandardOpenOption.CREATE_NEW);
    }

    private static void atomicWrite(Path path, String content) throws IOException {
        // 在同目录写临时文件再替换，避免读到半个检查点；不等于整批记忆事务。
        ensureDirectory(path.getParent());
        if (Files.isSymbolicLink(path)) throw new IOException("Workspace symbolic links are not allowed.");
        Path temporary = Files.createTempFile(path.getParent(), ".add-", ".tmp");
        try {
            Files.writeString(temporary, content);
            try {
                Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException unavailable) {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static AddAgentException conflict(String message) {
        return new AddAgentException(HttpStatus.CONFLICT, "ADD_REQUEST_CONFLICT", message);
    }

    public record Workspace(Path user, Path state, LocalFilesystem filesystem, WorkspaceManager manager)
            implements AutoCloseable {
        @Override public void close() { manager.close(); }
    }
}
