package com.pa.sagememory.agent;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.pa.sagememory.agent.middleware.AddLoggingMiddleware;
import com.pa.sagememory.agent.workspace.AddWorkspaceStore;
import com.pa.sagememory.dto.AddMessage;
import com.pa.sagememory.dto.AddRequest;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import reactor.core.publisher.Flux;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 检查中文阶段日志可串联请求，且不会因错误诊断打印原始消息或模型异常中的密钥。 */
class AddLoggingTest {
    @TempDir Path directory;

    @Test
    void identifiersStayOnOneLineAndBatchNumberIsHumanReadable() {
        RuntimeContext context = RuntimeContext.builder().userId("张三\n伪造日志")
                .sessionId("session\r\n1").put("request_id", "r\u2028x").put("batch_index", 0).build();
        assertThat(AddLogSupport.trace(context)).contains("user_id=张三_伪造日志", "session_id=session__1", "batch=1")
                .doesNotContain("\n", "\r", "\u2028");
        assertThat(AddLogSupport.value("x".repeat(200))).hasSizeLessThan(130).contains("sha256:");
    }

    @Test
    void failureSummaryNeverIncludesRawExceptionMessage() {
        RuntimeException sdk = new IllegalStateException("fake-secret-key fake-private-dialogue");
        var failure = new AddAgentException(HttpStatus.BAD_GATEWAY, "ADD_AGENT_FAILED", "safe-public-message", sdk);
        assertThat(AddLogSupport.failure(failure)).contains("ADD_AGENT_FAILED", "IllegalStateException")
                .doesNotContain("fake-secret-key", "fake-private-dialogue", "safe-public-message");
        assertThat(AddLogSupport.positions(failure)).doesNotContain("fake-secret-key", "fake-private-dialogue");
    }

    @Test
    void realPipelineEmitsChineseStageLogsWithoutConversationContent() {
        AddAgentProperties properties = new AddAgentProperties();
        properties.setWorkspaceRoot(directory.resolve("success").toString());
        ListAppender<ILoggingEvent> captured = new ListAppender<>();
        captured.start();
        List<Logger> loggers = loggers();
        loggers.forEach(logger -> logger.addAppender(captured));
        try (AddAgentService service = new AddAgentService(properties, new AddAgentServiceTest.SavingModel(),
                new AddWorkspaceStore(properties))) {
            assertThat(service.add(request()).success()).isTrue();
        } finally {
            loggers.forEach(logger -> logger.detachAppender(captured));
            captured.stop();
        }
        String logs = captured.list.stream().map(ILoggingEvent::getFormattedMessage).reduce("", (first, next) -> first + "\n" + next);
        assertThat(logs).contains("[Add分组规划]", "[Add任务开始]", "[Add模型调用开始]", "[Add工具调用]",
                "[Add记忆台账校验通过]", "[Add整单落盘完成]", "request_id=log-r", "user_id=zhangsan", "session_id=session-1")
                .contains("[Add归并Agent启动]", "[Add记忆归并完成]")
                .doesNotContain("fake-private-dialogue", "batch-0 fact", "You are a memory-management assistant");
    }

    @Test
    void failingModelIsDiagnosableWithoutLeakingItsExceptionPayload() {
        AddAgentProperties properties = new AddAgentProperties();
        properties.setWorkspaceRoot(directory.resolve("failure").toString());
        Model failing = new Model() {
            @Override public String getModelName() { return "offline-failure"; }
            @Override public Flux<ChatResponse> stream(List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
                return Flux.error(new IllegalStateException("fake-secret-key fake-private-dialogue"));
            }
        };
        ListAppender<ILoggingEvent> captured = new ListAppender<>();
        captured.start();
        List<Logger> loggers = loggers();
        loggers.forEach(logger -> logger.addAppender(captured));
        try (AddAgentService service = new AddAgentService(properties, failing, new AddWorkspaceStore(properties))) {
            assertThatThrownBy(() -> service.add(request())).isInstanceOf(AddAgentException.class);
        } finally {
            loggers.forEach(logger -> logger.detachAppender(captured));
            captured.stop();
        }
        assertThat(captured.list.stream().map(ILoggingEvent::getFormattedMessage).toList())
                .anyMatch(message -> message.contains("[Add任务失败]") && message.contains("ADD_AGENT_FAILED"));
        assertThat(captured.list).allSatisfy(event -> {
            assertThat(event.getFormattedMessage()).doesNotContain("fake-secret-key", "fake-private-dialogue");
            assertThat(event.getThrowableProxy()).isNull();
        });
    }

    private AddRequest request() {
        return new AddRequest("log-r", "zhangsan", "session-1", List.of(new AddMessage("user", null,
                JsonMapper.builder().build().valueToTree("fake-private-dialogue"))));
    }

    private List<Logger> loggers() {
        return List.of(AddAgentService.class, AddTaskExecutor.class, AddWorkspaceStore.class,
                AddLoggingMiddleware.class, com.pa.sagememory.agent.mergeMemoryAgent.MergeMemoryAgentService.class)
                .stream().map(type -> (Logger) LoggerFactory.getLogger(type)).toList();
    }
}
