package com.pa.sagememory.agent;

import com.pa.sagememory.agent.workspace.AddWorkspaceStore;
import com.pa.sagememory.dto.AddRequest;
import io.agentscope.core.agent.RuntimeContext;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;

/** 统一日志关联信息；不依赖 MDC，避免 Reactor 切换线程后丢失请求标识。 */
public final class AddLogSupport {
    private AddLogSupport() {}

    public static String trace(AddRequest request) {
        return "request_id=" + value(request.requestId()) + " user_id=" + value(request.userId())
                + " session_id=" + value(request.sessionId());
    }

    public static String trace(RuntimeContext context) {
        Integer batch = context.get("batch_index", Integer.class);
        return "request_id=" + value(context.get("request_id", String.class))
                + " user_id=" + value(context.getUserId()) + " session_id=" + value(context.getSessionId())
                + " batch=" + (batch == null ? "-" : batch + 1);
    }

    /** 用户可控标识必须保持单行且限制长度；日志中的批次编号从 1 开始。 */
    public static String value(Object value) {
        if (value == null) return "-";
        String raw = value.toString();
        StringBuilder safe = new StringBuilder();
        for (int index = 0; index < Math.min(raw.length(), 96); index++) {
            char character = raw.charAt(index);
            safe.append(Character.isISOControl(character) || character == '\u2028' || character == '\u2029'
                    ? '_' : character);
        }
        if (raw.length() > 96) safe.append("...[sha256:").append(AddWorkspaceStore.hash(raw), 0, 8).append(']');
        return safe.toString();
    }

    public static long elapsedMillis(long startedAt) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);
    }

    /** SDK 异常 message/堆栈首行可能含请求正文、认证信息；只记录类型和本项目错误码。 */
    public static String failure(Throwable error) {
        Throwable root = error;
        String code = "-";
        for (int depth = 0; root != null && depth < 10; depth++) {
            if (root instanceof AddAgentException known) code = known.getCode();
            if (root.getCause() == null || root.getCause() == root) break;
            root = root.getCause();
        }
        return "error_code=" + value(code) + " exception_type="
                + (root == null ? "-" : root.getClass().getSimpleName());
    }

    /** DEBUG 仅输出代码位置，不输出可能包含密钥的异常原始信息。 */
    public static String positions(Throwable error) {
        return Arrays.stream(error.getStackTrace()).limit(4).map(StackTraceElement::toString)
                .reduce("", (first, next) -> first.isEmpty() ? next : first + " <- " + next);
    }
}
