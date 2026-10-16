package com.pa.sagememory.agent.middleware;

import com.pa.sagememory.agent.AddAgentException;
import com.pa.sagememory.agent.AddLogSupport;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.middleware.ActingInput;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.message.ToolUseBlock;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import org.springframework.http.HttpStatus;
import reactor.core.publisher.Flux;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 检查模型工具参数的一致性；会话范围由工具固定，不接受模型额外指定路径或会话。 */
public class AddToolGuardMiddleware implements MiddlewareBase {
    private static final Logger log = LoggerFactory.getLogger(AddToolGuardMiddleware.class);
    private static final Set<String> SESSION_PARAMETERS = Set.of("query", "source_ids");

    @Override
    public Flux<AgentEvent> onActing(Agent agent, RuntimeContext context, ActingInput input,
            Function<ActingInput, Flux<AgentEvent>> next) {
        for (ToolUseBlock call : input.toolCalls()) {
            Map<String, Object> arguments = call.getInput();
            if (call.getContent() != null && !call.getContent().isBlank()) {
                Map<String, Object> parsed = tools.jackson.databind.json.JsonMapper.builder().build()
                        .readValue(call.getContent(), new tools.jackson.core.type.TypeReference<Map<String, Object>>() {});
                if (!arguments.isEmpty() && !arguments.equals(parsed)) return Flux.error(rejected(context, call, "结构化参数与原始 JSON 不一致"));
                arguments = parsed;
            }
            if ("session_search".equals(call.getName())) {
                if (!SESSION_PARAMETERS.containsAll(arguments.keySet()))
                    return Flux.error(rejected(context, call, "会话回查含有不支持的参数，不能指定其他 Agent、会话或文件路径"));
            }
        }
        return next.apply(input);
    }

    private AddAgentException rejected(RuntimeContext context, ToolUseBlock call, String reason) {
        log.warn("[Add工具作用域拒绝] {}，tool={}，tool_call_id={}，原因={}；不打印工具参数", AddLogSupport.trace(context),
                AddLogSupport.value(call.getName()), AddLogSupport.value(call.getId()), reason);
        return new AddAgentException(HttpStatus.BAD_GATEWAY, "ADD_TOOL_SCOPE_REJECTED",
                "The agent requested a tool operation outside the allowed Add workspace scope.");
    }
}
