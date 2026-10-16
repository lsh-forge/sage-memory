package com.pa.sagememory.agent.mergeMemoryTools;

import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import java.util.Objects;

/** 创建新主题；标题、文件根标题和段落间隔由程序生成，不覆盖已有主题。 */
public final class CreateTopicTool {
    private final MergeMemoryContext context;
    public CreateTopicTool(MergeMemoryContext context) { this.context = Objects.requireNonNull(context); }

    @Tool(name = "create_topic", description =
            "Create a new top-level memory topic at the file end. The host generates its heading; existing topics are never overwritten.")
    public ToolResultBlock createTopic(
            @ToolParam(name = "topic", description = "New topic name only, without Markdown heading markers or newlines. Reuse an existing topic instead of creating a duplicate.") String topic,
            @ToolParam(name = "content", description = "Self-contained Markdown fact bullets, without headings. Preserve supported original source_ids and source_date.") String content) {
        return context.createTopic(topic, content);
    }
}
