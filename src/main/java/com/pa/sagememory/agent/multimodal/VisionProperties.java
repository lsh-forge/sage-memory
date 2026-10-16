package com.pa.sagememory.agent.multimodal;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** Add 与 Search 共用的视觉配置；独立于文本模型配置，保留现有配置前缀。 */
@Component
@ConfigurationProperties(prefix = "sage.memory.add.vision")
public class VisionProperties {
    private String apiKey;
    private String baseUrl = "https://api.openai.com/v1";
    private String modelName = "gpt-4o-mini";
    private int concurrency = 10;
    private int timeoutSeconds = 180;
    private int maxOutputTokens = 10000;

    public void validate() {
        if (concurrency < 1 || timeoutSeconds < 1 || maxOutputTokens < 1
                || modelName == null || modelName.isBlank()) {
            throw new IllegalArgumentException("Invalid sage.memory.add.vision configuration.");
        }
    }

    public String getApiKey() { return apiKey; }
    public void setApiKey(String value) { apiKey = value; }
    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String value) { baseUrl = value; }
    public String getModelName() { return modelName; }
    public void setModelName(String value) { modelName = value; }
    public int getConcurrency() { return concurrency; }
    public void setConcurrency(int value) { concurrency = value; }
    public int getTimeoutSeconds() { return timeoutSeconds; }
    public void setTimeoutSeconds(int value) { timeoutSeconds = value; }
    public int getMaxOutputTokens() { return maxOutputTokens; }
    public void setMaxOutputTokens(int value) { maxOutputTokens = value; }
}
