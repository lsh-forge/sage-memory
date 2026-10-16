package com.pa.sagememory.agent;

import com.pa.sagememory.agent.addTools.MemorySaveTool;
import com.pa.sagememory.agent.addTools.SessionSearchTool;
import com.pa.sagememory.agent.mergeMemoryTools.MergeMemoryContext;
import com.pa.sagememory.agent.searchTools.SearchEvidenceAssembler;
import com.pa.sagememory.agent.searchTools.SearchEvidenceStore;
import com.pa.sagememory.agent.workspace.AddWorkspaceStore;
import com.pa.sagememory.agent.workspace.WorkspaceIdentity;
import com.pa.sagememory.dto.AddMessage;
import com.pa.sagememory.dto.AddRequest;
import com.pa.sagememory.dto.SearchRequest;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Flux;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

/** 使用含冒号/原始 # 的业务 ID 跑真实离线治理、回查与 Search，不访问云端模型。 */
class CompetitionIdentifiersTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    @TempDir Path root;

    @Test void addRetryToolsMergeAndSearchUseOneMappingWithoutChangingExternalIds() throws Exception {
        String user = "eval:run_abc123:multimodal:conv-0";
        String session = "eval:run_abc123:session#1";
        String requestId = "eval:run_abc123:chunk:0";
        var properties = new AddAgentProperties(); properties.setWorkspaceRoot(root.resolve("workspace").toString());
        var store = new AddWorkspaceStore(properties);
        var model = new AddAgentServiceTest.SavingModel();
        var request = new AddRequest(requestId, user, session, List.of(
                new AddMessage("user", 1740873600000L, JSON.valueToTree("Milo wears a blue harness.")),
                new AddMessage("assistant", 1740873600000L, JSON.valueToTree("A matching reply."))));
        try (var add = new AddAgentService(properties, model, store)) {
            var response = add.add(request);
            assertThat(response.success()).isTrue();
            assertThat(response.userId()).isEqualTo(user);
            assertThat(response.requestId()).isEqualTo(requestId);
            assertThat(response.sessionId()).isEqualTo(session);
            assertThat(model.batches).hasSize(1);
            assertThat(model.consolidationInputs).hasSize(1);
            Path transcript = store.userRoot(user).resolve("agents/add-memory/sessions/" + WorkspaceIdentity.pathSegment(session) + ".jsonl");
            assertThat(transcript).exists();
            var entry = JSON.readTree(Files.readAllLines(transcript).get(0));
            var source = JSON.readTree(entry.path("content").asString());
            assertThat(source.path("session_id").asString()).isEqualTo(session);
            assertThat(source.path("request_id").asString()).isEqualTo(requestId);
            assertThat(add.add(request)).isEqualTo(response);
            assertThat(model.batches).hasSize(1);
            try (var workspace = store.open(request)) {
                var context = RuntimeContext.builder().userId(user).sessionId(session)
                        .put(SessionSearchTool.SOURCE_UPPER_BOUND, 2L).build();
                String found = new SessionSearchTool(workspace.manager()).sessionSearch(context, "blue harness", null);
                assertThat(found).contains("Milo wears a blue harness.", "source_id: 1", "source_id: 2").doesNotContain("Error:");
                String normalized = new MemorySaveTool(workspace.manager()).preparedContent(context, "Milo wears blue. (source_ids: 1)");
                assertThat(normalized).contains("source_ids: 1", "source_date: 2025-03-02");
            }
        }
        var merge = new MergeMemoryContext(store, user);
        ToolResultBlock verified = new com.pa.sagememory.agent.mergeMemoryTools.GetEvidenceTool(merge).getEvidence(List.of("1"));
        assertThat(verified.getState()).isNotEqualTo(ToolResultState.ERROR);
        String verifiedText = verified.getOutput().stream().filter(TextBlock.class::isInstance)
                .map(block -> ((TextBlock) block).getText()).reduce("", String::concat);
        assertThat(verifiedText).contains("session_id: " + session, "Memory ID: 1", "Memory ID: 2");
        AtomicInteger searchCalls = new AtomicInteger();
        Model searchModel = new Model() {
            @Override public String getModelName() { return "offline-competition-search"; }
            @Override public Flux<ChatResponse> stream(List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
                int call = searchCalls.incrementAndGet();
                String name = call == 1 ? "get_evidence" : "finish_search";
                Map<String, Object> input = call == 1 ? Map.of("source_ids", List.of("1")) : Map.of("ids", List.of("1"));
                return Flux.just(ChatResponse.builder().content(List.of(new ToolUseBlock(UUID.randomUUID().toString(), name,
                        input, JSON.writeValueAsString(input), Map.of()))).finishReason("stop").build());
            }
        };
        try (var search = new SearchAgentService(new SearchAgentProperties(), new SearchEvidenceStore(store),
                new SearchEvidenceAssembler(store), searchModel)) {
            var response = search.search(new SearchRequest(user, JSON.valueToTree("Find Milo's harness."), 100, List.of()));
            assertThat(response.data()).hasSize(1);
            assertThat(response.data().get(0).id()).isEqualTo("1");
            assertThat(response.data().get(0).content().get(0).path("text").asString()).isEqualTo("Milo wears a blue harness.");
        }
        assertThat(searchCalls.get()).isEqualTo(2);
        assertThat(store.userRoot("eval###run_abc123###multimodal###conv-0")).isNotEqualTo(store.userRoot(user));
        assertThat(store.userRoot("eval###run_abc123###multimodal###conv-0")).doesNotExist();
    }
}
