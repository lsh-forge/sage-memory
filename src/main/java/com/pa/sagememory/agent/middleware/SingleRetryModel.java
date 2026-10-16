package com.pa.sagememory.agent.middleware;

import com.pa.sagememory.agent.AddLogSupport;
import io.agentscope.core.message.Msg;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.ExecutionConfig;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ModelHttpException;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.model.transport.HttpTransportException;
import java.io.IOException;
import java.time.Duration;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** 单轮模型调用最多尝试两次；不重放 Agent 或工具，不让失败尝试的增量进入 Agent 历史。 */
public final class SingleRetryModel implements Model {
    private static final Logger log = LoggerFactory.getLogger(SingleRetryModel.class);
    private final Model delegate;
    private final Duration attemptTimeout;
    private final Supplier<Duration> remaining;
    private final String trace;
    private final AgentLogRedactor redactor;
    private final Duration retryDelay;

    public SingleRetryModel(Model delegate, Duration attemptTimeout, Supplier<Duration> remaining,
            String trace, AgentLogRedactor redactor) {
        this(delegate, attemptTimeout, remaining, trace, redactor, Duration.ofSeconds(2));
    }

    // 离线测试可缩短退避时间；生产固定只重试一次、退避两秒，不增加新的配置项。
    SingleRetryModel(Model delegate, Duration attemptTimeout, Supplier<Duration> remaining,
            String trace, AgentLogRedactor redactor, Duration retryDelay) {
        this.delegate = Objects.requireNonNull(delegate);
        this.attemptTimeout = Objects.requireNonNull(attemptTimeout);
        this.remaining = Objects.requireNonNull(remaining);
        this.trace = Objects.requireNonNull(trace);
        this.redactor = Objects.requireNonNull(redactor);
        this.retryDelay = Objects.requireNonNull(retryDelay);
    }

    @Override public String getModelName() { return delegate.getModelName(); }

    @Override public Flux<ChatResponse> stream(List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
        return attempt(List.copyOf(messages), List.copyOf(tools), options, 1);
    }

    private Flux<ChatResponse> attempt(List<Msg> messages, List<ToolSchema> tools, GenerateOptions options, int number) {
        return Flux.defer(() -> {
            Duration budget = remaining.get();
            Duration timeout = budget.compareTo(attemptTimeout) < 0 ? budget : attemptTimeout;
            if (timeout.isZero() || timeout.isNegative()) return Flux.error(new TimeoutException("Model call has no remaining time."));
            // 宿主负责完整生成计时与一次重试，SDK 仅保留单次配置，避免重试层叠加。
            // 外层每次订阅都会刷新预算，不能复用第一次调用时的剩余时间。
            GenerateOptions effective = GenerateOptions.mergeOptions(GenerateOptions.builder().executionConfig(
                    ExecutionConfig.builder().timeout(timeout).maxAttempts(1).build()).build(), options);
            log.info("[模型单轮尝试开始] {}，model={}，attempt={}/2，本次上限={}ms",
                    trace, AddLogSupport.value(getModelName()), number, timeout.toMillis());
            return Flux.defer(() -> delegate.stream(messages, tools, effective))
                    .collectList().timeout(timeout).flatMapMany(Flux::fromIterable)
                    .doOnComplete(() -> {
                        if (number == 2) log.info("[模型单轮重试成功] {}，attempt=2/2；继续当前Agent轮次，不重跑已完成工具", trace);
                    });
        }).onErrorResume(error -> {
            if (number == 2) {
                log.warn("[模型单轮重试耗尽] {}，attempt=2/2，异常类型={}；以下为脱敏异常\n{}",
                        trace, error.getClass().getSimpleName(), redactor.exception(error));
                return Flux.error(new ExhaustedException(error));
            }
            if (!retryable(error)) return Flux.error(error);
            Duration budget = remaining.get(); // 整单/小组已过期时直接透出对应错误，不再排队重试。
            if (budget.compareTo(retryDelay) <= 0) {
                log.warn("[模型单轮重试跳过] {}，剩余={}ms，不足退避时间；不延长小组或整单时限", trace, budget.toMillis());
                return Flux.error(new ExhaustedException(error));
            }
            log.warn("[模型单轮重试] {}，第1次失败，退避={}ms，准备attempt=2/2，异常类型={}；以下为脱敏异常\n{}",
                    trace, retryDelay.toMillis(), error.getClass().getSimpleName(), redactor.exception(error));
            return Mono.delay(retryDelay).thenMany(attempt(messages, tools, options, 2));
        });
    }

    private static boolean retryable(Throwable error) {
        if (Thread.currentThread().isInterrupted()) return false;
        var seen = Collections.newSetFromMap(new IdentityHashMap<Throwable, Boolean>());
        boolean retry = false;
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (!seen.add(cause)) return false;
            if (cause instanceof InterruptedException || cause instanceof CancellationException
                    || reactor.core.Exceptions.isCancel(cause) || cause instanceof ExhaustedException) return false;
            // 某些 Provider 异常实现 ModelHttpException 但没有状态码；继续检查其网络异常原因链。
            if (cause instanceof ModelHttpException http && http.getStatusCode() != null) {
                if (!http.isRetryableHttpStatus()) return false;
                retry = true;
            } else if (cause instanceof HttpTransportException transport) {
                if (!transport.isRetryable()) return false;
                retry = true;
            } else if (cause instanceof IOException || cause instanceof TimeoutException) retry = true;
        }
        return retry;
    }

    /** 标记单轮最多两次已经耗尽；Agent 级兜底由宿主独立限制次数，原始异常保留在原因链。 */
    public static final class ExhaustedException extends RuntimeException {
        private ExhaustedException(Throwable cause) { super("Model call retry budget exhausted.", cause); }
    }
}
