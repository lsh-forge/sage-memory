package com.pa.sagememory.agent.workspace;

import com.pa.sagememory.agent.AddAgentException;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.workspace.WorkspaceManager;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpStatus;

/** 当前 Add Agent 尝试的台账写入边界；失败先封闭再恢复，不按引用 ID 删除任何记录。 */
public final class AddMemoryAttempt {
    private final WorkspaceManager manager;
    private final Path root;
    private final Runnable checkDeadline;
    private final Map<Path, Snapshot> touched = new LinkedHashMap<>();
    private boolean closed;
    private IOException storageFailure;

    public AddMemoryAttempt(WorkspaceManager manager, Runnable checkDeadline) {
        this.manager = manager;
        this.root = manager.getWorkspace().toAbsolutePath().normalize();
        this.checkDeadline = checkDeadline;
    }

    public synchronized void append(RuntimeContext context, String relative, String entry) throws IOException {
        if (closed) throw new IllegalStateException("This Add attempt is closed; late writes are rejected.");
        checkDeadline.run();
        checkHealthy();
        try {
            if (!relative.matches("memory/\\d{4}-\\d{2}-\\d{2}\\.md")) throw new IOException("Unexpected daily ledger path.");
            Path file = root.resolve(relative).normalize();
            byte[] current = read(file);
            Snapshot snapshot = touched.get(file);
            if (snapshot == null) {
                snapshot = new Snapshot(current);
                touched.put(file, snapshot);
            }
            if (!Arrays.equals(current, snapshot.expected)) throw new IOException("Daily ledger changed outside this Add attempt.");
            byte[] previous = current == null ? new byte[0] : current;
            byte[] addition = entry.getBytes(StandardCharsets.UTF_8);
            snapshot.expected = Arrays.copyOf(previous, previous.length + addition.length);
            System.arraycopy(addition, 0, snapshot.expected, previous.length, addition.length);
            manager.appendUtf8WorkspaceRelative(context, relative, entry);
            if (!Arrays.equals(read(file), snapshot.expected)) throw new IOException("Daily ledger append could not be verified.");
        } catch (IOException error) {
            recordStorageFailure(error);
            throw error;
        } catch (RuntimeException error) {
            recordStorageFailure(new IOException("Daily ledger append failed.", error));
            throw error;
        }
    }

    public synchronized void recordStorageFailure(IOException error) {
        if (storageFailure == null) storageFailure = error;
    }

    public synchronized void checkHealthy() {
        if (storageFailure != null) throw new AddAgentException(HttpStatus.INTERNAL_SERVER_ERROR,
                "ADD_STORAGE_FAILED", "Could not safely read or write the current Add attempt's ledger.", storageFailure);
    }

    public synchronized void seal() throws IOException {
        if (closed) throw new IllegalStateException("This Add attempt is already closed.");
        checkDeadline.run();
        checkHealthy();
        for (var entry : touched.entrySet())
            if (!Arrays.equals(read(entry.getKey()), entry.getValue().expected)) throw new IOException("Daily ledger changed before Add completion.");
        closed = true;
    }

    public synchronized void rollback() throws IOException {
        closed = true;
        // 先检查全部文件；遇到外部修改不能恢复旧快照覆盖用户的新内容。
        for (var entry : touched.entrySet()) {
            byte[] current = read(entry.getKey());
            Snapshot snapshot = entry.getValue();
            if (!Arrays.equals(current, snapshot.expected) && !Arrays.equals(current, snapshot.original))
                throw new IOException("Daily ledger changed unexpectedly; refusing to overwrite it during rollback.");
        }
        for (var entry : touched.entrySet()) {
            Path file = entry.getKey();
            byte[] original = entry.getValue().original;
            if (Arrays.equals(read(file), original)) continue;
            if (original == null) Files.delete(file); // 仅本次新建的、路径已核验的日期台账。
            else {
                Path temporary = Files.createTempFile(file.getParent(), ".add-rollback-", ".tmp");
                try {
                    Files.write(temporary, original);
                    try { Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
                    catch (AtomicMoveNotSupportedException unsupported) { Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING); }
                } finally { Files.deleteIfExists(temporary); }
            }
        }
    }

    private byte[] read(Path file) throws IOException {
        if (!file.startsWith(root)) throw new IOException("Ledger path is outside user workspace.");
        Path cursor = file.getRoot();
        for (Path part : file) {
            cursor = cursor.resolve(part);
            if (Files.isSymbolicLink(cursor)) throw new IOException("Workspace symlinks are not allowed.");
        }
        if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) return null;
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Daily ledger must be a regular file.");
        return Files.readAllBytes(file);
    }

    private static final class Snapshot {
        final byte[] original;
        byte[] expected;
        Snapshot(byte[] original) { this.original = original; this.expected = original; }
    }
}
