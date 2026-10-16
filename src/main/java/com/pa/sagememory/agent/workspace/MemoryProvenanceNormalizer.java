package com.pa.sagememory.agent.workspace;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 统一明确的来源标注格式，不修改事实正文、不推测或补齐来源值。 */
public final class MemoryProvenanceNormalizer {
    private static final String IDS = "[1-9]\\d*(?:[ \\t]*(?:[,，、;；]|[-–—])[ \\t]*[1-9]\\d*)*";
    private static final String DATE = "(?:\\d{4}-\\d{2}-\\d{2}|unknown|未知)";
    private static final Pattern ANNOTATION = Pattern.compile("([（(\\[])([^\\r\\n()（）\\[\\]]*)([）)\\]])");
    private static final Pattern SOURCE_FIRST = Pattern.compile(
            "[ \\t]*(source_ids?|sources?)[ \\t]*[:：][ \\t]*(" + IDS + ")"
                    + "(?:[ \\t]*[,，;；]?[ \\t]*(source_date|date)[ \\t]*[:：][ \\t]*(" + DATE + "))?[ \\t]*",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern DATE_FIRST = Pattern.compile(
            "[ \\t]*(source_date|date)[ \\t]*[:：][ \\t]*(" + DATE + ")"
                    + "[ \\t]*[,，;；]?[ \\t]*(source_ids?|sources?)[ \\t]*[:：][ \\t]*(" + IDS + ")[ \\t]*",
            Pattern.CASE_INSENSITIVE);

    private MemoryProvenanceNormalizer() {}

    public static String normalize(String content) {
        if (content == null || content.isEmpty()) return content;
        StringBuilder normalized = new StringBuilder();
        char fence = 0;
        int fenceLength = 0;
        // 保留原有换行；代码块中的示例不是引用标注，不能被改写。
        for (String line : content.split("(?<=\\n)", -1)) {
            String leading = line.stripLeading();
            int run = leading.isEmpty() ? 0 : repeated(leading, 0);
            char first = leading.isEmpty() ? 0 : leading.charAt(0);
            if ((first == '`' || first == '~') && run >= 3) {
                if (fence == 0) { fence = first; fenceLength = run; }
                else if (fence == first && run >= fenceLength && leading.substring(run).isBlank()) fence = 0;
                normalized.append(line);
            } else normalized.append(fence == 0 ? normalizeLine(line) : line);
        }
        return normalized.toString();
    }

    private static String normalizeLine(String line) {
        Matcher annotations = ANNOTATION.matcher(line);
        StringBuilder result = new StringBuilder();
        while (annotations.find()) {
            String original = annotations.group();
            String replacement = original;
            if (paired(annotations.group(1), annotations.group(3)) && !insideCode(line, annotations.start())) {
                Matcher fields = SOURCE_FIRST.matcher(annotations.group(2));
                if (fields.matches()) replacement = canonical(original, fields.group(1), fields.group(2), fields.group(4));
                else {
                    fields = DATE_FIRST.matcher(annotations.group(2));
                    if (fields.matches()) replacement = canonical(original, fields.group(3), fields.group(4), fields.group(2));
                }
            }
            annotations.appendReplacement(result, Matcher.quoteReplacement(replacement));
        }
        return annotations.appendTail(result).toString();
    }

    private static String canonical(String original, String label, String ids, String date) {
        if (date != null && !date.equalsIgnoreCase("unknown") && !date.equals("未知")) {
            try { LocalDate.parse(date); }
            catch (DateTimeParseException invalid) { return original; }
        }
        String values = ids.replaceAll("[ \\t]", "").replace('，', ',').replace('、', ',')
                .replace('；', ',').replace(';', ',').replace('–', '-').replace('—', '-');
        String key = label.toLowerCase(Locale.ROOT);
        if (!key.equals("source_id") && !key.equals("source_ids"))
            key = values.matches("[1-9]\\d*") ? "source_id" : "source_ids";
        return "(" + key + ": " + values + (date == null ? "" : ", source_date: " + date) + ")";
    }

    private static boolean paired(String open, String close) {
        return open.equals("(") && close.equals(")") || open.equals("（") && close.equals("）")
                || open.equals("[") && close.equals("]");
    }

    private static boolean insideCode(String line, int position) {
        int delimiter = 0;
        for (int index = 0; index < position;) {
            if (line.charAt(index) != '`') { index++; continue; }
            int run = repeated(line, index);
            if (delimiter == 0) delimiter = run;
            else if (delimiter == run) delimiter = 0;
            index += run;
        }
        return delimiter != 0;
    }

    private static int repeated(String text, int start) {
        int end = start + 1;
        while (end < text.length() && text.charAt(end) == text.charAt(start)) end++;
        return end - start;
    }
}
