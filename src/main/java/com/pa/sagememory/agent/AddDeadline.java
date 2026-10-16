package com.pa.sagememory.agent;

import java.time.Duration;
import org.springframework.http.HttpStatus;

/** 使用单调时钟的整体截止时间；所有小组共享，不因换组或系统时间调整而重新计时。 */
public final class AddDeadline {
    private final long expiresAt;

    public AddDeadline(int seconds) {
        expiresAt = System.nanoTime() + Duration.ofSeconds(seconds).toNanos();
    }

    public Duration remaining() {
        long remaining = expiresAt - System.nanoTime();
        if (remaining <= 0) throw expired();
        return Duration.ofNanos(remaining);
    }

    public Duration limit(int seconds) {
        Duration remaining = remaining();
        Duration configured = Duration.ofSeconds(seconds);
        return remaining.compareTo(configured) < 0 ? remaining : configured;
    }

    public void check() { remaining(); }

    public AddAgentException expired() {
        return new AddAgentException(HttpStatus.GATEWAY_TIMEOUT, "ADD_REQUEST_TIMEOUT",
                "Add request deadline exceeded. Retry with the same request_id to resume completed batches.");
    }
}
