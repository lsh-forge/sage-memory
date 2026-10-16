package com.pa.sagememory.agent.searchTools;

import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 展开长期记忆主题，返回该主题及子主题下的全部条目，不读取台账或原始会话。 */
public final class GetTopicEvidenceTool {
    private static final Logger log = LoggerFactory.getLogger(GetTopicEvidenceTool.class);
    private final SearchEvidenceContext context;

    public GetTopicEvidenceTool(SearchEvidenceContext context) {
        this.context = Objects.requireNonNull(context);
    }

    @Tool(name = "get_topic_evidence", readOnly = true, converter = EvidenceTextResultConverter.class,
            description = "Retrieve memories under a topic, including its subtopics. For an ambiguous title, return matching full topic names.")
    public String getTopicEvidence(
            @ToolParam(name = "topic", required = true, description = "Full topic path, parent topic name, or a unique short title.")
            String topic) {
        if (topic == null || topic.isBlank()) return "Invalid parameters: topic must be a non-empty topic name.";
        long startedAt = System.nanoTime();
        String name = topic.strip();
        SearchEvidenceContext.TopicMatch match = context.resolveTopic(name);
        if (match.ambiguous()) {
            log.info("[Search主题匹配歧义] user_id={}，候选主题数={}；仅返回完整主题名称供选择，不登记证据", context.userId(), match.names().size());
            return "Multiple topics matched. Specify a full topic name:\n" + match.names().stream()
                    .map(topicName -> "- " + topicName).collect(Collectors.joining("\n"));
        }
        List<SearchEvidence> evidence = match.evidence();
        if (evidence.isEmpty()) {
            log.info("[Search主题未命中] user_id={}，可用主题数={}；请使用目录中的完整主题名称", context.userId(), context.memoryTopics().size());
            return "Topic not found: " + name;
        }
        // 每条片段保留自己的完整主题路径，父主题查询不会把所有子主题都标成同一个父名称。
        String output = evidence.stream().map(SearchEvidence::display).collect(Collectors.joining("\n\n"));
        context.registerShown(evidence);
        log.info("[Search主题记忆回查] user_id={}，返回条目数={}，耗时={}ms；按文件顺序完整展开，不打印主题及记忆正文",
                context.userId(), evidence.size(), (System.nanoTime() - startedAt) / 1_000_000);
        return output;
    }
}
