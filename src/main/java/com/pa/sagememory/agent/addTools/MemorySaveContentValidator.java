package com.pa.sagememory.agent.addTools;

import com.pa.sagememory.agent.workspace.MemoryProvenanceNormalizer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Save 写入前的格式和来源身份校验，不判断原话是否在语义上支持事实。 */
final class MemorySaveContentValidator {
    private static final Pattern BULLET = Pattern.compile("^[-*+]\\s+(.+)$");
    private static final Pattern REFERENCE = Pattern.compile(
            "\\(source_ids?: ([1-9][0-9]*(?:,[1-9][0-9]*)*)(?:, source_date: (\\d{4}-\\d{2}-\\d{2}|unknown|未知))?\\)", Pattern.CASE_INSENSITIVE);

    private MemorySaveContentValidator() {}

    static String prepare(String content, Map<String, String> sources) {
        if (content == null || content.isBlank()) throw new IllegalArgumentException("content is required.");
        List<String> output = new ArrayList<>();
        for (String line : MemoryProvenanceNormalizer.normalize(content).strip().split("\\R")) {
            if (line.isBlank()) continue;
            int number = output.size() + 1;
            String value = line.strip();
            Matcher bullet = BULLET.matcher(value);
            String body;
            if (bullet.matches()) body = bullet.group(1);
            else {
                // 只容错省略列表符号；标题、代码块和空列表项仍不是可保存的事实。
                if (value.matches("^(?:#{1,6}(?:\\s.*|$)|`{3,}.*|~{3,}.*|[-*+])$"))
                    throw invalid(number, "use one Markdown bullet per line. Example: - xxxxxxx (source_ids: 1, source_date: 2026-10-15)");
                body = value;
            }
            Matcher references = REFERENCE.matcher(body);
            Map<String, LinkedHashSet<String>> byDate = new LinkedHashMap<>();
            int firstReference = -1;
            int end = -1;
            while (references.find()) {
                if (firstReference < 0) firstReference = references.start();
                else if (!body.substring(end, references.start()).isBlank())
                    throw invalid(number, "put all source annotations at the end of the bullet. Example: - xxxxxxx (source_ids: 1, source_date: 2026-10-15)");
                end = references.end();
                String suppliedDate = references.group(2);
                for (String id : references.group(1).split(",")) {
                    if (!sources.containsKey(id)) throw invalid(number, "source ID " + id + " is unavailable in the current session/batch.");
                    String date = sources.get(id);
                    // 日期只从原始消息取值；模型省略或填写 unknown 时可补齐，明确错误日期必须纠正。
                    if (suppliedDate != null && !suppliedDate.equalsIgnoreCase("unknown") && !suppliedDate.equals("未知")
                            && !suppliedDate.equals(date))
                        throw invalid(number, "source_date for ID " + id + " must be " + date + "; omit it if unknown.");
                    byDate.computeIfAbsent(date, ignored -> new LinkedHashSet<>()).add(id);
                }
            }
            if (firstReference < 0) throw invalid(number,
                    "missing valid source_ids. Use original numeric IDs, e.g. (source_ids: 61). Never add prefixes.");
            String suffix = body.substring(end).strip();
            if (!(suffix.isEmpty() || suffix.equals(".") || suffix.equals("。")))
                throw invalid(number, "put source annotations at the end of the bullet. Example: - xxxxxxx (source_ids: 1, source_date: 2026-10-15)");
            String fact = body.substring(0, firstReference).strip();
            if (fact.isEmpty()) throw invalid(number, "a non-empty fact is required before source_ids.");
            StringBuilder saved = new StringBuilder("- ").append(fact);
            // 同一事实的多个来源按真实日期分组，避免把不同 Session 日期强行改为同一天。
            byDate.forEach((date, ids) -> saved.append(" (source_ids: ").append(String.join(",", ids))
                    .append(date.equals("unknown") ? "" : ", source_date: " + date).append(')'));
            output.add(saved.toString());
        }
        if (output.isEmpty()) throw new IllegalArgumentException("content must contain facts.");
        return String.join("\n", output);
    }

    private static IllegalArgumentException invalid(int bullet, String reason) {
        return new IllegalArgumentException("bullet " + bullet + ": " + reason);
    }
}
