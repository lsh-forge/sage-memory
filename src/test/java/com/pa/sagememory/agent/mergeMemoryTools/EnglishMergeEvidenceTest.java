package com.pa.sagememory.agent.mergeMemoryTools;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

class EnglishMergeEvidenceTest {
    @TempDir Path root;

    @Test void originalEvidenceLabelsAreEnglishAndSourceTextIsPreserved() throws Exception {
        var json = JsonMapper.builder().build();
        Path sessions = Files.createDirectories(root.resolve("agents/add-memory/sessions"));
        var user = Map.of("source_id", "1", "session_id", "s", "role", "user", "source_date", "2025-03-02",
                "content", "Unused enriched content", "original_content", List.of(Map.of("type", "text", "text", "米洛戴蓝色胸背带。")),
                "images", List.of(Map.of("image_index", 1, "image_id", "p1-1", "description", "A dog wearing a blue harness.")));
        var assistant = Map.of("source_id", "2", "session_id", "s", "role", "assistant", "content", "A reply.");
        Files.writeString(sessions.resolve("s.jsonl"),
                json.writeValueAsString(Map.of("type", "message", "id", "1", "role", "user", "content", json.writeValueAsString(user)))
                + "\n" + json.writeValueAsString(Map.of("type", "message", "id", "2", "role", "assistant", "content", json.writeValueAsString(assistant))) + "\n");
        var reader = new MergeEvidenceReader(root, path -> {}, () -> {});
        String text = reader.retrieve(List.of("1")).text();
        assertThat(text).contains("Memory ID: 1", "Memory ID: 2", "session_id: s", "role=user, Content: 米洛戴蓝色胸背带。",
                "Image ID: p1-1", "Image description (vision-model generated): A dog wearing a blue harness.", "source_date: unknown");
        assertThat(text.replace("米洛戴蓝色胸背带。", "")).doesNotMatch("(?s).*\\p{IsHan}.*");
        assertThat(reader.retrieve(List.of("999")).text()).isEqualTo("Unavailable source_ids for this user: 999");
    }
}
