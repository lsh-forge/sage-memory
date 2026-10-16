package com.pa.sagememory.agent;

import com.pa.sagememory.agent.middleware.AgentDebugLogProperties;
import com.pa.sagememory.agent.middleware.AgentLogRedactor;
import com.pa.sagememory.agent.multimodal.ImageIngestionService;
import com.pa.sagememory.agent.multimodal.SearchImageQueryService;
import com.pa.sagememory.agent.multimodal.VisionProperties;
import com.pa.sagememory.agent.searchTools.SearchEvidenceAssembler;
import com.pa.sagememory.agent.searchTools.SearchEvidenceStore;
import com.pa.sagememory.agent.workspace.AddWorkspaceStore;
import com.pa.sagememory.dto.SearchRequest;
import io.agentscope.core.message.*;
import io.agentscope.core.model.*;
import io.agentscope.core.model.transport.HttpTransportException;
import io.agentscope.harness.agent.memory.session.SessionEntry;
import io.agentscope.harness.agent.memory.session.SessionTree;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Flux;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 全链路离线 Search 重跑验证：候选快照不变，展示/分页/选择/轮次状态全部重建。 */
class SearchAgentRetryTest {
    static final JsonMapper JSON = JsonMapper.builder().build();
    static final String PNG = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAusB9Wl6AAAAAElFTkSuQmCC";
    @TempDir Path root;
    AddWorkspaceStore workspace;
    SearchAgentProperties properties;
    Path memory;

    @BeforeEach void setUp() throws Exception {
        var add = new AddAgentProperties(); add.setWorkspaceRoot(root.resolve("workspace").toString());
        workspace = new AddWorkspaceStore(add);
        properties = new SearchAgentProperties(); properties.setMaxIters(3);
        Path user = workspace.userRoot("u");
        Files.createDirectories(user.resolve("agents/add-memory/sessions"));
        Files.createDirectories(user.resolve("assets/images"));
        Files.write(user.resolve("assets/images/p1-1.png"), Base64.getDecoder().decode(PNG));
        memory = user.resolve("MEMORY.md");
        Files.writeString(memory, "# MEMORY.md\n\n## Milo\n- Milo wears a blue harness (source_ids: 1, source_date: 2025-03-02)\n");
        var tree = new SessionTree(user.resolve("agents/add-memory/sessions/s.jsonl"), user, null);
        var source = Map.of("source_id", "1", "session_id", "s", "role", "user", "source_date", "2025-03-02",
                "content", "Milo wears a blue harness", "original_content", List.of(Map.of("type", "text", "text", "Milo wears a blue harness")),
                "images", List.of(Map.of("image_index", 1, "image_id", "p1-1", "path", "assets/images/p1-1.png",
                        "media_type", "image/png", "description", "Milo dog wearing a blue harness")));
        tree.append(new SessionEntry.MessageEntry("1", null, Instant.parse("2025-03-02T00:00:00Z"), "user",
                JSON.writeValueAsString(source), null, List.of("text", "image")));
        tree.flush();
    }
    static Flux<ChatResponse> tool(String name, Map<String, Object> input) {
        return Flux.just(ChatResponse.builder().content(List.of(new ToolUseBlock(UUID.randomUUID().toString(), name,
                input, JSON.writeValueAsString(input), Map.of()))).finishReason("stop").build());
    }
    SearchAgentService service(Model model) {
        var debug = new AgentDebugLogProperties(); debug.setEnabled(false);
        return new SearchAgentService(properties, new SearchEvidenceStore(workspace), new SearchEvidenceAssembler(workspace), model, debug, new AgentLogRedactor());
    }
    SearchRequest request() { return new SearchRequest("u", JSON.valueToTree("Find Milo's blue harness"), 100, List.of()); }

    static final class RetryModel implements Model {
        final boolean alwaysFail;
        int starts;
        final List<String> inputs = new ArrayList<>();
        RetryModel(boolean alwaysFail) { this.alwaysFail = alwaysFail; }
        @Override public String getModelName() { return "offline-search-agent-retry"; }
        @Override public Flux<ChatResponse> stream(List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
            long results = messages.stream().flatMap(m -> m.getContentBlocks(ToolResultBlock.class).stream()).count();
            if (results == 0) {
                starts++;
                inputs.add(messages.get(1).getTextContent());
                assertThat(messages).hasSize(2);
                assertThat(messages.get(0).getTextContent()).isEqualTo(SearchAgentPrompts.SYSTEM_PROMPT.strip());
                assertThat(tools).hasSize(5);
                return tool("search_evidence", Map.of("keywords", List.of("Milo")));
            }
            if (results == 1) {
                String text = messages.stream().flatMap(m -> m.getContentBlocks(ToolResultBlock.class).stream())
                        .flatMap(r -> r.getOutput().stream()).filter(TextBlock.class::isInstance)
                        .map(b -> ((TextBlock)b).getText()).reduce("", String::concat);
                assertThat(text).contains("Milo", "blue harness");
                return tool("search_images", Map.of("keywords", List.of("blue harness")));
            }
            String images = messages.stream().flatMap(m -> m.getContentBlocks(ToolResultBlock.class).stream())
                    .filter(r -> "search_images".equals(r.getName())).flatMap(r -> r.getOutput().stream())
                    .filter(TextBlock.class::isInstance).map(b -> ((TextBlock)b).getText()).reduce("", String::concat);
            assertThat(images).contains("blue harness");
            if (alwaysFail || starts == 1) return tool("finish_search", Map.of("ids", List.of("m999")));
            return tool("finish_search", Map.of("ids", List.of("1")));
        }
    }

    @Test void stepLimitRetriesOnceWithFreshContextAndSameSnapshot() throws Exception {
        String original = Files.readString(memory);
        var model = new RetryModel(false);
        try (var service = service(model)) {
            var response = service.search(request());
            assertThat(response.data()).hasSize(1);
            assertThat(response.data().get(0).id()).isEqualTo("1");
            assertThat(response.data().get(0).content()).hasSize(2);
        }
        assertThat(model.starts).isEqualTo(2);
        assertThat(model.inputs.get(0)).isEqualTo(model.inputs.get(1));
        assertThat(Files.readString(memory)).isEqualTo(original);
    }
    @Test void twoFailedAgentsReturnErrorNotFakeEmptyResults() {
        var model = new RetryModel(true);
        try (var service = service(model)) {
            assertThatThrownBy(() -> service.search(request())).isInstanceOfSatisfying(SearchAgentException.class,
                    e -> assertThat(e.getCode()).isEqualTo("SEARCH_FINISH_REQUIRED"));
        }
        assertThat(model.starts).isEqualTo(2);
    }
    @Test void authenticationFailureDoesNotRetryAgentOrModel() {
        AtomicInteger calls = new AtomicInteger();
        Model model = new Model() {
            @Override public String getModelName() { return "offline-unauthorized"; }
            @Override public Flux<ChatResponse> stream(List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
                calls.incrementAndGet(); return Flux.error(new HttpTransportException("unauthorized", 401, ""));
            }
        };
        try (var service = service(model)) {
            assertThatThrownBy(() -> service.search(request())).isInstanceOf(SearchAgentException.class);
        }
        assertThat(calls.get()).isEqualTo(1);
    }
    @Test void queryImageUnderstandingIsNotRepeatedOnAgentRetry() {
        AtomicInteger visionCalls = new AtomicInteger();
        Model caption = new Model() {
            @Override public String getModelName() { return "offline-vision"; }
            @Override public Flux<ChatResponse> stream(List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
                visionCalls.incrementAndGet();
                return Flux.just(ChatResponse.builder().content(List.of(TextBlock.builder().text("A dog wearing a blue harness").build())).finishReason("stop").build());
            }
        };
        var model = new RetryModel(false);
        try (var vision = new ImageIngestionService(new VisionProperties(), caption);
                var service = new SearchAgentService(properties, new SearchEvidenceStore(workspace),
                        new SearchEvidenceAssembler(workspace), model, new SearchImageQueryService(vision))) {
            var query = JSON.valueToTree(List.of(Map.of("type", "text", "text", "Find Milo"),
                    Map.of("type", "image_url", "image_url", Map.of("url", "data:image/png;base64," + PNG))));
            assertThat(service.search(new SearchRequest("u", query, 100, List.of())).data()).hasSize(1);
        }
        assertThat(visionCalls.get()).isEqualTo(1);
        assertThat(model.starts).isEqualTo(2);
        assertThat(model.inputs.get(0)).isEqualTo(model.inputs.get(1));
        assertThat(model.inputs.get(0)).contains("[Query image 1 description]: A dog wearing a blue harness")
                .doesNotContain("【查询附图");
    }
}
