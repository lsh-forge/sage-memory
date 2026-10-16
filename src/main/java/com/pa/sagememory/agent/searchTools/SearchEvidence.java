package com.pa.sagememory.agent.searchTools;

import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 本次 Search 的不可变证据快照。来源类别用简短标签展示，文件位置和图片资源仅供宿主使用。 */
public record SearchEvidence(
        Kind kind, String id, String topic, String content, String matchText, String role,
        List<String> sourceIds, Map<String, String> sourceDates,
        Path file, int lineNumber, String sessionId, int sessionPosition, ImageReference image,
        List<String> topicPath, Long timestamp) {

    public enum Kind { MEMORY, LEDGER, SESSION, IMAGE }

    public record ImageReference(String persistedId, Path file, String mediaType) {}

    public SearchEvidence {
        sourceIds = List.copyOf(sourceIds);
        sourceDates = Collections.unmodifiableMap(new LinkedHashMap<>(sourceDates));
        topicPath = List.copyOf(topicPath);
        if (kind == Kind.MEMORY) topic = topicName(topicPath);
    }

    /** 记忆片段没有毫秒时间；旧调用保留其日期级来源信息。 */
    public SearchEvidence(Kind kind, String id, String topic, String content, String matchText, String role,
            List<String> sourceIds, Map<String, String> sourceDates, Path file, int lineNumber,
            String sessionId, int sessionPosition, ImageReference image, List<String> topicPath) {
        this(kind, id, topic, content, matchText, role, sourceIds, sourceDates, file, lineNumber,
                sessionId, sessionPosition, image, topicPath, null);
    }

    /** 无层级的片段可直接传入主题名；Markdown 解析器另传真实标题路径，不拆解展示字符串。 */
    public SearchEvidence(Kind kind, String id, String topic, String content, String matchText, String role,
            List<String> sourceIds, Map<String, String> sourceDates, Path file, int lineNumber,
            String sessionId, int sessionPosition, ImageReference image) {
        this(kind, id, topic, content, matchText, role, sourceIds, sourceDates, file, lineNumber,
                sessionId, sessionPosition, image,
                kind == Kind.MEMORY && !topic.isBlank() ? List.of(topic) : List.of());
    }

    static String topicName(List<String> path) { return String.join(" - ", path); }

    /** 图片属于原始会话来源，与消息共同分享候选名额，不作为第四类来源。 */
    public Kind sourceKind() { return kind == Kind.IMAGE ? Kind.SESSION : kind; }

    /** 来源标签由宿主的真实类别生成，不依赖模型猜 ID，也不改写落盘的记忆正文。 */
    public String sourceLabel() {
        return switch (kind) {
            case MEMORY -> "Long-term memory";
            case LEDGER -> "Daily ledger";
            case SESSION, IMAGE -> "Original message";
        };
    }

    public String dateText() {
        if (sourceIds.isEmpty()) return "unknown";
        List<String> dates = sourceIds.stream().map(this::displayDate).toList();
        if (dates.stream().distinct().count() == 1) return dates.get(0);
        return String.join("; ", sourceIds.stream()
                .map(id -> id + "=" + displayDate(id)).toList());
    }

    private String displayDate(String sourceId) {
        String date = sourceDates.getOrDefault(sourceId, "unknown");
        return "未知".equals(date) ? "unknown" : date;
    }

    /** 输出模型需要的内容与来源标签；不暴露文件路径、行号、算法分数或 Base64。 */
    public String display() {
        // 原图属于原始消息，但图片描述是视觉模型的派生观察，不伪装成用户原话或原图。
        if (kind == Kind.IMAGE) return "Image ID: " + id + "\nMemory source: " + sourceLabel()
                + "\nImage description (vision-model generated): " + content
                + "\nsource_id: " + String.join(",", sourceIds) + " source_date: " + dateText();
        StringBuilder result = new StringBuilder("Memory ID: ").append(id)
                .append("\nMemory source: ").append(sourceLabel()).append('\n');
        if (kind == Kind.MEMORY && !topic.isBlank()) result.append("Topic: ").append(topic).append('\n');
        if (kind == Kind.SESSION) result.append("role=").append(role).append(", ");
        result.append("Content: ").append(content).append('\n');
        if (kind != Kind.SESSION) result.append("source_ids: ")
                .append(sourceIds.isEmpty() ? "unknown" : String.join(",", sourceIds)).append(' ');
        return result.append("source_date: ").append(dateText()).toString();
    }
}
