package com.pa.sagememory.agent.workspace;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class KeyedLocksTest {
    @Test void hashCollisionsDoNotShareLocksAndUnusedEntriesAreReclaimed() {
        var locks = new KeyedLocks();
        ReentrantLock original;
        assertThat("Aa".hashCode()).isEqualTo("BB".hashCode());
        try (var first = locks.retain("Aa"); var same = locks.retain("Aa"); var other = locks.retain("BB")) {
            original = first.lock();
            assertThat(same.lock()).isSameAs(original);
            assertThat(other.lock()).isNotSameAs(original);
        }
        try (var fresh = locks.retain("Aa")) { assertThat(fresh.lock()).isNotSameAs(original); }
    }

    @Test void waitingLeaseKeepsTheSameLockWhenOriginalOwnerLeaves() throws Exception {
        var locks = new KeyedLocks();
        var owner = locks.retain("user"); owner.lock().lock();
        var waiting = new CountDownLatch(1);
        var acquired = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var next = CompletableFuture.runAsync(() -> {
            try (var lease = locks.retain("user")) {
                waiting.countDown(); lease.lock().lock();
                try {
                    acquired.countDown();
                    if (!release.await(5, TimeUnit.SECONDS)) throw new AssertionError("Test release timed out");
                } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); }
                finally { lease.lock().unlock(); }
            }
        });
        try {
            assertThat(waiting.await(5, TimeUnit.SECONDS)).isTrue();
            owner.lock().unlock(); owner.close();
            assertThat(acquired.await(5, TimeUnit.SECONDS)).isTrue();
            try (var third = locks.retain("user")) {
                assertThat(third.lock().tryLock(20, TimeUnit.MILLISECONDS)).isFalse();
            }
        } finally {
            if (owner.lock().isHeldByCurrentThread()) owner.lock().unlock();
            owner.close(); release.countDown();
        }
        next.get(5, TimeUnit.SECONDS);
    }
}
