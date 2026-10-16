package com.pa.sagememory.agent.multimodal;

import com.pa.sagememory.agent.AddAgentException;
import com.pa.sagememory.agent.AddLogSupport;
import com.pa.sagememory.agent.SearchAgentException;
import com.pa.sagememory.agent.middleware.SearchAgentMiddleware;
import com.pa.sagememory.dto.SearchRequest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import tools.jackson.databind.JsonNode;

/** 查询图片仅在请求内转成文字，不分配证据 ID、不写入任何记忆文件。 */
@Service
public final class SearchImageQueryService {
    private static final Logger log = LoggerFactory.getLogger(SearchImageQueryService.class);
    private static final int MAX_IMAGE_BYTES = 10 * 1024 * 1024;
    private static final int MAX_REQUEST_BYTES = 30 * 1024 * 1024;
    private final ImageIngestionService vision;

    public SearchImageQueryService(ImageIngestionService vision) { this.vision = vision; }

    public String prepare(SearchRequest request, long expiresAt) {
        SearchAgentMiddleware.remaining(expiresAt);
        if (request.query().isString()) return request.query().asString();
        List<Image> images = images(request.query());
        if (images.isEmpty()) return questionText(request.query());
        // 手动构造的离线 Search 服务需要显式注入视觉服务；不能误用文本模型或偷偷访问真实 API。
        if (vision == null) throw new SearchAgentException(HttpStatus.SERVICE_UNAVAILABLE,
                "SEARCH_VISION_MODEL_NOT_CONFIGURED", "A vision service is required for image queries.");
        String question = questionText(request.query());
        String user = AddLogSupport.value(request.userId());
        long startedAt = System.nanoTime();
        log.info("[Search查询图片处理开始] user_id={}，图片数={}，共享并发上限={}；仅生成查询描述，不落盘",
                user, images.size(), vision.concurrency());
        try {
            // 并行完成顺序可能不同，以原内容块位置映射，回填时仍按原 query 顺序。
            Map<Integer, String> descriptions = Flux.fromIterable(images)
                    .flatMap(image -> vision.describeQueryImage(question, image.mediaType(), image.bytes(), expiresAt,
                                    "user_id=" + user + "，附图=" + image.ordinal() + "，content_index=" + image.partIndex())
                            .map(description -> {
                                log.info("[Search查询附图描述完成] user_id={}，附图={}，content_index={}，描述字符数={}",
                                        user, image.ordinal(), image.partIndex(), description.length());
                                return Map.entry(image.partIndex(), description);
                            }), vision.concurrency())
                    .collectMap(Map.Entry::getKey, Map.Entry::getValue)
                    .timeout(SearchAgentMiddleware.remaining(expiresAt)).block();
            SearchAgentMiddleware.remaining(expiresAt);
            List<String> parts = new ArrayList<>();
            int ordinal = 0;
            for (int index = 0; index < request.query().size(); index++) {
                JsonNode part = request.query().get(index);
                if ("text".equals(part.path("type").asString())) parts.add(part.path("text").asString());
                else parts.add("[Query image " + (++ordinal) + " description]: " + descriptions.get(index));
            }
            log.info("[Search查询图片回填完成] user_id={}，图片数={}，耗时={}ms；检索 Agent 仅接收文字描述",
                    user, images.size(), AddLogSupport.elapsedMillis(startedAt));
            return String.join("\n", parts);
        } catch (RuntimeException error) {
            log.warn("[Search查询图片处理失败] user_id={}，异常类型={}，耗时={}ms；内部原因链保留，完整脱敏堆栈见接口错误日志",
                    user, error.getClass().getSimpleName(), AddLogSupport.elapsedMillis(startedAt));
            for (Throwable cause = error; cause != null; cause = cause.getCause()) {
                if (cause instanceof SearchAgentException known) throw known;
                if (cause instanceof AddAgentException known) throw new SearchAgentException(known.getStatus(),
                        known.getCode().replace("ADD_", "SEARCH_"), "Query image understanding failed; check vision configuration or retry.", error);
                if (cause instanceof TimeoutException) throw new SearchAgentException(HttpStatus.GATEWAY_TIMEOUT,
                        "SEARCH_VISION_TIMEOUT", "Query image understanding exceeded its deadline.", error);
                if (cause instanceof RejectedExecutionException) throw new SearchAgentException(HttpStatus.SERVICE_UNAVAILABLE,
                        "SEARCH_VISION_BUSY", "Vision workers are busy; retry later.", error);
            }
            throw new SearchAgentException(HttpStatus.BAD_GATEWAY, "SEARCH_VISION_FAILED", "Query image understanding failed.", error);
        }
    }

    /** 视觉模型只收到原问题文字与当前图片，不附带选项或历史记忆。 */
    private static String questionText(JsonNode query) {
        return query.valueStream().filter(part -> "text".equals(part.path("type").asString()))
                .map(part -> part.path("text").asString()).collect(Collectors.joining("\n"));
    }

    private static List<Image> images(JsonNode query) {
        List<Image> images = new ArrayList<>();
        long totalBytes = 0;
        for (int index = 0; index < query.size(); index++) {
            JsonNode part = query.get(index);
            if (!"image_url".equals(part.path("type").asString())) continue;
            String uri = part.path("image_url").path("url").asString();
            String type = uri.substring(5, uri.indexOf(';'));
            String data = uri.substring(uri.indexOf(',') + 1);
            if (data.length() > ((MAX_IMAGE_BYTES + 2) / 3) * 4) throw tooLarge();
            byte[] bytes;
            try { bytes = Base64.getDecoder().decode(data); }
            catch (IllegalArgumentException invalid) { throw invalidImage(); }
            if (bytes.length > MAX_IMAGE_BYTES || (totalBytes += bytes.length) > MAX_REQUEST_BYTES) throw tooLarge();
            if (!signatureMatches(bytes, type)) throw invalidImage();
            images.add(new Image(index, images.size() + 1, type, bytes));
        }
        return images;
    }

    private static boolean signatureMatches(byte[] bytes, String type) {
        return switch (type) {
            case "image/png" -> bytes.length >= 8 && bytes[0] == (byte) 0x89 && bytes[1] == 'P' && bytes[2] == 'N'
                    && bytes[3] == 'G' && bytes[4] == 13 && bytes[5] == 10 && bytes[6] == 26 && bytes[7] == 10;
            case "image/jpeg" -> bytes.length >= 3 && bytes[0] == (byte) 0xff && bytes[1] == (byte) 0xd8 && bytes[2] == (byte) 0xff;
            case "image/webp" -> bytes.length >= 12 && bytes[0] == 'R' && bytes[1] == 'I' && bytes[2] == 'F' && bytes[3] == 'F'
                    && bytes[8] == 'W' && bytes[9] == 'E' && bytes[10] == 'B' && bytes[11] == 'P';
            default -> false;
        };
    }
    private static SearchAgentException invalidImage() {
        return new SearchAgentException(HttpStatus.UNPROCESSABLE_CONTENT, "SEARCH_INVALID_IMAGE", "Query image bytes must match their JPEG, PNG or WebP media type.");
    }
    private static SearchAgentException tooLarge() {
        return new SearchAgentException(HttpStatus.CONTENT_TOO_LARGE, "SEARCH_IMAGE_TOO_LARGE", "Query images must not exceed 10 MiB each or 30 MiB in total.");
    }
    private record Image(int partIndex, int ordinal, String mediaType, byte[] bytes) {}
}
