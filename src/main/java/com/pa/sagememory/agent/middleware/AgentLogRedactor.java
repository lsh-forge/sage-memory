package com.pa.sagememory.agent.middleware;

import com.pa.sagememory.agent.AddAgentProperties;
import com.pa.sagememory.agent.multimodal.VisionProperties;
import com.pa.sagememory.controller.ApiKeyProperties;
import io.agentscope.core.message.*;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** 日志专用视图，不修改模型输入；绝不序列化客户端、请求头或 GenerateOptions。 */
@Component
public final class AgentLogRedactor {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String MASK = "[认证信息已隐藏]";
    private static final Pattern INLINE_BASE64 = Pattern.compile("(?i)data:[^\\s;,\"']+;base64,[A-Za-z0-9+/=_-]+");
    private static final Pattern BINARY_FIELD = Pattern.compile(
            "(?i)(\\b(?:image[_-]?base64|image[_-]?data|base64)\\b[\"']?\\s*[:=]\\s*)(?:\"[^\"]*\"|'[^']*'|[^\\s,;}{\\]\\r\\n]+)");
    private static final Pattern LONG_BASE64 = Pattern.compile(
            "(?<![A-Za-z0-9+/=_-])[A-Za-z0-9+/]{128,}={0,2}(?![A-Za-z0-9+/=_-])");
    private static final Pattern AUTH = Pattern.compile("(?i)\\b(?:Bearer|Basic|Token)\\s+[A-Za-z0-9._~+/=-]+");
    private static final Pattern COOKIE_HEADER = Pattern.compile("(?im)(\\b(?:set-cookie|cookie)\\s*:\\s*)[^\\r\\n]+");
    private static final Pattern API_KEY = Pattern.compile("\\bsk-[A-Za-z0-9_-]+");
    private static final Pattern JWT = Pattern.compile("\\beyJ[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\b");
    private static final Pattern URL_AUTH = Pattern.compile("(?i)(https?://)[^/@\\s]+:[^/@\\s]+@");
    private static final Pattern KEY_VALUE = Pattern.compile(
            "(?i)(\\b(?:x[ _-]?api[ _-]?key|api[ _-]?key|authorization|proxy[_-]?authorization|access[_-]?token|refresh[_-]?token|client[_-]?secret|secret|password|credential|token|cookie|set[_-]?cookie)\\b[\"']?\\s*[:=]\\s*)"
                    + "(?:\"(?:\\\\.|[^\"\\\\])*\"|'[^']*'|[^\\s,;}{\\]\\r\\n]+)");
    private final Supplier<List<String>> secrets;

    @Autowired
    public AgentLogRedactor(AddAgentProperties add, VisionProperties vision, ApiKeyProperties api) {
        this(() -> Arrays.asList(add.getApiKey(), vision.getApiKey(), api.getApiKey()));
    }
    public AgentLogRedactor(AddAgentProperties add, VisionProperties vision) {
        this(() -> Arrays.asList(add.getApiKey(), vision.getApiKey()));
    }
    public AgentLogRedactor() { this(List::of); }
    public AgentLogRedactor(Supplier<List<String>> secrets) { this.secrets = secrets; }

    public String text(String value) {
        if (value == null) return "";
        // 工具可能把 JSON 放在 TextBlock 中，而不是作为对象返回；同样检查其中的图片数据和认证字段。
        String trimmed = value.strip();
        if ((trimmed.startsWith("{") && trimmed.endsWith("}")) || (trimmed.startsWith("[") && trimmed.endsWith("]"))) {
            try { return plainText(JSON.writeValueAsString(view(JSON.readTree(trimmed)))); }
            catch (RuntimeException invalidJson) { /* 普通文字继续执行保守脱敏，不输出异常或解析片段。 */ }
        }
        return plainText(value);
    }

    /** 完整保留异常类型、错误消息、原因链及 suppressed 堆栈，再统一脱敏；不直接把 Throwable 交给日志框架。 */
    public String exception(Throwable error) {
        if (error == null) return "[无异常信息]";
        StringWriter trace = new StringWriter();
        error.printStackTrace(new PrintWriter(trace));
        return text(trace.toString());
    }

    private String plainText(String value) {
        String safe = value;
        for (String secret : secrets.get()) if (secret != null && !secret.isBlank()) safe = safe.replace(secret, MASK);
        // 对完整文本处理后才输出；密钥或 Base64 跨流式分块也不会逐段泄露。
        safe = INLINE_BASE64.matcher(safe).replaceAll("[多模态Base64已隐藏]");
        safe = BINARY_FIELD.matcher(safe).replaceAll("$1[多模态Base64已隐藏]");
        safe = LONG_BASE64.matcher(safe).replaceAll("[疑似Base64长串已隐藏]");
        safe = AUTH.matcher(safe).replaceAll(MASK);
        safe = COOKIE_HEADER.matcher(safe).replaceAll("$1" + MASK);
        safe = API_KEY.matcher(safe).replaceAll(MASK);
        safe = JWT.matcher(safe).replaceAll(MASK);
        safe = URL_AUTH.matcher(safe).replaceAll("$1[认证信息已隐藏]@");
        return KEY_VALUE.matcher(safe).replaceAll("$1" + MASK);
    }

    public String value(Object value) {
        try { return text(JSON.writeValueAsString(view(JSON.valueToTree(value)))); }
        catch (RuntimeException failure) { return "[内容无法安全格式化]"; }
    }

    private Object view(JsonNode node) {
        if (node == null || node.isNull()) return null;
        if (node.isString()) return text(node.asString());
        if (node.isArray()) {
            List<Object> values = new ArrayList<>();
            for (JsonNode child : node) values.add(view(child));
            return values;
        }
        if (node.isObject()) {
            Map<String, Object> result = new LinkedHashMap<>();
            String type = node.path("type").asString();
            String media = node.path("media_type").asString(node.path("mediaType").asString());
            boolean binary = "base64".equalsIgnoreCase(type) || media.startsWith("image/");
            for (var property : node.properties()) {
                String key = property.getKey();
                String normalized = key.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
                boolean sensitive = List.of("apikey", "xapikey", "authorization", "proxyauthorization", "password", "secret",
                        "clientsecret", "token", "accesstoken", "refreshtoken", "credential", "credentials", "cookie", "setcookie").contains(normalized);
                boolean binaryField = List.of("base64", "imagebase64", "imagedata").contains(normalized)
                        || binary && "data".equals(normalized);
                result.put(plainText(key), sensitive ? MASK : binaryField
                        ? "[Base64已隐藏，编码字符数=" + property.getValue().asString().length() + "]" : view(property.getValue()));
            }
            return result;
        }
        return node;
    }

    public String blocks(List<ContentBlock> blocks) {
        if (blocks == null) return "";
        List<String> rendered = new ArrayList<>();
        for (ContentBlock block : blocks) {
            if (block instanceof TextBlock text) rendered.add(text(text.getText()));
            else if (block instanceof ThinkingBlock thinking) rendered.add("[模型思考]\n" + text(thinking.getThinking()));
            else if (block instanceof ImageBlock image) rendered.add(media(image.getSource()));
            else if (block instanceof DataBlock data) {
                rendered.add(media(data.getSource()));
            } else if (block instanceof ToolUseBlock tool) rendered.add("[工具调用] " + text(tool.getName()) + "\n参数：" + arguments(tool));
            else if (block instanceof ToolResultBlock result) rendered.add("[工具返回] " + text(result.getName()) + "\n" + blocks(result.getOutput()));
            else rendered.add("[内容块类型=" + block.getClass().getSimpleName() + "，正文未展开]");
        }
        return String.join("\n", rendered);
    }

    private String media(Source source) {
        if (source instanceof Base64Source encoded) return "[图片/多模态内容，类型="
                + text(encoded.getMediaType()) + "，Base64编码字符数=" + (encoded.getData() == null ? 0 : encoded.getData().length()) + "，数据已隐藏]";
        return "[多模态内容，来源类型=" + (source == null ? "未知" : source.getClass().getSimpleName()) + "，数据已隐藏]";
    }

    public String arguments(ToolUseBlock call) {
        if (call.getContent() == null || call.getContent().isBlank()) return value(call.getInput());
        return jsonArguments(call.getContent());
    }

    public String jsonArguments(String json) {
        try { return value(JSON.readTree(json)); }
        catch (RuntimeException invalidJson) { return "[工具参数 JSON 不完整，正文未打印]"; }
    }
}
