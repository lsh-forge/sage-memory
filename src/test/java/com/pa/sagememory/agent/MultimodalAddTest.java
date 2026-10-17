package com.pa.sagememory.agent;

import com.pa.sagememory.agent.addTools.SessionSearchTool;
import com.pa.sagememory.agent.multimodal.ImageIngestionService;
import com.pa.sagememory.agent.multimodal.VisionProperties;
import com.pa.sagememory.agent.workspace.AddWorkspaceStore;
import com.pa.sagememory.dto.AddMessage;
import com.pa.sagememory.dto.AddRequest;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Base64Source;
import io.agentscope.core.message.DataBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.extensions.model.openai.formatter.OpenAIChatFormatter;
import io.agentscope.harness.agent.memory.session.SessionTree;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 仅验证多模态关键闭环：真实工具/文件、并发回填、失败重试；不请求真实模型。 */
class MultimodalAddTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String PNG = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAusB9Wl6AAAAAElFTkSuQmCC";
    @TempDir Path directory;

    @Test
    void tenVisionThreadsFillTheCorrectPositionsAndTheFullAddFlowPersistsEvidence() throws Exception {
        AddAgentProperties properties = new AddAgentProperties();
        properties.setWorkspaceRoot(directory.resolve("workspace").toString());
        properties.setMaxBatchChars(4_000);
        AddWorkspaceStore store = new AddWorkspaceStore(properties);
        var textModel = new AddAgentServiceTest.SavingModel();
        AtomicInteger active = new AtomicInteger();
        AtomicInteger peak = new AtomicInteger();
        AtomicInteger calls = new AtomicInteger();
        CountDownLatch firstTen = new CountDownLatch(10);
        List<String> completed = java.util.Collections.synchronizedList(new ArrayList<>());
        Model vision = new Model() {
            public String getModelName() { return "qwen3.7-plus"; }
            public Flux<ChatResponse> stream(List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
                assertThat(tools).isEmpty();
                assertThat(messages).hasSize(2);
                assertThat(options.getExecutionConfig().getMaxAttempts()).isEqualTo(1);
                assertThat(options.getExecutionConfig().getTimeout()).isLessThanOrEqualTo(Duration.ofSeconds(180));
                assertThat(options.getAdditionalBodyParams()).containsEntry("enable_thinking", false);
                var formatter = new OpenAIChatFormatter();
                assertThat(formatter.buildRequest(getModelName(), formatter.format(messages), false,
                        options, GenerateOptions.builder().build(), List.of(), null).getExtraParams())
                        .containsEntry("enable_thinking", false);
                DataBlock image = messages.get(1).getFirstContentBlock(DataBlock.class);
                assertThat(image.getSource()).isInstanceOf(Base64Source.class);
                // 同时验证依赖的 formatter 真正生成 image_url，而非把 DataBlock 当普通文本。
                assertThat(JSON.writeValueAsString(new OpenAIChatFormatter().format(messages)))
                        .contains("image_url", "data:image/png;base64," + PNG);
                String context = messages.get(1).getTextContent();
                String marker = context.substring(context.indexOf("marker-"), context.indexOf("marker-") + 9);
                String ordinal = context.contains("attached image 2") ? "2" : "1";
                return Mono.fromCallable(() -> {
                    calls.incrementAndGet();
                    int count = active.incrementAndGet();
                    peak.accumulateAndGet(count, Math::max);
                    firstTen.countDown();
                    try {
                        assertThat(firstTen.await(5, TimeUnit.SECONDS)).as("不得串行识图").isTrue();
                        // 首张图故意慢于后续图，以验证不是按完成顺序回填。
                        TimeUnit.MILLISECONDS.sleep(marker.equals("marker-00") && ordinal.equals("1") ? 150 : 5);
                        String caption = "description-" + marker + "-" + ordinal + " 蓝色胸背带";
                        completed.add(caption);
                        return response(caption);
                    } finally { active.decrementAndGet(); }
                }).flux();
            }
        };
        List<AddMessage> messages = new ArrayList<>();
        for (int i = 0; i < 12; i++) messages.add(message("marker-%02d".formatted(i), i == 0 ? 2 : 1,
                i == 11 ? "assistant" : "user"));
        AddRequest request = new AddRequest("images", "zhangsan", "session-1", messages);
        var visionProperties = new VisionProperties();
        visionProperties.setConcurrency(10);
        try (var images = new ImageIngestionService(visionProperties, vision);
             var service = new AddAgentService(properties, textModel, store, images)) {
            assertThat(service.add(request).success()).isTrue();
            assertThat(peak.get()).isEqualTo(10);
            assertThat(calls.get()).isEqualTo(13);
            assertThat(completed.get(0)).doesNotContain("marker-00-1");
            var sources = textModel.batches.stream().flatMap(batch -> batch.path("new_messages").valueStream()).toList();
            assertThat(sources).hasSize(12);
            for (int i = 0; i < sources.size(); i++) {
                String content = sources.get(i).path("content").asString();
                assertThat(content).contains((i == 11 ? "Assistant message: " : "User message: ") + "marker-%02d".formatted(i),
                        "[Attached image 1 description]: description-marker-%02d-1".formatted(i));
                assertThat(sources.get(i).has("images")).isFalse();
                assertThat(sources.get(i).has("original_content")).isFalse();
            }
            assertThat(sources.get(0).path("content").asString()).containsSubsequence(
                    "User message: marker-00", "[Attached image 1 description]: description-marker-00-1", "User message: between",
                    "[Attached image 2 description]: description-marker-00-2");
            assertThat(AddWorkspaceStore.json(textModel.batches)).doesNotContain(PNG, "image_id", "assets/images");
            Path transcript = store.userRoot("zhangsan").resolve("agents/add-memory/sessions/session-1.jsonl");
            SessionTree tree = new SessionTree(transcript, store.userRoot("zhangsan"), null);
            tree.load();
            var first = JSON.readTree(tree.getMessageEntries().get(0).getContent());
            assertThat(first.path("images")).hasSize(2);
            assertThat(first.path("images").get(0).path("image_id").asString()).isEqualTo("p1-1");
            assertThat(first.path("images").get(0).path("path").asString()).isEqualTo("assets/images/p1-1.png");
            assertThat(first.path("images").get(1).path("path").asString()).isEqualTo("assets/images/p1-2.png");
            assertThat(first.path("images").get(1).path("content_index").asInt()).isEqualTo(3);
            assertThat(first.path("images").get(0).path("generated_by").asString()).isEqualTo("vision_model");
            assertThat(first.path("original_content").get(0).path("text").asString()).isEqualTo("marker-00");
            assertThat(first.path("source_date").asString()).isEqualTo("2025-03-02");
            assertThat(first.path("content")).isEqualTo(sources.get(0).path("content"));
            assertThat(Files.readAllBytes(store.userRoot("zhangsan").resolve(
                    first.path("images").get(0).path("path").asString()))).isEqualTo(Base64.getDecoder().decode(PNG));
            assertThat(Files.readString(transcript)).doesNotContain(PNG);
            try (var files = Files.list(store.userRoot("zhangsan").resolve("assets/images"))) {
                assertThat(files.filter(Files::isRegularFile).count()).isEqualTo(13);
            }
            try (var workspace = store.open(request)) {
                assertThat(new SessionSearchTool(workspace.manager()).sessionSearch(
                        RuntimeContext.builder().userId("zhangsan").sessionId("session-1")
                                .put(SessionSearchTool.SOURCE_UPPER_BOUND, 24L).build(),
                        "蓝色胸背带", null)).contains("蓝色胸背带", "[Attached image ");
            }
            // 已完成重试不重新识图、不重复生成台账和长期记忆。
            assertThat(service.add(request).success()).isTrue();
            assertThat(calls.get()).isEqualTo(13);
            assertThat(textModel.consolidationInputs).hasSize(1);
        }
    }

    @Test
    void failedImageDoesNotStartGovernanceAndRetryReusesSuccessfulCaption() throws Exception {
        AddAgentProperties properties = new AddAgentProperties();
        properties.setWorkspaceRoot(directory.resolve("workspace").toString());
        var store = new AddWorkspaceStore(properties);
        var textModel = new AddAgentServiceTest.SavingModel();
        AtomicInteger firstCalls = new AtomicInteger();
        AtomicInteger secondCalls = new AtomicInteger();
        CountDownLatch firstPersisted = new CountDownLatch(1);
        Model vision = new Model() {
            public String getModelName() { return "offline-vision"; }
            public Flux<ChatResponse> stream(List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
                assertThat(options.getAdditionalBodyParams()).doesNotContainKey("enable_thinking");
                if (messages.get(1).getTextContent().contains("marker-00")) {
                    firstCalls.incrementAndGet();
                    return Flux.just(response("a blue harness"));
                }
                if (secondCalls.incrementAndGet() == 1) {
                    return Mono.fromCallable(() -> {
                        // 等待第一张描述真正写入检查点，然后模拟另一张图失败。
                        Path caption = store.requestState("u", "retry").resolve("image-captions/m00000-p00001.json");
                        for (int n = 0; n < 500 && !Files.exists(caption); n++) TimeUnit.MILLISECONDS.sleep(10);
                        assertThat(caption).exists();
                        firstPersisted.countDown();
                        throw new IllegalStateException("offline vision failure");
                    }).cast(ChatResponse.class).flux();
                }
                return Flux.just(response("a red ball"));
            }
        };
        var request = new AddRequest("retry", "u", "s", List.of(message("marker-00", 1, "user"), message("marker-01", 1, "user")));
        try (var images = new ImageIngestionService(new VisionProperties(), vision);
             var service = new AddAgentService(properties, textModel, store, images)) {
            assertThatThrownBy(() -> service.add(request)).isInstanceOfSatisfying(AddAgentException.class,
                    error -> assertThat(error.getCode()).isEqualTo("ADD_VISION_FAILED"));
            assertThat(firstPersisted.getCount()).isZero();
            assertThat(textModel.batches).isEmpty();
            assertThat(store.requestState("u", "retry").resolve("complete")).doesNotExist();
            assertThat(store.userRoot("u").resolve("agents/add-memory/sessions/s.jsonl")).doesNotExist();
            Path plan = store.requestState("u", "retry").resolve("source-ids.json");
            String reserved = Files.readString(plan);
            assertThat(JSON.readTree(reserved).path("source_ids").get(0).asString()).isEqualTo("1");
            // 原请求虽然识图失败，但 1、2 已预留；另一请求必须使用 3，不能占用图片编号。
            service.add(new AddRequest("interleaved", "u", "other-session", List.of(
                    new AddMessage("user", 1740873600000L, JSON.valueToTree("another text request")))));
            assertThat(textModel.batches.get(0).path("new_messages").get(0).path("source_id").asString()).isEqualTo("3");
            assertThat(service.add(request).success()).isTrue();
            assertThat(Files.readString(plan)).isEqualTo(reserved);
            assertThat(firstCalls.get()).isEqualTo(1);
            assertThat(secondCalls.get()).isEqualTo(2);
            var retried = textModel.batches.get(textModel.batches.size() - 1).path("new_messages");
            assertThat(retried.get(0).path("source_id").asString()).isEqualTo("1");
            assertThat(retried.get(1).path("source_id").asString()).isEqualTo("2");
            assertThat(retried.get(0).path("content").asString()).contains("a blue harness");
            assertThat(retried.get(1).path("content").asString()).contains("a red ball");
            assertThat(store.userRoot("u").resolve("assets/images/p1-1.png")).exists();
            assertThat(store.userRoot("u").resolve("assets/images/p2-1.png")).exists();
        }
    }

    private static AddMessage message(String text, int images, String role) {
        List<Object> parts = new ArrayList<>();
        parts.add(Map.of("type", "text", "text", text));
        parts.add(Map.of("type", "image_url", "image_url", Map.of("url", "data:image/png;base64," + PNG)));
        if (images == 2) {
            parts.add(Map.of("type", "text", "text", "between"));
            parts.add(Map.of("type", "image_url", "image_url", Map.of("url", "data:image/png;base64," + PNG)));
        }
        return new AddMessage(role, 1740873600000L, JSON.valueToTree(parts));
    }

    private static ChatResponse response(String text) {
        return ChatResponse.builder().content(List.of(TextBlock.builder().text(text).build())).finishReason("stop").build();
    }
}
