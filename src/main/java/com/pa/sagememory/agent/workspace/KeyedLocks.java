package com.pa.sagememory.agent.workspace;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/** Exact-key locks shared by holders and waiters, removed after the last lease closes. */
public final class KeyedLocks {
    private final ConcurrentHashMap<String, Entry> entries = new ConcurrentHashMap<>();

    public Lease retain(String key) {
        Entry entry = entries.compute(key, (ignored, current) -> {
            Entry selected = current == null ? new Entry() : current;
            selected.references++;
            return selected;
        });
        return new Lease(key, entry);
    }

    private static final class Entry {
        final ReentrantLock lock = new ReentrantLock();
        int references;
    }

    public final class Lease implements AutoCloseable {
        private final String key;
        private final Entry entry;
        private boolean closed;

        private Lease(String key, Entry entry) { this.key = key; this.entry = entry; }

        public ReentrantLock lock() { return entry.lock; }

        /** The caller must unlock before closing; failed lock acquisition must also close the lease. */
        @Override public void close() {
            if (closed) return;
            closed = true;
            entries.compute(key, (ignored, current) -> {
                if (current != entry) throw new IllegalStateException("Lock lease is no longer registered.");
                return --entry.references == 0 ? null : entry;
            });
        }
    }
}
