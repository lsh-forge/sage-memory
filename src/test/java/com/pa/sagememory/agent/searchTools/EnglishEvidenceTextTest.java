package com.pa.sagememory.agent.searchTools;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 固定模型可见文案使用英文；证据正文及用户主题名称保持原样。 */
class EnglishEvidenceTextTest {
    private static final Pattern HAN = Pattern.compile("\\p{IsHan}");

    private SearchEvidence evidence(SearchEvidence.Kind kind, String id, String topic, String text, int position) {
        return new SearchEvidence(kind, id, topic, text, text, "user", List.of("1"), Map.of("1", "2025-03-02"),
                Path.of("unused"), position + 1, "s", position,
                kind == SearchEvidence.Kind.IMAGE ? new SearchEvidence.ImageReference(id, Path.of("unused.png"), "image/png") : null);
    }

    private SearchEvidenceContext context() {
        return new SearchEvidenceContext("u", List.of(
                evidence(SearchEvidence.Kind.SESSION, "1", "", "Milo is a dog.", 0),
                new SearchEvidence(SearchEvidence.Kind.SESSION, "2", "", "A reply.", "A reply.", "assistant",
                        List.of("2"), Map.of("2", "2025-03-02"), Path.of("unused"), 2, "s", 1, null),
                evidence(SearchEvidence.Kind.IMAGE, "p1-1", "", "A dog with a blue harness.", 0),
                evidence(SearchEvidence.Kind.MEMORY, "m1", "Milo", "Milo is a dog.", 0),
                evidence(SearchEvidence.Kind.LEDGER, "20250302-1", "", "Milo is a dog.", 0)));
    }

    private void english(String text) {
        assertThat(text).isNotBlank();
        assertThat(HAN.matcher(text).find()).as("No generated Chinese labels in: %s", text).isFalse();
    }

    @ParameterizedTest @EnumSource(SearchEvidence.Kind.class)
    void allSourceDisplaysUseEnglish(SearchEvidence.Kind kind) {
        String text = evidence(kind, kind == SearchEvidence.Kind.IMAGE ? "p1-1" : "1", "Milo", "A fact.", 0).display();
        english(text);
        assertThat(text).contains(kind == SearchEvidence.Kind.IMAGE ? "Image ID: " : "Memory ID: ", "Memory source: ", "source_date: ");
    }

    @Test void legacyUnknownDatesAreTranslatedOnlyForDisplay() {
        var evidence = new SearchEvidence(SearchEvidence.Kind.MEMORY, "m1", "Milo", "A fact.", "A fact.", "",
                List.of("1", "2"), Map.of("1", "未知", "2", "2025-03-02"), Path.of("unused"), 1, "", -1, null);
        assertThat(evidence.dateText()).isEqualTo("1=unknown; 2=2025-03-02");
        assertThat(evidence.sourceDates().get("1")).isEqualTo("未知");
        english(evidence.display());
        var noSources = new SearchEvidence(SearchEvidence.Kind.LEDGER, "ledger", "", "A fact.", "A fact.", "",
                List.of(), Map.of(), Path.of("unused"), 1, "", -1, null);
        assertThat(noSources.display()).contains("source_ids: unknown source_date: unknown");
        english(noSources.display());
    }

    @Test void searchToolsReturnEnglishLabelsAndKeepMessageImageAssociation() {
        var context = context();
        String evidence = new SearchEvidenceTool(context).searchEvidence(List.of("dog"));
        english(evidence);
        assertThat(evidence).contains("Memory ID: 1", "Memory source: Original message", "[Attached image 1 description]: ",
                "Long-term memory", "Daily ledger").doesNotContain("p1-1");
        String images = new SearchImagesTool(context).searchImages(List.of("blue harness"));
        english(images);
        assertThat(images).contains("Memory ID: 1", "Memory ID: 2", "Original text: Milo", "[Attached image 1 description]: ")
                .doesNotContain("p1-1");
        String originals = new GetEvidenceTool(context).getEvidence(List.of("1"), null, null);
        english(originals);
        assertThat(originals).contains("Memory ID: 1", "Memory ID: 2", "Content: Milo", "[Attached image 1 description]: ")
                .doesNotContain("p1-1");
        String topics = new GetTopicEvidenceTool(context).getTopicEvidence("Milo");
        english(topics);
        assertThat(topics).contains("Topic: Milo", "Memory ID: m1");
    }

    @Test void emptyAndAlreadyShownResultsUseEnglish() {
        var evidence = new SearchEvidenceTool(context());
        english(evidence.searchEvidence(List.of("no-match")));
        evidence.searchEvidence(List.of("dog"));
        assertThat(evidence.searchEvidence(List.of("dog"))).startsWith("No new matching fragments.");
        var images = new SearchImagesTool(context());
        english(images.searchImages(List.of("no-match")));
        images.searchImages(List.of("dog"));
        assertThat(images.searchImages(List.of("dog"))).startsWith("No new matching images.");
    }

    @Test void paginationHintsUseEnglish() {
        var entries = new ArrayList<SearchEvidence>();
        IntStream.rangeClosed(1, 35).forEach(i -> {
            entries.add(evidence(SearchEvidence.Kind.SESSION, "" + i, "", "A dog.", i - 1));
            entries.add(new SearchEvidence(SearchEvidence.Kind.IMAGE, "p" + i + "-1", "", "A dog.", "A dog.", "user",
                    List.of("" + i), Map.of("" + i, "2025-03-02"), Path.of("unused"), i, "s", i - 1,
                    new SearchEvidence.ImageReference("p" + i + "-1", Path.of("unused.png"), "image/png")));
        });
        String evidence = new SearchEvidenceTool(new SearchEvidenceContext("u", entries)).searchEvidence(List.of("dog"));
        english(evidence); assertThat(evidence).contains("More matching fragments are available");
        String images = new SearchImagesTool(new SearchEvidenceContext("u", entries)).searchImages(List.of("dog"));
        english(images); assertThat(images).contains("More matching images are available");
    }

    @Test void validationAndMissingEvidenceHintsUseEnglish() {
        for (List<String> keywords : Arrays.asList(null, List.<String>of(), List.of(" "), Arrays.asList((String) null),
                List.of("x".repeat(201)), IntStream.range(0, 33).mapToObj(i -> "x" + i).toList())) {
            english(new SearchEvidenceTool(context()).searchEvidence(keywords));
            english(new SearchImagesTool(context()).searchImages(keywords));
        }
        var originals = new GetEvidenceTool(context());
        english(originals.getEvidence(List.of("1"), -1, 0));
        english(originals.getEvidence(List.of("1"), 0, 3));
        for (List<String> ids : Arrays.asList(null, List.<String>of(), Arrays.asList((String) null), List.of("p1-1"), List.of("m1")))
            english(originals.getEvidence(ids, null, null));
        assertThat(originals.getEvidence(List.of("999"), null, null)).isEqualTo("Source IDs not found: 999");
        var topics = new GetTopicEvidenceTool(context());
        english(topics.getTopicEvidence(null)); english(topics.getTopicEvidence(" "));
        assertThat(topics.getTopicEvidence("Absent")).isEqualTo("Topic not found: Absent");
        var a = evidence(SearchEvidence.Kind.MEMORY, "m2", "", "Fact A.", 0);
        var b = evidence(SearchEvidence.Kind.MEMORY, "m3", "", "Fact B.", 1);
        a = new SearchEvidence(a.kind(), a.id(), "", a.content(), a.matchText(), "", a.sourceIds(), a.sourceDates(),
                a.file(), 1, "", -1, null, List.of("Milo", "Details"));
        b = new SearchEvidence(b.kind(), b.id(), "", b.content(), b.matchText(), "", b.sourceIds(), b.sourceDates(),
                b.file(), 2, "", -1, null, List.of("Finn", "Details"));
        String ambiguous = new GetTopicEvidenceTool(new SearchEvidenceContext("u", List.of(a, b))).getTopicEvidence("Details");
        english(ambiguous); assertThat(ambiguous).contains("Multiple topics matched", "Milo - Details", "Finn - Details");
    }

    @Test void finishSelectionErrorsUseEnglishAndDoNotChangeValidation() {
        var context = context(); context.registerShown(context.candidates());
        for (List<String> ids : Arrays.asList(null, Arrays.asList((String) null), List.of(" "), List.of("p1-1"), List.of("999"))) {
            assertThatThrownBy(() -> context.finish(ids)).isInstanceOf(IllegalArgumentException.class)
                    .satisfies(error -> english(error.getMessage()));
            assertThat(context.isFinished()).isFalse();
        }
        context.finish(List.of("1"));
        assertThatThrownBy(() -> context.finish(List.of("2"))).isInstanceOf(IllegalArgumentException.class)
                .satisfies(error -> english(error.getMessage()));
        assertThat(context.selectedIds()).containsExactly("1");
    }

    @Test void chineseEvidenceAndTopicNamesRemainUntranslated() {
        var memory = evidence(SearchEvidence.Kind.MEMORY, "m1", "米洛", "米洛戴蓝色胸背带。", 0);
        var context = new SearchEvidenceContext("u", List.of(memory));
        assertThat(new SearchEvidenceTool(context).searchEvidence(List.of("蓝色")))
                .contains("Topic: 米洛", "Content: 米洛戴蓝色胸背带。", "Memory ID: m1");
        assertThat(memory.content()).isEqualTo("米洛戴蓝色胸背带。");
    }
}
