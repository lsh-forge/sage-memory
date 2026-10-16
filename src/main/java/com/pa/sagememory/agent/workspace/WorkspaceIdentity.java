package com.pa.sagememory.agent.workspace;

/** 只转换内部路径：冒号变为 ###，原始 # 先转义为 #23；业务 ID 始终保持原值。 */
public final class WorkspaceIdentity {
    private WorkspaceIdentity() {}

    public static String pathSegment(String id) {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("Workspace identifier must not be blank.");
        String segment = id.replace("#", "#23").replace(":", "###");
        // 为会话文件的 .log.jsonl 后缀预留 10 个字符，不超过 Windows 单个文件名的 255 字符。
        if (segment.length() > 245) throw new IllegalArgumentException("Mapped workspace identifier must not exceed 245 characters.");
        boolean reserved = segment.stripLeading().matches("(?i)(con|prn|aux|nul|conin\\$|conout\\$|com[1-9¹²³]|lpt[1-9¹²³])(\\..*)?");
        if (segment.equals(".") || segment.equals("..") || segment.endsWith(".") || segment.endsWith(" ") || reserved
                || segment.chars().anyMatch(character -> Character.isISOControl(character)
                        || "<>:\"/\\|?*".indexOf(character) >= 0)) {
            throw new IllegalArgumentException("Workspace identifier must map to a safe Windows file name; only colons and # are escaped.");
        }
        return segment;
    }
}
