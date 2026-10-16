package com.pa.sagememory.agent.searchTools;

import com.pa.sagememory.agent.AddAgentProperties;
import com.pa.sagememory.agent.workspace.AddWorkspaceStore;
import com.pa.sagememory.dto.EvidenceCard;
import io.agentscope.harness.agent.memory.session.SessionEntry;
import io.agentscope.harness.agent.memory.session.SessionTree;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Base64;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

/** 来源时间和实际响应的离线验证，不调用模型、不访问真实用户工作区。 */
class SearchEvidenceTimeTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    @TempDir Path root;
    private AddWorkspaceStore workspace;
    private SearchEvidenceAssembler assembler;

    @BeforeEach void setUp() {
        var properties = new AddAgentProperties();
        properties.setWorkspaceRoot(root.toString());
        workspace = new AddWorkspaceStore(properties);
        assembler = new SearchEvidenceAssembler(workspace);
    }

    private SearchEvidence evidence(SearchEvidence.Kind kind, Map<String, String> dates, Long timestamp) {
        return new SearchEvidence(kind, kind == SearchEvidence.Kind.SESSION ? "1" : "m1", "Topic", "fact", "fact", "user",
                List.copyOf(dates.keySet()), dates, root.resolve("MEMORY.md"), 1, "s", 0, null,
                kind == SearchEvidence.Kind.MEMORY ? List.of("Topic") : List.of(), timestamp);
    }

    private EvidenceCard assemble(SearchEvidence evidence, SearchEvidence... originals) throws Exception {
        var candidates = new ArrayList<SearchEvidence>();
        candidates.add(evidence); candidates.addAll(List.of(originals));
        var context = new SearchEvidenceContext("time-test", candidates);
        context.registerShown(List.of(evidence));
        context.finish(List.of(evidence.id()));
        return assembler.assemble(context, 100).data().get(0);
    }

    @ParameterizedTest @ValueSource(longs = {0, -1, 1740918896789L})
    void originalMessageUsesExactTimestampIncludingMilliseconds(long timestamp) throws Exception {
        var card = assemble(evidence(SearchEvidence.Kind.SESSION, Map.of("1", "2025-03-02"), timestamp));
        assertThat(card.createdAt()).isEqualTo(Instant.ofEpochMilli(timestamp).toString());
        var serialized = JSON.readTree(JSON.writeValueAsString(card));
        assertThat(serialized.path("created_at").asString()).isEqualTo(card.createdAt());
        assertThat(serialized.has("createdAt")).isFalse();
    }

    @ParameterizedTest @EnumSource(value = SearchEvidence.Kind.class, names = {"MEMORY", "LEDGER"})
    void summariesReturnOriginalTimestampNotSummaryOrFileDate(SearchEvidence.Kind kind) throws Exception {
        long timestamp = Instant.parse("2025-03-02T12:34:56.789Z").toEpochMilli();
        var card = assemble(evidence(kind, Map.of("1", "2025-03-02"), 0L),
                evidence(SearchEvidence.Kind.SESSION, Map.of("1", "2025-03-02"), timestamp));
        assertThat(card.id()).isEqualTo("1");
        assertThat(card.createdAt()).isEqualTo("2025-03-02T12:34:56.789Z");
    }

    @Test void missingOriginalTimestampDoesNotFallBackToDate() throws Exception {
        var card = assemble(evidence(SearchEvidence.Kind.SESSION, Map.of("1", "2025-03-02"), null));
        assertThat(card.createdAt()).isNull();
        assertThat(JSON.readTree(JSON.writeValueAsString(card)).has("created_at")).isFalse();
    }

    @Test void summaryDatesCannotFabricateMissingOriginalTimestamp() throws Exception {
        for (String date : List.of("未知", "not-a-date", "2025-03-02")) {
            var card = assemble(evidence(SearchEvidence.Kind.MEMORY, Map.of("1", date), 0L),
                    evidence(SearchEvidence.Kind.SESSION, Map.of("1", "2025-03-02"), null));
            assertThat(card.id()).isEqualTo("1");
            assertThat(card.createdAt()).isNull();
        }
    }

    @Test void persistedTimestampSurvivesSnapshotAndImageBindingWithoutChangingOrder() throws Exception {
        Path user = workspace.userRoot("time-test");
        Files.createDirectories(user.resolve("assets/images"));
        Files.createDirectories(user.resolve("memory"));
        Files.createDirectories(user.resolve("agents/add-memory/sessions"));
        Files.write(user.resolve("assets/images/p1-1.png"), Base64.getDecoder().decode(
                "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAusB9Wl6AAAAAElFTkSuQmCC"));
        Files.writeString(user.resolve("MEMORY.md"), "# MEMORY.md\n## Topic\n- fact (source_ids: 1, source_date: 2025-03-02)\n");
        Files.writeString(user.resolve("memory/2026-10-16.md"), "- fact (source_ids: 1, source_date: 2025-03-02)\n");
        long timestamp = Instant.parse("2025-03-02T12:34:56.789Z").toEpochMilli();
        var source = new LinkedHashMap<String, Object>();
        source.put("source_id", "1"); source.put("session_id", "s"); source.put("role", "user");
        source.put("timestamp", timestamp); source.put("source_date", "2025-03-02");
        source.put("content", "fact"); source.put("original_content", List.of(Map.of("type", "text", "text", "fact")));
        source.put("images", List.of(Map.of("image_index", 1, "image_id", "p1-1", "path", "assets/images/p1-1.png",
                "media_type", "image/png", "description", "A visible dog")));
        var tree = new SessionTree(user.resolve("agents/add-memory/sessions/s.jsonl"), user, null);
        tree.append(new SessionEntry.MessageEntry("1", null, Instant.ofEpochMilli(timestamp), "user",
                JSON.writeValueAsString(source), null, List.of("text", "image")));
        tree.flush();
        var context = new SearchEvidenceStore(workspace).open("time-test");
        assertThat(context.sources().get("1").timestamp()).isEqualTo(timestamp);
        context.registerShown(context.candidates());
        context.finish(List.of("m3", "20261016-1", "1"));
        var response = assembler.assemble(context, 100);
        assertThat(response.data()).extracting(EvidenceCard::id).containsExactly("1");
        assertThat(response.data()).extracting(EvidenceCard::createdAt).containsExactly(
                "2025-03-02T12:34:56.789Z");
        assertThat(response.data().get(0).content()).hasSize(2);
        // 即使只选择摘要，最终条目仍是原始消息及其毫秒时间和附图。
        context = new SearchEvidenceStore(workspace).open("time-test");
        context.registerShown(context.candidates()); context.finish(List.of("m3"));
        var summary = assembler.assemble(context, 100).data().get(0);
        assertThat(summary.id()).isEqualTo("1");
        assertThat(summary.createdAt()).isEqualTo("2025-03-02T12:34:56.789Z");
        assertThat(summary.content()).hasSize(2);
    }
}
