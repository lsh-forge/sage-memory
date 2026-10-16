package com.pa.sagememory.agent.mergeMemoryTools;

import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import java.util.Objects;

/** 默认在已有主题正文末尾补充事实；可选唯一原文锚点用于指定位置，不创建主题或替换原文。 */
public final class AppendMemoryTool {
    private final MergeMemoryContext context;
    public AppendMemoryTool(MergeMemoryContext context) { this.context = Objects.requireNonNull(context); }

    @Tool(name = "append_memory", description =
            "Add new facts to an existing topic. Normally supply only topic and content: the host appends fact bullets at the end of that topic's own body, before any child or following topic, and handles newlines. Supply anchor only for a specific insertion position; it must uniquely match existing text within topic. Identical full fact lines in the same topic are skipped. Use create_topic for new topics; use edit_memory to replace text.")
    public ToolResultBlock appendMemory(
            @ToolParam(name = "anchor", required = false, description = "Normally omit this parameter. If a specific position is needed, copy unique exact text already in topic, not a new fact from pending saves. A full heading inserts before its body. Fact bullets require a line-end anchor; inline additions require an anchor and their own spaces. Invalid or ambiguous anchors are rejected, never redirected to the topic end.") String anchor,
            @ToolParam(name = "content", description = "Required new facts as self-contained Markdown bullet lines, preserving source_ids and source_date; newlines around the list are automatic. Put every new fact here, never in anchor. No headings. With anchor, a short inline addition is also allowed; include its needed spaces.") String content,
            @ToolParam(name = "topic", description = "Required exact existing topic name or Parent > Child path. Limits the append destination and anchor search; never creates a topic.") String topic) {
        return context.appendMemory(anchor, content, topic);
    }
}
