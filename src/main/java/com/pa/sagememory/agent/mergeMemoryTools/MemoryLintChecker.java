package com.pa.sagememory.agent.mergeMemoryTools;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.LinkedHashMap;
import java.util.regex.Pattern;

/** 确定性结构和引用检查；不调用模型，不判断事实真伪或语义重复。 */
final class MemoryLintChecker {
    private static final Pattern BULLET = Pattern.compile("^\\s*[-*+]\\s+(.+)$");
    private static final Pattern SOURCE_FIELD = Pattern.compile("\\bsource_ids?\\s*:");
    private static final Pattern REFERENCE = Pattern.compile(
            "\\(source_ids?:\\s*([1-9][0-9]*(?:\\s*,\\s*[1-9][0-9]*)*)(?:\\s*,\\s*source_date:\\s*(\\d{4}-\\d{2}-\\d{2}|unknown|未知))?\\)");

    private MemoryLintChecker() {}

    static List<String> check(String content, Map<String, String> sources) {
        List<String> issues = new ArrayList<>();
        // 新用户或已无值得保留的知识时，空记忆合法，不能为了通过检查逼模型编造事实。
        if (content.isBlank()) return issues;
        if (!content.stripLeading().lines().findFirst().orElse("").strip().equals("# MEMORY.md"))
            issues.add("- The document must start with # MEMORY.md.");
        String[] lines = content.split("\\R", -1);
        String[] parents = new String[6];
        Set<String> topicPaths = new HashSet<>();
        Map<String, SeenFact> seenFacts = new LinkedHashMap<>();
        List<Topic> topics = new ArrayList<>();
        Set<Integer> bulletLines = new HashSet<>();
        String currentTopic = "document root";
        String factTopic = currentTopic;
        int roots = 0;
        int previousLevel = 0;
        int factLine = 0;
        boolean inTopic = false;
        StringBuilder fact = new StringBuilder();
        for (int index = 0; index < lines.length; index++) {
            String line = lines[index];
            if (line.stripLeading().startsWith("```") || line.stripLeading().startsWith("~~~")) {
                issues.add(at(index + 1, "code fences are not supported in curated memory."));
                continue;
            }
            var heading = MergeMemoryContext.HEADING.matcher(line);
            var bullet = BULLET.matcher(line);
            if (heading.matches() || bullet.matches()) {
                if (!fact.isEmpty()) lintFactAndDuplicates(fact.toString(), factLine, factTopic, sources, issues, seenFacts);
                fact.setLength(0);
            }
            if (heading.matches()) {
                int level = heading.group(1).length();
                if (previousLevel > 0 && level > previousLevel + 1) issues.add(at(index + 1, "heading levels must not skip a level."));
                previousLevel = level;
                if (level == 1) { roots++; inTopic = false; }
                else {
                    inTopic = true;
                    parents[level - 1] = heading.group(2).strip();
                    for (int child = level; child < parents.length; child++) parents[child] = null;
                    List<String> pathParts = new ArrayList<>();
                    for (int parent = 1; parent < level; parent++) pathParts.add(parents[parent]);
                    currentTopic = String.join(" > ", pathParts);
                    if (!topicPaths.add(currentTopic)) issues.add(at(index + 1, "duplicate topic heading under the same parent."));
                    topics.add(new Topic(index + 1, level, currentTopic));
                }
            } else if (bullet.matches()) {
                if (!inTopic) issues.add(at(index + 1, "facts must belong to a topic heading."));
                factLine = index + 1;
                factTopic = currentTopic;
                bulletLines.add(factLine);
                fact.append(bullet.group(1));
            } else if (!line.isBlank()) {
                if ((line.startsWith(" ") || line.startsWith("\t")) && !fact.isEmpty()) fact.append('\n').append(line);
                else issues.add(at(index + 1, "use topic headings and Markdown bullets; code fences or standalone prose are not supported."));
            }
        }
        if (!fact.isEmpty()) lintFactAndDuplicates(fact.toString(), factLine, factTopic, sources, issues, seenFacts);
        for (Topic topic : topics) {
            int end = topics.stream().filter(next -> next.line() > topic.line() && next.level() <= topic.level())
                    .mapToInt(Topic::line).findFirst().orElse(lines.length + 1);
            if (bulletLines.stream().noneMatch(line -> line > topic.line() && line < end))
                issues.add(at(topic.line(), "empty topic '" + topic.path() + "'; add supported facts or remove the unused topic."));
        }
        if (roots != 1) issues.add("- Keep exactly one document root heading.");
        return issues;
    }

    private static void lintFactAndDuplicates(String fact, int line, String topic, Map<String, String> sources,
            List<String> issues, Map<String, SeenFact> seen) {
        lintFact(fact, line, sources, issues);
        // 只比较完整事实文本（包括引用），不按 source_id 去重，也不进行语义去重。
        SeenFact first = seen.putIfAbsent(fact.strip(), new SeenFact(line, topic));
        if (first != null) issues.add(at(line, "identical fact also occurs on line " + first.line() + " under '" + first.topic()
                + "'; keep one appropriate copy. This occurrence is under '" + topic + "'."));
    }

    private static void lintFact(String fact, int line, Map<String, String> sources, List<String> issues) {
        if (REFERENCE.matcher(fact).replaceAll("").strip().matches("[.。]*"))
            issues.add(at(line, "a source annotation alone is not a memory fact."));
        var references = REFERENCE.matcher(fact);
        int count = 0;
        while (references.find()) {
            count++;
            String date = references.group(2);
            if (date != null && !date.equals("unknown") && !date.equals("未知")) {
                try { LocalDate.parse(date); }
                catch (DateTimeParseException invalid) { issues.add(at(line, "source_date is not a valid calendar date.")); continue; }
            }
            for (String id : references.group(1).split("\\s*,\\s*")) {
                if (!sources.containsKey(id)) issues.add(at(line, "source ID " + id + " does not exist for this user."));
                else if (date != null) {
                    String expected = sources.get(id);
                    String actual = date.equals("未知") ? "unknown" : date;
                    if (!actual.equals(expected)) issues.add(at(line, "source_date for ID " + id + " must be " + expected + "."));
                }
            }
        }
        if (count == 0) issues.add(at(line, "missing valid numeric source_ids; use (source_ids: 1, source_date: YYYY-MM-DD)."));
        if (SOURCE_FIELD.matcher(fact).results().count() > count)
            issues.add(at(line, "malformed source annotation; use numeric IDs in parentheses and an optional ISO source_date."));
    }

    private static String at(int line, String reason) { return "- Line " + line + ": " + reason; }
    private record Topic(int line, int level, String path) {}
    private record SeenFact(int line, String topic) {}
}
