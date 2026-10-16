package com.pa.sagememory.agent;

import io.agentscope.core.model.ModelHttpException;
import io.agentscope.core.model.transport.HttpTransportException;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.RejectedExecutionException;

/** Agent 执行层的一次兜底；不改变模型输入、工具行为或原请求截止时间。 */
public final class AgentRetryPolicy {
    public static final int MAX_ATTEMPTS = 2;
    private static final Set<String> RETRYABLE_CODES = Set.of(
            "ADD_BATCH_INCOMPLETE", "ADD_AGENT_FAILED", "ADD_MODEL_TIMEOUT", "ADD_BATCH_TIMEOUT",
            "ADD_CONSOLIDATION_FAILED", "ADD_CONSOLIDATION_TIMEOUT", "ADD_CONSOLIDATION_LINT_REQUIRED",
            "ADD_CONSOLIDATION_ITERATION_LIMIT", "SEARCH_FINISH_REQUIRED", "SEARCH_MODEL_FAILED", "SEARCH_TIMEOUT");

    private AgentRetryPolicy() {}

    public static boolean retryable(Throwable error) {
        if (Thread.currentThread().isInterrupted() || !(error instanceof RuntimeException)) return false;
        var seen = Collections.newSetFromMap(new IdentityHashMap<Throwable, Boolean>());
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (!seen.add(cause)) return false;
            if (cause instanceof InterruptedException || cause instanceof CancellationException
                    || cause instanceof RejectedExecutionException || cause instanceof SecurityException
                    || reactor.core.Exceptions.isCancel(cause)) return false;
            if (cause instanceof ModelHttpException http && http.getStatusCode() != null && !http.isRetryableHttpStatus()) return false;
            if (cause instanceof HttpTransportException http && !http.isRetryable()) return false;
            if (cause instanceof AddAgentException known && !RETRYABLE_CODES.contains(known.getCode())) return false;
            if (cause instanceof SearchAgentException known && !RETRYABLE_CODES.contains(known.getCode())) return false;
        }
        return !(error instanceof IllegalArgumentException);
    }
}
