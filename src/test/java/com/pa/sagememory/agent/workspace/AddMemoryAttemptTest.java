package com.pa.sagememory.agent.workspace;

import com.pa.sagememory.agent.AddAgentException;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.filesystem.local.LocalFilesystem;
import io.agentscope.harness.agent.workspace.WorkspaceManager;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AddMemoryAttemptTest {
    @TempDir Path root;
    WorkspaceManager manager;
    final RuntimeContext runtime = RuntimeContext.builder().userId("u").sessionId("s").build();
    final String day = "memory/2026-10-16.md";
    @BeforeEach void setUp() throws IOException {
        Files.createDirectories(root.resolve("memory"));
        manager = new WorkspaceManager(root, new LocalFilesystem(root, true, 10), null);
    }
    @Test void rollbackPreservesPreviousContentByteForByte() throws Exception {
        Path file = root.resolve(day);
        Files.writeString(file, "previous group\r\n");
        byte[] before = Files.readAllBytes(file);
        var attempt = new AddMemoryAttempt(manager, () -> {});
        attempt.append(runtime, day, "first failed save\n");
        attempt.append(runtime, day, "second failed save\n");
        attempt.rollback();
        assertThat(Files.readAllBytes(file)).isEqualTo(before);
        assertThatThrownBy(() -> attempt.append(runtime, day, "late")).isInstanceOf(IllegalStateException.class);
        assertThat(Files.readAllBytes(file)).isEqualTo(before);
    }
    @Test void rollbackRemovesOnlyNewlyCreatedLedger() throws Exception {
        Files.writeString(root.resolve("MEMORY.md"), "long-term unchanged");
        var attempt = new AddMemoryAttempt(manager, () -> {});
        attempt.append(runtime, day, "failed new file");
        attempt.rollback();
        assertThat(root.resolve(day)).doesNotExist();
        assertThat(root.resolve("MEMORY.md")).hasContent("long-term unchanged");
    }
    @Test void rollbackHandlesBothDaysAtMidnight() throws Exception {
        String tomorrow = "memory/2026-10-17.md";
        Files.writeString(root.resolve(day), "old records");
        var attempt = new AddMemoryAttempt(manager, () -> {});
        attempt.append(runtime, day, "before midnight");
        attempt.append(runtime, tomorrow, "after midnight");
        attempt.rollback();
        assertThat(root.resolve(day)).hasContent("old records");
        assertThat(root.resolve(tomorrow)).doesNotExist();
    }
    @Test void sealRejectsLateWritesWithoutLosingCommittedContent() throws Exception {
        var attempt = new AddMemoryAttempt(manager, () -> {});
        attempt.append(runtime, day, "successful save");
        attempt.seal();
        assertThatThrownBy(() -> attempt.append(runtime, day, "late")).isInstanceOf(IllegalStateException.class);
        assertThat(root.resolve(day)).hasContent("successful save");
    }
    @Test void rollbackNeverOverwritesAnExternalEdit() throws Exception {
        Files.writeString(root.resolve(day), "original");
        var attempt = new AddMemoryAttempt(manager, () -> {});
        attempt.append(runtime, day, " own save");
        Files.writeString(root.resolve(day), "external edit");
        assertThatThrownBy(attempt::rollback).isInstanceOf(IOException.class).hasMessageContaining("refusing to overwrite");
        assertThat(root.resolve(day)).hasContent("external edit");
        assertThatThrownBy(() -> attempt.append(runtime, day, "late")).isInstanceOf(IllegalStateException.class);
    }
    @Test void storageFailureCannotBeHiddenByAgentAcknowledgement() throws Exception {
        Files.createDirectory(root.resolve(day));
        var attempt = new AddMemoryAttempt(manager, () -> {});
        assertThatThrownBy(() -> attempt.append(runtime, day, "save")).isInstanceOf(IOException.class);
        assertThatThrownBy(attempt::checkHealthy).isInstanceOfSatisfying(AddAgentException.class,
                e -> assertThat(e.getCode()).isEqualTo("ADD_STORAGE_FAILED"));
        attempt.rollback();
        assertThat(root.resolve(day)).isDirectory();
    }
    @Test void onlyHostDailyLedgerPathsCanBeTouched() {
        var attempt = new AddMemoryAttempt(manager, () -> {});
        assertThatThrownBy(() -> attempt.append(runtime, "../MEMORY.md", "save")).isInstanceOf(IOException.class);
        assertThat(root.resolve("MEMORY.md")).doesNotExist();
    }
    @Test void rollbackWaitsForActiveWriteAndThenBlocksQueuedWrites() throws Exception {
        Files.writeString(root.resolve(day), "previous");
        CountDownLatch writing = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        var attempt = new AddMemoryAttempt(manager, () -> {
            writing.countDown();
            try { if (!release.await(2, TimeUnit.SECONDS)) throw new IllegalStateException("test write gate timed out"); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); }
        });
        var workers = Executors.newFixedThreadPool(2);
        try {
            var write = workers.submit(() -> { attempt.append(runtime, day, " temporary"); return null; });
            assertThat(writing.await(2, TimeUnit.SECONDS)).isTrue();
            var rollback = workers.submit(() -> { attempt.rollback(); return null; });
            release.countDown();
            write.get(2, TimeUnit.SECONDS);
            rollback.get(2, TimeUnit.SECONDS);
            assertThat(root.resolve(day)).hasContent("previous");
            assertThatThrownBy(() -> attempt.append(runtime, day, "late")).isInstanceOf(IllegalStateException.class);
        } finally { release.countDown(); workers.shutdownNow(); }
    }
}
