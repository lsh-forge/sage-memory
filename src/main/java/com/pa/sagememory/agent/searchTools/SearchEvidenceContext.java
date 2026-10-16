package com.pa.sagememory.agent.searchTools;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** 每次 Search 单独创建，工具共用；禁止做成跨请求的单例或按用户长期缓存。 */
public final class SearchEvidenceContext {
    private final String userId;
    private final List<SearchEvidence> candidates;
    private final Map<String, SearchEvidence> sources;
    private final Map<String, List<SearchEvidence>> sessions;
    private final Map<String, List<SearchEvidence>> sourceImages;
    private final Map<String, SearchEvidence> evidenceById;
    private final Map<List<String>, List<SearchEvidence>> topicEvidence;
    private final List<String> visibleTopics;
    private final Map<String, SearchEvidence> shownEvidence = new LinkedHashMap<>();
    private final LinkedHashSet<String> shownImageSearchIds = new LinkedHashSet<>();
    private List<String> selectedIds;

    public SearchEvidenceContext(String userId, List<SearchEvidence> candidates) {
        this.userId = userId;
        this.candidates = List.copyOf(candidates);
        Map<String, SearchEvidence> allIds = new LinkedHashMap<>();
        Map<String, SearchEvidence> rawSources = new LinkedHashMap<>();
        Map<String, List<SearchEvidence>> bySession = new LinkedHashMap<>();
        Map<String, List<SearchEvidence>> bySource = new LinkedHashMap<>();
        for (SearchEvidence evidence : candidates) {
            // 图片以 p 开头，其余条目使用 m、日期或纯数字；本次快照中所有 ID 共用一个命名空间。
            if (allIds.putIfAbsent(evidence.id(), evidence) != null)
                throw new IllegalArgumentException("Duplicate evidence ID in the Search snapshot.");
            if (evidence.kind() == SearchEvidence.Kind.SESSION) {
                rawSources.put(evidence.id(), evidence);
                bySession.computeIfAbsent(evidence.sessionId(), ignored -> new ArrayList<>()).add(evidence);
            }
            if (evidence.kind() == SearchEvidence.Kind.IMAGE) {
                for (String sourceId : evidence.sourceIds())
                    bySource.computeIfAbsent(sourceId, ignored -> new ArrayList<>()).add(evidence);
            }
        }
        evidenceById = Collections.unmodifiableMap(allIds);
        sources = Collections.unmodifiableMap(rawSources);
        Map<String, List<SearchEvidence>> ordered = new LinkedHashMap<>();
        bySession.forEach((id, entries) -> ordered.put(id, entries.stream()
                .sorted(java.util.Comparator.comparingInt(SearchEvidence::sessionPosition)).toList()));
        sessions = Collections.unmodifiableMap(ordered);
        Map<String, List<SearchEvidence>> images = new LinkedHashMap<>();
        bySource.forEach((id, entries) -> images.put(id, entries.stream()
                .sorted(java.util.Comparator.comparingInt(item -> Integer.parseInt(
                        item.id().substring(item.id().lastIndexOf('-') + 1)))).toList()));
        sourceImages = Collections.unmodifiableMap(images);
        Map<List<String>, List<SearchEvidence>> topics = new LinkedHashMap<>();
        LinkedHashSet<List<String>> directTopics = new LinkedHashSet<>();
        candidates.stream().filter(item -> item.kind() == SearchEvidence.Kind.MEMORY && !item.topic().isBlank())
                .sorted(Comparator.comparingInt(SearchEvidence::lineNumber)).forEach(item -> {
                    // 索引保留祖先主题以支持整体展开；目录只展示有直属事实的主题，隐藏纯分组父标题。
                    directTopics.add(item.topicPath());
                    for (int level = 1; level <= item.topicPath().size(); level++) {
                        List<String> path = List.copyOf(item.topicPath().subList(0, level));
                        topics.computeIfAbsent(path, ignored -> new ArrayList<>()).add(item);
                    }
                });
        Map<List<String>, List<SearchEvidence>> frozenTopics = new LinkedHashMap<>();
        topics.forEach((name, entries) -> frozenTopics.put(name, List.copyOf(entries)));
        topicEvidence = Collections.unmodifiableMap(frozenTopics);
        visibleTopics = directTopics.stream().map(SearchEvidence::topicName).toList();
    }

    public String userId() { return userId; }
    public List<SearchEvidence> candidates() { return candidates; }
    public Map<String, SearchEvidence> sources() { return sources; }
    public Map<String, List<SearchEvidence>> sessions() { return sessions; }
    public List<SearchEvidence> imagesForSource(String sourceId) {
        return sourceImages.getOrDefault(sourceId, List.of());
    }

    /** 取实际相邻的问答搭档，结果按对话顺序；不跨会话，不按来源 ID 加减配对。 */
    public List<SearchEvidence> sourceWithPartner(String sourceId) {
        SearchEvidence source = sources.get(sourceId);
        if (source == null) throw new IllegalArgumentException("Image source is missing from the Search snapshot.");
        List<SearchEvidence> session = sessions.get(source.sessionId());
        int position = session.indexOf(source);
        if (position < 0) throw new IllegalStateException("Source is missing from its Search session snapshot.");
        if ("assistant".equals(source.role()) && position > 0
                && "user".equals(session.get(position - 1).role())) return List.of(session.get(position - 1), source);
        if ("user".equals(source.role()) && position + 1 < session.size()
                && "assistant".equals(session.get(position + 1).role())) return List.of(source, session.get(position + 1));
        return List.of(source);
    }

    synchronized boolean wasShownInImageSearch(SearchEvidence image) {
        return shownImageSearchIds.contains(image.id());
    }

    /** 图片专用通道的分页状态与全局可选择 ID 分开；来源原文及搭档也必须真实展示后登记。 */
    synchronized void registerImageSearch(List<SearchEvidence> images, List<SearchEvidence> messages) {
        for (SearchEvidence image : images) {
            if (image.kind() != SearchEvidence.Kind.IMAGE || !image.equals(evidenceById.get(image.id())))
                throw new IllegalArgumentException("Image is outside this Search snapshot.");
        }
        registerShown(messages);
        registerShown(images);
        images.forEach(image -> shownImageSearchIds.add(image.id()));
    }

    public List<String> memoryTopics() { return visibleTopics; }

    public record TopicMatch(List<String> names, List<SearchEvidence> evidence) {
        public boolean ambiguous() { return names.size() > 1; }
    }

    /** 完整路径优先；再按末级标题精确匹配。歧义时只给候选名称，不返回或登记任意一方的正文。 */
    public TopicMatch resolveTopic(String topic) {
        String name = topic.strip();
        List<List<String>> paths = topicEvidence.keySet().stream()
                .filter(path -> SearchEvidence.topicName(path).equals(name)).toList();
        if (paths.isEmpty()) paths = topicEvidence.keySet().stream()
                .filter(path -> path.get(path.size() - 1).equals(name)).toList();
        if (paths.size() != 1) return new TopicMatch(paths.stream().map(SearchEvidence::topicName).toList(), List.of());
        return new TopicMatch(List.of(SearchEvidence.topicName(paths.get(0))), topicEvidence.get(paths.get(0)));
    }

    /** 供后续 Search Agent 拼接提示词，只列出本次快照的主题名称，不预加载记忆正文。 */
    public String memoryTopicCatalog() {
        if (visibleTopics.isEmpty()) return "";
        return "Available memory topics:\n" + String.join("\n", memoryTopics().stream().map(name -> "- " + name).toList());
    }

    public synchronized boolean wasShown(SearchEvidence evidence) {
        return shownEvidence.containsKey(evidence.id());
    }

    /** 只登记实际展示的消息、记忆及附图描述；图片映射仅供宿主处理附件。 */
    public synchronized void registerShown(List<SearchEvidence> evidence) {
        for (SearchEvidence item : evidence) {
            if (!item.equals(evidenceById.get(item.id()))) throw new IllegalArgumentException("Evidence is outside this Search snapshot.");
            shownEvidence.putIfAbsent(item.id(), item);
        }
    }

    public synchronized Optional<SearchEvidence> shownMemory(String id) {
        return shownEvidence(id).filter(item -> item.kind() != SearchEvidence.Kind.IMAGE);
    }

    public synchronized Optional<SearchEvidence> shownImage(String id) {
        return shownEvidence(id).filter(item -> item.kind() == SearchEvidence.Kind.IMAGE);
    }

    public synchronized Optional<SearchEvidence> shownEvidence(String id) {
        return Optional.ofNullable(shownEvidence.get(id));
    }

    /** 仅供响应组装读取已确认的记忆选择。 */
    public synchronized Optional<SearchEvidence> selectedEvidence(String id) {
        if (selectedIds == null || !selectedIds.contains(id)) return Optional.empty();
        return Optional.ofNullable(evidenceById.get(id));
    }

    /** 校验全部通过后才提交；按首次出现去重，不按代码分数重新排序。 */
    public synchronized List<String> finish(List<String> ids) {
        if (ids == null) throw new IllegalArgumentException("ids must be an array; use an empty array when no evidence is useful.");
        LinkedHashSet<String> unique = new LinkedHashSet<>();
        for (String value : ids) {
            if (value == null || value.isBlank()) throw new IllegalArgumentException("ids must not contain null or blank IDs.");
            String id = value.strip();
            SearchEvidence evidence = shownEvidence.get(id);
            if (id.startsWith("p") || evidence != null && evidence.kind() == SearchEvidence.Kind.IMAGE)
                throw new IllegalArgumentException("finish_search accepts only memory IDs, not image IDs; select the image's original source message ID instead.");
            if (evidence == null)
                throw new IllegalArgumentException("ID " + id + " has not been shown in this search. Select a memory ID already returned by a retrieval tool.");
            unique.add(id);
        }
        List<String> ordered = List.copyOf(unique);
        // 同一请求只能确认一份最终结果；重复的同一选择允许返回，其他选择不能覆盖已完成结果。
        if (selectedIds != null && !selectedIds.equals(ordered))
            throw new IllegalArgumentException("The search has finished; the final selection cannot be changed.");
        selectedIds = ordered;
        return selectedIds;
    }

    public synchronized boolean isFinished() { return selectedIds != null; }

    /** 未完成不能当作空结果，宿主接入时应明确检查完成状态。 */
    public synchronized List<String> selectedIds() {
        if (selectedIds == null) throw new IllegalStateException("Search has not finished.");
        return selectedIds;
    }
}
