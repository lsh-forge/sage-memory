package com.pa.sagememory.agent.mergeMemoryTools;

import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import java.util.Objects;

/** 唯一文本替换，支持主题标题、短句、整行或连续文本块，不全量重写。 */
public final class EditMemoryTool {
    private final MergeMemoryContext context;
    public EditMemoryTool(MergeMemoryContext context) { this.context = Objects.requireNonNull(context); }

    @Tool(name = "edit_memory", description =
            "Replace unique exact text: a full heading to rename a topic, a fact to revise it, or a complete section to replace it. Use topic only to narrow matching.")
    public ToolResultBlock editMemory(
            @ToolParam(name = "anchor", description = "Non-empty exact original text to replace, not just a topic selector. For a whole section, include its heading and body. Include more context if ambiguous.") String anchor,
            @ToolParam(name = "new_content", description = "Non-empty replacement text with relevant original source_ids and source_date preserved.") String newContent,
            @ToolParam(name = "topic", required = false, description = "Optional exact topic name or Parent > Child path to restrict matching to that section.") String topic) {
        return context.editMemory(anchor, newContent, topic);
    }
}
