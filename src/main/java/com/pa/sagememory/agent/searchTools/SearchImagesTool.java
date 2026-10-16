package com.pa.sagememory.agent.searchTools;

import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 图片描述专用召回：不读取台账或长期记忆，原文仅提供上下文，不参与图片相关性计分。 */
public final class SearchImagesTool {
    private static final Logger log = LoggerFactory.getLogger(SearchImagesTool.class);
    private final SearchEvidenceContext context;
    private final KeywordEvidenceRanker ranker = new KeywordEvidenceRanker();

    public SearchImagesTool(SearchEvidenceContext context) {
        this.context = Objects.requireNonNull(context);
    }

    @Tool(name = "search_images", readOnly = true, converter = EvidenceTextResultConverter.class, description = """
            Search image descriptions attached to original messages by keywords.
            Matching uses literal substrings, not semantic similarity; 
            Use short keywords or phrases as separate items. If no results are found, shorten them or try synonyms.
            """)
    public String searchImages(
            @ToolParam(name = "keywords", required = true, description = "Visual keywords or short phrases; include Chinese and English equivalents when relevant.")
            List<String> keywords) {
        final List<String> normalized;
        try {
            normalized = KeywordEvidenceRanker.keywords(keywords);
        } catch (IllegalArgumentException invalid) {
            return "Invalid parameters: " + invalid.getMessage();
        }
        long startedAt = System.nanoTime();
        synchronized (context) {
            KeywordEvidenceRanker.Selection selection = ranker.selectImages(context, normalized);
            List<SearchEvidence> images = selection.items().stream().map(KeywordEvidenceRanker.Ranked::evidence).toList();
            LinkedHashSet<SearchEvidence> shownMessages = new LinkedHashSet<>();
            List<String> blocks = new ArrayList<>();
            for (int index = 0; index < images.size();) {
                SearchEvidence image = images.get(index);
                String sourceId = image.sourceIds().get(0);
                // 连续来自同一消息的命中图共享一次原文，避免多附图重复灌入上下文。
                // 只合并连续组，不把后面低分的同源图片挪到前面，保持严格的图片相关性顺序。
                int end = index + 1;
                while (end < images.size() && images.get(end).sourceIds().get(0).equals(sourceId)) end++;
                List<SearchEvidence> matchedImages = images.subList(index, end);
                List<SearchEvidence> messages = context.sourceWithPartner(sourceId);
                List<String> group = new ArrayList<>();
                for (SearchEvidence message : messages) {
                    group.add(originalMessage(message, sourceId.equals(message.id()) ? matchedImages : List.of()));
                }
                blocks.add(String.join("\n\n", group));
                shownMessages.addAll(messages);
                index = end;
            }
            // 只登记实际命中的图及展示的原文，不把同一消息中的其他附图标记为已展示。
            context.registerImageSearch(images, List.copyOf(shownMessages));
            log.info("[Search图片关键词召回] user_id={}，关键词数={}，匹配图片数={}，未展示匹配数={}，返回图片数={}，附带原文数={}，耗时={}ms；不打印关键词、原文或图片描述",
                    context.userId(), normalized.size(), selection.totalMatches(), selection.unseenMatches(),
                    images.size(), shownMessages.size(), (System.nanoTime() - startedAt) / 1_000_000);
            if (images.isEmpty()) return selection.totalMatches() == 0
                    ? "No matching images found. Try other visual keywords, phrases or synonyms."
                    : "No new matching images. Evaluate the evidence already retrieved, or try other keywords.";
            String output = String.join("\n\n", blocks);
            if (selection.unseenMatches() > images.size()) output += "\n\nMore matching images are available; you may continue retrieving.";
            return output;
        }
    }

    private static String originalMessage(SearchEvidence message, List<SearchEvidence> matchedImages) {
        // matchText 是用户/助手原始文字，不是拼接全部附图描述后的增强消息。
        StringBuilder result = new StringBuilder("Memory ID: ").append(message.id())
                .append("\nMemory source: ").append(message.sourceLabel())
                .append("\nrole=").append(message.role()).append(", Original text: ").append(message.matchText());
        // 描述紧随所属原文，保留原附图序号；内部图片 ID 仍用于定位、排序及分页，不展示给 Agent。
        for (SearchEvidence image : matchedImages) {
            String ordinal = image.id().substring(image.id().lastIndexOf('-') + 1);
            result.append("\n[Attached image ").append(ordinal).append(" description]: ").append(image.content());
        }
        return result.append("\nsource_date: ").append(message.dateText()).toString();
    }
}
