package com.pa.sagememory.agent.middleware;

import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ModelHttpException;
import io.agentscope.core.model.ModelUtils;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.model.transport.HttpTransportException;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntFunction;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Signal;

import static org.assertj.core.api.Assertions.assertThat;

/** 真实 Reactor 调度与 SDK 执行配置的离线验证，不请求任何模型接口。 */
class SingleRetryModelTest {
    private static final ChatResponse OK = ChatResponse.builder()
            .content(List.of(TextBlock.builder().text("complete").build())).finishReason("stop").build();
    private static final GenerateOptions OPTIONS = GenerateOptions.builder().build();

    static final class ScriptModel implements Model {
        final AtomicInteger calls = new AtomicInteger();
        final IntFunction<Flux<ChatResponse>> script;
        ScriptModel(IntFunction<Flux<ChatResponse>> script) { this.script = script; }
        @Override public String getModelName() { return "offline-single-retry"; }
        @Override public Flux<ChatResponse> stream(List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
            assertThat(options.getExecutionConfig().getMaxAttempts()).isEqualTo(1);
            return script.apply(calls.incrementAndGet());
        }
    }

    private Model wrap(Model model) {
        return wrap(model, Duration.ofSeconds(1), () -> Duration.ofSeconds(5), Duration.ZERO);
    }
    private Model wrap(Model model, Duration timeout, Supplier<Duration> remaining, Duration delay) {
        return new SingleRetryModel(model, timeout, remaining, "agent=offline", new AgentLogRedactor(), delay);
    }
    private List<ChatResponse> success(Model model) {
        return model.stream(List.of(), List.of(), OPTIONS).collectList().block(Duration.ofSeconds(5));
    }
    private Throwable failure(Model model) {
        return model.stream(List.of(), List.of(), OPTIONS).materialize().filter(Signal::isOnError)
                .map(Signal::getThrowable).blockLast(Duration.ofSeconds(5));
    }

    @Test void successDoesNotRetry() {
        var model = new ScriptModel(n -> Flux.just(OK));
        assertThat(success(wrap(model))).containsExactly(OK);
        assertThat(model.calls.get()).isEqualTo(1);
    }

    @Test void transientHttpAndNetworkFailuresRetryOnce() {
        for (Throwable error : List.of(new HttpTransportException("busy", 429, ""),
                new HttpTransportException("server", 503, ""), new IOException("connection reset"))) {
            var model = new ScriptModel(n -> n == 1 ? Flux.error(error) : Flux.just(OK));
            assertThat(success(wrap(model))).containsExactly(OK);
            assertThat(model.calls.get()).isEqualTo(2);
        }
    }

    @Test void providerWithoutHttpStatusStillChecksNetworkCause() {
        class ProviderFailure extends RuntimeException implements ModelHttpException {
            ProviderFailure() { super(new IOException("connection reset")); }
            @Override public Integer getStatusCode() { return null; }
        }
        var model = new ScriptModel(n -> n == 1 ? Flux.error(new ProviderFailure()) : Flux.just(OK));
        assertThat(success(wrap(model))).containsExactly(OK);
        assertThat(model.calls.get()).isEqualTo(2);
    }

    @Test void clientErrorsDoNotRetryEvenWithIoCause() {
        for (int status : List.of(400, 401, 403, 404, 422)) {
            var error = new HttpTransportException("invalid request", status, "", new IOException("nested"));
            var model = new ScriptModel(n -> Flux.error(error));
            assertThat(failure(wrap(model))).isSameAs(error);
            assertThat(model.calls.get()).isEqualTo(1);
        }
    }

    @Test void cancellationAndInterruptionDoNotRetry() {
        for (Throwable error : List.of(new CancellationException("cancel"),
                new HttpTransportException("interrupted", new InterruptedException("stop")),
                new IOException("wrapped", new CancellationException("stop")))) {
            var model = new ScriptModel(n -> Flux.error(error));
            assertThat(failure(wrap(model))).isSameAs(error);
            assertThat(model.calls.get()).isEqualTo(1);
        }
    }

    @Test void unknownProgrammingFailureDoesNotRetry() {
        var error = new IllegalStateException("bug");
        var model = new ScriptModel(n -> Flux.error(error));
        assertThat(failure(wrap(model))).isSameAs(error);
        assertThat(model.calls.get()).isEqualTo(1);
    }

    @Test void persistentFailureHasExactlyTwoAttempts() {
        var error = new IOException("still offline");
        var model = new ScriptModel(n -> Flux.error(error));
        assertThat(failure(wrap(model))).isInstanceOf(SingleRetryModel.ExhaustedException.class).hasCause(error);
        assertThat(model.calls.get()).isEqualTo(2);
    }

    @Test void failedPartialOutputNeverLeaksIntoSuccessfulResponse() {
        var model = new ScriptModel(n -> n == 1 ? Flux.concat(Flux.just(OK), Flux.error(new IOException("after partial"))) : Flux.just(OK));
        assertThat(success(wrap(model))).containsExactly(OK);
        assertThat(model.calls.get()).isEqualTo(2);
    }

    @Test void wholeAttemptTimeoutRetriesAndDropsContinuousPartialChunks() {
        var model = new ScriptModel(n -> n == 1 ? Flux.interval(Duration.ofMillis(5)).map(i -> OK) : Flux.just(OK));
        assertThat(success(wrap(model, Duration.ofMillis(100), () -> Duration.ofSeconds(5), Duration.ZERO))).containsExactly(OK);
        assertThat(model.calls.get()).isEqualTo(2);
    }

    @Test void expiredRequestStopsBeforeAnotherAttempt() {
        var reads = new AtomicInteger();
        var expired = new IllegalStateException("request deadline expired");
        var model = new ScriptModel(n -> Flux.error(new IOException("network")));
        assertThat(failure(wrap(model, Duration.ofSeconds(1), () -> {
            if (reads.incrementAndGet() > 1) throw expired;
            return Duration.ofSeconds(5);
        }, Duration.ZERO))).isSameAs(expired);
        assertThat(model.calls.get()).isEqualTo(1);
    }

    @Test void insufficientBudgetDoesNotWaitOrRetry() {
        var model = new ScriptModel(n -> Flux.error(new IOException("network")));
        assertThat(failure(wrap(model, Duration.ofSeconds(1), () -> Duration.ofMillis(500), Duration.ofSeconds(1))))
                .isInstanceOf(SingleRetryModel.ExhaustedException.class);
        assertThat(model.calls.get()).isEqualTo(1);
    }

    @Test void cancellationDuringBackoffPreventsRetry() {
        var model = new ScriptModel(n -> Flux.error(new IOException("network")));
        var subscription = wrap(model, Duration.ofSeconds(1), () -> Duration.ofSeconds(5), Duration.ofMillis(100))
                .stream(List.of(), List.of(), OPTIONS).subscribe();
        subscription.dispose();
        Mono.delay(Duration.ofMillis(200)).block();
        assertThat(model.calls.get()).isEqualTo(1);
    }

    @Test void sdkRetriesAreDisabledToAvoidMultiplyingAttempts() {
        var requests = new AtomicInteger();
        Model sdkModel = new Model() {
            @Override public String getModelName() { return "offline-sdk-model"; }
            @Override public Flux<ChatResponse> stream(List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
                return ModelUtils.applyTimeoutAndRetry(Flux.defer(() -> {
                    requests.incrementAndGet();
                    return Flux.error(new IOException("offline"));
                }), options, OPTIONS, getModelName(), "offline");
            }
        };
        assertThat(failure(wrap(sdkModel))).isInstanceOf(SingleRetryModel.ExhaustedException.class);
        assertThat(requests.get()).isEqualTo(2);
    }
}
