package com.pa.sagememory.agent;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.pa.sagememory.agent.middleware.AgentDebugLogProperties;
import com.pa.sagememory.agent.middleware.AgentLogRedactor;
import com.pa.sagememory.agent.multimodal.ImageIngestionService;
import com.pa.sagememory.agent.multimodal.VisionProperties;
import com.pa.sagememory.agent.workspace.AddWorkspaceStore;
import com.pa.sagememory.dto.AddMessage;
import com.pa.sagememory.dto.AddRequest;
import io.agentscope.core.message.Base64Source;
import io.agentscope.core.message.DataBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Offline output validation and safe metadata logs; never invokes a provider. */
class VisionOutputDiagnosticsTest {
    private static final String PNG = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAusB9Wl6AAAAAElFTkSuQmCC";
    private static final String PRIVATE_TEXT = "private-image-description-must-not-be-logged";
    @TempDir Path directory;

    static Stream<Arguments> invalidOutputs() {
        return Stream.of(
                Arguments.of(Flux.just(response(PRIVATE_TEXT, "length")), "unexpected_finish_reason", "finish_reason=length", 1),
                Arguments.of(Flux.just(response("   ", "stop")), "empty_description", "finish_reason=stop", 1),
                Arguments.of(Flux.<ChatResponse>empty(), "no_model_response", "描述字符数=0", 0),
                Arguments.of(Flux.just(response("```" + PRIVATE_TEXT + "```", "stop")), "code_block_in_description", "含代码块=true", 1),
                Arguments.of(Flux.just(ChatResponse.builder().content(List.of(
                        TextBlock.builder().text(PRIVATE_TEXT).build(),
                        DataBlock.builder().source(Base64Source.builder().mediaType("image/png").data(PNG).build()).build()))
                        .finishReason("stop").build()), "non_text_content_block", "含非文本块=true", 1));
    }

    @ParameterizedTest @MethodSource("invalidOutputs")
    void addFailureLogsSpecificReasonsWithoutLoggingTheImageOrDescription(Flux<ChatResponse> output,
            String reason, String detail, int responseCount) throws Exception {
        Logger logger = (Logger) LoggerFactory.getLogger(ImageIngestionService.class);
        var captured = new ListAppender<ILoggingEvent>();
        captured.start(); logger.addAppender(captured);
        AddAgentProperties addProperties = new AddAgentProperties();
        addProperties.setWorkspaceRoot(directory.resolve("workspace").toString());
        var store = new AddWorkspaceStore(addProperties);
        var request = new AddRequest("diagnostic-request", "diagnostic-user", "D1", List.of(new AddMessage("user", null,
                JsonMapper.builder().build().valueToTree(List.of(Map.of("type", "text", "text", "look"),
                        Map.of("type", "image_url", "image_url", Map.of("url", "data:image/png;base64," + PNG)))))));
        try (var workspace = store.open(request);
             var service = new ImageIngestionService(new VisionProperties(), model(output))) {
            assertThatThrownBy(() -> service.enrich(request,
                    List.of(new LinkedHashMap<String, Object>(Map.of("source_id", "1"))), store, workspace, new AddDeadline(60)))
                    .isInstanceOfSatisfying(AddAgentException.class,
                            error -> assertThat(error.getCode()).isEqualTo("ADD_VISION_INVALID_OUTPUT"));
        } finally { logger.detachAppender(captured); captured.stop(); }
        var diagnostics = captured.list.stream().map(ILoggingEvent::getFormattedMessage)
                .filter(message -> message.contains("[Add视觉输出校验失败]")).toList();
        assertThat(diagnostics).singleElement().asString().contains(reason, detail, "max_output_tokens=10000",
                "响应片段数=" + responseCount, "request_id=diagnostic-request", "message_index=0", "content_index=1");
        assertThat(captured.list).allSatisfy(event -> {
            assertThat(event.getFormattedMessage()).doesNotContain(PRIVATE_TEXT, PNG, "data:image/png;base64,");
            assertThat(event.getThrowableProxy()).isNull();
        });
    }

    @Test
    void searchAlsoLogsFailureMetadataWhenDetailedDebugLoggingIsDisabled() {
        Logger logger = (Logger) LoggerFactory.getLogger(ImageIngestionService.class);
        var captured = new ListAppender<ILoggingEvent>(); captured.start(); logger.addAppender(captured);
        try (var service = new ImageIngestionService(new VisionProperties(), model(Flux.just(response(PRIVATE_TEXT, "length"))),
                new AgentDebugLogProperties(), new AgentLogRedactor())) {
            assertThatThrownBy(() -> service.describeQueryImage("look", "image/png", Base64.getDecoder().decode(PNG),
                    System.nanoTime() + Duration.ofSeconds(60).toNanos(), "user_id=diagnostic-user").block())
                    .isInstanceOf(AddAgentException.class);
        } finally { logger.detachAppender(captured); captured.stop(); }
        assertThat(captured.list.stream().map(ILoggingEvent::getFormattedMessage).toList())
                .anySatisfy(message -> assertThat(message).contains("[Search视觉输出校验失败]", "unexpected_finish_reason",
                        "finish_reason=length", "max_output_tokens=10000", "vision_call_id=1"));
        assertThat(captured.list).allSatisfy(event -> assertThat(event.getFormattedMessage()).doesNotContain(PRIVATE_TEXT, PNG));
    }

    @Test
    void completeResponsesRemainAcceptedAndOptionsUseTheIncreasedLimit() {
        try (var service = new ImageIngestionService(new VisionProperties(), model(Flux.just(response("a blue bowl", "stop"))))) {
            assertThat(service.describeQueryImage("look", "image/png", Base64.getDecoder().decode(PNG),
                    System.nanoTime() + Duration.ofSeconds(60).toNanos(), "test").block()).isEqualTo("a blue bowl");
        }
    }

    private static Model model(Flux<ChatResponse> output) {
        return new Model() {
            public String getModelName() { return "offline-vision"; }
            public Flux<ChatResponse> stream(List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
                assertThat(options.getMaxTokens()).isEqualTo(10000);
                return output;
            }
        };
    }

    private static ChatResponse response(String text, String reason) {
        return ChatResponse.builder().content(List.of(TextBlock.builder().text(text).build())).finishReason(reason).build();
    }
}
