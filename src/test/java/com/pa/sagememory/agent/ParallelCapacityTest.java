package com.pa.sagememory.agent;

import com.pa.sagememory.agent.multimodal.ImageIngestionService;
import com.pa.sagememory.agent.multimodal.VisionProperties;
import com.pa.sagememory.agent.searchTools.SearchEvidenceAssembler;
import com.pa.sagememory.agent.searchTools.SearchEvidenceStore;
import com.pa.sagememory.agent.workspace.AddWorkspaceStore;
import com.pa.sagememory.dto.AddMessage;
import com.pa.sagememory.dto.AddRequest;
import com.pa.sagememory.dto.SearchRequest;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.ClassPathResource;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.assertThat;

/** Offline barriers prove requests execute together, rather than merely fitting in a queue. */
class ParallelCapacityTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    @TempDir Path directory;

    @Test void sixteenAddsWithIdenticalUserHashCodesReachTheModelTogetherAndPersistSeparately() throws Exception {
        var properties = new AddAgentProperties();
        properties.setWorkspaceRoot(directory.resolve("workspace").toString());
        var store = new AddWorkspaceStore(properties);
        var model = new GatedModel(16);
        List<String> users = new ArrayList<>(List.of(""));
        for (int i = 0; i < 4; i++) users = users.stream().flatMap(s -> java.util.stream.Stream.of(s + "Aa", s + "BB")).toList();
        assertThat(users.stream().map(String::hashCode).distinct()).hasSize(1);
        try (var service = new AddAgentService(properties, model, store)) {
            var jobs = new ArrayList<CompletableFuture<?>>();
            for (String user : users) jobs.add(service.addAsync(new AddRequest("request", user, "session",
                    List.of(new AddMessage("user", null, JSON.valueToTree("Remember this message"))))));
            try { assertThat(model.started.await(15, TimeUnit.SECONDS)).as("All 16 Add workers entered model execution").isTrue(); }
            finally { model.release.tryEmitValue(text("NO_REPLY")); }
            CompletableFuture.allOf(jobs.toArray(CompletableFuture[]::new)).get(20, TimeUnit.SECONDS);
            for (String user : users) {
                assertThat(store.requestState(user, "request").resolve("complete")).exists();
                assertThat(Files.readString(store.userRoot(user).resolve("agents/add-memory/sessions/session.jsonl")))
                        .contains("Remember this message");
            }
        } finally { model.release.tryEmitValue(text("NO_REPLY")); }
    }

    @Test void sixteenSearchesReachTheModelBeforeAnyOneFinishes() throws Exception {
        var add = new AddAgentProperties(); add.setWorkspaceRoot(directory.resolve("workspace").toString());
        var store = new AddWorkspaceStore(add);
        var model = new GatedModel(16);
        try (var service = new SearchAgentService(new SearchAgentProperties(), new SearchEvidenceStore(store),
                new SearchEvidenceAssembler(store), model)) {
            var jobs = new ArrayList<CompletableFuture<?>>();
            for (int i = 0; i < 16; i++) jobs.add(service.searchAsync(new SearchRequest("user-" + i,
                    JSON.valueToTree("Find related history"), 100, List.of())));
            var finish = ChatResponse.builder().content(List.of(new ToolUseBlock("finish", "finish_search",
                    Map.of("ids", List.of()), "{\"ids\":[]}", Map.of()))).finishReason("stop").build();
            try { assertThat(model.started.await(15, TimeUnit.SECONDS)).as("All 16 Search workers entered model execution").isTrue(); }
            finally { model.release.tryEmitValue(finish); }
            CompletableFuture.allOf(jobs.toArray(CompletableFuture[]::new)).get(20, TimeUnit.SECONDS);
        } finally { model.release.tryEmitValue(text("unused")); }
    }

    @Test void sharedVisionPoolRunsThirtyTwoCallsTogether() throws Exception {
        var model = new GatedModel(32);
        try (var service = new ImageIngestionService(new VisionProperties(), model)) {
            var pending = new ArrayList<CompletableFuture<String>>();
            long deadline = System.nanoTime() + Duration.ofSeconds(45).toNanos();
            for (int i = 0; i < 32; i++) pending.add(service.describeQueryImage("Describe", "image/png",
                    new byte[]{1}, deadline, "offline-capacity-" + i).toFuture());
            try { assertThat(model.started.await(15, TimeUnit.SECONDS)).as("Shared vision capacity for Add 16 plus Search 16").isTrue(); }
            finally { model.release.tryEmitValue(text("A visible blue object")); }
            CompletableFuture.allOf(pending.toArray(CompletableFuture[]::new)).get(20, TimeUnit.SECONDS);
            assertThat(pending).allSatisfy(f -> assertThat(f.join()).isEqualTo("A visible blue object"));
        } finally { model.release.tryEmitValue(text("A visible blue object")); }
    }

    @Test void shippedYamlEnablesTheSameParallelCapacityAsDefaults() {
        var yaml = new YamlPropertiesFactoryBean(); yaml.setResources(new ClassPathResource("application.yml"));
        var values = yaml.getObject();
        assertThat(values).isNotNull();
        assertThat(values.getProperty("sage.memory.add.worker-threads")).isEqualTo("16");
        assertThat(values.getProperty("sage.memory.search.worker-threads")).isEqualTo("16");
        assertThat(values.getProperty("sage.memory.add.vision.concurrency")).isEqualTo("32");
    }

    private static ChatResponse text(String value) {
        return ChatResponse.builder().content(List.of(TextBlock.builder().text(value).build())).finishReason("stop").build();
    }

    private static final class GatedModel implements Model {
        final CountDownLatch started;
        final Sinks.One<ChatResponse> release = Sinks.one();
        GatedModel(int count) { started = new CountDownLatch(count); }
        @Override public String getModelName() { return "offline-parallel-capacity"; }
        @Override public Flux<ChatResponse> stream(List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
            return Flux.defer(() -> { started.countDown(); return release.asMono().flux(); });
        }
    }
}
