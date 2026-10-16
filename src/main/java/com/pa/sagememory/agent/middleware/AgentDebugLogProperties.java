package com.pa.sagememory.agent.middleware;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** 详细正文日志单独开关，不依赖 SDK DEBUG；关闭时保留业务阶段日志。 */
@Component
@ConfigurationProperties(prefix = "sage.memory.debug-log")
public class AgentDebugLogProperties {
    private boolean enabled;
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean value) { enabled = value; }
}
