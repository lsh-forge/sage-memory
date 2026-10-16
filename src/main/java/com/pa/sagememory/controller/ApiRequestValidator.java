package com.pa.sagememory.controller;

import com.pa.sagememory.dto.AddMessage;
import com.pa.sagememory.dto.AddRequest;
import com.pa.sagememory.dto.SearchRequest;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;

/** 只校验接口外层与内容结构，不改写消息顺序、角色、时间戳或正文。 */
@Component
public class ApiRequestValidator {
    private static final Set<String> ROLES = Set.of("user", "assistant");
    private static final Set<String> IMAGE_PREFIXES = Set.of(
            "data:image/jpeg;base64,", "data:image/png;base64,", "data:image/webp;base64,");

    public void validate(AddRequest request) {
        // 全量结构校验通过后交给业务层，再检查图片解码字节数与视觉模型配置。
        if (request == null) {
            throw invalid("Request body is required.");
        }
        requireText(request.requestId(), "request_id");
        requireText(request.userId(), "user_id");
        requireText(request.sessionId(), "session_id");
        if (request.messages() == null || request.messages().isEmpty()) {
            throw invalid("messages must contain at least one message.");
        }
        for (int index = 0; index < request.messages().size(); index++) {
            AddMessage message = request.messages().get(index);
            String field = "messages[" + index + "]";
            if (message == null) {
                throw invalid(field + " must not be null.");
            }
            if (message.role() == null || !ROLES.contains(message.role())) {
                throw invalid(field + ".role must be user or assistant.");
            }
            requireContent(message.content(), field + ".content");
        }
    }

    public void validate(SearchRequest request) {
        if (request == null) {
            throw invalid("Request body is required.");
        }
        requireText(request.userId(), "user_id");
        requireContent(request.query(), "query");
        if (request.topK() == null || request.topK() <= 0) {
            throw invalid("top_k must be a positive integer.");
        }
    }

    private void requireContent(JsonNode content, String field) {
        if (content != null && content.isString()) {
            requireText(content.asString(), field);
            return;
        }
        if (content == null || !content.isArray() || content.isEmpty()) {
            throw invalid(field + " must be a non-empty string or ordered content array.");
        }
        for (int index = 0; index < content.size(); index++) {
            JsonNode part = content.get(index);
            String partField = field + "[" + index + "]";
            if (part == null || !part.isObject()) {
                throw invalid(partField + " must be an object.");
            }
            String type = requireStringNode(part.get("type"), partField + ".type");
            if ("text".equals(type)) {
                requireStringNode(part.get("text"), partField + ".text");
            } else if ("image_url".equals(type)) {
                JsonNode image = part.get("image_url");
                if (image == null || !image.isObject()) {
                    throw invalid(partField + ".image_url must be an object.");
                }
                String url = requireStringNode(image.get("url"), partField + ".image_url.url");
                boolean supported = IMAGE_PREFIXES.stream()
                        .anyMatch(prefix -> url.startsWith(prefix) && url.length() > prefix.length());
                if (!supported) {
                    throw invalid(partField + ".image_url.url must be an inline JPEG, PNG or WebP Base64 URI.");
                }
            } else {
                throw invalid(partField + ".type must be text or image_url.");
            }
        }
    }

    private String requireStringNode(JsonNode node, String field) {
        if (node == null || !node.isString()) {
            throw invalid(field + " must be a non-empty string.");
        }
        String value = node.asString();
        requireText(value, field);
        return value;
    }

    private void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw invalid(field + " must not be blank.");
        }
    }

    private ResponseStatusException invalid(String message) {
        return new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT, message);
    }
}
