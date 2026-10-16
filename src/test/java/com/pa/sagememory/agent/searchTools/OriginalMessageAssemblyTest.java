package com.pa.sagememory.agent.searchTools;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.pa.sagememory.agent.AddAgentProperties;
import com.pa.sagememory.agent.SearchAgentException;
import com.pa.sagememory.agent.workspace.AddWorkspaceStore;
import com.pa.sagememory.dto.EvidenceCard;
import com.pa.sagememory.dto.SearchResponse;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 只测最终宿主组装，不调用模型、不改工具、也不操作真实用户文件。 */
class OriginalMessageAssemblyTest {
    @TempDir Path root;
    private Path user;
    private SearchEvidenceAssembler assembler;
    private final List<SearchEvidence> candidates = new ArrayList<>();

    @BeforeEach void setUp() throws Exception {
        var properties = new AddAgentProperties(); properties.setWorkspaceRoot(root.toString());
        var store = new AddWorkspaceStore(properties);
        user = store.userRoot("u"); Files.createDirectories(user.resolve("assets/images"));
        assembler = new SearchEvidenceAssembler(store);
    }

    private SearchEvidence raw(String id, String role, String text) {
        var evidence = new SearchEvidence(SearchEvidence.Kind.SESSION, id, "", "Enriched content must not be returned.",
                text, role, List.of(id), Map.of(id, "2025-03-02"), user.resolve("s.jsonl"), Integer.parseInt(id),
                "s", Integer.parseInt(id), null, List.of(), 1740873600000L + Long.parseLong(id));
        candidates.add(evidence); return evidence;
    }

    private void summary(String id, SearchEvidence.Kind kind, String... sources) {
        candidates.add(new SearchEvidence(kind, id, "Topic", "Summary must not be returned.", "Summary text", "",
                List.of(sources), Map.of(), user.resolve("MEMORY.md"), 1, "", -1, null));
    }

    private void image(String sourceId, int ordinal, int color) throws Exception {
        var pixels = new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB); pixels.setRGB(0, 0, color);
        var output = new ByteArrayOutputStream(); ImageIO.write(pixels, "png", output);
        String id = "p" + sourceId + "-" + ordinal;
        Path file = user.resolve("assets/images/" + id + ".png"); Files.write(file, output.toByteArray());
        imageReference(sourceId, ordinal, file);
    }

    private void imageReference(String sourceId, int ordinal, Path file) {
        String id = "p" + sourceId + "-" + ordinal;
        candidates.add(new SearchEvidence(SearchEvidence.Kind.IMAGE, id, "", "Generated image description must not be returned.",
                "description", "user", List.of(sourceId), Map.of(), user.resolve("s.jsonl"), 1, "s", 0,
                new SearchEvidence.ImageReference(id, file, "image/png")));
    }

    private SearchEvidenceContext select(String... ids) {
        var context = new SearchEvidenceContext("u", candidates);
        // 只展示模型实际选中的条目；摘要对应原文不需要额外工具展示或模型选择。
        context.registerShown(candidates.stream().filter(e -> List.of(ids).contains(e.id())).toList());
        context.finish(List.of(ids)); return context;
    }

    private SearchResponse assemble(int topK, String... ids) throws Exception { return assembler.assemble(select(ids), topK); }

    @Test void expandsAllSourcesAcrossMemoryKindsAndKeepsFirstOccurrenceOrder() throws Exception {
        raw("1", "user", "First fact."); raw("5", "user", "Another fact."); raw("7", "user", "Image fact.");
        raw("55", "user", "User statement."); raw("56", "assistant", "Assistant reply.");
        image("7", 1, 0x112233); image("55", 1, 0x445566);
        summary("m35", SearchEvidence.Kind.MEMORY, "5", "1");
        summary("20261016-8", SearchEvidence.Kind.LEDGER, "7", "5");
        summary("m40", SearchEvidence.Kind.MEMORY, "56", "55");
        var context = select("m35", "20261016-8", "7", "1", "m40");
        var response = assembler.assemble(context, 100);
        assertThat(response.data()).extracting(EvidenceCard::id).containsExactly("1", "5", "7", "55", "56");
        assertThat(response.data()).extracting(card -> card.content().size()).containsExactly(1, 1, 2, 2, 1);
        assertThat(response.data().get(0).content().get(0).path("text").asString()).isEqualTo("First fact.");
        assertThat(response.data().get(4).content().get(0).path("text").asString()).isEqualTo("Assistant reply.");
        assertThat(response.data().get(4).createdAt()).isEqualTo(Instant.ofEpochMilli(1740873600056L).toString());
        assertThat(context.selectedIds()).containsExactly("m35", "20261016-8", "7", "1", "m40");
        assertThat(context.shownMemory("55")).isEmpty();
    }

    @Test void sourceOrderIsNumericNotLexicographicAndNotGlobalAcrossSelections() throws Exception {
        raw("1", "user", "Same text."); raw("2", "user", "Same text."); raw("10", "user", "Same text.");
        summary("m1", SearchEvidence.Kind.MEMORY, "10", "2", "1");
        assertThat(assemble(100, "m1").data()).extracting(EvidenceCard::id).containsExactly("1", "2", "10");
        assertThat(assemble(100, "10", "1", "2").data()).extracting(EvidenceCard::id).containsExactly("10", "1", "2");
    }

    @Test void assistantSelectionDoesNotAddItsUserPartnerOrBorrowImages() throws Exception {
        raw("55", "user", "User with a photo."); raw("56", "assistant", "A reply."); image("55", 1, 0x123456);
        summary("m1", SearchEvidence.Kind.MEMORY, "56");
        for (String selected : List.of("56", "m1")) {
            var response = assemble(100, selected);
            assertThat(response.data()).extracting(EvidenceCard::id).containsExactly("56");
            assertThat(response.data().get(0).content()).hasSize(1);
            assertThat(response.data().get(0).content().get(0).path("type").asString()).isEqualTo("text");
        }
    }

    @Test void topKAppliesAfterExpansionAndDoesNotLoadAttachmentsBeyondTheLimit() throws Exception {
        raw("1", "user", "Fact one."); raw("5", "user", "Fact five."); raw("7", "user", "Fact seven.");
        summary("m1", SearchEvidence.Kind.MEMORY, "7", "1", "5");
        imageReference("7", 1, user.resolve("assets/images/nonexistent.png"));
        var response = assemble(2, "m1", "1", "5", "7");
        assertThat(response.data()).extracting(EvidenceCard::id).containsExactly("1", "5");
    }

    @Test void firstReturnedSourceOwnsDuplicateImageEvenIfALaterSourceWasDirectlySelected() throws Exception {
        raw("1", "user", "First user text."); raw("5", "user", "Second user text.");
        image("1", 1, 0x123456); image("5", 1, 0x123456);
        summary("m1", SearchEvidence.Kind.MEMORY, "5");
        var response = assemble(100, "m1", "1");
        assertThat(response.data()).extracting(EvidenceCard::id).containsExactly("5", "1");
        assertThat(response.data().get(0).content()).hasSize(2);
        assertThat(response.data().get(1).content()).hasSize(1);
        assertThat(response.data().get(1).content().get(0).path("text").asString()).isEqualTo("First user text.");
    }

    @Test void duplicateImageOnlyMessageIsOmittedAndLaterSourceFillsTopK() throws Exception {
        raw("1", "user", ""); raw("5", "user", ""); raw("7", "assistant", "Useful reply.");
        image("1", 1, 0x123456); image("5", 1, 0x123456);
        summary("m1", SearchEvidence.Kind.MEMORY, "7", "5", "1");
        var response = assemble(2, "m1");
        assertThat(response.data()).extracting(EvidenceCard::id).containsExactly("1", "7");
        assertThat(response.data().get(0).content()).hasSize(1);
        assertThat(response.data().get(0).content().get(0).path("type").asString()).isEqualTo("image_url");
        assertThat(response.data().get(1).content().get(0).path("text").asString()).isEqualTo("Useful reply.");
    }

    @Test void multipleImagesKeepTheirOriginalOrdinalOrder() throws Exception {
        raw("1", "user", "User text."); image("1", 2, 0x223344); image("1", 1, 0x112233);
        summary("m1", SearchEvidence.Kind.LEDGER, "1");
        var content = assemble(100, "m1").data().get(0).content();
        assertThat(content).hasSize(3);
        assertThat(content.get(0).path("text").asString()).isEqualTo("User text.");
        String first = content.get(1).path("image_url").path("url").asString();
        assertThat(first).isEqualTo("data:image/png;base64," + java.util.Base64.getEncoder().encodeToString(
                Files.readAllBytes(user.resolve("assets/images/p1-1.png"))));
    }

    @Test void validSourcesSurviveMissingOrUnannotatedSummaryReferencesWithoutSummaryFallback() throws Exception {
        raw("1", "user", "Original fact.");
        summary("m1", SearchEvidence.Kind.MEMORY, "999", "1");
        summary("20261016-1", SearchEvidence.Kind.LEDGER);
        var response = assemble(100, "m1", "20261016-1");
        assertThat(response.data()).extracting(EvidenceCard::id).containsExactly("1");
        assertThat(response.data().get(0).content().get(0).path("text").asString()).isEqualTo("Original fact.");
    }

    @Test void nonEmptySelectionWithNoResolvableSourceFailsInsteadOfReturningFakeEmptyResults() {
        summary("m1", SearchEvidence.Kind.MEMORY, "999");
        summary("m2", SearchEvidence.Kind.LEDGER);
        for (String selected : List.of("m1", "m2")) {
            assertThatThrownBy(() -> assemble(100, selected)).isInstanceOfSatisfying(SearchAgentException.class,
                    error -> assertThat(error.getCode()).isEqualTo("SEARCH_SOURCE_TRACE_FAILED"));
        }
    }

    @Test void intentionalEmptySelectionStillReturnsEmpty() throws Exception {
        assertThat(assemble(100).data()).isEmpty();
    }

    @Test void malformedEmptyOriginalIsNotReplacedWithSummaryOrGeneratedDescription() {
        raw("1", "user", ""); summary("m1", SearchEvidence.Kind.MEMORY, "1");
        assertThatThrownBy(() -> assemble(100, "m1")).isInstanceOf(IOException.class);
    }

    @Test void imagePathSafetyIsPreservedAfterTracingSummary() {
        raw("1", "user", "Original."); summary("m1", SearchEvidence.Kind.MEMORY, "1");
        imageReference("1", 1, root.resolve("outside.png"));
        assertThatThrownBy(() -> assemble(100, "m1")).isInstanceOf(IOException.class)
                .hasMessageContaining("outside the user's assets");
    }

    @Test void finalLogListsOnlyActualReturnedSourceIdsAndBoundImagesInResponseOrder() throws Exception {
        raw("1", "user", "First fact."); raw("3", "user", "Photo."); raw("4", "assistant", "Reply.");
        image("3", 1, 0x123456); summary("m35", SearchEvidence.Kind.MEMORY, "4", "3", "1");
        Logger logger = (Logger) LoggerFactory.getLogger(SearchEvidenceAssembler.class);
        var appender = new ListAppender<ILoggingEvent>(); appender.start(); logger.addAppender(appender);
        try {
            var response = assemble(2, "m35", "1");
            assertThat(response.data()).extracting(EvidenceCard::id).containsExactly("1", "3");
            String line = appender.list.stream().map(ILoggingEvent::getFormattedMessage)
                    .filter(text -> text.startsWith("[Search最终响应]")).findFirst().orElseThrow();
            assertThat(line).contains("返回顺序=[1, (3, p3-1)]", "记忆数=2", "去重原始ID次数=1")
                    .doesNotContain("m35", "base64", "First fact", "Photo.");
        } finally { logger.detachAppender(appender); appender.stop(); }
    }

    @Test void nonPositiveTopKIsStillRejected() {
        assertThatThrownBy(() -> assemble(0)).isInstanceOf(IllegalArgumentException.class);
    }
}
