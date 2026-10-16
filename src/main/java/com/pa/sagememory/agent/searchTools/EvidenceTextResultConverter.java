package com.pa.sagememory.agent.searchTools;

import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tool.ToolResultConverter;
import java.lang.reflect.Type;

/** 保留证据正文的真实换行，避免 SDK 默认把 String 再序列化为带引号、转义符的 JSON。 */
public final class EvidenceTextResultConverter implements ToolResultConverter {
    @Override
    public ToolResultBlock convert(Object result, Type returnType) {
        return ToolResultBlock.text((String) result);
    }
}
