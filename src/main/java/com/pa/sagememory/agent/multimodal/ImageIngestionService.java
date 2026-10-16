package com.pa.sagememory.agent.multimodal;

import com.pa.sagememory.agent.AddAgentException;
import com.pa.sagememory.agent.AddDeadline;
import com.pa.sagememory.agent.AddLogSupport;
import com.pa.sagememory.agent.middleware.SearchAgentMiddleware;
import com.pa.sagememory.agent.middleware.AgentDebugLogProperties;
import com.pa.sagememory.agent.middleware.AgentLogRedactor;
import com.pa.sagememory.agent.workspace.AddWorkspaceStore;
import com.pa.sagememory.agent.workspace.ImageIdentity;
import com.pa.sagememory.dto.AddMessage;
import com.pa.sagememory.dto.AddRequest;
import io.agentscope.core.message.Base64Source;
import io.agentscope.core.message.DataBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.ExecutionConfig;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.extensions.model.openai.OpenAIChatModel;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Signal;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** 图片预处理：独立线程池并行直调模型，汇总后按消息位置回填，不创建 Agent 或注册工具。 */
@Service
public class ImageIngestionService implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(ImageIngestionService.class);
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final int MAX_IMAGE_BYTES = 10 * 1024 * 1024;
    private static final int MAX_REQUEST_BYTES = 30 * 1024 * 1024;
    private final VisionProperties properties;
    private final Supplier<Model> suppliedModel;
    private final AgentDebugLogProperties debugLog;
    private final AgentLogRedactor redactor;
    private final AtomicLong queryCalls = new AtomicLong();
    private final Scheduler workers;
    private volatile Model configuredModel;

    @Autowired
    public ImageIngestionService(VisionProperties properties, @Qualifier("visionModel") ObjectProvider<Model> models,
            AgentDebugLogProperties debugLog, AgentLogRedactor redactor) {
        // 可选的视觉专用 bean；不能误用文本 Agent 注入的普通 Model bean。
        this(properties, models::getIfAvailable, debugLog, redactor);
    }

    /** 注入离线模型验证并发与位置映射，不请求真实付费 API。 */
    public ImageIngestionService(VisionProperties properties, Model model) {
        this(properties, model, new AgentDebugLogProperties(), new AgentLogRedactor());
    }

    /** 离线注入视觉模型及日志组件，不请求真实模型接口。 */
    public ImageIngestionService(VisionProperties properties, Model model,
            AgentDebugLogProperties debugLog, AgentLogRedactor redactor) {
        this(properties, () -> model, debugLog, redactor);
    }

    private ImageIngestionService(VisionProperties properties, Supplier<Model> model,
            AgentDebugLogProperties debugLog, AgentLogRedactor redactor) {
        properties.validate();
        this.properties = properties;
        this.suppliedModel = model;
        this.debugLog = debugLog;
        this.redactor = redactor;
        // Add 与 Search 共享视觉并发上限，不因请求数量增加而无限创建线程。
        workers = Schedulers.newBoundedElastic(properties.getConcurrency(), 64, "memory-vision");
    }

    /** 在任何落盘和推理之前全量检查；比赛限制按解码后的实际字节数计算。 */
    public void validate(AddRequest request) {
        inputs(request);
    }

    public boolean hasImages(AddRequest request) {
        return request.messages().stream().anyMatch(message -> message.content().isArray()
                && message.content().valueStream().anyMatch(part -> "image_url".equals(part.path("type").asString())));
    }

    public void ensureConfigured(AddRequest request) {
        if (hasImages(request)) model();
    }

    /** Search 只读视觉调用：无文件、缓存或来源编号；排队时间同样计入整单预算。 */
    public Mono<String> describeQueryImage(String question, String mediaType, byte[] bytes, long expiresAt, String trace) {
        return Mono.fromCallable(() -> {
            Duration remaining = SearchAgentMiddleware.remaining(expiresAt);
            Duration timeout = remaining.compareTo(Duration.ofSeconds(properties.getTimeoutSeconds())) < 0
                    ? remaining : Duration.ofSeconds(properties.getTimeoutSeconds());
            String description = describeImage(model(), VisionPrompts.QUERY_SYSTEM_PROMPT,
                    "Search question:\n" + question, mediaType, bytes, timeout,
                    trace + "，vision_call_id=" + queryCalls.incrementAndGet(), true);
            SearchAgentMiddleware.remaining(expiresAt);
            return description;
        }).subscribeOn(workers);
    }

    public int concurrency() { return properties.getConcurrency(); }

    /** 只用于分组预算预检查，图片字节不进入字符预算；纯文本消息保持原始结构。 */
    public List<AddMessage> textMessages(AddRequest request) {
        return request.messages().stream().map(message -> containsImage(message.content())
                ? new AddMessage(message.role(), message.timestamp(), JSON.valueToTree(text(message.content())))
                : message).toList();
    }

    public void enrich(AddRequest request, List<Map<String, Object>> sources, AddWorkspaceStore store,
            AddWorkspaceStore.Workspace workspace, AddDeadline deadline) throws IOException {
        List<ImageInput> inputs = inputs(request);
        if (inputs.isEmpty()) return;
        Model model = model();
        long startedAt = System.nanoTime();
        String trace = AddLogSupport.trace(request);
        log.info("[Add图片预处理开始] {}，图片数={}，并发线程上限={}，视觉模型={}；直接调用模型，不注册工具",
                trace, inputs.size(), properties.getConcurrency(), AddLogSupport.value(model.getModelName()));
        List<ImageResult> results;
        try {
            // flatMap 允许完成顺序不同。严禁在遍历消息时逐张 block；只有整批汇总时等待一次。
            List<Signal<ImageResult>> attempts = Flux.fromIterable(inputs)
                    .flatMap(image -> Mono.fromCallable(() -> processImage(
                            request, image, ImageIdentity.id(sources.get(image.messageIndex()).get("source_id").toString(), image.ordinal()),
                            model, store, workspace, deadline)).subscribeOn(workers).materialize(),
                            properties.getConcurrency())
                    .collectList().timeout(deadline.remaining()).block();
            // 某张图失败不取消其他已开始调用；收齐结果后才判整批失败，成功描述保留供重试。
            // 避免 fail-fast 取消阻塞 HTTP 调用产生额外中断及 onErrorDropped。
            results = new ArrayList<>();
            for (Signal<ImageResult> attempt : attempts) {
                if (attempt.isOnError()) throw reactor.core.Exceptions.propagate(attempt.getThrowable());
                if (attempt.hasValue()) results.add(attempt.get());
            }
        } catch (RuntimeException error) {
            deadline.check();
            log.warn("[Add图片预处理失败] {}，耗时={}ms，{}；不开始台账治理、不标记整单成功，保留已成功识图检查点",
                    trace, AddLogSupport.elapsedMillis(startedAt), AddLogSupport.failure(error));
            for (Throwable cause = error; cause != null; cause = cause.getCause()) {
                if (cause instanceof IOException storage) throw storage;
                if (cause instanceof AddAgentException known) throw known;
                if (cause instanceof RejectedExecutionException) throw new AddAgentException(
                        HttpStatus.SERVICE_UNAVAILABLE, "ADD_VISION_BUSY", "Vision workers are busy; retry with the same request_id.");
            }
            throw new AddAgentException(HttpStatus.BAD_GATEWAY, "ADD_VISION_FAILED",
                    "Image understanding failed; no image was silently omitted. Retry with the same request_id.", error);
        }
        deadline.check();
        Map<String, ImageResult> byPosition = new LinkedHashMap<>();
        for (ImageResult result : results) byPosition.put(result.input().key(), result);
        if (byPosition.size() != inputs.size()) throw new IOException("Image results were not fully collected.");

        for (int messageIndex = 0; messageIndex < request.messages().size(); messageIndex++) {
            AddMessage message = request.messages().get(messageIndex);
            if (!containsImage(message.content())) continue;
            List<Object> original = new ArrayList<>();
            List<Map<String, Object>> images = new ArrayList<>();
            StringBuilder enriched = new StringBuilder();
            // 有序内容中的文字和图片描述仍按原位置呈现，text-image-text 的先后关系不改变。
            for (int partIndex = 0; partIndex < message.content().size(); partIndex++) {
                JsonNode part = message.content().get(partIndex);
                if ("text".equals(part.path("type").asString())) {
                    original.add(part);
                    append(enriched, ("user".equals(message.role()) ? "User message: " : "Assistant message: ")
                            + part.path("text").asString());
                } else {
                    ImageResult result = byPosition.get(key(messageIndex, partIndex));
                    if (result == null) throw new IOException("Image result position is missing.");
                    String imageId = result.imageId();
                    Map<String, Object> reference = Map.of("type", "image_reference", "image_id", imageId,
                            "path", result.path(), "media_type", result.input().mediaType());
                    original.add(reference);
                    Map<String, Object> evidence = new LinkedHashMap<>(reference);
                    evidence.put("content_index", partIndex);
                    evidence.put("image_index", result.input().ordinal());
                    evidence.put("description", result.description());
                    evidence.put("generated_by", "vision_model");
                    evidence.put("model", result.model());
                    images.add(evidence);
                    append(enriched, "[Attached image " + result.input().ordinal() + " description]: " + result.description());
                }
            }
            Map<String, Object> source = sources.get(messageIndex);
            source.put("original_content", original);
            source.put("images", images);
            source.put("content", JSON.valueToTree(enriched.toString()));
        }
        log.info("[Add图片回填完成] {}，图片数={}，含图消息数={}，耗时={}ms；按原消息和内容块位置回填，原图引用仅存会话",
                trace, inputs.size(), request.messages().stream().filter(m -> containsImage(m.content())).count(),
                AddLogSupport.elapsedMillis(startedAt));
    }

    /** Agent 看增强文本即可；图片 ID、路径和原文备份只留在 session JSONL。 */
    public static List<Map<String, Object>> agentSources(List<Map<String, Object>> sources) {
        return sources.stream().map(source -> {
            Map<String, Object> readable = new LinkedHashMap<>(source);
            readable.remove("original_content");
            readable.remove("images");
            return readable;
        }).toList();
    }

    private ImageResult processImage(AddRequest request, ImageInput image, String imageId, Model model,
            AddWorkspaceStore store, AddWorkspaceStore.Workspace workspace, AddDeadline deadline) throws IOException {
        deadline.check();
        String sha = AddWorkspaceStore.hash(image.bytes());
        String path = store.saveImage(workspace, imageId, image.bytes(), image.mediaType());
        Map<String, String> cached = store.loadImageCaption(workspace, image.key(), sha);
        if (cached != null) {
            log.info("[Add图片描述复用] {}，message_index={}，附图={}；不重复调用视觉模型",
                    AddLogSupport.trace(request), image.messageIndex(), image.ordinal());
            return new ImageResult(image, imageId, path, cached.get("description"), cached.get("model"));
        }
        Duration timeout = deadline.limit(properties.getTimeoutSeconds());
        String trace = AddLogSupport.trace(request);
        long startedAt = System.nanoTime();
        boolean qwen = model.getModelName() != null && model.getModelName().toLowerCase(Locale.ROOT).startsWith("qwen");
        log.info("[Add视觉模型调用开始] {}，message_index={}，content_index={}，附图={}，图片字节数={}，实际预算={}ms，思考模式={}",
                trace, image.messageIndex(), image.partIndex(), image.ordinal(), image.bytes().length, timeout.toMillis(),
                qwen ? "已关闭（enable_thinking=false）" : "不传 Qwen 专用参数");
        String context = "Accompanying message (role=" + request.messages().get(image.messageIndex()).role()
                + ", attached image " + image.ordinal() + "):\n"
                + text(request.messages().get(image.messageIndex()).content());
        try {
            String description = describeImage(model, VisionPrompts.SYSTEM_PROMPT, context,
                    image.mediaType(), image.bytes(), timeout,
                    trace + "，message_index=" + image.messageIndex() + "，content_index=" + image.partIndex()
                            + "，附图=" + image.ordinal(), false);
            deadline.check();
            store.saveImageCaption(workspace, image.key(), sha, description, model.getModelName());
            log.info("[Add视觉模型调用完成] {}，message_index={}，附图={}，耗时={}ms，描述字符数={}；不打印图片或描述正文",
                    trace, image.messageIndex(), image.ordinal(), AddLogSupport.elapsedMillis(startedAt), description.length());
            return new ImageResult(image, imageId, path, description, model.getModelName());
        } catch (RuntimeException error) {
            deadline.check();
            log.warn("[Add视觉模型调用失败] {}，message_index={}，附图={}，耗时={}ms，{}",
                    trace, image.messageIndex(), image.ordinal(), AddLogSupport.elapsedMillis(startedAt), AddLogSupport.failure(error));
            if (error instanceof AddAgentException known) throw known;
            for (Throwable cause = error; cause != null; cause = cause.getCause()) {
                if (cause instanceof TimeoutException) throw new AddAgentException(HttpStatus.GATEWAY_TIMEOUT,
                        "ADD_VISION_TIMEOUT", "Image understanding exceeded its deadline; retry with the same request_id.", error);
            }
            throw new AddAgentException(HttpStatus.BAD_GATEWAY, "ADD_VISION_FAILED",
                    "Image understanding failed; retry with the same request_id.", error);
        }
    }

    /** 两个阶段共用直调模型逻辑，存储与回填由各阶段负责。 */
    private String describeImage(Model model, String system, String context, String mediaType, byte[] bytes, Duration timeout,
            String trace, boolean query) {
        List<Msg> messages = List.of(
                Msg.builder().role(MsgRole.SYSTEM).textContent(system).build(),
                Msg.builder().role(MsgRole.USER).content(
                        TextBlock.builder().text(context).build(),
                        DataBlock.builder().source(Base64Source.builder().mediaType(mediaType)
                                .data(Base64.getEncoder().encodeToString(bytes)).build()).build()).build());
        var optionsBuilder = GenerateOptions.builder().stream(false).temperature(0.0)
                .maxTokens(properties.getMaxOutputTokens())
                .executionConfig(ExecutionConfig.builder().timeout(timeout).maxAttempts(1).build());
        // 图片描述无需深度思考；通过请求正文关闭 Qwen 默认思考，仅影响本次视觉调用。
        // enable_thinking 非 OpenAI 标准字段，切回 gpt-4o-mini 时不发送，避免未知参数错误。
        if (model.getModelName() != null && model.getModelName().toLowerCase(Locale.ROOT).startsWith("qwen"))
            optionsBuilder.additionalBodyParam("enable_thinking", false);
        GenerateOptions options = optionsBuilder.build();
        // 只记录 Search 视觉调用的实际文字输入；不序列化携带 Base64 的 messages 或认证配置。
        boolean logQuery = query && debugLog.isEnabled();
        if (logQuery) safeDebugLog(() -> log.info("[Search视觉调试-模型输入] {}，model={}，图片类型={}，图片字节数={}，调用上限={}ms\n系统提示词：\n{}\n用户提示词：\n{}\n图片附件：已传入模型，Base64不打印",
                redactor.text(trace), redactor.text(model.getModelName()), mediaType, bytes.length, timeout.toMillis(),
                redactor.text(system), redactor.text(context)));
        // 超时限制整次生成，而不是每个流式片段；本调用无工具、无 Agent 历史。
        Output output = Flux.defer(() -> model.stream(messages, List.of(), options))
                .reduce(new Output(), Output::append).timeout(timeout)
                .doOnError(error -> {
                    if (logQuery) safeDebugLog(() -> log.warn("[Search视觉调试-模型异常] {}，model={}；以下为脱敏异常\n{}",
                            redactor.text(trace), redactor.text(model.getModelName()), redactor.exception(error)));
                }).block();
        // 在压成单行和校验输出之前记录完整文本，length 等异常结束原因也可直接观察。
        if (logQuery) safeDebugLog(() -> log.info("[Search视觉调试-模型返回] {}，model={}，finish_reason={}，含非文本块={}，原始文本字符数={}\n模型原始文本：\n{}",
                redactor.text(trace), redactor.text(model.getModelName()), output == null ? "无返回" : redactor.text(output.finishReason),
                output != null && output.invalidBlock, output == null ? 0 : output.text.length(),
                output == null ? "[无模型输出]" : redactor.text(output.text.toString())));
        String description = output == null ? "" : output.text.toString().strip().replaceAll("\\s*\\R\\s*", " ");
        List<String> failures = new ArrayList<>();
        if (output == null || output.responseCount == 0) failures.add("no_model_response");
        if (description.isBlank()) failures.add("empty_description");
        if (description.contains("```")) failures.add("code_block_in_description");
        if (output != null) {
            if (output.invalidBlock) failures.add("non_text_content_block");
            if (!(output.finishReason.isEmpty() || output.finishReason.equals("stop")
                    || output.finishReason.equals("end_turn"))) failures.add("unexpected_finish_reason");
        }
        if (!failures.isEmpty()) {
            // 失败诊断不依赖调试开关；仅记录元数据，不泄露描述正文、图片或模型配置中的凭据。
            safeDebugLog(() -> log.warn("[{}视觉输出校验失败] {}，model={}，失败原因={}，finish_reason={}，响应片段数={}，原始文本字符数={}，描述字符数={}，含非文本块={}，含代码块={}，max_output_tokens={}，调用预算={}ms；不打印描述正文或图片Base64",
                    query ? "Search" : "Add", redactor.text(trace), redactor.text(model.getModelName()), failures,
                    output == null ? "无返回" : redactor.text(output.finishReason),
                    output == null ? 0 : output.responseCount, output == null ? 0 : output.text.length(),
                    description.length(), output != null && output.invalidBlock, description.contains("```"),
                    properties.getMaxOutputTokens(), timeout.toMillis()));
            throw new AddAgentException(HttpStatus.BAD_GATEWAY, "ADD_VISION_INVALID_OUTPUT",
                    "Vision model must return a complete non-empty image description.");
        }
        if (Thread.currentThread().isInterrupted()) throw new AddAgentException(HttpStatus.SERVICE_UNAVAILABLE,
                "ADD_VISION_INTERRUPTED", "Image understanding was interrupted.");
        return description;
    }

    private static void safeDebugLog(Runnable action) {
        try { action.run(); }
        catch (RuntimeException failure) {
            log.warn("[Search视觉调试日志跳过] 异常类型={}；日志格式化失败不影响模型调用", failure.getClass().getSimpleName());
        }
    }

    private Model model() {
        Model supplied = suppliedModel.get();
        if (supplied != null) return supplied;
        if (properties.getApiKey() == null || properties.getApiKey().isBlank()) throw new AddAgentException(
                HttpStatus.SERVICE_UNAVAILABLE, "ADD_VISION_MODEL_NOT_CONFIGURED",
                "Configure sage.memory.add.vision.api-key / VISION_API_KEY / OPENAI_API_KEY for image Add/Search.");
        try {
            URI uri = URI.create(properties.getBaseUrl());
            if (!("https".equals(uri.getScheme()) || "http".equals(uri.getScheme())) || uri.getHost() == null)
                throw new IllegalArgumentException("Invalid vision base URL.");
        } catch (RuntimeException invalid) {
            throw new AddAgentException(HttpStatus.SERVICE_UNAVAILABLE, "ADD_VISION_CONFIGURATION_INVALID",
                    "Vision base-url must be an absolute HTTP(S) URL.", invalid);
        }
        if (configuredModel == null) {
            synchronized (this) {
                if (configuredModel == null) {
                    configuredModel = OpenAIChatModel.builder().apiKey(properties.getApiKey())
                            .baseUrl(properties.getBaseUrl()).modelName(properties.getModelName()).stream(false).build();
                    log.info("[视觉模型初始化] model={}，Add/Search共享并发线程上限={}；不打印密钥或完整地址",
                            AddLogSupport.value(properties.getModelName()), properties.getConcurrency());
                }
            }
        }
        return configuredModel;
    }

    private static List<ImageInput> inputs(AddRequest request) {
        List<ImageInput> images = new ArrayList<>();
        long totalBytes = 0;
        for (int index = 0; index < request.messages().size(); index++) {
            JsonNode content = request.messages().get(index).content();
            if (!content.isArray()) continue;
            int ordinal = 0;
            for (int partIndex = 0; partIndex < content.size(); partIndex++) {
                JsonNode part = content.get(partIndex);
                if (!"image_url".equals(part.path("type").asString())) continue;
                String uri = part.path("image_url").path("url").asString();
                int comma = uri.indexOf(',');
                String encoded = uri.substring(comma + 1);
                if (encoded.length() > ((MAX_IMAGE_BYTES + 2) / 3) * 4) throw tooLarge();
                byte[] bytes;
                try {
                    bytes = Base64.getDecoder().decode(encoded);
                } catch (IllegalArgumentException malformed) {
                    throw invalidImage();
                }
                if (bytes.length > MAX_IMAGE_BYTES || (totalBytes += bytes.length) > MAX_REQUEST_BYTES) throw tooLarge();
                String mediaType = uri.substring(5, uri.indexOf(';'));
                if (!signatureMatches(bytes, mediaType)) throw invalidImage();
                images.add(new ImageInput(index, partIndex, ++ordinal, mediaType, bytes));
            }
        }
        return images;
    }

    private static boolean signatureMatches(byte[] bytes, String type) {
        return switch (type) {
            case "image/png" -> bytes.length >= 8 && bytes[0] == (byte) 0x89 && bytes[1] == 'P'
                    && bytes[2] == 'N' && bytes[3] == 'G' && bytes[4] == 13 && bytes[5] == 10 && bytes[6] == 26 && bytes[7] == 10;
            case "image/jpeg" -> bytes.length >= 3 && bytes[0] == (byte) 0xff && bytes[1] == (byte) 0xd8 && bytes[2] == (byte) 0xff;
            case "image/webp" -> bytes.length >= 12 && bytes[0] == 'R' && bytes[1] == 'I' && bytes[2] == 'F'
                    && bytes[3] == 'F' && bytes[8] == 'W' && bytes[9] == 'E' && bytes[10] == 'B' && bytes[11] == 'P';
            default -> false;
        };
    }

    private static AddAgentException invalidImage() {
        return new AddAgentException(HttpStatus.UNPROCESSABLE_CONTENT, "ADD_INVALID_IMAGE",
                "Image must contain valid Base64 bytes matching its JPEG, PNG or WebP media type.");
    }

    private static AddAgentException tooLarge() {
        return new AddAgentException(HttpStatus.CONTENT_TOO_LARGE, "ADD_IMAGE_TOO_LARGE",
                "Decoded images must not exceed 10 MiB each or 30 MiB per Add request.");
    }

    private static boolean containsImage(JsonNode content) {
        return content.isArray() && content.valueStream().anyMatch(part -> "image_url".equals(part.path("type").asString()));
    }

    private static String text(JsonNode content) {
        if (content.isString()) return content.asString();
        StringBuilder text = new StringBuilder();
        for (JsonNode part : content) if ("text".equals(part.path("type").asString())) append(text, part.path("text").asString());
        return text.toString();
    }

    private static void append(StringBuilder target, String value) {
        if (!target.isEmpty()) target.append('\n');
        target.append(value);
    }

    private static String key(int messageIndex, int partIndex) { return "m%05d-p%05d".formatted(messageIndex, partIndex); }
    private record ImageInput(int messageIndex, int partIndex, int ordinal, String mediaType, byte[] bytes) {
        String key() { return ImageIngestionService.key(messageIndex, partIndex); }
    }
    private record ImageResult(ImageInput input, String imageId, String path, String description, String model) {}

    private static final class Output {
        private final StringBuilder text = new StringBuilder();
        private String finishReason = "";
        private boolean invalidBlock;
        private int responseCount;
        Output append(ChatResponse response) {
            responseCount++;
            if (response.getContent() != null) response.getContent().forEach(block -> {
                if (block instanceof TextBlock textBlock) {
                    if (textBlock.getText() != null) text.append(textBlock.getText());
                } else invalidBlock = true;
            });
            if (response.getFinishReason() != null && !response.getFinishReason().isBlank()) finishReason = response.getFinishReason();
            return this;
        }
    }

    @Override @PreDestroy public void close() { workers.dispose(); }
}
