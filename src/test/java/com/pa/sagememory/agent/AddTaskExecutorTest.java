package com.pa.sagememory.agent;

import com.pa.sagememory.dto.AddMessage;
import com.pa.sagememory.dto.AddRequest;
import com.pa.sagememory.dto.AddResponse;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AddTaskExecutorTest {
    @Test
    void inFlightDuplicatesJoinOneJobAndChangedPayloadIsRejected() throws Exception {
        AddAgentProperties properties = properties();
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger invocations = new AtomicInteger();
        try (var tasks = new AddTaskExecutor(properties)) {
            AddRequest request = request("same", "one");
            var first = tasks.submit(request, deadline -> {
                invocations.incrementAndGet(); started.countDown(); await(release); return success(request);
            });
            assertThat(started.await(3, TimeUnit.SECONDS)).isTrue();
            var second = tasks.submit(request, deadline -> { throw new AssertionError("Must reuse first job"); });
            assertThatThrownBy(() -> tasks.submit(request("same", "different"), deadline -> success(request)))
                    .isInstanceOfSatisfying(AddAgentException.class,
                            error -> assertThat(error.getCode()).isEqualTo("ADD_REQUEST_CONFLICT"));
            // Cancelling one waiter does not cancel the common worker or another waiter.
            first.cancel(false);
            release.countDown();
            assertThat(second.get(3, TimeUnit.SECONDS).success()).isTrue();
            assertThat(invocations.get()).isEqualTo(1);
        } finally { release.countDown(); }
    }

    @Test
    void fullQueueRejectsImmediatelyAndDoesNotStartRejectedTask() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try (var tasks = new AddTaskExecutor(properties())) {
            var first = tasks.submit(request("first", "one"), deadline -> {
                started.countDown(); await(release); return success(request("first", "one"));
            });
            assertThat(started.await(3, TimeUnit.SECONDS)).isTrue();
            var second = tasks.submit(request("second", "two"), deadline -> success(request("second", "two")));
            var third = tasks.submit(request("third", "three"), deadline -> { throw new AssertionError("Rejected task started"); });
            assertThatThrownBy(third::join).hasCauseInstanceOf(AddAgentException.class)
                    .satisfies(error -> assertThat(((AddAgentException) error.getCause()).getCode()).isEqualTo("ADD_CAPACITY_EXCEEDED"));
            release.countDown();
            assertThat(first.get(3, TimeUnit.SECONDS).success()).isTrue();
            assertThat(second.get(3, TimeUnit.SECONDS).success()).isTrue();
        } finally { release.countDown(); }
    }

    @Test
    void queuedTaskExpiresWithoutExecutingAndCanBeRetried() throws Exception {
        AddAgentProperties properties = properties();
        properties.setQueueTimeoutSeconds(1);
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger queuedInvocations = new AtomicInteger();
        try (var tasks = new AddTaskExecutor(properties)) {
            var first = tasks.submit(request("first", "one"), deadline -> {
                started.countDown(); await(release); return success(request("first", "one"));
            });
            assertThat(started.await(3, TimeUnit.SECONDS)).isTrue();
            AddRequest request = request("queued", "two");
            var queued = tasks.submit(request, deadline -> { queuedInvocations.incrementAndGet(); return success(request); });
            assertThatThrownBy(() -> queued.get(3, TimeUnit.SECONDS)).hasCauseInstanceOf(AddAgentException.class)
                    .satisfies(error -> assertThat(((AddAgentException) error.getCause()).getCode()).isEqualTo("ADD_QUEUE_TIMEOUT"));
            assertThat(queuedInvocations.get()).isZero();
            release.countDown();
            first.get(3, TimeUnit.SECONDS);
            assertThat(tasks.submit(request, deadline -> success(request)).get(3, TimeUnit.SECONDS).success()).isTrue();
        } finally { release.countDown(); }
    }

    @Test
    void overallDeadlineAlsoBoundsQueueWaitWhenShorterThanQueueTimeout() throws Exception {
        AddAgentProperties properties = properties();
        properties.setRequestTimeoutSeconds(1);
        properties.setQueueTimeoutSeconds(5);
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try (var tasks = new AddTaskExecutor(properties)) {
            var first = tasks.submit(request("blocking", "one"), deadline -> {
                started.countDown(); await(release); return success(request("blocking", "one"));
            });
            assertThat(started.await(3, TimeUnit.SECONDS)).isTrue();
            var queued = tasks.submit(request("queued", "two"), deadline -> { throw new AssertionError("Expired job ran"); });
            assertThatThrownBy(() -> queued.get(3, TimeUnit.SECONDS)).hasCauseInstanceOf(AddAgentException.class)
                    .satisfies(error -> assertThat(((AddAgentException) error.getCause()).getCode()).isEqualTo("ADD_REQUEST_TIMEOUT"));
            release.countDown();
            assertThatThrownBy(() -> first.get(3, TimeUnit.SECONDS)).hasCauseInstanceOf(AddAgentException.class);
        } finally { release.countDown(); }
    }

    private AddAgentProperties properties() {
        AddAgentProperties properties = new AddAgentProperties();
        properties.setWorkerThreads(1);
        properties.setQueueCapacity(1);
        return properties;
    }

    private AddRequest request(String id, String text) {
        return new AddRequest(id, "user", "session", List.of(new AddMessage("user", null,
                JsonMapper.builder().build().valueToTree(text))));
    }

    private AddResponse success(AddRequest request) {
        return new AddResponse(true, request.requestId(), request.userId(), request.sessionId());
    }

    private void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) throw new AssertionError("Timed out waiting for test release");
        } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); }
    }
}
