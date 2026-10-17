package com.pa.sagememory.web;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.pa.sagememory.agent.AddAgentProperties;
import com.pa.sagememory.agent.middleware.AgentLogRedactor;
import com.pa.sagememory.agent.multimodal.VisionProperties;
import com.pa.sagememory.controller.ApiKeyAuthenticationFilter;
import com.pa.sagememory.controller.ApiKeyProperties;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ApiKeyAuthenticationFilterTest {
    private static final String KEY = "00000000-0000-4000-8000-000000000001";

    @ParameterizedTest @NullAndEmptySource @ValueSource(strings = {" ", "short", "00000000-0000-4000-8000-000000000001\n"})
    void invalidConfigurationFailsClosed(String key) {
        var properties = new ApiKeyProperties(); properties.setApiKey(key);
        assertThatThrownBy(() -> new ApiKeyAuthenticationFilter(properties)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("sage.memory.api.api-key");
    }

    @Test
    void configuredApiKeyIsIncludedInTheExistingLogRedactor() {
        var properties = properties();
        var redactor = new AgentLogRedactor(new AddAgentProperties(), new VisionProperties(), properties);
        assertThat(redactor.text("a plain credential: " + KEY)).doesNotContain(KEY).contains("认证信息已隐藏");
        assertThat(redactor.text("X-Api-Key: " + KEY)).doesNotContain(KEY);
    }

    @Test
    void unauthorizedResponseAndLogsDoNotEchoHeadersBodiesOrQueryStrings() throws Exception {
        Logger logger = (Logger) LoggerFactory.getLogger(ApiKeyAuthenticationFilter.class);
        var captured = new ListAppender<ILoggingEvent>(); captured.start(); logger.addAppender(captured);
        var request = new MockHttpServletRequest("POST", "/add");
        request.addHeader("X-Api-Key", "untrusted-private-header");
        request.setQueryString("secret-query");
        request.setContent("private-body".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        var response = new MockHttpServletResponse();
        AtomicInteger calls = new AtomicInteger();
        try { new ApiKeyAuthenticationFilter(properties()).doFilter(request, response, (req, res) -> calls.incrementAndGet()); }
        finally { logger.detachAppender(captured); captured.stop(); }
        assertThat(calls).hasValue(0);
        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
        assertThat(response.getContentAsString()).contains("UNAUTHORIZED").doesNotContain(KEY, "untrusted-private-header", "secret-query", "private-body");
        assertThat(captured.list).isNotEmpty().allSatisfy(event ->
                assertThat(event.getFormattedMessage()).doesNotContain(KEY, "untrusted-private-header", "secret-query", "private-body"));
    }

    @ParameterizedTest @ValueSource(strings = {"/add;alias=1", "/add/", "/search;alias=1", "/health/other"})
    void suppliedInvalidKeysAreRejectedOnOtherPaths(String path) throws Exception {
        var request = new MockHttpServletRequest("POST", path);
        request.addHeader("X-Api-Key", "wrong-key");
        var response = new MockHttpServletResponse();
        new ApiKeyAuthenticationFilter(properties()).doFilter(request, response, (req, res) -> { throw new AssertionError("Must not pass"); });
        assertThat(response.getStatus()).isEqualTo(401);
    }

    @ParameterizedTest @ValueSource(strings = {"/add", "/search"})
    void missingHeaderAllowsAnonymousAccess(String path) throws Exception {
        var request = new MockHttpServletRequest("POST", path);
        AtomicInteger calls = new AtomicInteger();
        var response = new MockHttpServletResponse();
        new ApiKeyAuthenticationFilter(properties()).doFilter(request, response, (req, res) -> calls.incrementAndGet());
        assertThat(calls).hasValue(1);
        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    void healthIsPublicWhenTheApplicationHasAContextPath() throws Exception {
        var request = new MockHttpServletRequest("GET", "/memory/health"); request.setContextPath("/memory");
        AtomicInteger calls = new AtomicInteger();
        new ApiKeyAuthenticationFilter(properties()).doFilter(request, new MockHttpServletResponse(), (req, res) -> calls.incrementAndGet());
        assertThat(calls).hasValue(1);
    }

    private static ApiKeyProperties properties() {
        var properties = new ApiKeyProperties(); properties.setApiKey(KEY); return properties;
    }
}
