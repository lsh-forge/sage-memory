package com.pa.sagememory.agent.searchTools;

import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 根据原始消息来源 ID 回查原文、附图描述及同会话上下文，共用本次 Search 快照。 */
public final class GetEvidenceTool {
    private static final Logger log = LoggerFactory.getLogger(GetEvidenceTool.class);
    private final SearchEvidenceContext context;

    public GetEvidenceTool(SearchEvidenceContext context) {
        this.context = Objects.requireNonNull(context);
    }

    @Tool(name = "get_evidence", readOnly = true, converter = EvidenceTextResultConverter.class, description = """
            Retrieve original messages and their attached image descriptions by source message IDs.
            Use to verify source evidence or read nearby context from the same session.
            """)
    public String getEvidence(
            @ToolParam(name = "source_ids", required = true, description = "Original source message IDs to retrieve, such as 7.")
            List<String> sourceIds,
            @ToolParam(name = "before", required = false, description = "Number of preceding messages to include per source, from 0 to 2.")
            Integer before,
            @ToolParam(name = "after", required = false, description = "Number of following messages to include per source, from 0 to 2.")
            Integer after) {
        final List<String> ids;
        boolean autoPair = before == null && after == null;
        int preceding = before == null ? 0 : before;
        int following = after == null ? 0 : after;
        try {
            if (preceding < 0 || preceding > 2 || following < 0 || following > 2)
                throw new IllegalArgumentException("before and after must be between 0 and 2.");
            ids = sourceIds(sourceIds);
        } catch (IllegalArgumentException invalid) {
            return "Invalid parameters: " + invalid.getMessage();
        }
        long startedAt = System.nanoTime();
        // 同一个 session 的窗口合并后按对话顺序输出，session 按首次请求到它的顺序组织。
        Map<String, Set<Integer>> windows = new LinkedHashMap<>();
        List<String> missing = new ArrayList<>();
        for (String id : ids) {
            SearchEvidence source = context.sources().get(id);
            if (source == null) {
                missing.add(id);
                continue;
            }
            List<SearchEvidence> session = context.sessions().get(source.sessionId());
            int position = session.indexOf(source);
            if (position < 0) throw new IllegalStateException("Source is missing from its Search session snapshot.");
            Set<Integer> selected = new TreeSet<>();
            // 用户级 ID 可以不连续或跨 session，绝不能用 source_id 加减寻找前后消息。
            if (autoPair) {
                for (SearchEvidence paired : context.sourceWithPartner(source.id())) selected.add(session.indexOf(paired));
            }
            if (!autoPair) {
                // 显式窗口（包括 0）替代默认问答配对。
                for (int index = Math.max(0, position - preceding);
                        index <= Math.min(session.size() - 1, position + following); index++) selected.add(index);
            }
            windows.computeIfAbsent(source.sessionId(), ignored -> new TreeSet<>()).addAll(selected);
        }
        List<SearchEvidence> shown = new ArrayList<>();
        List<String> blocks = new ArrayList<>();
        int messageCount = 0;
        int imageCount = 0;
        for (var window : windows.entrySet()) {
            List<SearchEvidence> session = context.sessions().get(window.getKey());
            for (int index : window.getValue()) {
                SearchEvidence source = session.get(index);
                List<SearchEvidence> images = context.imagesForSource(source.id());
                blocks.add(display(source, images));
                shown.add(source);
                shown.addAll(images);
                messageCount++;
                imageCount += images.size();
            }
        }
        // get_evidence 不隐藏此前召回过的条目：回查就是为了再次阅读完整原文与上下文。
        // 登记实际展示的消息及附图描述；图片 ID 仅在宿主内部保留映射，不展示给 Agent。
        context.registerShown(shown);
        log.info("[Search原始证据回查] user_id={}，请求ID数={}，读取模式={}，before={}，after={}，命中会话数={}，返回消息数={}，图片数={}，缺失ID数={}，耗时={}ms；不打印消息或图片描述正文",
                context.userId(), ids.size(), autoPair ? "自动问答配对" : "显式上下文窗口",
                before, after, windows.size(), messageCount, imageCount,
                missing.size(), (System.nanoTime() - startedAt) / 1_000_000);
        if (!missing.isEmpty()) blocks.add("Source IDs not found: " + String.join(",", missing));
        return String.join("\n\n", blocks);
    }

    private static List<String> sourceIds(List<String> input) {
        if (input == null || input.isEmpty()) throw new IllegalArgumentException("source_ids must be a non-empty array of original source message IDs.");
        Set<String> ids = new LinkedHashSet<>();
        for (String value : input) {
            if (value == null) throw new IllegalArgumentException("source_ids must not contain null.");
            String id = value.strip();
            try {
                if (!id.matches("[1-9]\\d*") || Long.parseLong(id) < 1) throw new NumberFormatException();
            } catch (NumberFormatException invalid) {
                throw new IllegalArgumentException("source_ids accepts only positive integer original source message IDs, not image, long-term memory or daily ledger entry IDs.");
            }
            ids.add(id);
        }
        return List.copyOf(ids);
    }

    private static String display(SearchEvidence source, List<SearchEvidence> images) {
        // matchText 对含图消息保存的是原始文字，不是已经拼接过图片描述的增强正文。
        StringBuilder block = new StringBuilder("Memory ID: ").append(source.id())
                .append("\nMemory source: ").append(source.sourceLabel())
                .append("\nrole=").append(source.role()).append(", Content: ").append(source.matchText());
        for (SearchEvidence image : images) {
            String ordinal = image.id().substring(image.id().lastIndexOf('-') + 1);
            block.append("\n[Attached image ").append(ordinal).append(" description]: ").append(image.content());
        }
        return block.append("\nsource_date: ").append(source.dateText()).toString();
    }
}
