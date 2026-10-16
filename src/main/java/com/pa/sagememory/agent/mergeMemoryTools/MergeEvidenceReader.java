package com.pa.sagememory.agent.mergeMemoryTools;

import com.pa.sagememory.agent.workspace.AddWorkspaceStore;
import com.pa.sagememory.agent.workspace.ImageIdentity;
import com.pa.sagememory.agent.workspace.WorkspaceIdentity;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** 复用 Search 的原始 ID/配对语义；只在首次核验时读取当前用户 JSONL，不读取记忆、台账或图片字节。 */
final class MergeEvidenceReader {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final int MAX_RESPONSE_CHARS = 24_000;
    private final Map<String, Source> sources = new LinkedHashMap<>();
    private final Map<String, List<Source>> sessions = new LinkedHashMap<>();

    MergeEvidenceReader(Path user, PathGuard guard, Runnable checkDeadline) throws IOException {
        Path directory = user.resolve("agents/" + AddWorkspaceStore.AGENT_NAME + "/sessions");
        guard.check(directory);
        if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) return;
        try (var files = Files.list(directory)) {
            for (Path file : files.filter(path -> path.getFileName().toString().endsWith(".jsonl")
                    && !path.getFileName().toString().endsWith(".log.jsonl"))
                    .sorted(Comparator.comparing(path -> path.getFileName().toString())).toList()) {
                checkDeadline.run();
                guard.check(file);
                if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Source transcript must be a regular file.");
                String sessionKey = file.getFileName().toString().replaceFirst("\\.jsonl$", "");
                String sessionId = null;
                List<Source> conversation = new ArrayList<>();
                try (var reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        checkDeadline.run();
                        if (line.isBlank()) continue;
                        JsonNode entry = JSON.readTree(line);
                        if (!"message".equals(entry.path("type").asString())) continue;
                        JsonNode source = JSON.readTree(entry.path("content").asString());
                        String id = source.path("source_id").asString();
                        String role = source.path("role").asString();
                        String originalSessionId = source.path("session_id").asString();
                        if (!numericId(id) || !id.equals(entry.path("id").asString())
                                || !sessionKey.equals(WorkspaceIdentity.pathSegment(originalSessionId))
                                || (sessionId != null && !sessionId.equals(originalSessionId))
                                || !(role.equals("user") || role.equals("assistant")) || !role.equals(entry.path("role").asString()))
                            throw new IOException("Source identity, role or session is inconsistent.");
                        sessionId = originalSessionId;
                        String date = source.path("source_date").isString() ? source.path("source_date").asString() : "unknown";
                        if (!date.equals("unknown")) LocalDate.parse(date);
                        // 含图消息读取用户原文和独立描述，不能把增强文本中的描述误当作用户原话。
                        boolean hasImages = source.has("images");
                        if (hasImages && (!source.path("images").isArray() || !source.path("original_content").isArray()))
                            throw new IOException("Image source metadata is inconsistent.");
                        String original = text(hasImages ? source.path("original_content") : source.path("content"));
                        List<Image> images = new ArrayList<>();
                        Set<Integer> ordinals = new LinkedHashSet<>();
                        for (JsonNode image : source.path("images")) {
                            int ordinal = image.path("image_index").asInt();
                            String description = image.path("description").asString();
                            if (ordinal < 1 || !ordinals.add(ordinal) || description.isBlank()
                                    || !image.path("image_id").asString().equals(ImageIdentity.id(id, ordinal)))
                                throw new IOException("Stored image description identity is inconsistent.");
                            images.add(new Image(ordinal, description));
                        }
                        images.sort(Comparator.comparingInt(Image::ordinal));
                        Source message = new Source(id, sessionId, conversation.size(), role, date, original, List.copyOf(images));
                        if (sources.putIfAbsent(id, message) != null) throw new IOException("Source IDs are duplicated for this user.");
                        conversation.add(message);
                    }
                }
                if (sessionId != null) sessions.put(sessionId, List.copyOf(conversation));
            }
        }
    }

    static List<String> sourceIds(List<String> requested) {
        Set<String> ids = new LinkedHashSet<>();
        if (requested == null || requested.isEmpty()) throw new IllegalArgumentException("source_ids must be a non-empty array.");
        for (String value : requested) {
            String id = value == null ? "" : value.strip();
            if (!numericId(id)) throw new IllegalArgumentException("source_ids must be original positive integer IDs, not memory or image IDs.");
            ids.add(id);
        }
        return List.copyOf(ids);
    }

    Result retrieve(List<String> requested) {
        List<String> ids = sourceIds(requested);
        Map<String, Set<Integer>> included = new LinkedHashMap<>();
        Set<String> missing = new LinkedHashSet<>();
        Set<String> omitted = new LinkedHashSet<>();
        int chars = 0;
        for (String id : ids) {
            Source anchor = sources.get(id);
            if (anchor == null) { missing.add(id); continue; }
            List<Source> conversation = sessions.get(anchor.session());
            Set<Integer> pair = new TreeSet<>();
            pair.add(anchor.position());
            // 只能按同 Session 的实际相邻位置配对，不能用用户级数字 ID 加减。
            if (anchor.role().equals("assistant") && anchor.position() > 0
                    && conversation.get(anchor.position() - 1).role().equals("user")) pair.add(anchor.position() - 1);
            else if (anchor.role().equals("user") && anchor.position() + 1 < conversation.size()
                    && conversation.get(anchor.position() + 1).role().equals("assistant")) pair.add(anchor.position() + 1);
            Set<Integer> selected = included.computeIfAbsent(anchor.session(), ignored -> new TreeSet<>());
            int size = pair.stream().filter(position -> !selected.contains(position))
                    .mapToInt(position -> display(conversation.get(position)).length() + 2).sum();
            // 整对预留预算；不截断原文，也不单独丢掉搭档，省略的 ID 明确反馈。
            if (chars + size > MAX_RESPONSE_CHARS) {
                pair.stream().map(position -> conversation.get(position).id()).filter(value -> !sourcesShown(included, value)).forEach(omitted::add);
            } else { selected.addAll(pair); chars += size; }
        }
        List<String> blocks = new ArrayList<>();
        int messages = 0;
        int images = 0;
        for (var window : included.entrySet()) {
            for (int position : window.getValue()) {
                Source source = sessions.get(window.getKey()).get(position);
                blocks.add(display(source)); messages++; images += source.images().size(); omitted.remove(source.id());
            }
        }
        if (!missing.isEmpty()) blocks.add("Unavailable source_ids for this user: " + idList(missing));
        if (!omitted.isEmpty()) blocks.add("Response size limit; omitted complete messages/pairs: " + idList(omitted) + ". Request fewer source_ids.");
        return new Result(String.join("\n\n", blocks), messages, images, missing.size(), omitted.size());
    }

    private boolean sourcesShown(Map<String, Set<Integer>> included, String id) {
        Source source = sources.get(id);
        return included.getOrDefault(source.session(), Set.of()).contains(source.position());
    }

    private static boolean numericId(String id) {
        if (!id.matches("[1-9][0-9]*")) return false;
        try { return Long.parseLong(id) > 0; }
        catch (NumberFormatException invalid) { return false; }
    }

    private static String text(JsonNode content) throws IOException {
        if (content.isString()) return content.asString();
        if (!content.isArray()) throw new IOException("Source content must be text or ordered content blocks.");
        List<String> texts = new ArrayList<>();
        for (JsonNode part : content) if ("text".equals(part.path("type").asString())) texts.add(part.path("text").asString());
        return String.join("\n", texts);
    }

    private static String display(Source source) {
        StringBuilder result = new StringBuilder("Memory ID: ").append(source.id()).append("\nsession_id: ").append(source.session())
                .append("\nrole=").append(source.role()).append(", Content: ").append(source.text());
        for (Image image : source.images()) result.append("\nImage ID: ").append(ImageIdentity.id(source.id(), image.ordinal()))
                .append("\nImage description (vision-model generated): ").append(image.description());
        return result.append("\nsource_date: ").append(source.date()).toString();
    }

    private static String idList(Set<String> ids) {
        return String.join(",", ids.stream().limit(50).toList()) + (ids.size() > 50 ? " (and " + (ids.size() - 50) + " more)" : "");
    }

    @FunctionalInterface interface PathGuard { void check(Path path) throws IOException; }
    record Result(String text, int messages, int images, int missing, int omitted) {}
    private record Source(String id, String session, int position, String role, String date, String text, List<Image> images) {}
    private record Image(int ordinal, String description) {}
}
