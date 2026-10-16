package com.pa.sagememory.agent.middleware;

import com.pa.sagememory.agent.AddLogSupport;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.*;
import io.agentscope.core.middleware.*;
import io.agentscope.core.message.MsgRole;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;

/** 每个 Agent 实例单独创建；按一轮模型输出和一次工具回执汇总打印，不逐 token 刷屏。 */
public final class AgentDebugLoggingMiddleware implements MiddlewareBase {
    private static final Logger log = LoggerFactory.getLogger(AgentDebugLoggingMiddleware.class);
    private static final int BOX_WIDTH = 100;
    private static final String BORDER = "+" + "=".repeat(BOX_WIDTH + 2) + "+";
    private final AgentDebugLogProperties properties;
    private final AgentLogRedactor redactor;
    private final AtomicInteger rounds = new AtomicInteger();
    private final AtomicInteger toolCalls = new AtomicInteger();
    public AgentDebugLoggingMiddleware(AgentDebugLogProperties properties, AgentLogRedactor redactor) {
        this.properties = properties; this.redactor = redactor;
    }
    // 靠近核心，观察经过其它 middleware 改写后实际送给模型的 messages。
    @Override public int order() { return 0; }

    @Override public Flux<AgentEvent> onModelCall(Agent agent, RuntimeContext context, ModelCallInput input,
            Function<ModelCallInput, Flux<AgentEvent>> next) {
        return Flux.defer(() -> {
            if (!properties.isEnabled()) return next.apply(input);
            String trace = trace(agent, context);
            int round = rounds.incrementAndGet();
            safeLog(() -> {
                // 每个 Agent 仅在首次模型调用时打印实际提示词；后续轮次不再展开累积的对话历史。
                if (round == 1) {
                    for (MsgRole role : java.util.List.of(MsgRole.SYSTEM, MsgRole.USER)) {
                        var messages = input.messages().stream().filter(message -> message.getRole() == role).toList();
                        if (messages.isEmpty()) continue;
                        String body = String.join("\n\n", messages.stream().map(message -> redactor.blocks(message.getContent())).toList());
                        section(role == MsgRole.SYSTEM ? "系统提示词" : "用户提示词", trace, "仅首次打印", body);
                    }
                } else {
                    // 收尾提示是新注入的控制消息，可单独观察；仍不打印已有用户问题或累计历史上下文。
                    input.messages().stream().filter(message -> message.getRole() == MsgRole.USER
                                    && SearchAgentMiddleware.FINISH_REMINDER_NAME.equals(message.getName()))
                            .forEach(message -> section("收尾提示词", trace, "第" + round + "次模型调用，仅打印本轮新增提醒",
                                    redactor.blocks(message.getContent())));
                }
                section("模型调用开始", trace, "第" + round + "次模型调用", "");
            });
            StringBuilder text = new StringBuilder();
            StringBuilder thinking = new StringBuilder();
            Map<String, ToolOutput> tools = new LinkedHashMap<>();
            return Flux.defer(() -> next.apply(input)).doOnNext(event -> {
                if (event instanceof TextBlockDeltaEvent delta) append(text, delta.getDelta());
                else if (event instanceof ThinkingBlockDeltaEvent delta) append(thinking, delta.getDelta());
                else if (event instanceof ToolCallStartEvent call) tools.computeIfAbsent(call.getToolCallId(), ignored -> new ToolOutput()).name = call.getToolCallName();
                else if (event instanceof ToolCallDeltaEvent call) {
                    ToolOutput output = tools.computeIfAbsent(call.getToolCallId(), ignored -> new ToolOutput());
                    if (call.getToolCallName() != null) output.name = call.getToolCallName();
                    append(output.text, call.getDelta());
                }
                // DataBlockDeltaEvent 的增量可能包含裸 Base64，不保存或打印其原始内容。
            }).doOnComplete(() -> safeLog(() -> {
                // 完成通知交给下一步之前打印，尽量保持“模型输出 → 工具执行”的日志顺序。
                StringBuilder output = new StringBuilder();
                if (!thinking.isEmpty()) output.append("[模型思考]\n").append(redactor.text(thinking.toString())).append('\n');
                if (!text.isEmpty()) output.append("[文本]\n").append(redactor.text(text.toString())).append('\n');
                tools.forEach((id, tool) -> output.append("[工具调用] id=").append(redactor.text(id))
                        .append("，name=").append(redactor.text(tool.name)).append("\n参数：").append(redactor.jsonArguments(tool.text.toString())).append('\n'));
                section("模型输出", trace, "第" + round + "次模型调用，结束状态=onComplete",
                        output.isEmpty() ? "[无文本或工具调用输出]" : output);
            })).doOnError(error -> safeLog(() -> section("模型调用异常", trace,
                    "第" + round + "次模型调用，异常类型=" + error.getClass().getSimpleName()
                            + "；以下为完整脱敏原因链及堆栈",
                    redactor.exception(error)))).doFinally(signal -> safeLog(() -> {
                if (signal != reactor.core.publisher.SignalType.ON_COMPLETE) section("模型输出未完成", trace,
                        "第" + round + "次模型调用，结束状态=" + signal, "不打印可能含半截认证信息的增量正文");
            }));
        });
    }

    @Override public Flux<AgentEvent> onActing(Agent agent, RuntimeContext context, ActingInput input,
            Function<ActingInput, Flux<AgentEvent>> next) {
        return Flux.defer(() -> {
            if (!properties.isEnabled()) return next.apply(input);
            String trace = trace(agent, context);
            Map<String, ToolOutput> results = new LinkedHashMap<>();
            input.toolCalls().forEach(call -> {
                ToolOutput result = new ToolOutput();
                result.name = call.getName(); result.number = toolCalls.incrementAndGet(); result.round = rounds.get();
                results.put(call.getId(), result);
                safeLog(() -> section("工具参数", trace, toolDetails(result, call.getId()), redactor.arguments(call)));
            });
            return next.apply(input).doOnNext(event -> safeLog(() -> {
                if (event instanceof ToolResultTextDeltaEvent delta) {
                    ToolOutput result = results.computeIfAbsent(delta.getToolCallId(), ignored -> new ToolOutput());
                    result.name = delta.getToolCallName(); append(result.text, delta.getDelta());
                } else if (event instanceof ToolResultDataDeltaEvent data) {
                    ToolOutput result = results.computeIfAbsent(data.getToolCallId(), ignored -> new ToolOutput());
                    result.name = data.getToolCallName();
                    result.text.append('\n').append(redactor.blocks(java.util.List.of(data.getData())));
                } else if (event instanceof ToolResultEndEvent end) {
                    ToolOutput result = results.remove(end.getToolCallId());
                    section("工具返回", trace, (result == null ? "tool=" + redactor.text(end.getToolCallName())
                            : toolDetails(result, end.getToolCallId())) + "，状态=" + end.getState(),
                            result == null || result.text.isEmpty() ? "[无返回正文]" : redactor.text(result.text.toString()));
                }
            })).doFinally(signal -> safeLog(() -> results.forEach((id, result) -> section(
                    "工具未完整返回", trace, toolDetails(result, id) + "，结束状态=" + signal, "不打印增量正文"))));
        });
    }

    @Override public Flux<AgentEvent> onAgent(Agent agent, RuntimeContext context, AgentInput input,
            Function<AgentInput, Flux<AgentEvent>> next) {
        if (!properties.isEnabled()) return next.apply(input);
        return next.apply(input).doOnNext(event -> {
            if (event instanceof AgentResultEvent result) safeLog(() -> section("最终输出", trace(agent, context),
                    "模型调用总数=" + rounds.get() + "，工具调用总数=" + toolCalls.get() + "，结束原因=" + result.getResult().getGenerateReason(),
                    redactor.blocks(result.getResult().getContent())));
        });
    }
    private void section(String title, String trace, String details, Object body) {
        log.info("\n{}", boxed("[Agent调试-" + title + "]\n" + trace + "\n" + details + "\n\n" + body));
    }

    /** 固定显示宽度包裹正文；长行换行但不截断，中文按双列估算，避免右边框错位。 */
    static String boxed(String text) {
        StringBuilder box = new StringBuilder(BORDER).append('\n');
        boxLine(box, "", 0);
        for (String line : text.replace("\r\n", "\n").replace('\r', '\n').replace("\t", "    ").split("\n", -1)) {
            StringBuilder part = new StringBuilder();
            int width = 0;
            for (int offset = 0; offset < line.length();) {
                int point = line.codePointAt(offset);
                int columns = displayWidth(point);
                if (width + columns > BOX_WIDTH) {
                    boxLine(box, part.toString(), width);
                    part.setLength(0); width = 0;
                }
                part.appendCodePoint(point); width += columns;
                offset += Character.charCount(point);
            }
            boxLine(box, part.toString(), width);
        }
        boxLine(box, "", 0);
        return box.append(BORDER).toString();
    }

    private static void boxLine(StringBuilder box, String line, int width) {
        box.append("| ").append(line).append(" ".repeat(BOX_WIDTH - width)).append(" |\n");
    }

    private static int displayWidth(int point) {
        int type = Character.getType(point);
        if (type == Character.NON_SPACING_MARK || type == Character.ENCLOSING_MARK || point == 0x200D) return 0;
        return point >= 0x1100 && (point <= 0x115F || point >= 0x2E80 && point <= 0xA4CF
                || point >= 0xAC00 && point <= 0xD7A3 || point >= 0xF900 && point <= 0xFAFF
                || point >= 0xFE10 && point <= 0xFE6F || point >= 0xFF00 && point <= 0xFF60
                || point >= 0xFFE0 && point <= 0xFFE6 || point >= 0x1F300 && point <= 0x1FAFF
                || point >= 0x20000 && point <= 0x3FFFD) ? 2 : 1;
    }
    private String toolDetails(ToolOutput tool, String id) {
        return "第" + tool.number + "次工具调用，所属模型轮次=" + tool.round
                + "，tool=" + redactor.text(tool.name) + "，tool_call_id=" + redactor.text(id);
    }
    private String trace(Agent agent, RuntimeContext context) {
        return redactor.text("agent=" + AddLogSupport.value(agent.getName()) + " agent_id=" + AddLogSupport.value(agent.getAgentId())
                + " " + AddLogSupport.trace(context));
    }
    private static void append(StringBuilder buffer, String delta) { if (delta != null) buffer.append(delta); }
    private static void safeLog(Runnable action) {
        try { action.run(); }
        catch (RuntimeException failure) { log.warn("[Agent调试日志跳过] 异常类型={}；日志格式化失败不影响业务，不打印异常原文", failure.getClass().getSimpleName()); }
    }
    private static final class ToolOutput {
        String name;
        int number;
        int round;
        final StringBuilder text = new StringBuilder();
    }
}
