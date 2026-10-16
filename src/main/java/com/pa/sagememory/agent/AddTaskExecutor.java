package com.pa.sagememory.agent;

import com.pa.sagememory.agent.workspace.AddWorkspaceStore;
import com.pa.sagememory.dto.AddRequest;
import com.pa.sagememory.dto.AddResponse;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.springframework.http.HttpStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 单 JVM 有界任务队列：重复请求共享任务，HTTP 等待者退出不取消后台写入。 */
public final class AddTaskExecutor implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(AddTaskExecutor.class);
    private final AddAgentProperties properties;
    private final ThreadPoolExecutor workers;
    private final ScheduledThreadPoolExecutor timer;
    private final ConcurrentHashMap<String, Job> running = new ConcurrentHashMap<>();

    public AddTaskExecutor(AddAgentProperties properties) {
        properties.validate();
        this.properties = properties;
        AtomicInteger sequence = new AtomicInteger();
        workers = new ThreadPoolExecutor(properties.getWorkerThreads(), properties.getWorkerThreads(),
                0, TimeUnit.SECONDS, new ArrayBlockingQueue<>(properties.getQueueCapacity()), action -> {
                    Thread thread = new Thread(action, "add-worker-" + sequence.incrementAndGet());
                    thread.setDaemon(true);
                    return thread;
                }, new ThreadPoolExecutor.AbortPolicy());
        timer = new ScheduledThreadPoolExecutor(1, action -> {
            Thread thread = new Thread(action, "add-queue-deadlines");
            thread.setDaemon(true);
            return thread;
        });
        timer.setRemoveOnCancelPolicy(true);
        log.info("[Add执行器初始化] 工作线程数={}，排队容量={}，排队时限={}秒，整单时限={}秒",
                properties.getWorkerThreads(), properties.getQueueCapacity(),
                properties.getQueueTimeoutSeconds(), properties.getRequestTimeoutSeconds());
    }

    public CompletableFuture<AddResponse> submit(AddRequest request,
            Function<AddDeadline, AddResponse> action) {
        String key = AddWorkspaceStore.hash(request.userId()) + ":" + AddWorkspaceStore.hash(request.requestId());
        String payload = AddWorkspaceStore.hash(AddWorkspaceStore.json(request));
        Job candidate = new Job(key, payload, AddLogSupport.trace(request), action);
        Job existing = running.putIfAbsent(key, candidate);
        if (existing != null) {
            if (!existing.payload.equals(payload)) {
                log.warn("[Add请求冲突] {}，相同请求标识携带了不同内容", candidate.trace);
                throw new AddAgentException(HttpStatus.CONFLICT,
                        "ADD_REQUEST_CONFLICT", "Running request_id was reused with different source messages or identifiers.");
            }
            log.info("[Add复用任务] {}，已关联正在运行或排队的任务，不重复执行", candidate.trace);
            return existing.result.copy();
        }
        try {
            var expiry = timer.schedule(candidate::expireQueued,
                    candidate.deadline.limit(properties.getQueueTimeoutSeconds()).toNanos(), TimeUnit.NANOSECONDS);
            candidate.result.whenComplete((result, failure) -> expiry.cancel(false));
            workers.execute(candidate);
            log.info("[Add任务提交] {}，活跃线程数={}，当前队列长度={}", candidate.trace,
                    workers.getActiveCount(), workers.getQueue().size());
        } catch (RejectedExecutionException e) {
            candidate.fail(new AddAgentException(HttpStatus.SERVICE_UNAVAILABLE, "ADD_CAPACITY_EXCEEDED",
                    "Add workers or queue are unavailable. Retry later with the same request_id."));
        } catch (RuntimeException e) {
            candidate.fail(e);
        }
        return candidate.result.copy();
    }

    private final class Job implements Runnable {
        private final String key;
        private final String payload;
        private final String trace;
        private final long submittedAt = System.nanoTime();
        private final Function<AddDeadline, AddResponse> action;
        private final AddDeadline deadline = new AddDeadline(properties.getRequestTimeoutSeconds());
        private final AtomicBoolean claimed = new AtomicBoolean();
        private final CompletableFuture<AddResponse> result = new CompletableFuture<>();

        private Job(String key, String payload, String trace, Function<AddDeadline, AddResponse> action) {
            this.key = key;
            this.payload = payload;
            this.trace = trace;
            this.action = action;
        }

        @Override public void run() {
            if (!claimed.compareAndSet(false, true)) return;
            log.info("[Add任务开始] {}，排队耗时={}ms", trace, AddLogSupport.elapsedMillis(submittedAt));
            AddResponse response = null;
            Throwable failure = null;
            try {
                deadline.check();
                response = action.apply(deadline);
                deadline.check();
            } catch (Throwable e) {
                failure = e;
            } finally {
                running.remove(key, this);
            }
            if (failure == null) {
                log.info("[Add任务完成] {}，总耗时={}ms", trace, AddLogSupport.elapsedMillis(submittedAt));
                result.complete(response);
            } else {
                log.warn("[Add任务失败] {}，总耗时={}ms，{}；请保持原 request_id 重试", trace,
                        AddLogSupport.elapsedMillis(submittedAt), AddLogSupport.failure(failure));
                log.debug("[Add异常位置] {}，{}", trace, AddLogSupport.positions(failure));
                result.completeExceptionally(failure);
            }
        }

        private void expireQueued() {
            if (!claimed.compareAndSet(false, true)) return;
            workers.remove(this);
            AddAgentException failure;
            try {
                deadline.check();
                failure = new AddAgentException(HttpStatus.GATEWAY_TIMEOUT, "ADD_QUEUE_TIMEOUT",
                        "Add queue wait exceeded its deadline. No batch was started; retry with the same request_id.");
            } catch (AddAgentException expired) { failure = expired; }
            fail(failure);
        }

        private void fail(Throwable failure) {
            claimed.set(true);
            running.remove(key, this);
            log.warn("[Add任务未启动] {}，等待耗时={}ms，{}，队列长度={}", trace,
                    AddLogSupport.elapsedMillis(submittedAt), AddLogSupport.failure(failure), workers.getQueue().size());
            result.completeExceptionally(failure);
        }
    }

    @Override public void close() {
        log.info("[Add执行器关闭] 活跃线程数={}，队列长度={}", workers.getActiveCount(), workers.getQueue().size());
        workers.shutdownNow();
        timer.shutdownNow();
        running.values().forEach(job -> {
            if (job.claimed.compareAndSet(false, true)) job.fail(new AddAgentException(
                    HttpStatus.SERVICE_UNAVAILABLE, "ADD_SHUTTING_DOWN", "Add service is shutting down; retry later."));
        });
    }
}
