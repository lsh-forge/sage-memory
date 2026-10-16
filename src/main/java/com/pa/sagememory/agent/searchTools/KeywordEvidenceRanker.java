package com.pa.sagememory.agent.searchTools;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** 加权关键词覆盖排序，不是向量检索，也不是完整 BM25；重复出现同一关键词不累加。 */
final class KeywordEvidenceRanker {
    static final int LIMIT = 25;
    static final int IMAGE_LIMIT = 20;
    private static final int RESERVED_PER_SOURCE = 3;
    private static final int RESERVED_USER_OR_IMAGE = 2;
    private static final double ASSISTANT_TEXT_WEIGHT = 0.7;
    private static final Comparator<Ranked> ORDER = Comparator.comparingDouble(Ranked::score).reversed()
            .thenComparing(item -> item.evidence().sourceKind().ordinal())
            .thenComparing(item -> item.evidence().file().toString())
            .thenComparingInt(item -> item.evidence().lineNumber())
            .thenComparing(item -> item.evidence().id());

    record Ranked(SearchEvidence evidence, double score, List<SearchEvidence> images) {
        Ranked(SearchEvidence evidence, double score) { this(evidence, score, List.of()); }
    }
    record Selection(List<Ranked> items, int totalMatches, int unseenMatches) {}
    private record Hit(int start, int end) {}

    private static final class MessageMatch {
        final SearchEvidence evidence;
        final Map<String, SearchEvidence> images = new LinkedHashMap<>();
        double score;
        boolean messageMatched;

        MessageMatch(SearchEvidence evidence) { this.evidence = evidence; }

        Ranked ranked(SearchEvidenceContext context) {
            List<SearchEvidence> attachments = evidence.kind() != SearchEvidence.Kind.SESSION ? List.of()
                    : context.imagesForSource(evidence.id()).stream()
                            .filter(image -> messageMatched || images.containsKey(image.id())).toList();
            return new Ranked(evidence, score, attachments);
        }
    }

    /** 限制工具输入规模；关键词和证据使用同一套标点/空白归一化，等价短语只计一次。 */
    static List<String> keywords(List<String> input) {
        if (input == null || input.isEmpty() || input.size() > 32)
            throw new IllegalArgumentException("Provide 1 to 32 non-empty keywords or phrases.");
        List<String> result = new ArrayList<>();
        for (String value : input) {
            if (value == null) throw new IllegalArgumentException("Keywords must not be null.");
            String normalized = normalize(value).strip();
            if (normalized.isEmpty()) continue;
            if (normalized.codePointCount(0, normalized.length()) > 200)
                throw new IllegalArgumentException("Each keyword must not exceed 200 characters. Split it into keywords or phrases.");
            if (!result.contains(normalized)) result.add(normalized);
        }
        if (result.isEmpty()) throw new IllegalArgumentException("Provide at least one non-empty keyword.");
        return result.stream().sorted(Comparator.comparingInt(String::length).reversed()
                .thenComparing(Comparator.naturalOrder())).toList();
    }

    Selection select(SearchEvidenceContext context, List<String> keywords) {
        // 先按原始消息归组，再分配三类来源名额和截取；同一消息的多张图不重复占候选名额。
        Map<String, MessageMatch> grouped = new LinkedHashMap<>();
        for (Ranked hit : score(context.candidates(), keywords, false)) {
            SearchEvidence evidence = hit.evidence();
            boolean imageHit = evidence.kind() == SearchEvidence.Kind.IMAGE;
            SearchEvidence owner = imageHit ? context.sources().get(evidence.sourceIds().get(0)) : evidence;
            if (owner == null) throw new IllegalStateException("Matched image has no source message.");
            MessageMatch match = grouped.computeIfAbsent(owner.id(), ignored -> new MessageMatch(owner));
            // 以最相关的命中项计分，不因附图多或相同信息重复命中而累加抬高排序。
            match.score = Math.max(match.score, hit.score());
            if (imageHit) match.images.put(evidence.id(), evidence);
            else match.messageMatched = true;
        }
        List<Ranked> allMatches = grouped.values().stream().map(match -> match.ranked(context)).sorted(ORDER).toList();
        // 消息已展示但新描述尚未展示时仍可补充；未命中的兄弟图片不登记为已展示。
        List<Ranked> matches = allMatches.stream().filter(item -> !context.wasShown(item.evidence())
                || item.images().stream().anyMatch(image -> !context.wasShown(image))).toList();
        Map<SearchEvidence, Ranked> selected = new LinkedHashMap<>();
        for (SearchEvidence.Kind kind : List.of(SearchEvidence.Kind.MEMORY,
                SearchEvidence.Kind.LEDGER, SearchEvidence.Kind.SESSION)) {
            List<Ranked> sourceMatches = matches.stream()
                    .filter(item -> item.evidence().sourceKind() == kind).toList();
            Map<SearchEvidence, Ranked> reserved = new LinkedHashMap<>();
            if (kind == SearchEvidence.Kind.SESSION) {
                // 会话原有 3 个预留名额中，优先保护最多 2 个用户原话/含图消息。
                sourceMatches.stream().filter(item -> !item.images().isEmpty()
                                || "user".equals(item.evidence().role()))
                        .limit(RESERVED_USER_OR_IMAGE).forEach(item -> reserved.put(item.evidence(), item));
            }
            // 不足时按本来源的相关性正常补位，不限制助手数量，也不另占总返回名额。
            for (Ranked item : sourceMatches) {
                if (reserved.size() >= RESERVED_PER_SOURCE) break;
                reserved.putIfAbsent(item.evidence(), item);
            }
            selected.putAll(reserved);
        }
        for (Ranked item : matches) {
            if (selected.size() >= LIMIT) break;
            selected.putIfAbsent(item.evidence(), item);
        }
        // 预留名额只保证候选机会；输出仍按共同分数排序，不给无命中来源凑数。
        return new Selection(selected.values().stream().sorted(ORDER).toList(), allMatches.size(), matches.size());
    }

    /** 图片专用通道：只匹配描述、只在图片语料中计算稀有程度，不匹配原文、主题或日期。 */
    Selection selectImages(SearchEvidenceContext context, List<String> keywords) {
        List<SearchEvidence> images = context.candidates().stream()
                .filter(item -> item.kind() == SearchEvidence.Kind.IMAGE).toList();
        List<Ranked> allMatches = score(images, keywords, true);
        // 独立翻页：其他工具展示过的图片仍可在此补全原文；此工具再次检索则优先返回新图片。
        List<Ranked> unseen = allMatches.stream()
                .filter(item -> !context.wasShownInImageSearch(item.evidence())).toList();
        return new Selection(unseen.stream().limit(IMAGE_LIMIT).toList(), allMatches.size(), unseen.size());
    }

    /** 共享关键词覆盖、IDF 和短语长度加权；图片通道不套用三类来源配额。 */
    private List<Ranked> score(List<SearchEvidence> corpus, List<String> keywords, boolean captionsOnly) {
        List<double[]> weights = new ArrayList<>();
        int[] documentFrequency = new int[keywords.size()];
        for (SearchEvidence evidence : corpus) {
            // 来源 ID 不作为匹配文本。日期来自原始消息，而非台账文件名或 Save 的操作时间。
            // 原文和描述独立计分，综合通道稍后按消息合并；图片专用通道仍使用图片候选。
            double[] body = matches(evidence.matchText(), keywords);
            double[] topic = !captionsOnly && evidence.kind() == SearchEvidence.Kind.MEMORY
                    ? matches(evidence.topic(), keywords) : new double[keywords.size()];
            // 日期命中由所属消息承接，不能将未命中描述的图片误判为描述命中。
            double[] dates = captionsOnly || evidence.kind() == SearchEvidence.Kind.IMAGE ? new double[keywords.size()]
                    : matches(String.join(" ", evidence.sourceDates().values()), keywords);
            double[] item = new double[keywords.size()];
            for (int i = 0; i < keywords.size(); i++) {
                item[i] = Math.max(body[i], Math.max(topic[i] * 0.65, dates[i] * 0.85));
                if (item[i] > 0) documentFrequency[i]++;
            }
            weights.add(item);
        }
        List<Ranked> matches = new ArrayList<>();
        for (int index = 0; index < corpus.size(); index++) {
            double score = 0;
            for (int i = 0; i < keywords.size(); i++) {
                double idf = Math.log1p((corpus.size() - documentFrequency[i] + 0.5)
                        / (documentFrequency[i] + 0.5));
                int length = keywords.get(i).codePointCount(0, keywords.get(i).length());
                // 长短语稍加权，上限 1.5；不让长字符串压倒关键词覆盖与稀有程度。
                double lengthBoost = 1 + Math.min(0.5, Math.log1p(length) * 0.12);
                score += weights.get(index)[i] * idf * lengthBoost;
            }
            if (score <= 0) continue;
            // 只对助手原始文本软降权，不删除候选；图片描述和两类 Markdown 记忆不受影响。
            SearchEvidence evidence = corpus.get(index);
            if (evidence.kind() == SearchEvidence.Kind.SESSION && "assistant".equals(evidence.role()))
                score *= ASSISTANT_TEXT_WEIGHT;
            matches.add(new Ranked(evidence, score));
        }
        matches.sort(ORDER);
        return matches;
    }

    private static double[] matches(String text, List<String> keywords) {
        String normalized = normalize(text);
        double[] result = new double[keywords.size()];
        List<Hit> occupied = new ArrayList<>();
        for (int i = 0; i < keywords.size(); i++) {
            String keyword = keywords.get(i);
            List<Hit> accepted = new ArrayList<>();
            int position = normalized.indexOf(keyword);
            while (position >= 0) {
                Hit hit = new Hit(position, position + keyword.length());
                // 同一位置被长短语覆盖时，短词不重复计分；别处独立命中仍可计分一次。
                if (occupied.stream().noneMatch(existing -> existing.start() <= hit.start()
                        && existing.end() >= hit.end())) accepted.add(hit);
                position = normalized.indexOf(keyword, position + 1);
            }
            if (!accepted.isEmpty()) {
                result[i] = 1;
                occupied.addAll(accepted);
            }
        }
        return result;
    }

    private static String normalize(String text) {
        String value = Normalizer.normalize(text == null ? "" : text, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
        StringBuilder result = new StringBuilder(value.length());
        boolean gap = false;
        for (int offset = 0; offset < value.length();) {
            int point = value.codePointAt(offset);
            int next = offset + Character.charCount(point);
            // 只放宽词间分隔：long, wavy fur / long、wavy fur / 多空格或换行等价。
            // 分隔符替换成空格而非删除，避免 red,dog 错误匹配成 reddog。
            boolean numericComma = point == ',' && offset > 0 && next < value.length()
                    && Character.isDigit(value.codePointBefore(offset)) && Character.isDigit(value.codePointAt(next));
            boolean separator = Character.isWhitespace(point) || Character.isSpaceChar(point)
                    || point == '、' || point == ';' || (point == ',' && !numericComma);
            if (separator) gap = !result.isEmpty();
            else {
                if (gap) result.append(' ');
                result.appendCodePoint(point);
                gap = false;
            }
            offset = next;
        }
        // 不一刀切移除标点：保留 C++、C#、.NET、版本号、日期、时间和数字千分位等有意义字符。
        return result.toString();
    }
}
