package com.pa.sagememory.agent.mergeMemoryTools;

import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import java.util.List;
import java.util.Objects;

/** 归并阶段只读核验，不搜索关键词、不登记 Search 候选、不返回原图。 */
public final class GetEvidenceTool {
    private final MergeMemoryContext context;
    public GetEvidenceTool(MergeMemoryContext context) { this.context = Objects.requireNonNull(context); }

    @Tool(name = "get_evidence", readOnly = true, description =
            "Verify uncertain memory facts against original messages by source IDs. Returns each message"
                    + " with its adjacent user-assistant partner and stored image descriptions.")
    public ToolResultBlock getEvidence(
            @ToolParam(name = "source_ids", description = "Original numeric source IDs to verify; multiple IDs are supported.")
            List<String> sourceIds) {
        return context.getEvidence(sourceIds);
    }
}
