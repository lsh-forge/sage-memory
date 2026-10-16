package com.pa.sagememory.agent.mergeMemoryTools;

import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tool.Tool;
import java.util.Objects;

/** 程序只读检查并反馈行号；引用存在不等于语义支持，不做模型审核。 */
public final class LintMemoryTool {
    private final MergeMemoryContext context;
    public LintMemoryTool(MergeMemoryContext context) { this.context = Objects.requireNonNull(context); }

    @Tool(name = "lint_memory", readOnly = true, description =
            "Validate the latest memory's structure, exact duplicates, source IDs, dates and character limit. Reports issues without modifying the file.")
    public ToolResultBlock lintMemory() { return context.lintMemory(); }
}
