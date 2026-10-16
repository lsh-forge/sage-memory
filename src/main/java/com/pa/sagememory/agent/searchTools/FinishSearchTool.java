package com.pa.sagememory.agent.searchTools;

import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.json.JsonMapper;

/** 确认模型选定的有序证据 ID；不读取片段，不总结、不做二次排序。 */
public final class FinishSearchTool {
    private static final Logger log = LoggerFactory.getLogger(FinishSearchTool.class);
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final SearchEvidenceContext context;

    public FinishSearchTool(SearchEvidenceContext context) {
        this.context = Objects.requireNonNull(context);
    }

    // SDK 仅在同轮全部工具为 returnDirect 且全部成功时停止。
    // 后续接入 Search Agent 时，让 finish_search 独立执行，不与检索工具混在同轮调用。
    @Tool(name = "finish_search", readOnly = true, returnDirect = true, description = """
            Finish the search by selecting memory IDs that provide useful evidence for answering the user's question.
            Order the selected IDs from most to least relevant to the question.
            """)
    public ToolResultBlock finishSearch(
            @ToolParam(name = "ids", required = true, description = "Selected memory IDs, ordered by relevance. Use an empty array if none are useful.")
            List<String> ids) {
        final List<String> selected;
        try {
            selected = context.finish(ids);
        } catch (IllegalArgumentException invalid) {
            log.warn("[Search最终选择拒绝] user_id={}，提交 ID 数={}；参数无效、传入图片 ID、记忆 ID 未展示或检索已完成，不修改最终结果",
                    context.userId(), ids == null ? 0 : ids.size());
            // ERROR 不触发 returnDirect，使模型有机会修正，而不是以错误文字结束检索。
            return ToolResultBlock.error(invalid.getMessage());
        }
        log.info("[Search最终选择完成] user_id={}，提交 ID 数={}，去重后={}，重复数={}；保留模型相关性顺序，直接返回 ID，不调用总结模型",
                context.userId(), ids.size(), selected.size(), ids.size() - selected.size());
        return ToolResultBlock.text(JSON.writeValueAsString(selected)).withState(ToolResultState.SUCCESS);
    }
}
