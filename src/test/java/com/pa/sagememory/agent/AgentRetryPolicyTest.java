package com.pa.sagememory.agent;

import io.agentscope.core.model.transport.HttpTransportException;
import java.io.IOException;
import java.util.concurrent.CancellationException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import static org.assertj.core.api.Assertions.assertThat;

class AgentRetryPolicyTest {
    @Test void executionAndCompletionFailuresCanRetry() {
        for (String code : java.util.List.of("ADD_BATCH_INCOMPLETE", "ADD_AGENT_FAILED", "ADD_BATCH_TIMEOUT",
                "ADD_MODEL_TIMEOUT", "ADD_CONSOLIDATION_ITERATION_LIMIT", "ADD_CONSOLIDATION_LINT_REQUIRED")) {
            assertThat(AgentRetryPolicy.retryable(new AddAgentException(HttpStatus.BAD_GATEWAY, code, "failure"))).as(code).isTrue();
        }
        assertThat(AgentRetryPolicy.retryable(new SearchAgentException(HttpStatus.BAD_GATEWAY, "SEARCH_FINISH_REQUIRED", "failure"))).isTrue();
    }
    @Test void permanentFailuresAndCancellationNeverRetry() {
        for (String code : java.util.List.of("ADD_STORAGE_FAILED", "ADD_TOOL_SCOPE_REJECTED", "ADD_REQUEST_TIMEOUT", "ADD_MODEL_CONFIGURATION_INVALID"))
            assertThat(AgentRetryPolicy.retryable(new AddAgentException(HttpStatus.BAD_GATEWAY, code, "failure"))).as(code).isFalse();
        assertThat(AgentRetryPolicy.retryable(new SearchAgentException(HttpStatus.GATEWAY_TIMEOUT, "SEARCH_REQUEST_TIMEOUT", "expired"))).isFalse();
        assertThat(AgentRetryPolicy.retryable(new RuntimeException(new CancellationException()))).isFalse();
        assertThat(AgentRetryPolicy.retryable(new RuntimeException(new InterruptedException()))).isFalse();
        assertThat(AgentRetryPolicy.retryable(new RuntimeException(new HttpTransportException("unauthorized", 401, "")))).isFalse();
        assertThat(AgentRetryPolicy.retryable(new IOException("storage"))).isFalse();
        assertThat(AgentRetryPolicy.retryable(new IllegalArgumentException("invalid configuration"))).isFalse();
        assertThat(AgentRetryPolicy.retryable(new OutOfMemoryError())).isFalse();
    }
}
