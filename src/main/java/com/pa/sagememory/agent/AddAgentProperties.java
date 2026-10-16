package com.pa.sagememory.agent;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** Add 配置：YAML/环境变量覆盖 Java 默认值；密钥不得写入记忆工作区或诊断日志。 */
@Component
@ConfigurationProperties(prefix = "sage.memory.add")
public class AddAgentProperties {
    public static final int DEFAULT_CONSOLIDATION_TARGET_CHARS = 8_000;
    public static final int DEFAULT_CONSOLIDATION_MAX_CHARS = 12_000;
    private String workspaceRoot = ".sage-memory/workspace";
    private String apiKey = "xxxxx";
    private String baseUrl = "https://api1.v3.cm/v1";
    private String modelName = "gpt-4o-mini";
    private int batchSize = 5;
    // 字符预算用于拆分消息，不能等同于模型 token 预算。
    private int maxBatchChars = 20_000;
    private int maxIters = 10;
    private int timeoutSeconds = 600;
    // 模型、排队、整单与 HTTP 等待分别计时，HTTP 时限必须大于整单时限。
    private int modelTimeoutSeconds = 180;
    // 归并 Agent 单次模型调用时限；所有轮次和重试仍共享 Add 的整单截止时间。
    private int consolidationTimeoutSeconds = 300;
    private int consolidationMaxIters = 20;
    private int queueTimeoutSeconds = 30;
    // 比赛单次请求上限 30 分钟；整单 29 分钟、HTTP 29.5 分钟，为响应传输留出余量。
    private int requestTimeoutSeconds = 1740;
    private int httpWaitTimeoutSeconds = 1770;
    private int workerThreads = 4;
    private int queueCapacity = 64;
    // 固定最多两段同 Session 的 Save 摘要；总字符上限，0 表示不附带摘要。
    private int recentSaveMaxChars = 4_000;
    // 以实际字符串字符数治理文件容量；目标只引导精简，上限由最终 Lint 检查，不截断文件。
    private int consolidationTargetChars = DEFAULT_CONSOLIDATION_TARGET_CHARS;
    private int consolidationMaxChars = DEFAULT_CONSOLIDATION_MAX_CHARS;

    public void validate() {
        if (workspaceRoot == null || workspaceRoot.isBlank() || modelName == null || modelName.isBlank()
                || batchSize < 1 || maxBatchChars < 1 || maxIters < 1 || timeoutSeconds < 1
                || recentSaveMaxChars < 0 || modelTimeoutSeconds < 1 || consolidationTimeoutSeconds < 1
                || consolidationMaxIters < 1 || queueTimeoutSeconds < 1
                || requestTimeoutSeconds < 1 || httpWaitTimeoutSeconds <= requestTimeoutSeconds
                || workerThreads < 1 || queueCapacity < 1 || consolidationTargetChars < 1
                || consolidationMaxChars < consolidationTargetChars) {
            throw new IllegalArgumentException("Invalid sage.memory.add configuration.");
        }
    }

    public String getWorkspaceRoot() { return workspaceRoot; }
    public void setWorkspaceRoot(String value) { workspaceRoot = value; }
    public String getApiKey() { return apiKey; }
    public void setApiKey(String value) { apiKey = value; }
    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String value) { baseUrl = value; }
    public String getModelName() { return modelName; }
    public void setModelName(String value) { modelName = value; }
    public int getBatchSize() { return batchSize; }
    public void setBatchSize(int value) { batchSize = value; }
    public int getMaxBatchChars() { return maxBatchChars; }
    public void setMaxBatchChars(int value) { maxBatchChars = value; }
    public int getMaxIters() { return maxIters; }
    public void setMaxIters(int value) { maxIters = value; }
    public int getTimeoutSeconds() { return timeoutSeconds; }
    public void setTimeoutSeconds(int value) { timeoutSeconds = value; }
    public int getModelTimeoutSeconds() { return modelTimeoutSeconds; }
    public void setModelTimeoutSeconds(int value) { modelTimeoutSeconds = value; }
    public int getConsolidationTimeoutSeconds() { return consolidationTimeoutSeconds; }
    public void setConsolidationTimeoutSeconds(int value) { consolidationTimeoutSeconds = value; }
    public int getConsolidationMaxIters() { return consolidationMaxIters; }
    public void setConsolidationMaxIters(int value) { consolidationMaxIters = value; }
    public int getQueueTimeoutSeconds() { return queueTimeoutSeconds; }
    public void setQueueTimeoutSeconds(int value) { queueTimeoutSeconds = value; }
    public int getRequestTimeoutSeconds() { return requestTimeoutSeconds; }
    public void setRequestTimeoutSeconds(int value) { requestTimeoutSeconds = value; }
    public int getHttpWaitTimeoutSeconds() { return httpWaitTimeoutSeconds; }
    public void setHttpWaitTimeoutSeconds(int value) { httpWaitTimeoutSeconds = value; }
    public int getWorkerThreads() { return workerThreads; }
    public void setWorkerThreads(int value) { workerThreads = value; }
    public int getQueueCapacity() { return queueCapacity; }
    public void setQueueCapacity(int value) { queueCapacity = value; }
    public int getRecentSaveMaxChars() { return recentSaveMaxChars; }
    public void setRecentSaveMaxChars(int value) { recentSaveMaxChars = value; }
    public int getConsolidationTargetChars() { return consolidationTargetChars; }
    public void setConsolidationTargetChars(int value) { consolidationTargetChars = value; }
    public int getConsolidationMaxChars() { return consolidationMaxChars; }
    public void setConsolidationMaxChars(int value) { consolidationMaxChars = value; }
}
