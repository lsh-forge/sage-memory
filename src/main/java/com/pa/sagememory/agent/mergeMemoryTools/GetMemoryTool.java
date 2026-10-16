package com.pa.sagememory.agent.mergeMemoryTools;

import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tool.Tool;
import java.util.Objects;

/** 获取最新完整记忆，保留原始空格、换行和引用，方便复制精确锚点。 */
public final class GetMemoryTool {
    private final MergeMemoryContext context;
    public GetMemoryTool(MergeMemoryContext context) { this.context = Objects.requireNonNull(context); }

    @Tool(name = "get_memory", readOnly = true, description =
            "Read the full latest MEMORY.md on demand after edits or when anchors need checking. The initial memory snapshot is already provided in the user input.")
    public ToolResultBlock getMemory() { return context.getMemory(); }
}
