package com.pa.sagememory.agent.searchTools;

import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 一个 Search 请求一个实例；后续检索 Agent 只需通过 Toolkit.registerTool 注册。 */
public final class SearchEvidenceTool {
    private static final Logger log = LoggerFactory.getLogger(SearchEvidenceTool.class);
    private final SearchEvidenceContext context;
    private final KeywordEvidenceRanker ranker = new KeywordEvidenceRanker();

    public SearchEvidenceTool(SearchEvidenceContext context) {
        this.context = Objects.requireNonNull(context);
    }

    @Tool(name = "search_evidence", readOnly = true, converter = EvidenceTextResultConverter.class, description = """
            Search stored memories, conversation messages, and image descriptions by keywords.
            Matching uses literal substrings, not semantic similarity; phrases are not split into words.
            Use short keywords or phrases as separate items. If no results are found, shorten them or try synonyms.
            """)
    public String searchEvidence(
            @ToolParam(name = "keywords", required = true, description = "Specific keywords or short phrases; include Chinese and English equivalents when relevant.")
            List<String> keywords) {
        final List<String> normalized;
        try {
            normalized = KeywordEvidenceRanker.keywords(keywords);
        } catch (IllegalArgumentException invalid) {
            return "Invalid parameters: " + invalid.getMessage();
        }
        long startedAt = System.nanoTime();
        // ReAct 可能并行调用工具；选择与登记在同一个请求锁内，防止两个调用重复展示候选。
        synchronized (context) {
            KeywordEvidenceRanker.Selection selection = ranker.select(context, normalized);
            List<SearchEvidence> evidence = selection.items().stream().map(KeywordEvidenceRanker.Ranked::evidence).toList();
            context.registerShown(evidence);
            context.registerShown(selection.items().stream().flatMap(item -> item.images().stream()).toList());
            long memories = evidence.stream().filter(item -> item.sourceKind() == SearchEvidence.Kind.MEMORY).count();
            long ledgers = evidence.stream().filter(item -> item.sourceKind() == SearchEvidence.Kind.LEDGER).count();
            log.info("[Search关键词召回] user_id={}，关键词数={}，快照片段数={}，匹配数={}，未展示匹配数={}，返回数={}（长期={}，台账={}，原始消息/图片={}），耗时={}ms；不打印关键词及证据正文",
                    context.userId(), normalized.size(), context.candidates().size(), selection.totalMatches(),
                    selection.unseenMatches(), evidence.size(), memories, ledgers, evidence.size() - memories - ledgers,
                    (System.nanoTime() - startedAt) / 1_000_000);
            if (evidence.isEmpty()) return selection.totalMatches() == 0
                    ? "No matching fragments found. Try other keywords, aliases or synonyms." : "No new matching fragments. Evaluate the evidence already retrieved, or try other keywords.";
            String output = selection.items().stream().map(SearchEvidenceTool::display).collect(Collectors.joining("\n\n"));
            if (selection.unseenMatches() > evidence.size()) output += "\n\nMore matching fragments are available; you may continue retrieving.";
            return output;
        }
    }

    /** 原始消息始终以记忆 ID 展示；描述依附所属消息，不暴露内部图片 ID 或增强正文中的重复描述。 */
    private static String display(KeywordEvidenceRanker.Ranked item) {
        SearchEvidence message = item.evidence();
        if (message.kind() != SearchEvidence.Kind.SESSION) return message.display();
        StringBuilder result = new StringBuilder("Memory ID: ").append(message.id())
                .append("\nMemory source: ").append(message.sourceLabel())
                .append("\nrole=").append(message.role()).append(", Content: ").append(message.matchText());
        for (SearchEvidence image : item.images()) {
            String ordinal = image.id().substring(image.id().lastIndexOf('-') + 1);
            result.append("\n[Attached image ").append(ordinal).append(" description]: ").append(image.content());
        }
        return result.append("\nsource_date: ").append(message.dateText()).toString();
    }
}
