package com.pa.sagememory.agent.workspace;

import com.pa.sagememory.agent.AddAgentProperties;
import com.pa.sagememory.dto.AddMessage;
import com.pa.sagememory.dto.AddRequest;
import io.agentscope.core.agent.RuntimeContext;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WorkspaceLayoutTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    @TempDir Path directory;

    @Test
    void readableLayoutUsesNativeSessionIndexAndDoesNotPrecreateOptionalFiles() throws Exception {
        var store = store();
        AddRequest request = request("Part-01", "Zhangsan", "D1");
        try (var workspace = store.open(request)) {
            assertThat(workspace.user()).isEqualTo(directory.resolve("workspace/Zhangsan"));
            assertThat(workspace.state()).isEqualTo(directory.resolve("add-state/Zhangsan/Part-01"));
            assertThat(workspace.state().startsWith(workspace.user())).isFalse();
            for (String optional : List.of("AGENTS.md", "topics", "knowledge", "memory/.consolidation_state")) {
                assertThat(workspace.user().resolve(optional)).doesNotExist();
            }
            RuntimeContext context = context(request);
            store.appendSources(workspace, request, context, store.sourceMessages(workspace, request));
            Path sessions = workspace.user().resolve("agents/add-memory/sessions");
            assertThat(sessions.resolve("D1.jsonl")).exists();
            assertThat(sessions.resolve("D1.log.jsonl")).exists();
            assertThat(JSON.readTree(Files.readString(sessions.resolve("sessions.json"))).path("sessions").has("D1")).isTrue();
        }
    }

    @Test
    void identifiersKeepOriginalNamesAndUnsafeNamesAreRejectedRatherThanEncoded() {
        for (String id : List.of("zhangsan", "D1", "Alice", "alice", "王五", "session-1", ".add-state", "%41", "a".repeat(245))) {
            assertThat(WorkspaceIdentity.pathSegment(id)).isEqualTo(id);
        }
        for (String id : List.of("../../alice:名字", "../session:0", "con", "nul.txt", "CONIN$", "x.", "x ", "..", ".", "a?b", "a*b", "a\\b", "a\nb", "a".repeat(246))) {
            assertThatThrownBy(() -> WorkspaceIdentity.pathSegment(id)).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void competitionIdentifiersMapOnlyTheirPathsAndKeepOriginalSourceFields() throws Exception {
        AddRequest request = request("eval:run_abc123:chunk:0", "eval:run_abc123:multimodal:conv-0", "eval:run_abc123:session:0");
        var store = store();
        try (var workspace = store.open(request)) {
            assertThat(workspace.user()).isEqualTo(directory.resolve("workspace/eval###run_abc123###multimodal###conv-0"));
            assertThat(workspace.state()).isEqualTo(directory.resolve("add-state/eval###run_abc123###multimodal###conv-0/eval###run_abc123###chunk###0"));
            store.checkRequest(workspace, request, 5, 20000);
            store.appendSources(workspace, request, context(request), store.sourceMessages(workspace, request));
            Path transcript = workspace.user().resolve("agents/add-memory/sessions/eval###run_abc123###session###0.jsonl");
            assertThat(transcript).exists();
            assertThat(transcript.resolveSibling("eval###run_abc123###session###0.log.jsonl")).exists();
            assertThat(JSON.readTree(Files.readString(workspace.state().resolve("request.json"))).path("user_id").asString())
                    .isEqualTo(request.userId());
            var entry = JSON.readTree(Files.readAllLines(transcript).get(0));
            var source = JSON.readTree(entry.path("content").asString());
            assertThat(source.path("session_id").asString()).isEqualTo(request.sessionId());
            assertThat(source.path("request_id").asString()).isEqualTo(request.requestId());
        }
    }

    @Test
    void originalHashCharactersCannotCollideWithEscapedColons() {
        assertThat(WorkspaceIdentity.pathSegment("eval:run_abc12")).isEqualTo("eval###run_abc12");
        assertThat(WorkspaceIdentity.pathSegment("eval###run_abc12")).isEqualTo("eval#23#23#23run_abc12");
        assertThat(WorkspaceIdentity.pathSegment("a#23:b")).isEqualTo("a#2323###b");
        var encoded = new java.util.HashSet<String>();
        var originals = new java.util.ArrayList<String>(List.of("a", ":", "#", "2", "3"));
        for (int length = 1; length <= 4; length++) {
            var next = new java.util.ArrayList<String>();
            for (String original : originals) {
                assertThat(encoded.add(WorkspaceIdentity.pathSegment(original))).as("Unique mapping for %s", original).isTrue();
                for (String character : List.of("a", ":", "#", "2", "3")) next.add(original + character);
            }
            originals = next;
        }
    }

    @Test
    void fileNameLengthIsValidatedAfterExpansion() {
        assertThat(WorkspaceIdentity.pathSegment(":".repeat(81))).hasSize(243);
        assertThatThrownBy(() -> WorkspaceIdentity.pathSegment(":".repeat(82))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> WorkspaceIdentity.pathSegment("#".repeat(82))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> WorkspaceIdentity.pathSegment("a:b/c")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void reopeningCurrentLayoutPreservesHandwrittenFilesAndCheckpoints() throws Exception {
        var store = store();
        AddRequest request = request("part-01", "zhangsan", "session-1");
        try (var workspace = store.open(request)) {
            Files.writeString(workspace.user().resolve("AGENTS.md"), "handwritten instructions");
            Files.writeString(workspace.user().resolve("MEMORY.md"), "saved memory");
            store.checkRequest(workspace, request, 5, 20000);
            store.completeBatch(workspace, 0, "fingerprint");
            store.completeRequest(workspace);
        }
        try (var workspace = store.open(request)) {
            assertThat(workspace.user().resolve("AGENTS.md")).hasContent("handwritten instructions");
            assertThat(workspace.user().resolve("MEMORY.md")).hasContent("saved memory");
            assertThat(store.isComplete(workspace)).isTrue();
            assertThat(store.isBatchComplete(workspace, 0, "fingerprint")).isTrue();
        }
    }

    @Test
    void differentCustomWorkspaceRootsHaveIndependentCheckpointRoots() throws Exception {
        AddAgentProperties first = new AddAgentProperties();
        first.setWorkspaceRoot(directory.resolve("one").toString());
        AddAgentProperties second = new AddAgentProperties();
        second.setWorkspaceRoot(directory.resolve("two").toString());
        var firstStore = new AddWorkspaceStore(first);
        var secondStore = new AddWorkspaceStore(second);
        assertThat(firstStore.requestState("zhangsan", "part-01"))
                .isNotEqualTo(secondStore.requestState("zhangsan", "part-01"));
    }

    private AddWorkspaceStore store() {
        AddAgentProperties properties = new AddAgentProperties();
        properties.setWorkspaceRoot(directory.resolve("workspace").toString());
        return new AddWorkspaceStore(properties);
    }

    private AddRequest request(String id, String user, String session) {
        return new AddRequest(id, user, session, List.of(new AddMessage("user", 1740873600000L, JSON.valueToTree("existing source"))));
    }

    private RuntimeContext context(AddRequest request) {
        return RuntimeContext.builder().userId(request.userId()).sessionId(request.sessionId()).build();
    }

}
