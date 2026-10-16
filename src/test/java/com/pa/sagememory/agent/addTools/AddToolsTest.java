package com.pa.sagememory.agent.addTools;

import io.agentscope.core.agent.RuntimeContext;
import com.pa.sagememory.agent.workspace.AddWorkspaceStore;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.harness.agent.filesystem.local.LocalFilesystem;
import io.agentscope.harness.agent.memory.session.SessionEntry;
import io.agentscope.harness.agent.memory.session.SessionTree;
import io.agentscope.harness.agent.workspace.WorkspaceManager;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

class AddToolsTest {

    private static final Set<String> TOOL_NAMES = Set.of(
            "memory_search", "memory_get", "memory_save", "session_search");

    @TempDir
    Path workspace;

    private WorkspaceManager manager;

    @BeforeEach
    void setUp() {
        LocalFilesystem filesystem = new LocalFilesystem(workspace, true, 10);
        // No SQLite workspace index, model, HarnessAgent or memory hooks are needed.
        manager = new WorkspaceManager(workspace, filesystem, null);
    }

    @Test
    void toolkitExposesExactlyTheFourSelectedMemoryTools() {
        Toolkit toolkit = copiedToolkit();

        assertThat(toolkit.getToolNames()).containsExactlyInAnyOrderElementsOf(TOOL_NAMES);
        assertThat(toolkit.getToolSchemas()).hasSize(4);
    }

    @Test
    void memoryToolsStayNativeAndSessionSearchUsesAddParameters() {
        Toolkit copied = copiedToolkit();
        Toolkit nativeTools = new Toolkit();
        List<Object> originals = List.of(
                new io.agentscope.harness.agent.tool.MemorySearchTool(manager),
                new io.agentscope.harness.agent.tool.MemoryGetTool(manager),
                new io.agentscope.harness.agent.tool.MemorySaveTool(manager),
                new io.agentscope.harness.agent.tool.SessionSearchTool(manager));
        originals.forEach(nativeTools::registerTool);

        for (ToolSchema actual : copied.getToolSchemas()) {
            if (actual.getName().equals("session_search")) {
                assertThat(actual.getDescription()).contains("current session", "surrounding messages");
                assertThat(actual.getParameters().toString()).contains("query", "source_ids")
                        .doesNotContain("agentId", "maxResults", "RuntimeContext");
                continue;
            }
            ToolSchema expected = nativeTools.getToolSchemas().stream()
                    .filter(schema -> schema.getName().equals(actual.getName()))
                    .findFirst().orElseThrow();
            if (actual.getName().equals("memory_save")) {
                assertThat(actual.getDescription()).contains("daily ledger only", "does not update MEMORY.md");
                assertThat(actual.getParameters().toString()).contains("source_ids", "original numeric IDs");
            } else {
                assertThat(actual.getDescription()).isEqualTo(expected.getDescription());
                assertThat(actual.getParameters()).isEqualTo(expected.getParameters());
            }
            assertThat(actual.getStrict()).isEqualTo(expected.getStrict());
            assertThat(actual.getOutputSchema()).isEqualTo(expected.getOutputSchema());
        }

        for (Object copiedTool : copiedTools()) {
            if (copiedTool instanceof SessionSearchTool) continue;
            Object original = originals.stream()
                    .filter(tool -> tool.getClass().getSimpleName()
                            .equals(copiedTool.getClass().getSimpleName()))
                    .findFirst().orElseThrow();
            Arrays.stream(copiedTool.getClass().getMethods())
                    .filter(method -> method.isAnnotationPresent(Tool.class))
                    .forEach(method -> {
                        try {
                            Tool actual = method.getAnnotation(Tool.class);
                            Tool expected = original.getClass().getMethod(method.getName(), method.getParameterTypes())
                                    .getAnnotation(Tool.class);
                            if (actual.name().equals("memory_save")) {
                                assertThat(actual.name()).isEqualTo(expected.name());
                            } else {
                                assertThat(actual).isEqualTo(expected);
                            }
                        } catch (NoSuchMethodException e) {
                            throw new AssertionError(e);
                        }
                    });
        }
    }

    @Test
    void memorySaveAppendsOnlyToDailyLedgerAndLeavesCuratedMemoryUnchanged() throws Exception {
        MemorySaveTool save = new MemorySaveTool(manager);
        manager.writeUtf8WorkspaceRelative(null, "MEMORY.md", "# MEMORY.md\n\n## Existing\n- Previously curated fact\n");
        String beforeMemory = Files.readString(workspace.resolve("MEMORY.md"));
        LocalDate before = LocalDate.now();
        RuntimeContext context = RuntimeContext.builder().userId("u").sessionId("session-1")
                .put(SessionSearchTool.SOURCE_UPPER_BOUND, 1L).build();
        SessionTree tree = new SessionTree(manager.resolveSessionContextFile(context,
                AddWorkspaceStore.AGENT_NAME, "session-1"), workspace, null);
        tree.append(new SessionEntry.MessageEntry("1", null, null, "user", AddWorkspaceStore.json(Map.of(
                "source_id", "1", "session_id", "session-1", "role", "user", "source_date", "2025-03-02",
                "content", "User prefers tea and has a dog named Milo.")), null, List.of("text")));
        tree.flush();
        assertThat(save.memorySave(context, "  - User prefers tea (source_ids: 1)\n- Dog is Milo (source_ids: 1)  "))
                .startsWith("Saved 2 memories to memory/").endsWith(".md");
        assertThat(save.memorySave(context, "- Milo started training (source_ids: 1)"))
                .startsWith("Saved 1 memory to memory/").endsWith(".md");

        assertThat(Files.readString(workspace.resolve("MEMORY.md")))
                .isEqualTo(beforeMemory);
        Path daily = workspace.resolve("memory/" + before + ".md");
        if (!Files.exists(daily)) {
            daily = workspace.resolve("memory/" + LocalDate.now() + ".md");
        }
        assertThat(Files.readString(daily)).contains("## Memory Save", "- User prefers tea", "- Dog is Milo", "- Milo started training");
    }

    @Test
    void memorySearchPreservesCaseInsensitiveLiteralMatching() {
        manager.writeUtf8WorkspaceRelative(null, "MEMORY.md", "- Milo wears a red harness\n");
        manager.writeUtf8WorkspaceRelative(null, "memory/2025-03-02.md", "- Milo likes A+B\n");
        MemorySearchTool search = new MemorySearchTool(manager);

        assertThat(search.memorySearch(null, "mIlO")).contains("Found 2 matches", "Source:");
        assertThat(search.memorySearch(null, "A+B")).contains("likes A+B");
        assertThat(search.memorySearch(null, "Milo red"))
                .isEqualTo("No matching memories found for: Milo red");
        assertThat(search.memorySearch(null, " ")).isEqualTo("No query provided");
    }

    @Test
    void memoryGetPreservesInclusiveOneBasedLinesAndTraversalRejection() {
        manager.writeUtf8WorkspaceRelative(null, "MEMORY.md", "first\nsecond\nthird");
        MemoryGetTool get = new MemoryGetTool(manager);

        assertThat(get.memoryGet(null, "MEMORY.md", 2, 3))
                .isEqualTo("2|second" + System.lineSeparator() + "3|third" + System.lineSeparator());
        assertThat(get.memoryGet(null, "../outside.md", 1, 2))
                .isEqualTo("Error: path traversal not allowed");
        assertThat(get.memoryGet(null, "missing.md", 1, 2)).startsWith("Error: file not found:");
    }

    @Test
    void sessionSearchReturnsSameSessionNeighborsAndOriginalImageDescriptions() {
        RuntimeContext context = RuntimeContext.builder().userId("user").sessionId("session-1")
                .put(SessionSearchTool.SOURCE_UPPER_BOUND, 13L).build();
        Path transcript = manager.resolveSessionContextFile(context, AddWorkspaceStore.AGENT_NAME, "session-1");
        SessionTree tree = new SessionTree(transcript, workspace, null);
        List<String> ids = List.of("1", "3", "6", "7", "9", "11", "13", "16");
        List<String> texts = List.of("opening", "Milo wears a red harness",
                "prefix ".repeat(50) + "【附图1描述】：蓝色胸背带", "Yes, that one", "post", "end", "late", "FUTURE");
        for (int i = 0; i < ids.size(); i++) {
            String role = i % 2 == 0 ? "user" : "assistant";
            Map<String, Object> source = new LinkedHashMap<>();
            source.put("source_id", ids.get(i));
            source.put("session_id", "session-1");
            source.put("role", role);
            source.put("source_date", i == 3 ? null : "2025-03-02");
            source.put("content", i == 2 ? List.of(Map.of("type", "text", "text", texts.get(i)),
                    Map.of("type", "image_url", "image_url", Map.of("url", "BASE64_SENTINEL"))) : texts.get(i));
            source.put("original_content", "PRIVATE_BACKUP");
            source.put("images", List.of(Map.of("path", "assets/images/PRIVATE_IMAGE.png")));
            tree.append(new SessionEntry.MessageEntry(ids.get(i), i == 0 ? null : ids.get(i - 1),
                    null, role, AddWorkspaceStore.json(source), null, List.of("text")));
        }
        tree.flush();
        Path other = manager.resolveSessionContextFile(context, AddWorkspaceStore.AGENT_NAME, "other-session");
        SessionTree otherTree = new SessionTree(other, workspace, null);
        otherTree.append(new SessionEntry.MessageEntry(null, "user", "Milo OTHER_SESSION_SECRET"));
        otherTree.flush();
        SessionSearchTool search = new SessionSearchTool(manager);
        String keywordResult = search.sessionSearch(context, "mIlO", null);
        assertThat(keywordResult).contains("source_id: 3 [matched]", "role: assistant", "red harness",
                "source_id: 1 [context]", "source_id: 7 [context]", "source_date: unknown")
                .doesNotContain("OTHER_SESSION_SECRET", "BASE64_SENTINEL", "PRIVATE_BACKUP", "PRIVATE_IMAGE", "source_id: 9");
        String byId = search.sessionSearch(context, null, List.of("7", "9", "7"));
        assertThat(byId).contains("source_id: 3", "source_id: 13", "source_id: 7 [matched]", "source_id: 9 [matched]")
                .doesNotContain("source_id: 1 ", "FUTURE");
        assertThat(byId.split("source_id: 7 ", -1)).hasSize(2);
        assertThat(byId.indexOf("source_id: 3")).isLessThan(byId.indexOf("source_id: 7"));
        assertThat(search.sessionSearch(context, "蓝色胸背带", null)).contains(texts.get(2));
        assertThat(search.sessionSearch(context, null, List.of("16"))).contains("No matching", "Unavailable source_ids: 16");
        assertThat(search.sessionSearch(context, " ", null)).startsWith("Error: provide query or source_ids");
        assertThat(search.sessionSearch(context, null, List.of("../../other"))).startsWith("Error:");
        assertThat(search.sessionSearch(context, null, List.of("1", "3", "6", "7", "9", "11"))).startsWith("Error:");
        assertThat(search.sessionSearch(RuntimeContext.empty(), "Milo", null)).startsWith("Error: current session scope");
        assertThat(search.sessionSearch(RuntimeContext.builder().sessionId("session-1").build(), "Milo", null))
                .startsWith("Error: current batch source scope");
    }

    @Test
    void blankMemorySaveDoesNotCreateFiles() {
        assertThat(new MemorySaveTool(manager).memorySave(null, " "))
                .isEqualTo(MemorySaveTool.VALIDATION_ERROR_PREFIX + "content is required.");
        assertThat(workspace.resolve("MEMORY.md")).doesNotExist();
    }

    private List<Object> copiedTools() {
        return List.of(new MemorySearchTool(manager), new MemoryGetTool(manager),
                new MemorySaveTool(manager), new SessionSearchTool(manager));
    }

    private Toolkit copiedToolkit() {
        Toolkit toolkit = new Toolkit();
        copiedTools().forEach(toolkit::registerTool);
        return toolkit;
    }
}
