package com.pa.sagememory.agent.mergeMemoryTools;

import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import java.util.Objects;

/** 删除主题区块或原文片段命中的第一整行；省略列表符号和引用仍可定位，不做语义模糊匹配。 */
public final class DeleteMemoryTool {
    private final MergeMemoryContext context;
    public DeleteMemoryTool(MergeMemoryContext context) { this.context = Objects.requireNonNull(context); }

    @Tool(name = "delete_memory", description =
            "Remove a topic and its subtopics, or one complete line containing original target text. Exact full-line matches take priority; if multiple lines match, delete only the first in document order. Use topic to narrow line deletion; repeat only if more matching lines should be removed.")
    public ToolResultBlock deleteMemory(
            @ToolParam(name = "target_type", description = "topic to remove a topic section, or line to remove one complete line.") String targetType,
            @ToolParam(name = "target", description = "For topic deletion: exact topic name or Parent > Child path. For line deletion: a full line or distinctive literal text from it; the bullet marker and source annotations may be omitted. No newlines.") String target,
            @ToolParam(name = "topic", required = false, description = "Optional topic name or Parent > Child path limiting where target is matched. Used only with target_type=line; the first matching line within this scope is deleted.") String topic) {
        return context.deleteMemory(targetType, target, topic);
    }
}
