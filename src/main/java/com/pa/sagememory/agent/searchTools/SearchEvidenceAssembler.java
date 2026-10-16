package com.pa.sagememory.agent.searchTools;

import com.pa.sagememory.agent.SearchAgentException;
import com.pa.sagememory.agent.AddLogSupport;
import com.pa.sagememory.agent.workspace.AddWorkspaceStore;
import com.pa.sagememory.dto.EvidenceCard;
import com.pa.sagememory.dto.SearchResponse;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/** 将宿主确认的记忆选择追溯到原始消息；保留选择顺序，只返回原文及其所属附图。 */
@Component
public final class SearchEvidenceAssembler {
    private static final Logger log = LoggerFactory.getLogger(SearchEvidenceAssembler.class);
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final long IMAGE_BYTES_LIMIT = 30L * 1024 * 1024;
    private final AddWorkspaceStore store;
    public SearchEvidenceAssembler(AddWorkspaceStore store) { this.store = store; }

    public SearchResponse assemble(SearchEvidenceContext context, int topK) throws IOException {
        if (topK < 1) throw new IllegalArgumentException("top_k must be positive.");
        SourceExpansion expansion = expandSources(context);
        if (!context.selectedIds().isEmpty() && expansion.sources().isEmpty())
            throw new SearchAgentException(HttpStatus.BAD_GATEWAY, "SEARCH_SOURCE_TRACE_FAILED",
                    "Selected memories could not be traced to original messages.");
        Path user = store.userRoot(context.userId()).toAbsolutePath().normalize();
        ImageClaims claims = new ImageClaims(user);
        List<EvidenceCard> cards = new ArrayList<>();
        List<String> order = new ArrayList<>();
        int skippedImageOnly = 0;
        for (SearchEvidence source : expansion.sources()) {
            // 先展开并按原始 ID 去重，再按实际可返回消息计数；超额消息不读取或占用图片。
            if (cards.size() >= topK) break;
            List<Object> content = new ArrayList<>();
            List<ReturnedImage> images = new ArrayList<>();
            // 图片只随其所属用户消息返回；助手消息不借用其用户搭档的附件。
            List<SearchEvidence> attachments = "user".equals(source.role()) ? context.imagesForSource(source.id()) : List.of();
            for (SearchEvidence image : attachments) {
                ReturnedImage claimed = claims.claim(image);
                if (claimed != null) images.add(claimed);
            }
            String body = source.matchText();
            if (!body.isBlank()) content.add(Map.of("type", "text", "text", body));
            for (ReturnedImage image : images) content.add(image.block());
            // 纯图片消息若全部图片已返回，不再产生空条目，也不以模型描述冒充原文；继续取后续消息补位。
            if (content.isEmpty() && !attachments.isEmpty()) { skippedImageOnly++; continue; }
            if (content.isEmpty()) throw new IOException("Selected evidence has no returnable content.");
            // 正文保持不变；来源时间单独写入比赛方约定的 created_at 字段。
            cards.add(new EvidenceCard(source.id(), JSON.valueToTree(content), null, createdAt(source)));
            // 一项对应一个实际响应条目；括号内先记忆 ID，再按 content 顺序列出绑定图片 ID。
            order.add(images.isEmpty() ? source.id() : "(" + source.id() + ", "
                    + String.join(", ", images.stream().map(ReturnedImage::id).toList()) + ")");
        }
        String batch = UUID.randomUUID().toString();
        String userId = AddLogSupport.value(context.userId());
        log.info("[Search最终响应] user_id={}，响应批次={}，返回顺序={}，记忆数={}，图片数={}，去重原始ID次数={}，缺失来源数={}，省略重复纯图消息数={}，去重图片次数={}，图片字节数={}，top_k={}",
                userId, batch, "[" + String.join(", ", order) + "]", cards.size(), claims.imageCount(),
                expansion.duplicates(), expansion.missing(), skippedImageOnly, claims.duplicates, claims.bytes, topK);
        return new SearchResponse(List.copyOf(cards));
    }

    /** 不新增模型选择或工具展示状态；只在当前用户不可变快照内解析真实来源。 */
    private static SourceExpansion expandSources(SearchEvidenceContext context) {
        Map<String, SearchEvidence> originals = new LinkedHashMap<>();
        int duplicates = 0;
        int missing = 0;
        for (String id : context.selectedIds()) {
            SearchEvidence evidence = context.selectedEvidence(id).orElseThrow(() -> new IllegalStateException("Selected evidence is absent from the snapshot."));
            if (evidence.kind() == SearchEvidence.Kind.IMAGE)
                throw new IllegalStateException("Image IDs cannot be selected by finish_search.");
            List<String> ids = evidence.kind() == SearchEvidence.Kind.SESSION ? List.of(evidence.id())
                    : evidence.sourceIds().stream().distinct().sorted(Comparator.comparingLong(Long::parseLong)).toList();
            List<String> missingIds = new ArrayList<>();
            for (String sourceId : ids) {
                SearchEvidence source = context.sources().get(sourceId);
                if (source == null) { missingIds.add(sourceId); missing++; continue; }
                if (originals.putIfAbsent(sourceId, source) != null) duplicates++;
            }
            if (ids.isEmpty()) missing++;
            if (ids.isEmpty() || !missingIds.isEmpty())
                log.warn("[Search来源追溯不完整] user_id={}，选中记忆ID={}，缺失source_ids={}，未标注来源={}；仅返回可追溯原文，不回退摘要",
                        AddLogSupport.value(context.userId()), AddLogSupport.value(id), missingIds, ids.isEmpty());
        }
        return new SourceExpansion(List.copyOf(originals.values()), duplicates, missing);
    }

    /** 接口仅返回原始消息，时间也只取其原始毫秒 timestamp。 */
    private static String createdAt(SearchEvidence source) {
        return source.timestamp() == null ? null : Instant.ofEpochMilli(source.timestamp()).toString();
    }

    private record SourceExpansion(List<SearchEvidence> sources, int duplicates, int missing) {}

    private static byte[] imageBytes(Path user, Path file) throws IOException {
        Path image = file.toAbsolutePath().normalize();
        if (!image.startsWith(user.resolve("assets/images"))) throw new IOException("Image is outside the user's assets.");
        Path cursor = user;
        if (Files.isSymbolicLink(cursor)) throw new IOException("Image workspace cannot be a symbolic link.");
        for (Path segment : user.relativize(image)) {
            cursor = cursor.resolve(segment);
            if (Files.isSymbolicLink(cursor)) throw new IOException("Image paths cannot contain symbolic links.");
        }
        if (!image.toRealPath().startsWith(user.toRealPath())) throw new IOException("Image resolved outside its user scope.");
        try (var input = Files.newInputStream(image)) {
            byte[] bytes = input.readNBytes(10 * 1024 * 1024 + 1);
            if (bytes.length > 10 * 1024 * 1024) throw new SearchAgentException(HttpStatus.CONTENT_TOO_LARGE,
                    "SEARCH_EVIDENCE_IMAGE_TOO_LARGE", "An evidence image exceeds 10 MiB.");
            return bytes;
        }
    }
    private record ReturnedImage(String id, String mediaType, byte[] bytes) {
        Map<String, Object> block() {
            String uri = "data:" + mediaType + ";base64," + Base64.getEncoder().encodeToString(bytes);
            return Map.of("type", "image_url", "image_url", Map.of("url", uri));
        }
    }

    /** 不同 ID 但原图字节完全相同也只返回一次，不做语义或视觉相似去重。 */
    private static final class ImageClaims {
        final Path user;
        final Set<String> ids = new LinkedHashSet<>();
        final Set<String> hashes = new LinkedHashSet<>();
        long bytes;
        int duplicates;
        ImageClaims(Path user) { this.user = user; }

        ReturnedImage claim(SearchEvidence image) throws IOException {
            if (!ids.add(image.id())) { duplicates++; return null; }
            if (image.image() == null) throw new IOException("Image evidence has no file reference.");
            byte[] content = imageBytes(user, image.image().file());
            if (!hashes.add(AddWorkspaceStore.hash(content))) { duplicates++; return null; }
            if (bytes + content.length > IMAGE_BYTES_LIMIT)
                throw new SearchAgentException(HttpStatus.CONTENT_TOO_LARGE, "SEARCH_EVIDENCE_IMAGES_TOO_LARGE",
                        "Evidence images exceed the 30 MiB Search response limit.");
            bytes += content.length;
            return new ReturnedImage(image.id(), image.image().mediaType(), content);
        }

        int imageCount() { return hashes.size(); }
    }
}
