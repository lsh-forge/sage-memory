package com.pa.sagememory.agent.mergeMemoryAgent;

import io.agentscope.harness.agent.memory.MemoryConsolidator;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.Comparator;
import java.util.regex.Pattern;

/** 按 Save 标题中的保存时间筛选未归并记录，不能把整份当天台账重复送入模型。 */
final class PendingMemorySaves {
    private static final Pattern SAVE = Pattern.compile("^## Memory Save — (\\S+) \\| session_id=.+$");
    private final Path directory;
    private final Path state;

    PendingMemorySaves(Path user) throws IOException {
        directory = user.resolve("memory");
        state = directory.resolve(MemoryConsolidator.STATE_FILE);
        check(directory);
        check(state);
    }

    Instant watermark() throws IOException {
        if (!Files.exists(state)) return Instant.EPOCH;
        try { return Instant.parse(Files.readString(state).strip()); }
        catch (RuntimeException invalid) { throw new IOException("Invalid memory consolidation watermark.", invalid); }
    }

    Batch collect(Instant watermark, Instant cutoff) throws IOException {
        StringBuilder result = new StringBuilder();
        int count = 0;
        try (var files = Files.list(directory)) {
            for (Path path : files.filter(p -> p.getFileName().toString().endsWith(".md"))
                    .sorted(Comparator.comparing(p -> p.getFileName().toString())).toList()) {
                check(path);
                if (!Files.isRegularFile(path)) throw new IOException("Ledger must be a regular file.");
                boolean include = false;
                try (var reader = Files.newBufferedReader(path)) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        if (line.startsWith("## ")) {
                            var save = SAVE.matcher(line);
                            include = false;
                            if (save.matches()) {
                                Instant saved;
                                try { saved = Instant.parse(save.group(1)); }
                                catch (RuntimeException invalid) { throw new IOException("Invalid Memory Save timestamp.", invalid); }
                                include = saved.isAfter(watermark) && !saved.isAfter(cutoff);
                                if (include) { count++; result.append('\n'); }
                            } else if (line.startsWith("## Memory Save")) {
                                throw new IOException("Invalid Memory Save header.");
                            }
                        }
                        if (include) result.append(line).append('\n');
                    }
                }
            }
        }
        return new Batch(result.toString().strip(), count);
    }

    void advance(Instant cutoff) throws IOException {
        check(state);
        Path temporary = Files.createTempFile(directory, ".consolidation-", ".tmp");
        try {
            Files.writeString(temporary, cutoff.toString());
            try { Files.move(temporary, state, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException unavailable) {
                Files.move(temporary, state, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally { Files.deleteIfExists(temporary); }
    }

    private static void check(Path path) throws IOException {
        Path cursor = path.toAbsolutePath().getRoot();
        for (Path part : path.toAbsolutePath()) {
            cursor = cursor.resolve(part);
            if (Files.isSymbolicLink(cursor)) throw new IOException("Memory symbolic links are not allowed.");
        }
    }

    record Batch(String content, int count) {}
}
