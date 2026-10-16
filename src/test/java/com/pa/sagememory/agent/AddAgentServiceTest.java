package com.pa.sagememory.agent;

import com.pa.sagememory.agent.workspace.AddWorkspaceStore;
import com.pa.sagememory.dto.AddMessage;
import com.pa.sagememory.dto.AddRequest;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Flux;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AddAgentServiceTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    @TempDir Path directory;
    private AddAgentProperties properties;
    private AddWorkspaceStore store;
    private SavingModel model;
    private AddAgentService service;
    private final List<AddAgentService> services = new ArrayList<>();

    @BeforeEach
    void setUp() {
        properties = new AddAgentProperties();
        properties.setWorkspaceRoot(directory.resolve("workspace").toString());
        properties.setTimeoutSeconds(10);
        store = new AddWorkspaceStore(properties);
        model = new SavingModel();
        service = createService(model);
    }

    private AddAgentService createService(Model supplied) {
        AddAgentService created = new AddAgentService(properties, supplied, store);
        services.add(created);
        return created;
    }

    @AfterEach void closeServices() { services.forEach(AddAgentService::close); }

    @Test
    void processesGroupsSequentiallyWithOnlyRecentSameSessionSaveSummaries() throws Exception {
        AddRequest request = request("request-1", "user-1", "session-1", 12);
        assertThat(service.add(request).success()).isTrue();

        assertThat(model.batches).hasSize(3);
        assertThat(model.consolidationInputs).hasSize(1);
        assertThat(model.consolidationInputs.get(0)).contains("<pending_memory_saves>", "batch-0 fact", "batch-1 fact", "batch-2 fact");
        assertThat(model.consolidationInputs.get(0)).startsWith("<current_memory_snapshot>\n\n</current_memory_snapshot>");
        assertThat(model.batches.stream().map(batch -> batch.path("new_messages").size()))
                .containsExactly(5, 5, 2);
        assertThat(model.initialPrompts.get(0)).contains("memory_save", "global deduplication", "NO_REPLY")
                .doesNotContain("## Memory Recall", "## Memory Persistence", "AgentStateStore Context",
                        "Domain Knowledge", "workspace directory", "operating system", "memory_context");
        assertThat(model.initialPrompts).allMatch(prompt -> prompt.equals(AddAgentPrompts.SYSTEM_PROMPT.strip()));
        assertThat(model.batches.get(0).path("read_only_recent_saves")).isEmpty();
        assertThat(model.batches.get(1).path("read_only_recent_saves")).hasSize(1);
        assertThat(model.batches.get(1).path("read_only_recent_saves").get(0).asString()).contains("batch-0 fact");
        assertThat(model.batches.get(2).path("read_only_recent_saves")).hasSize(2);
        assertThat(model.batches.get(2).path("read_only_recent_saves").get(0).asString()).contains("batch-0 fact");
        assertThat(model.batches.get(2).path("read_only_recent_saves").get(1).asString()).contains("batch-1 fact");
        assertThat(model.batches).allMatch(batch -> !batch.has("read_only_previous_messages"));
        assertThat(model.batches.get(0).path("new_messages").get(0).path("timestamp").asLong())
                .isEqualTo(1740873600000L);
        assertThat(model.batches.get(0).path("new_messages").get(0).path("source_date").asString())
                .isEqualTo("2025-03-02");
        assertThat(model.initialPrompts.get(0)).contains("original message date in UTC", "never to the processing date");
        assertThat(model.toolNames).containsExactlyInAnyOrder("memory_save", "session_search");
        String memory = Files.readString(store.userRoot("user-1").resolve("MEMORY.md"));
        assertThat(memory).contains("batch-0 fact", "batch-1 fact", "batch-2 fact", "Sources: 1");
        assertThat(memory).startsWith("# MEMORY.md\n").contains("## Offline facts");
        assertThat(store.userRoot("user-1").resolve("memory/.consolidation_state")).exists();
        assertThat(model.batches.stream().flatMap(batch -> batch.path("new_messages").valueStream())
                .map(message -> message.path("source_id").asString()))
                .containsExactly("1", "2", "3", "4", "5", "6", "7", "8", "9", "10", "11", "12");
        assertThat(Files.readString(store.userRoot("user-1").resolve("memory/" + LocalDate.now() + ".md")))
                .contains("batch-2 fact");
    }

    @Test
    void preservesSourceRolesTimestampsAndTextArraysInNativeSessionHistory() throws Exception {
        List<AddMessage> messages = List.of(
                new AddMessage("user", 1740873600000L, JSON.readTree("\"Which harness?\"")),
                new AddMessage("assistant", 1740873600000L, JSON.readTree("\"The red harness.\"")),
                new AddMessage("user", null, JSON.readTree("[{\"type\":\"text\",\"text\":\"I chose red.\"}]")));
        service.add(new AddRequest("roles", "user", "session", messages));

        Path transcript = store.userRoot("user").resolve("agents/add-memory/sessions/session.jsonl");
        String persisted = Files.readString(transcript);
        assertThat(persisted).contains("assistant", "Which harness?", "The red harness.",
                "1740873600000", "\\\"timestamp\\\":null", "I chose red.",
                "\\\"source_date\\\":\\\"2025-03-02\\\"");
        assertThat(model.batches.get(0).path("new_messages").get(2).path("timestamp").isNull()).isTrue();
        assertThat(model.batches.get(0).path("new_messages").get(2).path("source_date").isNull()).isTrue();
    }

    @Test
    void sameUserSharesMemoryAcrossSessionsButDifferentUsersCannotSeeIt() throws Exception {
        service.add(request("r1", "alice", "s1", 1));
        service.add(request("r2", "alice", "s2", 1));
        service.add(request("r1", "bob", "s1", 1));

        assertThat(model.batches.get(1).path("read_only_recent_saves")).isEmpty();
        assertThat(model.consolidationInputs.get(1)).contains("batch-0 fact");
        assertThat(model.consolidationInputs.get(1)).startsWith("<current_memory_snapshot>\n# MEMORY.md");
        assertThat(model.consolidationInputs.get(2)).startsWith("<current_memory_snapshot>\n\n</current_memory_snapshot>");
        assertThat(model.initialPrompts.get(2)).doesNotContain("- batch-0 fact");
        assertThat(model.batches.stream().map(batch -> batch.path("new_messages").get(0).path("source_id").asString()))
                .containsExactly("1", "2", "1");
        assertThat(store.userRoot("alice")).isNotEqualTo(store.userRoot("bob"));
        try (var sessions = Files.list(store.userRoot("alice").resolve("agents/add-memory/sessions"))) {
            assertThat(sessions.filter(path -> path.toString().endsWith(".log.jsonl")).count()).isEqualTo(2);
        }
    }

    @Test
    void completedRequestIsIdempotentAndConflictingReuseIsRejected() throws Exception {
        AddRequest request = request("r", "u", "s", 6);
        service.add(request);
        String memory = Files.readString(store.userRoot("u").resolve("MEMORY.md"));
        int invocations = model.batches.size();

        assertThat(service.add(request).success()).isTrue();
        assertThat(model.batches).hasSize(invocations);
        assertThat(model.consolidationInputs).hasSize(1);
        assertThat(Files.readString(store.userRoot("u").resolve("MEMORY.md"))).isEqualTo(memory);
        assertThatThrownBy(() -> service.add(request("r", "u", "different-session", 6)))
                .isInstanceOf(AddAgentException.class).hasMessageContaining("reused");
    }

    @Test
    void retriesSkipCompletedGroupsAndDoNotDuplicateSourceTranscripts() throws Exception {
        model.failBatchOnce = 1;
        model.batchFailures = 2;
        AddRequest request = request("resume", "u", "s", 12);
        assertThatThrownBy(() -> service.add(request)).isInstanceOf(AddAgentException.class);
        // 原请求在识图前已预留 1～12；另一请求使用 13，重试尾部仍保留 11、12。
        assertThat(createService(plainModel("NO_REPLY")).add(request("other", "u", "other-session", 1)).success()).isTrue();
        assertThat(service.add(request).success()).isTrue();

        assertThat(model.batches.stream().map(batch -> batch.path("batch_index").asInt()))
                .containsExactly(0, 1, 1, 1, 2);
        assertThat(model.batches.get(1).path("new_messages"))
                .isEqualTo(model.batches.get(2).path("new_messages"));
        assertThat(model.batches.get(4).path("new_messages").get(0).path("source_id").asString()).isEqualTo("11");
        assertThat(model.batches.get(4).path("new_messages").get(1).path("source_id").asString()).isEqualTo("12");
        String memory = Files.readString(store.userRoot("u").resolve("MEMORY.md"));
        assertThat(memory.split("batch-0 fact", -1)).hasSize(2);
        Path transcript = store.userRoot("u").resolve("agents/add-memory/sessions/s.jsonl");
        try (var lines = Files.lines(transcript)) {
            assertThat(lines.count()).isEqualTo(12);
        }
    }

    @Test
    void failedAgentAttemptRollsBackOnlyItsOwnSavesAndRetriesSameGroup() throws Exception {
        properties.setBatchSize(1);
        properties.setMaxIters(3);
        AtomicInteger starts = new AtomicInteger();
        List<String> retryInputs = new ArrayList<>();
        Model partial = new Model() {
            @Override public String getModelName() { return "offline-partial-add-agent"; }
            @Override public Flux<ChatResponse> stream(List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
                if (isMerge(tools)) return model.stream(messages, tools, options);
                JsonNode batch = JSON.readTree(messages.get(1).getTextContent());
                boolean acted = messages.stream().anyMatch(m -> m.hasContentBlocks(ToolResultBlock.class));
                if (batch.path("batch_index").asInt() == 1) {
                    if (!acted) {
                        starts.incrementAndGet();
                        retryInputs.add(messages.get(1).getTextContent());
                    }
                    if (acted && starts.get() == 1) return answer(save("partial-" + messages.size(),
                            "- failed-only fact (source_ids: 2)"));
                }
                return model.stream(messages, tools, options);
            }
        };
        AddRequest request = request("rollback-current-group", "u", "s", 3);
        assertThat(createService(partial).add(request).success()).isTrue();
        assertThat(starts.get()).isEqualTo(2);
        assertThat(retryInputs).hasSize(2);
        assertThat(retryInputs.get(0)).isEqualTo(retryInputs.get(1)).doesNotContain("failed-only");
        assertThat(model.batches.stream().map(b -> b.path("batch_index").asInt())).containsExactly(0, 1, 1, 2);
        String ledger = Files.readString(store.userRoot("u").resolve("memory/" + LocalDate.now() + ".md"));
        assertThat(ledger).contains("batch-0 fact", "batch-1 fact", "batch-2 fact").doesNotContain("failed-only");
        assertThat(ledger.split("## Memory Save", -1)).hasSize(4);
        assertThat(Files.readAllLines(store.userRoot("u").resolve("agents/add-memory/sessions/s.jsonl"))).hasSize(3);
        assertThat(store.requestState("u", request.requestId()).resolve("complete")).exists();
    }

    @Test
    void twoFailedAgentAttemptsKeepCompletedGroupButDoNotStartLaterGroups() throws Exception {
        properties.setBatchSize(1);
        properties.setMaxIters(2);
        AtomicInteger starts = new AtomicInteger();
        Model partial = new Model() {
            @Override public String getModelName() { return "offline-two-partial-attempts"; }
            @Override public Flux<ChatResponse> stream(List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
                if (isMerge(tools)) throw new AssertionError("Merge must not start while a group failed.");
                JsonNode batch = JSON.readTree(messages.get(1).getTextContent());
                if (batch.path("batch_index").asInt() == 1) {
                    if (messages.stream().noneMatch(m -> m.hasContentBlocks(ToolResultBlock.class))) starts.incrementAndGet();
                    return answer(save("partial-" + starts.get() + "-" + messages.size(), "- failed-only fact (source_ids: 2)"));
                }
                return model.stream(messages, tools, options);
            }
        };
        AddRequest request = request("twice-incomplete", "u", "s", 3);
        assertThatThrownBy(() -> createService(partial).add(request)).isInstanceOfSatisfying(AddAgentException.class,
                error -> assertThat(error.getCode()).isEqualTo("ADD_BATCH_INCOMPLETE"));
        assertThat(starts.get()).isEqualTo(2);
        String ledger = Files.readString(store.userRoot("u").resolve("memory/" + LocalDate.now() + ".md"));
        assertThat(ledger).contains("batch-0 fact").doesNotContain("failed-only", "batch-1 fact", "batch-2 fact");
        assertThat(ledger.split("## Memory Save", -1)).hasSize(2);
        assertThat(store.requestState("u", request.requestId()).resolve("batch-0000.complete")).exists();
        assertThat(store.requestState("u", request.requestId()).resolve("batch-0001.complete")).doesNotExist();
        assertThat(store.requestState("u", request.requestId()).resolve("complete")).doesNotExist();
        assertThat(Files.readAllLines(store.userRoot("u").resolve("agents/add-memory/sessions/s.jsonl"))).hasSize(2);
    }

    @Test
    void failedConsolidationResumesWithoutRepeatingCompletedBatches() throws Exception {
        model.consolidationFailures = 2;
        AddRequest request = request("consolidation-retry", "u", "s", 7);
        assertThatThrownBy(() -> service.add(request)).isInstanceOfSatisfying(AddAgentException.class,
                error -> assertThat(error.getCode()).isEqualTo("ADD_CONSOLIDATION_FAILED"));
        String before = Files.readString(store.userRoot("u").resolve("MEMORY.md"));
        assertThat(before).isEmpty();
        try (var workspace = store.open(request)) {
            assertThat(store.isComplete(workspace)).isFalse();
            assertThat(workspace.state().resolve("batch-0000.complete")).exists();
            assertThat(workspace.state().resolve("batch-0001.complete")).exists();
        }
        assertThat(store.userRoot("u").resolve("memory/.consolidation_state")).doesNotExist();
        String daily = Files.readString(store.userRoot("u").resolve("memory/" + LocalDate.now() + ".md"));
        assertThat(daily).contains("batch-0 fact", "batch-1 fact");
        assertThat(service.add(request).success()).isTrue();
        assertThat(model.batches).hasSize(2);
        assertThat(model.consolidationInputs).hasSize(3);
        assertThat(Files.readString(store.userRoot("u").resolve("MEMORY.md")))
                .startsWith("# MEMORY.md\n").contains("batch-0 fact", "batch-1 fact");
        assertThat(Files.readString(store.userRoot("u").resolve("memory/" + LocalDate.now() + ".md")))
                .isEqualTo(daily);
    }

    @Test
    void consolidationRetriesOnceWithFreshOutputAndDoesNotRepeatSave() throws Exception {
        model.failConsolidationOnce = true;
        assertThat(service.add(request("automatic-retry", "u", "s", 7)).success()).isTrue();
        assertThat(model.batches).hasSize(2);
        assertThat(model.consolidationInputs).hasSize(2);
        assertThat(model.consolidationInputs.get(0)).isEqualTo(model.consolidationInputs.get(1));
        assertThat(Files.readString(store.userRoot("u").resolve("MEMORY.md")))
                .startsWith("# MEMORY.md\n").contains("batch-0 fact", "batch-1 fact")
                .doesNotContain("incomplete discarded fragment");
        assertThat(store.requestState("u", "automatic-retry").resolve("complete")).exists();
    }

    @Test
    void modelRetryAfterSavingContinuesCurrentRoundWithoutSavingTwice() throws Exception {
        AtomicInteger acknowledgements = new AtomicInteger();
        Model transientFailure = new Model() {
            @Override public String getModelName() { return "offline-retry-after-save"; }
            @Override public Flux<ChatResponse> stream(List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
                if (!isMerge(tools) && messages.stream().anyMatch(m -> m.hasContentBlocks(ToolResultBlock.class))) {
                    if (acknowledgements.incrementAndGet() == 1) return Flux.error(new java.io.IOException("temporary connection failure"));
                }
                return model.stream(messages, tools, options);
            }
        };
        assertThat(createService(transientFailure).add(request("single-round-retry", "u", "s", 1)).success()).isTrue();
        assertThat(acknowledgements.get()).isEqualTo(2);
        assertThat(model.batches).hasSize(1);
        String ledger = Files.readString(store.userRoot("u").resolve("memory/" + LocalDate.now() + ".md"));
        assertThat(ledger.split("## Memory Save", -1)).hasSize(2);
        assertThat(store.requestState("u", "single-round-retry").resolve("complete")).exists();
    }

    @Test
    void transientMergeModelFailureRetriesOnlyCurrentRoundAndDiscardsPartialOutput() throws Exception {
        AtomicInteger attempts = new AtomicInteger();
        Model transientFailure = new Model() {
            @Override public String getModelName() { return "offline-merge-single-retry"; }
            @Override public Flux<ChatResponse> stream(List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
                if (isMerge(tools) && messages.stream().anyMatch(m -> m.hasContentBlocks(ToolResultBlock.class))) {
                    if (attempts.incrementAndGet() == 1) return Flux.concat(answer(TextBlock.builder().text("discarded retry fragment").build()),
                            Flux.error(new java.io.IOException("temporary merge connection failure")));
                }
                assertThat(messages.stream().map(Msg::getTextContent).toList().toString()).doesNotContain("discarded retry fragment");
                return model.stream(messages, tools, options);
            }
        };
        assertThat(createService(transientFailure).add(request("merge-current-round", "u", "s", 1)).success()).isTrue();
        assertThat(model.consolidationInputs).hasSize(1);
        assertThat(model.batches).hasSize(1);
        assertThat(store.requestState("u", "merge-current-round").resolve("complete")).exists();
    }

    @Test
    void exhaustedSingleRoundRetriesAreBoundedByTwoAgentAttempts() throws Exception {
        AtomicInteger attempts = new AtomicInteger();
        Model transientFailure = new Model() {
            @Override public String getModelName() { return "offline-exhausted-merge-retry"; }
            @Override public Flux<ChatResponse> stream(List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
                if (isMerge(tools) && messages.stream().anyMatch(m -> m.hasContentBlocks(ToolResultBlock.class))) {
                    attempts.incrementAndGet();
                    return Flux.error(new java.io.IOException("persistent connection failure"));
                }
                return model.stream(messages, tools, options);
            }
        };
        assertThatThrownBy(() -> createService(transientFailure).add(request("merge-retry-exhausted", "u", "s", 1)))
                .isInstanceOfSatisfying(AddAgentException.class, error -> assertThat(error.getCode()).isEqualTo("ADD_CONSOLIDATION_FAILED"));
        assertThat(attempts.get()).isEqualTo(4);
        assertThat(model.consolidationInputs).hasSize(2);
        assertThat(store.userRoot("u").resolve("MEMORY.md")).hasContent("");
        assertThat(store.userRoot("u").resolve("memory/.consolidation_state")).doesNotExist();
        assertThat(store.requestState("u", "merge-retry-exhausted").resolve("complete")).doesNotExist();
    }

    @Test
    void invalidConsolidationOutputCannotReplaceMemoryOrCompleteRequest() throws Exception {
        model.consolidationResponse = "SAVED";
        AddRequest request = request("invalid-consolidation", "u", "s", 1);
        assertThatThrownBy(() -> service.add(request)).isInstanceOfSatisfying(AddAgentException.class,
                error -> assertThat(error.getCode()).isEqualTo("ADD_CONSOLIDATION_ITERATION_LIMIT"));
        assertThat(Files.readString(store.userRoot("u").resolve("MEMORY.md")))
                .isEmpty();
        assertThat(model.consolidationInputs).hasSize(2);
        assertThat(store.userRoot("u").resolve("memory/.consolidation_state")).doesNotExist();
        assertThat(store.requestState("u", request.requestId()).resolve("complete")).doesNotExist();
    }

    @Test
    void consolidationHasATotalDeadlineEvenWhenCustomModelIgnoresExecutionConfig() throws Exception {
        properties.setConsolidationTimeoutSeconds(1);
        AtomicInteger consolidationCalls = new AtomicInteger();
        Model neverConsolidates = new Model() {
            @Override public String getModelName() { return "offline-consolidation-timeout"; }
            @Override public Flux<ChatResponse> stream(List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
                // 连续返回流片段仍应在一秒内终止，不能用每片段重新计时的 Flux.timeout。
                if (isMerge(tools)) {
                    consolidationCalls.incrementAndGet();
                    return Flux.interval(Duration.ofMillis(50))
                            .map(index -> ChatResponse.builder().content(List.of(TextBlock.builder().text("partial").build())).build());
                }
                return model.stream(messages, tools, options);
            }
        };
        AddRequest request = request("consolidation-timeout", "u", "s", 1);
        assertThatThrownBy(() -> createService(neverConsolidates).add(request))
                .isInstanceOfSatisfying(AddAgentException.class,
                        error -> assertThat(error.getCode()).isEqualTo("ADD_CONSOLIDATION_TIMEOUT"));
        assertThat(consolidationCalls.get()).isEqualTo(4);
        assertThat(store.userRoot("u").resolve("MEMORY.md")).hasContent("");
        assertThat(store.userRoot("u").resolve("memory/.consolidation_state")).doesNotExist();
        assertThat(store.requestState("u", request.requestId()).resolve("batch-0000.complete")).exists();
        assertThat(store.requestState("u", request.requestId()).resolve("complete")).doesNotExist();
    }

    @Test
    void modelClaimWithoutToolWriteCannotAcknowledgeSuccess() {
        AddAgentService untrustworthy = createService(plainModel("SAVED"));
        assertThatThrownBy(() -> untrustworthy.add(request("lie", "u", "s", 1)))
                .isInstanceOf(AddAgentException.class).hasMessageContaining("must persist");
    }

    @Test
    void explicitNoReplyCompletesWithoutInventingMemory() throws Exception {
        AddAgentService noop = createService(plainModel("NO_REPLY"));
        assertThat(noop.add(request("noop", "u", "s", 1)).success()).isTrue();
        assertThat(Files.readString(store.userRoot("u").resolve("MEMORY.md"))).isEmpty();
    }

    @Test
    void noModelConfigurationAndInvalidImagesFailBeforeWorkspaceCreation() throws Exception {
        properties.setApiKey("");
        AddAgentService missing = createService(null);
        assertThatThrownBy(() -> missing.add(request("r", "u", "s", 1)))
                .isInstanceOf(AddAgentException.class).hasMessageContaining("api-key");
        JsonNode image = JSON.readTree("""
                [{"type":"text","text":"Milo"},
                 {"type":"image_url","image_url":{"url":"data:image/png;base64,AA=="}}]
                """);
        assertThatThrownBy(() -> service.add(new AddRequest("image", "u", "s",
                List.of(new AddMessage("user", null, image)))))
                .isInstanceOfSatisfying(AddAgentException.class,
                        error -> assertThat(error.getCode()).isEqualTo("ADD_INVALID_IMAGE"));
        assertThat(store.userRoot("u")).doesNotExist();
    }

    @Test
    void originalIdentifiersArePreservedAndQuestionAnswerBoundaryIsNotSplit() throws Exception {
        properties.setBatchSize(1);
        var messages = List.of(new AddMessage("user", null, JSON.readTree("\"Question\"")),
                new AddMessage("assistant", null, JSON.readTree("\"Answer\"")),
                new AddMessage("user", null, JSON.readTree("\"Follow-up\"")));
        assertThatThrownBy(() -> service.add(new AddRequest("../../r", "alice", "D1", messages)))
                .isInstanceOfSatisfying(AddAgentException.class,
                        error -> assertThat(error.getCode()).isEqualTo("ADD_INVALID_IDENTIFIER"));
        assertThat(store.userRoot("alice")).doesNotExist();
        AddRequest request = new AddRequest("Part-01", "Alice名字", "D1", messages);
        assertThat(service.add(request).success()).isTrue();

        assertThat(model.batches.stream().map(batch -> batch.path("new_messages").size()))
                .containsExactly(2, 1);
        assertThat(store.userRoot(request.userId())).startsWith(directory.resolve("workspace"));
        assertThat(store.userRoot(request.userId()).getFileName().toString()).isEqualTo("Alice名字");
        assertThat(store.userRoot(request.userId()).resolve("agents/add-memory/sessions/D1.jsonl")).exists();
    }

    @Test
    void successfulSaveCannotMaskAnotherFailedSave() throws Exception {
        Model mixed = scriptedModel(List.of(List.of(save("good", "- A supported fact. (source_ids: 1)"), save("bad", ""))));
        AddRequest request = request("partial", "u", "s", 1);
        assertThatThrownBy(() -> createService(mixed).add(request))
                .isInstanceOf(AddAgentException.class).hasMessageContaining("unresolved");
        try (var workspace = store.open(request)) {
            assertThat(store.isComplete(workspace)).isFalse();
            assertThat(workspace.state().resolve("batch-0000.complete")).doesNotExist();
        }
        assertThat(Files.readString(store.userRoot("u").resolve("MEMORY.md"))).isEmpty();
        assertThat(store.userRoot("u").resolve("memory/" + LocalDate.now() + ".md")).doesNotExist();
    }

    @Test
    void ignoresLargeLongTermMemoryAndSelectsRecentSameSessionSavesAcrossDays() throws Exception {
        AddRequest request = request("large-ledger", "u", "s", 1);
        try (var workspace = store.open(request)) {
            Files.writeString(workspace.user().resolve("MEMORY.md"),
                    "# Memory\n" + "Historical summary text.\n".repeat(2500) + "UNIQUE_LATEST_SUMMARY_FACT\n");
            Files.writeString(workspace.user().resolve("memory/" + LocalDate.now() + ".md"),
                    "## Memory Save — 2025-03-02T00:00:00Z | session_id=\"other\"\n- OTHER_SESSION_FACT\n"
                            + "## Memory Save — 2025-03-02T00:00:01Z | session_id=\"s\"\n- CURRENT_DAY_SUMMARY (source_id: 2, source_date: 2025-03-02)\n");
            Files.writeString(workspace.user().resolve("memory/" + LocalDate.now().minusDays(1) + ".md"),
                    "## Memory Save — 2025-03-01T00:00:00Z | session_id=\"s\"\n- OLDER_SAME_SESSION_FACT\n"
                            + "## Memory Save — 2025-03-01T00:00:01Z | session_id=\"s\"\n- PREVIOUS_DAY_SUMMARY (source_id: 1, source_date: 2025-03-01)\n");
            Files.writeString(workspace.user().resolve("memory/.consolidation_state"), "2025-03-03T00:00:00Z");
        }
        service.add(request);
        assertThat(model.initialPrompts.get(0)).isEqualTo(AddAgentPrompts.SYSTEM_PROMPT.strip());
        JsonNode recent = model.batches.get(0).path("read_only_recent_saves");
        assertThat(recent).hasSize(2);
        assertThat(recent.get(0).asString()).contains("PREVIOUS_DAY_SUMMARY", "source_date: 2025-03-01");
        assertThat(recent.get(1).asString()).contains("CURRENT_DAY_SUMMARY", "source_date: 2025-03-02");
        assertThat(recent.toString()).doesNotContain("OTHER_SESSION_FACT", "OLDER_SAME_SESSION_FACT",
                "UNIQUE_LATEST_SUMMARY_FACT", "Memory Save", "session_id", "memory/");
    }

    @Test
    void overallDeadlineDoesNotResetBetweenBatches() throws Exception {
        properties.setRequestTimeoutSeconds(1);
        properties.setBatchSize(1);
        SavingModel delegate = new SavingModel();
        Model slow = delayedModel(delegate, Duration.ofMillis(350));
        AddRequest request = request("total-timeout", "u", "s", 4);
        assertThatThrownBy(() -> createService(slow).add(request))
                .isInstanceOfSatisfying(AddAgentException.class,
                        error -> assertThat(error.getCode()).isEqualTo("ADD_REQUEST_TIMEOUT"));
        try (var workspace = store.open(request)) { assertThat(store.isComplete(workspace)).isFalse(); }
        assertThat(delegate.batches.size()).isLessThan(4);
    }

    @Test
    void modelCallHasItsOwnShorterDeadline() {
        properties.setModelTimeoutSeconds(1);
        Model never = new Model() {
            @Override public String getModelName() { return "offline-never"; }
            @Override public Flux<ChatResponse> stream(List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
                return Flux.never();
            }
        };
        assertThatThrownBy(() -> createService(never).add(request("model-timeout", "u", "s", 1)))
                .isInstanceOfSatisfying(AddAgentException.class,
                        error -> assertThat(error.getCode()).isEqualTo("ADD_MODEL_TIMEOUT"));
    }

    @Test
    void malformedBaseUrlIsRejectedWithoutAnyNetworkRequest() {
        properties.setApiKey("offline-placeholder");
        properties.setBaseUrl("hhttps://invalid.example/v1");
        assertThatThrownBy(() -> createService(null).add(request("bad-url", "u", "s", 1)))
                .isInstanceOfSatisfying(AddAgentException.class,
                        error -> assertThat(error.getCode()).isEqualTo("ADD_MODEL_CONFIGURATION_INVALID"));
        assertThat(store.userRoot("u")).doesNotExist();
    }

    @Test
    void sameUserLockWaitIsBoundedWithoutStartingAnotherAgent() throws Exception {
        properties.setQueueTimeoutSeconds(1);
        CountDownLatch started = new CountDownLatch(1);
        var release = reactor.core.publisher.Sinks.<ChatResponse>one();
        AtomicInteger modelCalls = new AtomicInteger();
        Model blocked = new Model() {
            @Override public String getModelName() { return "offline-blocked"; }
            @Override public Flux<ChatResponse> stream(List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
                modelCalls.incrementAndGet(); started.countDown(); return release.asMono().flux();
            }
        };
        AddAgentService guarded = createService(blocked);
        var first = guarded.addAsync(request("first", "u", "s", 1));
        assertThat(started.await(3, TimeUnit.SECONDS)).isTrue();
        var second = guarded.addAsync(request("second", "u", "s", 1));
        try {
            assertThatThrownBy(() -> second.get(3, TimeUnit.SECONDS)).hasCauseInstanceOf(AddAgentException.class)
                    .satisfies(error -> assertThat(((AddAgentException) error.getCause()).getCode()).isEqualTo("ADD_USER_LOCK_TIMEOUT"));
            assertThat(modelCalls.get()).isEqualTo(1);
        } finally {
            release.tryEmitValue(ChatResponse.builder().content(List.of(TextBlock.builder().text("NO_REPLY").build()))
                    .finishReason("stop").build());
        }
        assertThat(first.get(3, TimeUnit.SECONDS).success()).isTrue();
    }

    private Model delayedModel(Model delegate, Duration delay) {
        return new Model() {
            @Override public String getModelName() { return "offline-delayed"; }
            @Override public Flux<ChatResponse> stream(List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
                return Flux.defer(() -> delegate.stream(messages, tools, options)).delaySubscription(delay);
            }
        };
    }

    private Model scriptedModel(List<List<ToolUseBlock>> turns) {
        return new Model() {
            private int turn;
            @Override public String getModelName() { return "offline-scripted"; }
            @Override public Flux<ChatResponse> stream(List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
                if (isMerge(tools)) return mergeTurn(messages, null);
                if (messages.stream().noneMatch(m -> m.hasContentBlocks(ToolResultBlock.class))) turn = 0;
                if (turn < turns.size()) return Flux.just(ChatResponse.builder()
                        .content(new ArrayList<ContentBlock>(turns.get(turn++))).finishReason("stop").build());
                return answer(TextBlock.builder().text("SAVED").build());
            }
        };
    }

    private ToolUseBlock save(String id, String content) { return tool(id, "memory_save", Map.of("content", content)); }

    private ToolUseBlock tool(String id, String name, Map<String, Object> arguments) {
        return new ToolUseBlock(id, name, arguments, AddWorkspaceStore.json(arguments), Map.of());
    }

    private AddRequest request(String id, String user, String session, int count) {
        List<AddMessage> messages = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            messages.add(new AddMessage("user", 1740873600000L,
                    JSON.readTree("\"message-" + i + "\"")));
        }
        return new AddRequest(id, user, session, messages);
    }

    private Model plainModel(String response) {
        return new Model() {
            @Override public String getModelName() { return "offline"; }
            @Override public Flux<ChatResponse> stream(List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
                if (isMerge(tools)) return mergeTurn(messages, null);
                return answer(TextBlock.builder().text(response).build());
            }
        };
    }

    private static Flux<ChatResponse> answer(ContentBlock block) {
        return Flux.just(ChatResponse.builder().content(List.of(block)).finishReason("stop").build());
    }

    /** 离线模型通过真实归并工具提交，不模拟真实模型的语义能力。 */
    private static boolean isMerge(List<ToolSchema> tools) {
        return tools.stream().anyMatch(tool -> tool.getName().equals("get_memory"));
    }

    private static Flux<ChatResponse> mergeTurn(List<Msg> messages, String response) {
        if (response != null) return answer(TextBlock.builder().text(response).build());
        long results = messages.stream().flatMap(message -> message.getContentBlocks(ToolResultBlock.class).stream()).count();
        if (results == 0) {
            assertThat(messages.get(0).getTextContent()).doesNotContain("First call get_memory");
            String old = initialMemorySnapshot(messages);
            String document = consolidatedDocument(messages);
            String name = old.isEmpty() ? "create_topic" : "edit_memory";
            Map<String, Object> args = old.isEmpty() ? Map.of("topic", "Offline facts",
                    "content", document.substring(document.indexOf("## Offline facts\n") + "## Offline facts\n".length()))
                    : Map.of("anchor", old, "new_content", document);
            return answer(new ToolUseBlock("merge-write", name, args, AddWorkspaceStore.json(args), Map.of()));
        }
        if (results == 1) return answer(new ToolUseBlock("merge-lint", "lint_memory", Map.of(), "{}", Map.of()));
        return answer(TextBlock.builder().text("MERGED").build());
    }

    private static String consolidatedDocument(List<Msg> messages) {
        assertThat(messages.get(0).getTextContent()).contains("You are a memory consolidation assistant",
                "source_id", "source_date");
        String input = messages.get(1).getTextContent();
        String current = initialMemorySnapshot(messages);
        String facts = java.util.stream.Stream.concat(current.lines(), input.lines())
                .filter(line -> line.stripLeading().startsWith("- ")).distinct()
                .collect(java.util.stream.Collectors.joining("\n"));
        return "# MEMORY.md\n\n## Offline facts\n"
                + facts;
    }

    private static String initialMemorySnapshot(List<Msg> messages) {
        String input = messages.get(1).getTextContent();
        String start = "<current_memory_snapshot>\n";
        return input.substring(start.length(), input.indexOf("\n</current_memory_snapshot>"));
    }

    /** Exercises the real ReAct loop and native tool invoker without any network calls. */
    static class SavingModel implements Model {
        final List<JsonNode> batches = new ArrayList<>();
        final List<String> initialPrompts = new ArrayList<>();
        final List<String> consolidationInputs = new ArrayList<>();
        List<String> toolNames;
        final AtomicInteger toolId = new AtomicInteger();
        int failBatchOnce = -1;
        int batchFailures = 1;
        boolean failConsolidationOnce;
        int consolidationFailures;
        String consolidationResponse;

        @Override public String getModelName() { return "offline-saving-model"; }

        @Override
        public Flux<ChatResponse> stream(List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
            if (messages.stream().anyMatch(message -> message.hasContentBlocks(io.agentscope.core.message.DataBlock.class))) {
                assertThat(tools).isEmpty();
                return answer(TextBlock.builder().text("一只金色犬佩戴蓝色胸背带，旁边有红色玩具。").build());
            }
            if (isMerge(tools)) {
                if (messages.size() == 2) consolidationInputs.add(messages.get(1).getTextContent());
                if (messages.size() == 2 && (failConsolidationOnce || consolidationFailures > 0)) {
                    failConsolidationOnce = false;
                    if (consolidationFailures > 0) consolidationFailures--;
                    return Flux.concat(answer(TextBlock.builder().text("incomplete discarded fragment").build()),
                            Flux.error(new IllegalStateException("offline consolidation failure")));
                }
                return mergeTurn(messages, consolidationResponse);
            }
            boolean acted = messages.stream().anyMatch(message -> message.hasContentBlocks(ToolResultBlock.class));
            if (acted) {
                for (Msg message : messages) {
                    for (ToolResultBlock result : message.getContentBlocks(ToolResultBlock.class)) {
                        String output = result.getOutput().stream()
                                .filter(block -> block instanceof TextBlock)
                                .map(block -> ((TextBlock) block).getText()).reduce("", String::concat);
                        assertThat(result.getState()).as("Offline tool result: %s", output)
                                .isEqualTo(io.agentscope.core.message.ToolResultState.SUCCESS);
                        assertThat(result.getName()).isEqualTo("memory_save");
                        assertThat(output.startsWith("\"") ? JSON.readTree(output).asString() : output)
                                .startsWith("Saved ");
                    }
                }
                return answer(TextBlock.builder().text("SAVED").build());
            }
            Msg source = messages.stream().filter(message -> message.getRole().name().equals("USER"))
                    .reduce((first, second) -> second).orElseThrow();
            JsonNode batch = JSON.readTree(source.getTextContent());
            batches.add(batch);
            initialPrompts.add(messages.stream().filter(message -> message.getRole().name().equals("SYSTEM"))
                    .findFirst().orElseThrow().getTextContent());
            toolNames = tools.stream().map(ToolSchema::getName).toList();
            int index = batch.path("batch_index").asInt();
            if (index == failBatchOnce) {
                if (--batchFailures <= 0) failBatchOnce = -1;
                return Flux.error(new IllegalStateException("offline simulated failure"));
            }
            String content = "- batch-" + index + " fact. Sources: "
                    + batch.path("new_messages").get(0).path("source_id").asString()
                    + " (source_ids: " + batch.path("new_messages").get(0).path("source_id").asString() + ")";
            Map<String, Object> arguments = Map.of("content", content);
            return answer(new ToolUseBlock("save-" + toolId.incrementAndGet(), "memory_save",
                    arguments, AddWorkspaceStore.json(arguments), Map.of()));
        }
    }
}
