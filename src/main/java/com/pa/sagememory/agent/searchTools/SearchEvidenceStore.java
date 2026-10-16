package com.pa.sagememory.agent.searchTools;

import com.pa.sagememory.agent.workspace.AddWorkspaceStore;
import com.pa.sagememory.agent.workspace.ImageIdentity;
import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** 只读创建用户的检索快照；不创建目录，不扫描 Add 检查点、资源二进制和会话日志副本。 */
@Component
public final class SearchEvidenceStore {
    private static final Logger log = LoggerFactory.getLogger(SearchEvidenceStore.class);
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Pattern HEADING = Pattern.compile("^ {0,3}(#{1,6})\\s+(.+?)\\s*#*\\s*$");
    private static final Pattern BULLET = Pattern.compile("^(\\s*)(?:[-*+]\\s+|\\d+[.)]\\s+)(.*)$");
    private static final Pattern PROVENANCE = Pattern.compile(
            "[（(\\[][^）)\\]\\r\\n]*\\bsource_(?:ids?|date)\\s*[:：][^）)\\]\\r\\n]*[）)\\]]");
    private static final Pattern SOURCE_IDS = Pattern.compile("\\bsource_ids?\\s*[:：][ \\t]*([0-9][0-9 \\t,，、;；\\-–—]*)");
    private static final Pattern ID_OR_RANGE = Pattern.compile("([1-9]\\d*)(?:\\s*[-–—]\\s*([1-9]\\d*))?");
    private final AddWorkspaceStore workspaceStore;

    public SearchEvidenceStore(AddWorkspaceStore workspaceStore) {
        this.workspaceStore = workspaceStore;
    }

    /** 每个 Search 在注册工具前调用一次；后续多轮召回与 ID 解析都读同一快照。 */
    public SearchEvidenceContext open(String userId) throws IOException {
        long startedAt = System.nanoTime();
        Path user = workspaceStore.userRoot(userId).toAbsolutePath().normalize();
        if (Files.isSymbolicLink(user)) throw new IOException("Search user workspace cannot be a symbolic link.");
        if (!Files.exists(user, LinkOption.NOFOLLOW_LINKS)) return new SearchEvidenceContext(userId, List.of());
        if (!Files.isDirectory(user, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Search workspace is not a directory.");
        List<SearchEvidence> candidates = new ArrayList<>();
        Map<String, String> sourceDates = new LinkedHashMap<>();
        // 先解析原始消息，用真实 source_date 丰富 Markdown 的来源信息，不信任操作日期。
        Path sessions = safePath(user, "agents/" + AddWorkspaceStore.AGENT_NAME + "/sessions");
        int sessionFiles = 0;
        for (Path file : files(sessions)) {
            String name = file.getFileName().toString();
            if (!name.endsWith(".jsonl") || name.endsWith(".log.jsonl")) continue;
            loadSession(user, safePath(user, user.relativize(file).toString()), candidates, sourceDates);
            sessionFiles++;
        }
        TreeMap<Long, String> orderedSources = new TreeMap<>();
        sourceDates.keySet().forEach(id -> orderedSources.put(Long.parseLong(id), id));
        Path memory = safePath(user, "MEMORY.md");
        if (Files.exists(memory, LinkOption.NOFOLLOW_LINKS))
            loadMarkdown(memory, SearchEvidence.Kind.MEMORY, "m", sourceDates, orderedSources, candidates);
        int ledgerFiles = 0;
        for (Path file : files(safePath(user, "memory"))) {
            String name = file.getFileName().toString();
            if (!name.matches("\\d{4}-\\d{2}-\\d{2}\\.md")) continue;
            try { LocalDate.parse(name.substring(0, 10)); }
            catch (DateTimeParseException invalid) { throw new IOException("Invalid daily ledger date.", invalid); }
            loadMarkdown(safePath(user, user.relativize(file).toString()), SearchEvidence.Kind.LEDGER,
                    name.substring(0, 10).replace("-", "") + "-", sourceDates, orderedSources, candidates);
            ledgerFiles++;
        }
        SearchEvidenceContext context;
        try { context = new SearchEvidenceContext(userId, candidates); }
        catch (IllegalArgumentException invalid) { throw new IOException("Search evidence identities are inconsistent.", invalid); }
        long images = candidates.stream().filter(item -> item.kind() == SearchEvidence.Kind.IMAGE).count();
        log.info("[Search证据快照就绪] user_id={}，会话文件数={}，台账文件数={}，原始消息数={}，图片描述数={}，总候选数={}，耗时={}ms；只读，不加载图片字节",
                userId, sessionFiles, ledgerFiles, sourceDates.size(), images, candidates.size(),
                (System.nanoTime() - startedAt) / 1_000_000);
        return context;
    }

    private static void loadSession(Path user, Path file, List<SearchEvidence> candidates,
            Map<String, String> sourceDates) throws IOException {
        requireFile(file);
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line;
            int lineNumber = 0;
            int sessionPosition = 0;
            while ((line = reader.readLine()) != null) {
                lineNumber++;
                if (line.isBlank()) continue;
                try {
                    JsonNode entry = JSON.readTree(line);
                    if (!"message".equals(entry.path("type").asString())) continue;
                    JsonNode source = JSON.readTree(entry.path("content").asString());
                    String id = source.path("source_id").asString();
                    if (!id.matches("[1-9]\\d*") || Long.parseLong(id) < 1
                            || !id.equals(entry.path("id").asString()))
                        throw new IllegalArgumentException("Source ID does not match the session entry.");
                    String role = source.path("role").asString();
                    String sessionId = source.path("session_id").asString();
                    if (!(role.equals("user") || role.equals("assistant")) || sessionId.isBlank()
                            || !role.equals(entry.path("role").asString()))
                        throw new IllegalArgumentException("Source role or session is invalid.");
                    String date = source.path("source_date").isString() ? source.path("source_date").asString() : "未知";
                    if (!date.equals("未知")) LocalDate.parse(date);
                    if (sourceDates.putIfAbsent(id, date) != null)
                        throw new IllegalArgumentException("Source IDs must be unique across the user's sessions.");
                    String content = text(source.path("content"));
                    if (source.has("images") && (!source.path("images").isArray()
                            || !source.path("original_content").isArray()))
                        throw new IllegalArgumentException("Image source content must contain ordered original blocks.");
                    // 对含图消息，只用用户/助手原文进行消息匹配；视觉描述由独立图片候选承接。
                    String matchText = source.has("images") ? text(source.path("original_content")) : content;
                    Map<String, String> dates = Map.of(id, date);
                    JsonNode timestampNode = source.path("timestamp");
                    Long timestamp = timestampNode.isMissingNode() || timestampNode.isNull() ? null
                            : Long.valueOf(timestampNode.toString());
                    candidates.add(new SearchEvidence(SearchEvidence.Kind.SESSION, id, "", content, matchText, role,
                            List.of(id), dates, file, lineNumber, sessionId, sessionPosition, null, List.of(), timestamp));
                    Set<Integer> imageNumbers = new LinkedHashSet<>();
                    for (JsonNode image : source.path("images")) {
                        int ordinal = image.path("image_index").asInt();
                        String persistedId = image.path("image_id").asString();
                        String description = image.path("description").asString();
                        String mediaType = image.path("media_type").asString();
                        if (ordinal < 1 || !imageNumbers.add(ordinal) || !persistedId.equals(ImageIdentity.id(id, ordinal))
                                || description.isBlank() || !List.of("image/jpeg", "image/png", "image/webp").contains(mediaType))
                            throw new IllegalArgumentException("Image evidence metadata is inconsistent.");
                        String relative = image.path("path").asString();
                        if (!relative.equals(ImageIdentity.relativePath(persistedId, mediaType)))
                            throw new IOException("Image file path must match its image ID and media type.");
                        Path imageFile = safePath(user, relative);
                        if (!imageFile.startsWith(user.resolve("assets/images")))
                            throw new IOException("Image evidence must remain under the user's image assets.");
                        requireFile(imageFile);
                        var reference = new SearchEvidence.ImageReference(persistedId, imageFile, mediaType);
                        candidates.add(new SearchEvidence(SearchEvidence.Kind.IMAGE, persistedId, "", description,
                                description, role, List.of(id), dates, file, lineNumber, sessionId, sessionPosition, reference));
                    }
                    sessionPosition++;
                } catch (RuntimeException invalid) {
                    // 文件坏了不能伪装成“没有记忆”；不打印原消息和 Jackson 的可能含正文异常。
                    log.warn("[Search会话解析失败] 文件={}，行号={}，异常类型={}；终止快照加载", file, lineNumber, invalid.getClass().getSimpleName());
                    throw new IOException("Could not parse a persisted source message at line " + lineNumber + ".");
                }
            }
        }
    }

    private static String text(JsonNode content) {
        if (content.isString()) return content.asString();
        if (!content.isArray()) return "";
        List<String> parts = new ArrayList<>();
        for (JsonNode part : content) if ("text".equals(part.path("type").asString())) parts.add(part.path("text").asString());
        return String.join("\n", parts);
    }

    private static void loadMarkdown(Path file, SearchEvidence.Kind kind, String prefix,
            Map<String, String> sourceDates, TreeMap<Long, String> orderedSources,
            List<SearchEvidence> candidates) throws IOException {
        requireFile(file);
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        Map<Integer, String> headings = new TreeMap<>();
        StringBuilder block = new StringBuilder();
        List<String> topic = List.of();
        int start = 0;
        int bulletIndent = -1;
        String fence = "";
        for (int index = 0; index < lines.size(); index++) {
            String line = lines.get(index);
            String stripped = line.stripLeading();
            boolean fenceLine = stripped.startsWith("```") || stripped.startsWith("~~~");
            Matcher heading = HEADING.matcher(line);
            Matcher bullet = BULLET.matcher(line);
            boolean headingLine = fence.isEmpty() && heading.matches();
            boolean bulletLine = fence.isEmpty() && bullet.matches();
            int indent = bulletLine ? bullet.group(1).replace("\t", "    ").length() : -1;
            // 项目条目中的空行和缩进续文仍属于同一证据，不能截断来源标注或嵌套条目。
            if (headingLine || (fence.isEmpty() && line.isBlank() && bulletIndent < 0)
                    || (bulletLine && (bulletIndent < 0 || indent <= bulletIndent))) {
                addMarkdownBlock(file, kind, prefix, start, topic, block, sourceDates, orderedSources, candidates);
                block.setLength(0);
                start = 0;
                bulletIndent = -1;
            }
            if (headingLine) {
                int level = heading.group(1).length();
                headings.keySet().removeIf(existing -> existing >= level);
                if (level > 1) headings.put(level, heading.group(2).strip());
                // 保留真实标题数组；“ - ”只用于给模型展示，不从字符串反推父子关系。
                topic = List.copyOf(headings.values());
                continue;
            }
            if (block.isEmpty() && line.isBlank()) continue;
            if (block.isEmpty()) {
                start = index + 1;
                if (bulletLine) bulletIndent = indent;
            }
            if (!block.isEmpty()) block.append('\n');
            // 只去掉条目首行的项目符号，缩进子条目和多行正文完整保留。
            block.append(index + 1 == start && bulletLine ? bullet.group(2) : line);
            if (fenceLine) {
                String marker = stripped.startsWith("```") ? "```" : "~~~";
                if (fence.isEmpty()) fence = marker;
                else if (fence.equals(marker)) fence = "";
            }
        }
        addMarkdownBlock(file, kind, prefix, start, topic, block, sourceDates, orderedSources, candidates);
    }

    private static void addMarkdownBlock(Path file, SearchEvidence.Kind kind, String prefix, int line,
            List<String> topic, StringBuilder block, Map<String, String> sourceDates,
            TreeMap<Long, String> orderedSources, List<SearchEvidence> candidates) {
        if (block.isEmpty()) return;
        String original = block.toString().strip();
        Set<String> ids = new LinkedHashSet<>();
        Matcher metadata = SOURCE_IDS.matcher(original);
        while (metadata.find()) {
            Matcher numbers = ID_OR_RANGE.matcher(metadata.group(1));
            while (numbers.find()) {
                try {
                    long from = Long.parseLong(numbers.group(1));
                    if (numbers.group(2) == null) ids.add(Long.toString(from));
                    else {
                        long to = Long.parseLong(numbers.group(2));
                        // 范围按真实已有消息展开，不循环生成任意巨大的虚构 ID 区间。
                        if (to >= from) ids.addAll(orderedSources.subMap(from, true, to, true).values());
                    }
                } catch (NumberFormatException ignored) {
                    // 模型写入了无效来源标注，仍召回正文，但不伪造可回查的来源 ID。
                }
            }
        }
        Map<String, String> dates = new LinkedHashMap<>();
        ids.forEach(id -> dates.put(id, sourceDates.getOrDefault(id, "未知")));
        String content = PROVENANCE.matcher(original).replaceAll("").strip();
        if (content.isBlank()) return;
        candidates.add(new SearchEvidence(kind, prefix + line, "", content, content, "",
                new ArrayList<>(ids), dates, file, line, "", -1, null,
                kind == SearchEvidence.Kind.MEMORY ? topic : List.of()));
    }

    private static List<Path> files(Path directory) throws IOException {
        if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) return List.of();
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Search data directory is invalid.");
        try (var stream = Files.list(directory)) {
            // 符号链接候选交由 safePath 报错，不静默漏掉潜在越权的文件。
            return stream.sorted(Comparator.comparing(path -> path.getFileName().toString())).toList();
        }
    }

    private static Path safePath(Path user, String relative) throws IOException {
        Path candidate = user.resolve(relative).normalize();
        if (!candidate.startsWith(user) || candidate.equals(user)) throw new IOException("Search path is outside its user scope.");
        Path current = user;
        for (Path part : user.relativize(candidate)) {
            current = current.resolve(part);
            if (Files.isSymbolicLink(current)) throw new IOException("Search does not follow symbolic links.");
        }
        if (Files.exists(candidate, LinkOption.NOFOLLOW_LINKS) && !candidate.toRealPath().startsWith(user.toRealPath()))
            throw new IOException("Resolved search path is outside its user scope.");
        return candidate;
    }

    private static void requireFile(Path file) throws IOException {
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Search evidence file is missing or invalid.");
    }
}
