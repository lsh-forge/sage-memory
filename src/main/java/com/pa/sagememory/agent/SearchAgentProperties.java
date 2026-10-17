package com.pa.sagememory.agent;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** 检索运行参数独立配置；模型默认复用 Add 的文本模型，不重复配置密钥。 */
@Component
@ConfigurationProperties(prefix = "sage.memory.search")
public class SearchAgentProperties {
    private int maxIters = 12;
    private int modelTimeoutSeconds = 60;
    private int requestTimeoutSeconds = 180;
    private int httpWaitTimeoutSeconds = 200;
    private int maxOutputTokens = 8000;
    private int workerThreads = 16;
    private int queueCapacity = 32;
    public void validate() {
        if (maxIters < 1 || modelTimeoutSeconds < 1 || requestTimeoutSeconds < 1
                || httpWaitTimeoutSeconds <= requestTimeoutSeconds || maxOutputTokens < 1
                || workerThreads < 1 || queueCapacity < 1) throw new IllegalArgumentException("Invalid sage.memory.search configuration.");
    }
    public int getMaxIters() { return maxIters; }
    public void setMaxIters(int value) { maxIters = value; }
    public int getModelTimeoutSeconds() { return modelTimeoutSeconds; }
    public void setModelTimeoutSeconds(int value) { modelTimeoutSeconds = value; }
    public int getRequestTimeoutSeconds() { return requestTimeoutSeconds; }
    public void setRequestTimeoutSeconds(int value) { requestTimeoutSeconds = value; }
    public int getHttpWaitTimeoutSeconds() { return httpWaitTimeoutSeconds; }
    public void setHttpWaitTimeoutSeconds(int value) { httpWaitTimeoutSeconds = value; }
    public int getMaxOutputTokens() { return maxOutputTokens; }
    public void setMaxOutputTokens(int value) { maxOutputTokens = value; }
    public int getWorkerThreads() { return workerThreads; }
    public void setWorkerThreads(int value) { workerThreads = value; }
    public int getQueueCapacity() { return queueCapacity; }
    public void setQueueCapacity(int value) { queueCapacity = value; }
}
